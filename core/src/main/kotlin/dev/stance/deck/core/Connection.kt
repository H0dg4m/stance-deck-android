package dev.stance.deck.core

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/** Byte pipe with u16 big-endian length framing; also usable over in-memory streams in tests. */
class Framing(private val input: InputStream, private val output: OutputStream) {
    private val din = DataInputStream(input)

    fun write(data: ByteArray) {
        if (data.isEmpty() || data.size > 65535) throw DeckException("frame size out of bounds")
        val buf = ByteArray(2 + data.size)
        buf[0] = (data.size ushr 8).toByte(); buf[1] = data.size.toByte()
        System.arraycopy(data, 0, buf, 2, data.size)
        try { output.write(buf); output.flush() } catch (e: IOException) { throw DeckException(e.message ?: "write failed", DeckException.Kind.IO) }
    }

    fun writeRaw(b: Byte) {
        try { output.write(b.toInt()); output.flush() } catch (e: IOException) { throw DeckException(e.message ?: "write failed", DeckException.Kind.IO) }
    }

    fun read(max: Int): ByteArray {
        try {
            val len = din.readUnsignedShort()
            if (len == 0 || len > max) throw DeckException("frame size out of bounds")
            return ByteArray(len).also { din.readFully(it) }
        } catch (e: java.io.EOFException) {
            throw DeckException("connection closed", DeckException.Kind.CLOSED)
        } catch (e: SocketTimeoutException) {
            throw e
        } catch (e: IOException) {
            throw DeckException(e.message ?: "read failed", DeckException.Kind.IO)
        }
    }
}

/** An encrypted channel to a PC. [send] and [recv] may be used from different threads (one each). */
class Session internal constructor(
    private val socket: Socket?,
    private val framing: Framing,
    private val transport: Transport,
    val remoteStatic: ByteArray?,
) : AutoCloseable {
    private val sendLock = Any()

    fun send(msg: ClientMsg) {
        val plain = msg.toJson().toByteArray(Charsets.UTF_8)
        if (plain.size + 16 > POST_AUTH_MAX) throw DeckException("message too large")
        synchronized(sendLock) { framing.write(transport.encrypt(plain)) }
    }

    /** Next message, or null when [timeoutMs] passes with no data. */
    fun recv(timeoutMs: Int): ServerMsg? {
        socket?.soTimeout = timeoutMs
        val frame = try {
            framing.read(POST_AUTH_MAX)
        } catch (e: SocketTimeoutException) {
            return null
        }
        val plain = try { transport.decrypt(frame) } catch (e: NoiseException) { throw DeckException("decryption failed") }
        return parseServerMsg(String(plain, Charsets.UTF_8))
    }

    override fun close() {
        try { socket?.close() } catch (_: IOException) {}
    }
}

object Deck {
    const val HANDSHAKE_TIMEOUT_MS = 5000

    private fun open(host: String, port: Int, connectTimeoutMs: Int): Socket {
        val s = Socket()
        try {
            s.connect(InetSocketAddress(host, port), connectTimeoutMs)
            s.soTimeout = HANDSHAKE_TIMEOUT_MS
            s.tcpNoDelay = true
        } catch (e: IOException) {
            try { s.close() } catch (_: IOException) {}
            throw DeckException(e.message ?: "cannot connect", DeckException.Kind.IO)
        }
        return s
    }

    private fun refused(f: () -> ByteArray): ByteArray = try {
        f()
    } catch (e: DeckException) {
        // The PC closes without a word when it refuses.
        if (e.kind == DeckException.Kind.CLOSED || e.kind == DeckException.Kind.IO)
            throw DeckException("the PC refused the connection (not paired, or the pairing code was used or expired)", DeckException.Kind.REFUSED)
        throw e
    } catch (e: SocketTimeoutException) {
        throw DeckException("handshake timed out", DeckException.Kind.IO)
    }

    /** Session handshake (IK) over an existing byte pipe. Used by [connect] and by tests. */
    fun sessionOver(framing: Framing, socket: Socket?, own: KeyPair, pcPublic: ByteArray): Session {
        framing.writeRaw(MODE_SESSION)
        val hs = HandshakeState(Pattern.IK, true, prologueFor(MODE_SESSION), own, rs = pcPublic)
        framing.write(hs.writeMessage())
        try { hs.readMessage(refused { framing.read(PRE_AUTH_MAX) }) } catch (e: NoiseException) { throw DeckException("handshake failed") }
        socket?.soTimeout = 0
        return Session(socket, framing, hs.split(), hs.remoteStatic)
    }

    fun connect(host: String, port: Int, own: KeyPair, pcPublic: ByteArray, connectTimeoutMs: Int = 4000): Session {
        val s = open(host, port, connectTimeoutMs)
        try {
            return sessionOver(Framing(s.getInputStream(), s.getOutputStream()), s, own, pcPublic)
        } catch (e: Exception) {
            try { s.close() } catch (_: IOException) {}
            throw if (e is IOException) DeckException(e.message ?: "io", DeckException.Kind.IO) else e
        }
    }

    /** Pairing handshake (XXpsk3) over a byte pipe; checks the PC key, sends `pair`. */
    fun pairOver(framing: Framing, socket: Socket?, own: KeyPair, qr: QrPayload, deviceName: String): Session {
        framing.writeRaw(MODE_PAIR)
        val hs = HandshakeState(Pattern.XXPSK3, true, prologueFor(MODE_PAIR), own, psk = qr.psk)
        try {
            framing.write(hs.writeMessage())
            hs.readMessage(refused { framing.read(PRE_AUTH_MAX) })
            val got = hs.remoteStatic ?: throw DeckException("no PC key")
            if (!got.contentEquals(qr.pcPublic)) throw DeckException("the PC's key does not match the pairing code")
            framing.write(hs.writeMessage())
        } catch (e: NoiseException) {
            throw DeckException("handshake failed")
        }
        socket?.soTimeout = 0
        val session = Session(socket, framing, hs.split(), hs.remoteStatic)
        session.send(ClientMsg.Pair(cleanText(deviceName).ifEmpty { "Phone" }))
        return session
    }

    /** Tries each host from the QR in order. */
    fun pair(qr: QrPayload, own: KeyPair, deviceName: String, connectTimeoutMs: Int = 4000): Session {
        var last: DeckException? = null
        for (host in qr.hosts) {
            val s = try { open(host, qr.port, connectTimeoutMs) } catch (e: DeckException) { last = e; continue }
            try {
                return pairOver(Framing(s.getInputStream(), s.getOutputStream()), s, own, qr, deviceName)
            } catch (e: Exception) {
                try { s.close() } catch (_: IOException) {}
                if (e is DeckException && e.kind != DeckException.Kind.IO) throw e
                last = if (e is DeckException) e else DeckException(e.message ?: "io", DeckException.Kind.IO)
            }
        }
        throw last ?: DeckException("no address to try", DeckException.Kind.IO)
    }
}
