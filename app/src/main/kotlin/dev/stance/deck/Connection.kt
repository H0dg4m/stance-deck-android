package dev.stance.deck

import dev.stance.deck.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

sealed interface Link {
    data object Idle : Link
    data class Connecting(val attempt: Int) : Link
    data object Connected : Link
    data class Waiting(val reason: String, val seconds: Int) : Link
    /** The PC no longer knows this phone: unpaired. */
    data object Refused : Link
}

data class DeckView(
    val link: Link = Link.Idle,
    val hello: ServerMsg.Hello? = null,
    val state: DeckState? = null,
    /** Local time ([System.nanoTime]/1e6) when [state] arrived, for the pending countdown. */
    val stateAtMs: Long = 0,
    val busy: Set<String> = emptySet(),
)

/**
 * Keeps one PC connected while the app is in the foreground: tries the saved
 * addresses in turn, reconnects with back-off, and turns server messages into [view].
 */
class PcConnection(
    private val scope: CoroutineScope,
    private val pc: PcRecord,
    private val own: dev.stance.deck.core.KeyPair,
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

    /** Reads until the connection ends; returns why. */
    private suspend fun pump(s: Session): String {
        var lastPing = System.nanoTime()
        var lastSeen = System.nanoTime()
        while (currentCoroutineContext().isActive) {
            val m = try {
                s.recv(500)
            } catch (e: DeckException) {
                return e.message ?: "connection lost"
            }
            val now = System.nanoTime()
            if (m != null) lastSeen = now
            when (m) {
                null -> {}
                is ServerMsg.Hello -> {
                    _view.update { it.copy(hello = m, state = m.state, stateAtMs = now / 1_000_000) }
                    if (m.hosts.isNotEmpty()) onHosts(m.hosts, m.port)
                }
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
                is ServerMsg.Pong, is ServerMsg.Unknown -> {}
            }
            if (now - lastPing > 15_000_000_000L) {
                lastPing = now
                try { s.send(ClientMsg.Ping(nextReq())) } catch (e: DeckException) { return e.message ?: "connection lost" }
            }
            if (now - lastSeen > 45_000_000_000L) return "no answer from the PC"
        }
        return "stopped"
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
