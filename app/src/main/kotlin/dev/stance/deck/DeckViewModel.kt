package dev.stance.deck

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.stance.deck.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Home : Screen
    data object Pair : Screen
    data class Pc(val pcPublic: String) : Screen
}

/** A scanned or pasted code waiting for the user to compare fingerprints. */
data class PairRequest(val qr: QrPayload, val working: Boolean = false, val error: String? = null)

class DeckViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)

    private val _pcs = MutableStateFlow(store.list())
    val pcs: StateFlow<List<PcRecord>> = _pcs.asStateFlow()

    private val _screen = MutableStateFlow<Screen>(if (_pcs.value.size == 1) Screen.Pc(_pcs.value[0].pcPublic) else Screen.Home)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _pair = MutableStateFlow<PairRequest?>(null)
    val pair: StateFlow<PairRequest?> = _pair.asStateFlow()

    private val _connection = MutableStateFlow<PcConnection?>(null)
    val connection: StateFlow<PcConnection?> = _connection.asStateFlow()

    private var foreground = false

    fun go(s: Screen) {
        _screen.value = s
        if (s !is Screen.Pair) _pair.value = null
        syncConnection()
    }

    fun back(): Boolean = when (_screen.value) {
        Screen.Home -> false
        else -> { go(Screen.Home); true }
    }

    fun onStart() { foreground = true; syncConnection() }
    fun onStop() { foreground = false; syncConnection() }

    /** Foreground only: the connection lives while a PC screen is visible. */
    private fun syncConnection() {
        val s = _screen.value
        val want = if (foreground && s is Screen.Pc) _pcs.value.firstOrNull { it.pcPublic == s.pcPublic } else null
        val cur = _connection.value
        if (want == null) {
            cur?.stop(); _connection.value = null; return
        }
        if (cur != null && currentPc == want.pcPublic) { cur.start(); return }
        cur?.stop()
        val c = PcConnection(viewModelScope, want, store.ownKey(want)) { hosts, port -> updateHosts(want.pcPublic, hosts, port) }
        currentPc = want.pcPublic
        _connection.value = c
        c.start()
    }

    private var currentPc: String? = null

    private fun updateHosts(pcPublic: String, hosts: List<String>, port: Int) {
        val r = store.list().firstOrNull { it.pcPublic == pcPublic } ?: return
        val clean = hosts.map { cleanText(it, 64) }.filter { it.isNotEmpty() }.take(6)
        if (clean.isEmpty() || (clean == r.hosts && port == r.port)) return
        store.upsert(r.copy(hosts = clean, port = port))
        _pcs.value = store.list()
    }

    fun codeFound(text: String) {
        if (_pair.value != null) return
        try {
            _pair.value = PairRequest(parseQr(text))
        } catch (e: DeckException) {
            _pair.value = null
            _scanError.value = e.message
        }
    }

    private val _scanError = MutableStateFlow<String?>(null)
    val scanError: StateFlow<String?> = _scanError.asStateFlow()
    fun clearScanError() { _scanError.value = null }

    fun cancelPair() { _pair.value = null }

    fun confirmPair() {
        val req = _pair.value ?: return
        if (req.working) return
        _pair.value = req.copy(working = true, error = null)
        viewModelScope.launch {
            val own = KeyPair.generate()
            val deviceName = cleanText(Build.MODEL ?: "Phone").ifEmpty { "Phone" }
            val result = withContext(Dispatchers.IO) {
                try {
                    Deck.pair(req.qr, own, deviceName).use { s ->
                        // Wait for the hello so we only save a pairing the PC really accepted.
                        val end = System.currentTimeMillis() + 5000
                        var hello: ServerMsg.Hello? = null
                        while (hello == null && System.currentTimeMillis() < end) hello = s.recv(500) as? ServerMsg.Hello
                        hello ?: throw DeckException("the PC did not finish pairing")
                    }
                } catch (e: DeckException) {
                    e
                }
            }
            when (result) {
                is DeckException -> _pair.value = req.copy(working = false, error = friendly(result))
                is ServerMsg.Hello -> {
                    val pc = PcRecord(
                        pcPublic = B64Url.encode(req.qr.pcPublic),
                        name = cleanText(result.name).ifEmpty { req.qr.name },
                        hosts = req.qr.hosts,
                        port = req.qr.port,
                        ownPrivateWrapped = store.wrap(own),
                    )
                    store.upsert(pc)
                    _pcs.value = store.list()
                    _pair.value = null
                    go(Screen.Pc(pc.pcPublic))
                }
                else -> {}
            }
        }
    }

    private fun friendly(e: DeckException) = when (e.kind) {
        DeckException.Kind.REFUSED -> "The PC refused: the code was already used or has expired. Show a new one with `stance deck pair`."
        DeckException.Kind.IO -> "Cannot reach the PC (${e.message}). Same Wi-Fi? Is `stance deck serve` running?"
        else -> e.message ?: "Pairing failed"
    }

    fun forget(pcPublic: String) {
        store.remove(pcPublic)
        _pcs.value = store.list()
        if ((_screen.value as? Screen.Pc)?.pcPublic == pcPublic) go(Screen.Home) else syncConnection()
    }

    override fun onCleared() { _connection.value?.stop() }
}
