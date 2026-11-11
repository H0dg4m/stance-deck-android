package dev.stance.deck

import dev.stance.deck.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.concurrent.ConcurrentLinkedQueue

sealed interface Link {
    data object Idle : Link
    data class Connecting(val attempt: Int) : Link
    data object Connected : Link
    data class Waiting(val reason: String, val seconds: Int) : Link
    /** The PC no longer knows this phone: unpaired. */
    data object Refused : Link
}

/** What the platform does for transfers (Android clipboard, cache files, Downloads); faked in tests. */
interface TransferHost {
    /** False where files cannot be saved to the public Downloads (Android 9 and older): `file.pull` is not offered. */
    val canSaveFiles: Boolean
    fun setClipboard(text: String)
    /** Puts a (sniffed, size-checked) image on the clipboard; [mime] comes from the content. */
    fun setClipboardImage(bytes: ByteArray, mime: String) {}
    fun newTempFile(): File
    /** Copies [temp] into the public Downloads under [name]; returns where it went. */
    fun saveDownload(temp: File, name: String): String

    object None : TransferHost {
        override val canSaveFiles = false
        override fun setClipboard(text: String) {}
        override fun newTempFile(): File = File.createTempFile("stance", ".part")
        override fun saveDownload(temp: File, name: String): String = throw IOException("not supported")
    }
}

/** A file (or text) to send to the PC. */
class OutSource(val name: String, val size: Long, val mime: String?, val open: () -> InputStream)

/** A file the PC wants to send; waits for the user's Accept / Decline. */
data class FileAsk(val sid: Long, val name: String, val size: Long)

enum class Phase { PREPARING, WAITING, SENDING, DONE, FAILED }

/** The file this phone is sending to the PC (progress, then the result). */
data class OutState(val name: String, val size: Long, val phase: Phase, val sent: Long = 0, val message: String? = null) {
    val active get() = phase == Phase.PREPARING || phase == Phase.WAITING || phase == Phase.SENDING
}

const val CAP_CLIP = "clip.text"
const val CAP_IMAGE = "clip.image"
const val CAP_PUSH = "file.push"
const val CAP_PULL = "file.pull"

data class DeckView(
    val link: Link = Link.Idle,
    val hello: ServerMsg.Hello? = null,
    val state: DeckState? = null,
    /** Local time ([System.nanoTime]/1e6) when [state] arrived, for the pending countdown. */
    val stateAtMs: Long = 0,
    val busy: Set<String> = emptySet(),
    /** Capabilities the PC agreed to for this connection (v2). */
    val caps: List<String> = emptyList(),
    val asks: List<FileAsk> = emptyList(),
    val out: OutState? = null,
)

/**
 * Keeps one PC connected while the app is in the foreground: tries the saved
 * addresses in turn, reconnects with back-off, and turns server messages into [view].
 */
class PcConnection(
    private val scope: CoroutineScope,
    private val pc: PcRecord,
    private val own: dev.stance.deck.core.KeyPair,
    private val host: TransferHost = TransferHost.None,
    private val onHosts: (hosts: List<String>, port: Int) -> Unit = { _, _ -> },
) {
    private val _view = MutableStateFlow(DeckView())
    val view = _view.asStateFlow()
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private var job: Job? = null
    @Volatile private var session: Session? = null
    private var req = 0L
    private val pendingReq = HashMap<Long, String>()
    private val lock = Any()

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) { runLoop() }
    }

    fun stop() {
        job?.cancel(); job = null
        session?.let { s -> runCatching { s.send(ClientMsg.Bye) }; s.close() }
        session = null
        _view.update { DeckView() }
    }

    /** Retry now instead of waiting out the back-off. */
    fun retryNow() {
        if (_view.value.link is Link.Refused) return
        job?.cancel(); job = null
        start()
    }

    private suspend fun runLoop() {
        var attempt = 0
        var delaySec = 1
        while (currentCoroutineContext().isActive) {
            attempt++
            _view.update { it.copy(link = Link.Connecting(attempt)) }
            var failure = "cannot connect"
            val s = try {
                connectAny()
            } catch (e: DeckException) {
                if (e.kind == DeckException.Kind.REFUSED) {
                    _view.update { it.copy(link = Link.Refused) }
                    return
                }
                failure = e.message ?: failure
                null
            }
            if (s != null) {
                session = s
                delaySec = 1; attempt = 0
                _view.update { it.copy(link = Link.Connected) }
                failure = try { pump(s) } catch (e: CancellationException) { s.close(); throw e }
                s.close()
                session = null
                synchronized(lock) { pendingReq.clear() }
                _view.update { it.copy(busy = emptySet()) }
            }
            for (left in delaySec downTo 1) {
                _view.update { it.copy(link = Link.Waiting(failure, left)) }
                delay(1000)
            }
            delaySec = minOf(delaySec * 2, 15)
        }
    }

    private fun connectAny(): Session {
        val hosts = pc.hosts.ifEmpty { listOf("127.0.0.1") }
        var last: DeckException? = null
        for (h in hosts) {
            try {
                return Deck.connect(h, pc.port, own, B64Url.decodeKey(pc.pcPublic))
            } catch (e: DeckException) {
                if (e.kind == DeckException.Kind.REFUSED) {
                    // Another address may reach another host; only give up when every address refuses.
                    last = e
                } else last = e
            }
        }
        throw last ?: DeckException("no address", DeckException.Kind.IO)
    }

    // ---- transfers ----------------------------------------------------------------

    private sealed interface Cmd {
        class Offer(val job: OutJob) : Cmd
        class Answer(val sid: Long, val accept: Boolean) : Cmd
    }

    private class OutJob(val kind: String, val name: String?, val size: Long, val sha: String, val mime: String?, val open: () -> InputStream)
    private class OutRun(val job: OutJob, var input: InputStream? = null, var pos: Long = 0)

    private class Incoming(val kind: String, val size: Long, val name: String?) {
        var mem: ByteArrayOutputStream? = null
        var file: File? = null
        var out: OutputStream? = null

        fun write(host: TransferHost, b: ByteArray) {
            if (kind == CAP_CLIP || kind == CAP_IMAGE) {
                (mem ?: ByteArrayOutputStream().also { mem = it }).write(b)
            } else {
                val o = out ?: host.newTempFile().also { file = it }.outputStream().also { out = it }
                o.write(b)
            }
        }

        fun closeStream() { runCatching { out?.close() }; out = null }
        fun discard() { closeStream(); file?.delete(); file = null; mem = null }
    }

    private val cmds = ConcurrentLinkedQueue<Cmd>()
    private fun nowMs() = System.nanoTime() / 1_000_000

    /** Text to the PC (`clip.text`). Call on the user's button press only. */
    fun sendClip(text: String) {
        if (!canSend(CAP_CLIP, "Clipboard sharing is off on the PC")) return
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty() || text.isBlank()) { _events.tryEmit("The clipboard is empty"); return }
        if (bytes.size > CLIP_TEXT_MAX) { _events.tryEmit("Clipboard text is too large (1 MiB at most)"); return }
        cmds.add(Cmd.Offer(OutJob(CAP_CLIP, null, bytes.size.toLong(), sha256Hex(bytes), "text/plain") { ByteArrayInputStream(bytes) }))
    }

    /**
     * An image to the PC clipboard (`clip.image`), read from [src] now (call on the button press only).
     * At most 32 MiB; the type is sniffed from the bytes (PNG, JPEG, WebP, GIF).
     */
    fun sendClipImage(src: OutSource) {
        if (!canSend(CAP_IMAGE, "Clipboard images are off on the PC")) return
        if (src.size > IMAGE_MAX) { _events.tryEmit("The image is too large (32 MiB at most)"); return }
        scope.launch(Dispatchers.IO) {
            val bytes = try {
                src.open().use { it.readNBytes(IMAGE_MAX.toInt() + 1) }
            } catch (e: Exception) { _events.tryEmit("Cannot read the image"); return@launch }
            when {
                bytes.isEmpty() -> _events.tryEmit("The clipboard image is empty")
                bytes.size > IMAGE_MAX -> _events.tryEmit("The image is too large (32 MiB at most)")
                else -> {
                    val mime = sniffImage(bytes)
                    if (mime == null) _events.tryEmit("Only PNG, JPEG, WebP and GIF images can be sent")
                    else cmds.add(Cmd.Offer(OutJob(CAP_IMAGE, null, bytes.size.toLong(), sha256Hex(bytes), mime) { ByteArrayInputStream(bytes) }))
                }
            }
        }
    }

    /** A file to the PC (`file.push`); the PC user has to accept it there. */
    fun sendFile(src: OutSource) {
        if (!canSend(CAP_PUSH, "Receiving files is off on the PC")) return
        if (_view.value.out?.active == true) { _events.tryEmit("Another file is still being sent"); return }
        val name = cleanFileName(src.name)
        if (src.size <= 0) { _events.tryEmit("The file is empty or its size is unknown"); return }
        if (src.size > FILE_MAX) { _events.tryEmit("The file is larger than 512 MiB"); return }
        _view.update { it.copy(out = OutState(name, src.size, Phase.PREPARING)) }
        scope.launch(Dispatchers.IO) {
            try {
                val sha = src.open().use { sha256Hex(it) }
                cmds.add(Cmd.Offer(OutJob(CAP_PUSH, name, src.size, sha, src.mime, src.open)))
                _view.update { v -> v.out?.takeIf { it.phase == Phase.PREPARING }?.let { v.copy(out = it.copy(phase = Phase.WAITING)) } ?: v }
            } catch (e: Exception) {
                _view.update { it.copy(out = OutState(name, src.size, Phase.FAILED, message = "Cannot read the file")) }
                _events.tryEmit("Cannot read the file")
            }
        }
    }

    /** The user's answer to a file the PC wants to send. */
    fun answer(sid: Long, accept: Boolean) {
        _view.update { v -> v.copy(asks = v.asks.filter { it.sid != sid }) }
        cmds.add(Cmd.Answer(sid, accept))
    }

    fun dismissOut() = _view.update { v -> if (v.out?.active == true) v else v.copy(out = null) }

    private fun canSend(cap: String, off: String): Boolean {
        val v = _view.value
        if (v.link !is Link.Connected) { _events.tryEmit("Not connected"); return false }
        if ((v.hello?.proto ?: 1) < 2) { _events.tryEmit("This PC is too old for transfers (stance 0.7 or newer)"); return false }
        if (cap !in v.caps) { _events.tryEmit(off); return false }
        return true
    }

    /** One connection's stream state; all methods run on the pump coroutine. */
    private inner class Run(val s: Session) {
        var streams = Streams(emptyList(), false)
        val incoming = HashMap<Long, Incoming>()
        val runs = HashMap<Long, OutRun>()
        private var lastProgress = 0L

        fun stream(c: ClientMsg) = s.send(c)

        fun agreed(caps: List<String>) {
            if (streams.open > 0) return
            streams = Streams(caps, false)
            _view.update { it.copy(caps = caps) }
        }

        fun onStream(msg: StreamMsg) {
            var meta: Incoming? = null
            val outs = streams.onMsg(msg, nowMs()) { kind, size, name ->
                when {
                    kind == CAP_CLIP -> { meta = Incoming(kind, size, null); Decision.Accept }
                    kind == CAP_IMAGE && size <= IMAGE_MAX -> { meta = Incoming(kind, size, null); Decision.Accept }
                    kind == CAP_IMAGE -> Decision.Decline("too large")
                    kind == CAP_PULL && host.canSaveFiles -> { meta = Incoming(kind, size, cleanFileName(name ?: "file")); Decision.Defer }
                    else -> Decision.Decline("unsupported")
                }
            }
            val m = meta
            if (msg is StreamMsg.Offer && m != null) {
                incoming[msg.sid] = m
                if (m.kind == CAP_PULL) _view.update { it.copy(asks = it.asks + FileAsk(msg.sid, m.name ?: "file", m.size)) }
            }
            // Our own streams: the PC's answer.
            val run = runs[msg.sid]
            when {
                run != null && msg is StreamMsg.Accept -> setSending(run, 0)
                run != null && msg is StreamMsg.Decline -> result(msg.sid, false, declineMessage(run.job.kind, msg.reason))
                run != null && msg is StreamMsg.Done ->
                    if (msg.ok) result(msg.sid, true, when (run.job.kind) { CAP_CLIP -> "Clipboard sent"; CAP_IMAGE -> "Image sent"; else -> "Sent ${run.job.name}" })
                    else result(msg.sid, false, "Failed: " + cleanText(msg.error ?: "unknown error", 80))
                run != null && msg is StreamMsg.Cancel -> result(msg.sid, false, "Cancelled by the PC: " + cleanText(msg.reason, 60))
            }
            apply(outs)
        }

        fun onData(sid: Long, payload: ByteArray) = apply(streams.onData(sid, payload, nowMs()))

        fun apply(outs: List<Out>) {
            for (o in outs) when (o) {
                is Out.Send -> stream(ClientMsg.Stream(o.msg))
                is Out.Data -> try {
                    incoming[o.sid]?.write(host, o.payload)
                } catch (e: IOException) {
                    incoming[o.sid]?.discard()
                    apply(streams.cancel(o.sid, "write failed"))
                    _events.tryEmit("Cannot store the received data")
                }
                is Out.Finished -> finished(o.sid, o.kind, o.ok)
                is Out.Withdrawn -> {
                    incoming.remove(o.sid)?.discard()
                    _view.update { v -> v.copy(asks = v.asks.filter { it.sid != o.sid }) }
                }
            }
        }

        private fun finished(sid: Long, kind: String, ok: Boolean) {
            val inc = incoming.remove(sid) ?: return
            inc.closeStream()
            if (!ok) {
                inc.discard()
                _events.tryEmit(if (kind == CAP_PULL) "Receiving ${inc.name} failed" else if (kind == CAP_IMAGE) "Image from the PC failed" else "Clipboard from the PC failed")
                return
            }
            if (kind == CAP_IMAGE) {
                val bytes = inc.mem?.toByteArray() ?: ByteArray(0)
                // The content decides the type, never the offered mime.
                val mime = sniffImage(bytes)
                if (mime == null) { _events.tryEmit("The PC sent an image of an unknown type"); return }
                val name = cleanText(pc.name).ifEmpty { "PC" }
                scope.launch(Dispatchers.IO) {
                    try {
                        host.setClipboardImage(bytes, mime)
                        _events.tryEmit("Image from $name")
                    } catch (e: Exception) {
                        _events.tryEmit("Could not set the image: " + cleanText(e.message ?: "error", 60))
                    }
                }
            } else if (kind == CAP_CLIP) {
                val bytes = inc.mem?.toByteArray() ?: ByteArray(0)
                val text = try {
                    Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)).toString()
                } catch (e: CharacterCodingException) { null }
                if (text == null) { _events.tryEmit("The PC sent text that is not valid UTF-8"); return }
                host.setClipboard(text)
                _events.tryEmit("Clipboard from " + cleanText(pc.name).ifEmpty { "PC" })
            } else {
                val f = inc.file ?: return
                val name = inc.name ?: "file"
                // Verified by the stream layer (size + SHA-256); the copy runs off the pump loop.
                scope.launch(Dispatchers.IO) {
                    try {
                        host.saveDownload(f, name)
                        _events.tryEmit("Saved $name to Downloads")
                    } catch (e: Exception) {
                        _events.tryEmit("Could not save $name: " + cleanText(e.message ?: "error", 60))
                    } finally { f.delete() }
                }
            }
        }

        private fun setSending(run: OutRun, sent: Long) {
            val j = run.job
            if (j.kind == CAP_PUSH) _view.update { it.copy(out = OutState(j.name ?: "file", j.size, Phase.SENDING, sent)) }
        }

        private fun result(sid: Long, ok: Boolean, msg: String) {
            val run = runs.remove(sid) ?: return
            runCatching { run.input?.close() }
            val j = run.job
            if (j.kind == CAP_PUSH) _view.update { it.copy(out = OutState(j.name ?: "file", j.size, if (ok) Phase.DONE else Phase.FAILED, if (ok) j.size else run.pos, msg)) }
            _events.tryEmit(msg)
        }

        fun commands() {
            while (true) {
                when (val c = cmds.poll() ?: break) {
                    is Cmd.Offer -> {
                        val j = c.job
                        val offer = try {
                            streams.offer(j.kind, j.size, j.sha, j.name, j.mime, nowMs())
                        } catch (e: DeckException) {
                            val m = when (e.message) {
                                "not negotiated" -> when (j.kind) { CAP_CLIP -> "Clipboard sharing is off on the PC"; CAP_IMAGE -> "Clipboard images are off on the PC"; else -> "Receiving files is off on the PC" }
                                "busy" -> "Another transfer is still running"
                                else -> "Cannot send: " + cleanText(e.message ?: "", 60)
                            }
                            if (j.kind == CAP_PUSH) _view.update { it.copy(out = OutState(j.name ?: "file", j.size, Phase.FAILED, message = m)) }
                            _events.tryEmit(m)
                            continue
                        }
                        stream(ClientMsg.Stream(offer))
                        runs[offer.sid] = OutRun(j)
                        if (j.kind == CAP_PUSH) _view.update { it.copy(out = OutState(j.name ?: "file", j.size, Phase.WAITING)) }
                    }
                    is Cmd.Answer -> {
                        if (c.accept) streams.accept(c.sid, nowMs())?.let { apply(listOf(it)) }
                        else {
                            streams.decline(c.sid, "declined")?.let { apply(listOf(it)) }
                            incoming.remove(c.sid)?.discard()
                        }
                    }
                }
            }
        }

        /** Sends data on our streams as far as the PC's credit allows. */
        fun drive() {
            for ((sid, run) in runs.entries.toList()) {
                var chunks = 0
                while (chunks++ < 32) {
                    val q = streams.quota(sid)
                    val left = run.job.size - run.pos
                    if (q <= 0 || left <= 0) break
                    val n = minOf(q, left, 16_000L).toInt()
                    val buf = ByteArray(n)
                    try {
                        val input = run.input ?: run.job.open().also { run.input = it }
                        var got = 0
                        while (got < n) {
                            val r = input.read(buf, got, n - got)
                            if (r < 0) throw IOException("file shorter than announced")
                            got += r
                        }
                    } catch (e: IOException) {
                        apply(streams.cancel(sid, "read failed"))
                        result(sid, false, "Cannot read the file any more")
                        break
                    }
                    s.sendData(sid, buf)
                    run.pos += n
                    streams.sent(sid, n.toLong(), nowMs())?.let { end ->
                        stream(ClientMsg.Stream(end))
                        runCatching { run.input?.close() }
                    }
                    val t = nowMs()
                    if (run.job.kind == CAP_PUSH && (t - lastProgress > 100 || run.pos == run.job.size)) { lastProgress = t; setSending(run, run.pos) }
                }
            }
        }

        fun tick() = apply(streams.tick(nowMs()))

        /** The session is over: temp data goes, open transfers fail. */
        fun end() {
            val failed = streams.closeAll()
            for (o in failed) if (o is Out.Finished) incoming[o.sid]?.closeStream()
            incoming.values.forEach { it.discard() }
            incoming.clear()
            runs.values.forEach { r -> runCatching { r.input?.close() } }
            val lost = runs.values.firstOrNull { it.job.kind == CAP_PUSH }
            runs.clear()
            _view.update { v ->
                v.copy(
                    asks = emptyList(), caps = emptyList(),
                    out = v.out?.takeIf { it.active }?.let { it.copy(phase = Phase.FAILED, message = "Connection lost") } ?: v.out,
                )
            }
            if (lost != null) _events.tryEmit("Connection lost while sending ${lost.job.name}")
        }
    }

    /** Reads until the connection ends; returns why. */
    private suspend fun pump(s: Session): String {
        var lastPing = System.nanoTime()
        var lastSeen = System.nanoTime()
        var capsSent = false
        val run = Run(s)
        cmds.clear()
        try {
            while (currentCoroutineContext().isActive) {
                val m = s.recv(if (run.streams.open > 0) 50 else 250)
                val now = System.nanoTime()
                if (m != null) lastSeen = now
                when (m) {
                    null -> {}
                    is ServerMsg.Hello -> {
                        _view.update { it.copy(hello = m, state = m.state, stateAtMs = now / 1_000_000) }
                        if (m.hosts.isNotEmpty()) onHosts(m.hosts, m.port)
                        // v2: answer once per connection with the capabilities this app supports.
                        if (m.proto >= 2 && !capsSent) {
                            capsSent = true
                            val want = listOf(CAP_CLIP, CAP_IMAGE, CAP_PUSH) + if (host.canSaveFiles) listOf(CAP_PULL) else emptyList()
                            s.send(ClientMsg.Caps(PROTO, want))
                        }
                    }
                    is ServerMsg.Caps -> if (capsSent) run.agreed(m.caps)
                    is ServerMsg.State -> _view.update {
                        val old = it.state
                        if (old != null && m.state.version < old.version) it
                        else it.copy(state = m.state, stateAtMs = now / 1_000_000)
                    }
                    is ServerMsg.Ack -> {
                        if (!m.ok) { finish(m.req); _events.tryEmit(m.error ?: "refused") }
                    }
                    is ServerMsg.Result -> {
                        val id = finish(m.req)
                        val label = id?.let { i -> _view.value.hello?.buttons?.firstOrNull { it.id == i }?.label }
                        _events.tryEmit(
                            if (m.ok) (m.message?.let { cleanText(it, 80) }?.takeIf { it.isNotEmpty() } ?: label ?: "Done")
                            else cleanText(m.error ?: "failed", 120),
                        )
                    }
                    is ServerMsg.Bye -> return cleanText(m.reason, 80).ifEmpty { "the PC closed the connection" }
                    is ServerMsg.Stream -> run.onStream(m.msg)
                    is ServerMsg.Data -> run.onData(m.sid, m.payload)
                    is ServerMsg.Pong, is ServerMsg.Unknown -> {}
                }
                run.commands()
                run.drive()
                run.tick()
                if (now - lastPing > 15_000_000_000L) {
                    lastPing = now
                    s.send(ClientMsg.Ping(nextReq()))
                }
                if (now - lastSeen > 45_000_000_000L) return "no answer from the PC"
            }
            return "stopped"
        } catch (e: DeckException) {
            return e.message ?: "connection lost"
        } finally {
            run.end()
        }
    }

    private fun nextReq(): Long = synchronized(lock) { ++req }

    private fun finish(r: Long): String? {
        val id = synchronized(lock) { pendingReq.remove(r) }
        if (id != null) _view.update { it.copy(busy = it.busy - id) }
        return id
    }

    private fun sendAsync(msg: ClientMsg, busyId: String? = null, r: Long) {
        val s = session ?: run { _events.tryEmit("Not connected"); return }
        if (busyId != null) {
            synchronized(lock) { pendingReq[r] = busyId }
            _view.update { it.copy(busy = it.busy + busyId) }
        }
        scope.launch(Dispatchers.IO) {
            try { s.send(msg) } catch (e: DeckException) {
                if (busyId != null) finish(r)
                _events.tryEmit("Not connected")
            }
        }
    }

    fun press(id: String) { val r = nextReq(); sendAsync(ClientMsg.Press(id, r), id, r) }
    fun confirm() = sendAsync(ClientMsg.Confirm(nextReq()), null, 0)
    fun revert() = sendAsync(ClientMsg.Revert(nextReq()), null, 0)
}
