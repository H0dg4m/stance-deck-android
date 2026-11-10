package dev.stance.deck.core

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.Blake2sDigest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.SecureRandom

/** Thrown for any Noise failure (bad message, bad tag, bad state). */
class NoiseException(message: String) : Exception(message)

/** An X25519 key pair (raw 32-byte keys). */
class KeyPair(val private: ByteArray, val public: ByteArray) {
    companion object {
        fun generate(random: SecureRandom = SecureRandom()): KeyPair =
            fromPrivate(ByteArray(32).also { random.nextBytes(it) })

        fun fromPrivate(private: ByteArray): KeyPair {
            require(private.size == 32) { "X25519 keys are 32 bytes" }
            val pub = X25519PrivateKeyParameters(private, 0).generatePublicKey().encoded
            return KeyPair(private.copyOf(), pub)
        }
    }
}

private const val HASHLEN = 32
private const val BLOCKLEN = 64
private const val DHLEN = 32
private const val TAGLEN = 16

private fun blake2s(vararg parts: ByteArray): ByteArray {
    val d = Blake2sDigest(256)
    for (p in parts) d.update(p, 0, p.size)
    return ByteArray(HASHLEN).also { d.doFinal(it, 0) }
}

private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
    val m = HMac(Blake2sDigest(256))
    m.init(KeyParameter(key))
    m.update(data, 0, data.size)
    return ByteArray(HASHLEN).also { m.doFinal(it, 0) }
}

/** Noise HKDF; returns [n] outputs (2 or 3). */
private fun hkdf(ck: ByteArray, ikm: ByteArray, n: Int): List<ByteArray> {
    val temp = hmac(ck, ikm)
    val o1 = hmac(temp, byteArrayOf(1))
    val o2 = hmac(temp, o1 + byteArrayOf(2))
    return if (n == 2) listOf(o1, o2) else listOf(o1, o2, hmac(temp, o2 + byteArrayOf(3)))
}

private fun dh(private: ByteArray, public: ByteArray): ByteArray {
    val a = X25519Agreement()
    a.init(X25519PrivateKeyParameters(private, 0))
    val out = ByteArray(DHLEN)
    a.calculateAgreement(X25519PublicKeyParameters(public, 0), out, 0)
    return out
}

/** ChaChaPoly CipherState: 12-byte nonce = 4 zero bytes + 64-bit little endian counter. */
class CipherState internal constructor(private var key: ByteArray?) {
    private var n = 0L

    internal fun hasKey() = key != null

    private fun nonce(): ByteArray {
        val b = ByteArray(12)
        for (i in 0 until 8) b[4 + i] = ((n ushr (8 * i)) and 0xff).toByte()
        return b
    }

    fun encrypt(ad: ByteArray, plain: ByteArray): ByteArray {
        val k = key ?: return plain
        if (n == -1L) throw NoiseException("nonce exhausted")
        val c = ChaCha20Poly1305()
        c.init(true, AEADParameters(KeyParameter(k), TAGLEN * 8, nonce(), ad))
        val out = ByteArray(c.getOutputSize(plain.size))
        val l = c.processBytes(plain, 0, plain.size, out, 0)
        c.doFinal(out, l)
        n++
        return out
    }

    fun decrypt(ad: ByteArray, cipher: ByteArray): ByteArray {
        val k = key ?: return cipher
        if (cipher.size < TAGLEN) throw NoiseException("message too short")
        if (n == -1L) throw NoiseException("nonce exhausted")
        val c = ChaCha20Poly1305()
        c.init(false, AEADParameters(KeyParameter(k), TAGLEN * 8, nonce(), ad))
        val out = ByteArray(c.getOutputSize(cipher.size))
        try {
            val l = c.processBytes(cipher, 0, cipher.size, out, 0)
            c.doFinal(out, l)
        } catch (e: Exception) {
            throw NoiseException("decryption failed")
        }
        n++
        return out
    }
}

/** Established transport after a handshake. Any decrypt failure must end the session. */
class Transport internal constructor(
    private val send: CipherState,
    private val recv: CipherState,
    val handshakeHash: ByteArray,
    val remoteStatic: ByteArray?,
) {
    fun encrypt(plain: ByteArray): ByteArray = send.encrypt(ByteArray(0), plain)
    fun decrypt(cipher: ByteArray): ByteArray = recv.decrypt(ByteArray(0), cipher)
}

enum class Pattern(val protocolName: String) {
    IK("Noise_IK_25519_ChaChaPoly_BLAKE2s"),
    XXPSK3("Noise_XXpsk3_25519_ChaChaPoly_BLAKE2s"),
}

private enum class Tok { E, S, EE, ES, SE, SS, PSK }

/**
 * Noise handshake for the two patterns stance uses. Roles are fixed by the
 * pattern: the phone is always the initiator, the PC the responder.
 */
class HandshakeState(
    private val pattern: Pattern,
    private val initiator: Boolean,
    prologue: ByteArray,
    private val s: KeyPair,
    private var rs: ByteArray? = null,
    private val psk: ByteArray? = null,
    /** Test only: fixed ephemeral private key. */
    private val fixedEphemeral: ByteArray? = null,
) {
    private var e: KeyPair? = null
    private var re: ByteArray? = null
    private var ck: ByteArray
    private var h: ByteArray
    private var k = CipherState(null)
    private var msgIndex = 0
    private val messages: List<List<Tok>>
    private val hasPsk = pattern == Pattern.XXPSK3

    init {
        when (pattern) {
            Pattern.IK -> {
                messages = listOf(
                    listOf(Tok.E, Tok.ES, Tok.S, Tok.SS),
                    listOf(Tok.E, Tok.EE, Tok.SE),
                )
                require(!initiator || rs != null) { "IK initiator needs the responder's static key" }
            }
            Pattern.XXPSK3 -> {
                messages = listOf(
                    listOf(Tok.E),
                    listOf(Tok.E, Tok.EE, Tok.S, Tok.ES),
                    listOf(Tok.S, Tok.SE, Tok.PSK),
                )
                require(psk != null && psk.size == 32) { "XXpsk3 needs a 32-byte PSK" }
            }
        }
        val name = pattern.protocolName.toByteArray(Charsets.US_ASCII)
        h = if (name.size <= HASHLEN) name.copyOf(HASHLEN) else blake2s(name)
        ck = h
        mixHash(prologue)
        if (pattern == Pattern.IK) {
            // Pre-message "<- s": the responder's static key.
            mixHash(if (initiator) rs!! else s.public)
        }
    }

    val isFinished: Boolean get() = msgIndex >= messages.size

    private fun mixHash(data: ByteArray) {
        h = blake2s(h, data)
    }

    private fun mixKey(ikm: ByteArray) {
        val (nck, tk) = hkdf(ck, ikm, 2)
        ck = nck
        k = CipherState(tk)
    }

    private fun mixKeyAndHash(ikm: ByteArray) {
        val (nck, th, tk) = hkdf(ck, ikm, 3)
        ck = nck
        mixHash(th)
        k = CipherState(tk)
    }

    private fun encryptAndHash(plain: ByteArray): ByteArray {
        val c = k.encrypt(h, plain)
        mixHash(c)
        return c
    }

    private fun decryptAndHash(cipher: ByteArray): ByteArray {
        val p = k.decrypt(h, cipher)
        mixHash(cipher)
        return p
    }

    private fun myTurn() = (msgIndex % 2 == 0) == initiator

    private fun dhFor(tok: Tok): ByteArray {
        val me = initiator
        fun e() = (this.e ?: throw NoiseException("no ephemeral")).private
        val re = re
        return when (tok) {
            Tok.EE -> dh(e(), re ?: throw NoiseException("no remote ephemeral"))
            Tok.SS -> dh(s.private, rs ?: throw NoiseException("no remote static"))
            // "es": initiator e with responder s; "se": initiator s with responder e.
            Tok.ES -> if (me) dh(e(), rs ?: throw NoiseException("no remote static"))
            else dh(s.private, re ?: throw NoiseException("no remote ephemeral"))
            Tok.SE -> if (me) dh(s.private, re ?: throw NoiseException("no remote ephemeral"))
            else dh(e(), rs ?: throw NoiseException("no remote static"))
            else -> throw IllegalStateException()
        }
    }

    fun writeMessage(payload: ByteArray = ByteArray(0)): ByteArray {
        check(!isFinished && myTurn()) { "not our turn" }
        val out = java.io.ByteArrayOutputStream()
        for (tok in messages[msgIndex]) {
            when (tok) {
                Tok.E -> {
                    val kp = fixedEphemeral?.let { KeyPair.fromPrivate(it) } ?: KeyPair.generate()
                    e = kp
                    out.write(kp.public)
                    mixHash(kp.public)
                    if (hasPsk) mixKey(kp.public)
                }
                Tok.S -> out.write(encryptAndHash(s.public))
                Tok.PSK -> mixKeyAndHash(psk!!)
                else -> mixKey(dhFor(tok))
            }
        }
        out.write(encryptAndHash(payload))
        msgIndex++
        return out.toByteArray()
    }

    fun readMessage(message: ByteArray): ByteArray {
        check(!isFinished && !myTurn()) { "not our turn" }
        var off = 0
        fun take(n: Int): ByteArray {
            if (message.size - off < n) throw NoiseException("handshake message too short")
            return message.copyOfRange(off, off + n).also { off += n }
        }
        for (tok in messages[msgIndex]) {
            when (tok) {
                Tok.E -> {
                    re = take(DHLEN)
                    mixHash(re!!)
                    if (hasPsk) mixKey(re!!)
                }
                Tok.S -> {
                    val len = DHLEN + if (k.hasKey()) TAGLEN else 0
                    rs = decryptAndHash(take(len))
                }
                Tok.PSK -> mixKeyAndHash(psk!!)
                else -> mixKey(dhFor(tok))
            }
        }
        val payload = decryptAndHash(message.copyOfRange(off, message.size))
        msgIndex++
        return payload
    }

    /** The remote static key once the handshake has revealed it. */
    val remoteStatic: ByteArray? get() = rs
    val handshakeHash: ByteArray get() = h

    fun split(): Transport {
        check(isFinished) { "handshake not finished" }
        val (k1, k2) = hkdf(ck, ByteArray(0), 2)
        val c1 = CipherState(k1)
        val c2 = CipherState(k2)
        return if (initiator) Transport(c1, c2, h, rs) else Transport(c2, c1, h, rs)
    }
}
