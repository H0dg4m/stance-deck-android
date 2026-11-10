package dev.stance.deck.core

import kotlinx.serialization.json.*
import java.util.Base64

const val SCHEMA = 1
val PROLOGUE: ByteArray = "stance-deck/1".toByteArray(Charsets.US_ASCII)
const val MODE_SESSION: Byte = 1
const val MODE_PAIR: Byte = 2
const val PRE_AUTH_MAX = 1024
const val POST_AUTH_MAX = 16 * 1024
const val DEFAULT_PORT = 47800

fun prologueFor(mode: Byte): ByteArray = PROLOGUE + byteArrayOf(mode)

object B64Url {
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()
    fun encode(b: ByteArray): String = enc.encodeToString(b)
    fun decode(s: String): ByteArray = dec.decode(s.trim())
    fun decodeKey(s: String): ByteArray = try {
        decode(s).also { require(it.size == 32) }
    } catch (e: IllegalArgumentException) {
        throw DeckException("malformed key")
    }
}

/** First 8 base64url characters of a public key as `AbCd-EfGh`. */
fun fingerprint(publicB64: String): String {
    val t = publicB64.take(8)
    return t.take(4) + "-" + t.drop(4)
}

fun fingerprint(public: ByteArray): String = fingerprint(B64Url.encode(public))

/** Error carrying a stable kind so the UI can react differently. */
open class DeckException(message: String, val kind: Kind = Kind.PROTOCOL) : Exception(message) {
    enum class Kind { PROTOCOL, REFUSED, IO, CLOSED }
}

// ---- messages ---------------------------------------------------------------

data class Button(val id: String, val label: String, val icon: String?, val on: Boolean?)
data class Pending(val label: String, val remainingMs: Long)
data class DeckState(val version: Long, val activeMode: String?, val pending: Pending?, val night: Int?)

sealed interface ServerMsg {
    data class Hello(
        val schema: Int, val name: String, val hosts: List<String>, val port: Int,
        val buttons: List<Button>, val state: DeckState,
    ) : ServerMsg

    data class State(val state: DeckState) : ServerMsg
    data class Ack(val req: Long, val ok: Boolean, val error: String?) : ServerMsg
    data class Result(val req: Long, val ok: Boolean, val message: String?, val error: String?) : ServerMsg
    data class Pong(val req: Long) : ServerMsg
    data class Bye(val reason: String) : ServerMsg

    /** A type this client does not know: ignore it. */
    data class Unknown(val type: String) : ServerMsg
}

sealed interface ClientMsg {
    data class Pair(val name: String) : ClientMsg
    data class Press(val id: String, val req: Long) : ClientMsg
    data class Confirm(val req: Long) : ClientMsg
    data class Revert(val req: Long) : ClientMsg
    data class Ping(val req: Long) : ClientMsg
    data object Bye : ClientMsg
}

fun ClientMsg.toJson(): String = buildJsonObject {
    when (val m = this@toJson) {
        is ClientMsg.Pair -> { put("t", "pair"); put("name", m.name) }
        is ClientMsg.Press -> { put("t", "press"); put("id", m.id); put("req", m.req) }
        is ClientMsg.Confirm -> { put("t", "confirm"); put("req", m.req) }
        is ClientMsg.Revert -> { put("t", "revert"); put("req", m.req) }
        is ClientMsg.Ping -> { put("t", "ping"); put("req", m.req) }
        ClientMsg.Bye -> put("t", "bye")
    }
}.toString()

private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.long(k: String) = (this[k] as? JsonPrimitive)?.longOrNull
private fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull

private fun parseState(o: JsonObject): DeckState {
    val p = o["pending"] as? JsonObject
    return DeckState(
        version = o.long("version") ?: 0,
        activeMode = o.str("active_mode"),
        pending = p?.let { Pending(it.str("label") ?: "", it.long("remaining_ms") ?: 0) },
        night = o.long("night")?.toInt(),
    )
}

fun parseServerMsg(text: String): ServerMsg {
    val o = try {
        Json.parseToJsonElement(text) as? JsonObject
    } catch (e: Exception) {
        null
    } ?: throw DeckException("bad message")
    return when (val t = o.str("t") ?: throw DeckException("message without type")) {
        "hello" -> ServerMsg.Hello(
            schema = o.long("schema")?.toInt() ?: 0,
            name = o.str("name") ?: "",
            hosts = (o["hosts"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
            port = o.long("port")?.toInt() ?: DEFAULT_PORT,
            buttons = (o["buttons"] as? JsonArray)?.mapNotNull { b ->
                val bo = b as? JsonObject ?: return@mapNotNull null
                Button(bo.str("id") ?: return@mapNotNull null, bo.str("label") ?: "", bo.str("icon"), bo.bool("on"))
            } ?: emptyList(),
            state = parseState(o["state"] as? JsonObject ?: JsonObject(emptyMap())),
        )
        "state" -> ServerMsg.State(parseState(o["state"] as? JsonObject ?: JsonObject(emptyMap())))
        "ack" -> ServerMsg.Ack(o.long("req") ?: 0, o.bool("ok") ?: false, o.str("error"))
        "result" -> ServerMsg.Result(o.long("req") ?: 0, o.bool("ok") ?: false, o.str("message"), o.str("error"))
        "pong" -> ServerMsg.Pong(o.long("req") ?: 0)
        "bye" -> ServerMsg.Bye(o.str("reason") ?: "")
        else -> ServerMsg.Unknown(t)
    }
}

// ---- QR payload -------------------------------------------------------------

data class QrPayload(val hosts: List<String>, val port: Int, val pcPublic: ByteArray, val psk: ByteArray, val name: String) {
    val pcFingerprint: String get() = fingerprint(pcPublic)
}

private fun pctDecode(s: String): String {
    val out = java.io.ByteArrayOutputStream()
    val b = s.toByteArray(Charsets.UTF_8)
    var i = 0
    while (i < b.size) {
        if (b[i] == '%'.code.toByte() && i + 2 < b.size) {
            val v = String(b, i + 1, 2, Charsets.US_ASCII).toIntOrNull(16)
            if (v != null) { out.write(v); i += 3; continue }
        }
        out.write(b[i].toInt()); i++
    }
    return out.toString(Charsets.UTF_8)
}

/** Strips control characters and bounds length: PC-provided text is untrusted. */
fun cleanText(s: String, max: Int = 40): String = s.filter { !it.isISOControl() }.take(max).trim()

fun parseQr(text: String): QrPayload {
    val prefix = "stance-deck:1?"
    val t = text.trim()
    if (!t.startsWith(prefix)) throw DeckException("not a stance pairing code")
    val params = t.removePrefix(prefix).split('&').mapNotNull {
        val i = it.indexOf('='); if (i < 0) null else it.substring(0, i) to it.substring(i + 1)
    }.toMap()
    val hosts = (params["h"] ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() }.take(6)
    val port = params["p"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: throw DeckException("not a stance pairing code")
    val k = B64Url.decodeKey(params["k"] ?: throw DeckException("not a stance pairing code"))
    val s = B64Url.decodeKey(params["s"] ?: throw DeckException("not a stance pairing code"))
    if (hosts.isEmpty()) throw DeckException("pairing code has no address")
    return QrPayload(hosts, port, k, s, cleanText(pctDecode(params["n"] ?: "")).ifEmpty { "PC" })
}
