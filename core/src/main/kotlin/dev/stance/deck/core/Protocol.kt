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

/** Session protocol this app speaks (docs/adr/0003 in stance); a PC without `proto` is 1. */
const val PROTO = 2
const val DATA_TAG: Byte = 0
const val PAYLOAD_MAX = 16363

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
        val proto: Int = 1, val caps: List<String> = emptyList(),
    ) : ServerMsg

    /** v2: the capabilities both sides agreed on. */
    data class Caps(val proto: Int, val caps: List<String>) : ServerMsg

    /** v2: a stream message (offer, accept, ...). */
    data class Stream(val msg: StreamMsg) : ServerMsg

    /** v2: a binary data frame. */
    class Data(val sid: Long, val payload: ByteArray) : ServerMsg

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
    data class Caps(val proto: Int, val caps: List<String>) : ClientMsg
    data class Stream(val msg: StreamMsg) : ClientMsg
    data object Bye : ClientMsg
}

fun ClientMsg.toJson(): String = buildJsonObject {
    when (val m = this@toJson) {
        is ClientMsg.Pair -> { put("t", "pair"); put("name", m.name) }
        is ClientMsg.Press -> { put("t", "press"); put("id", m.id); put("req", m.req) }
        is ClientMsg.Confirm -> { put("t", "confirm"); put("req", m.req) }
        is ClientMsg.Revert -> { put("t", "revert"); put("req", m.req) }
        is ClientMsg.Ping -> { put("t", "ping"); put("req", m.req) }
        is ClientMsg.Caps -> { put("t", "caps"); put("proto", m.proto); put("caps", JsonArray(m.caps.map(::JsonPrimitive))) }
        is ClientMsg.Stream -> m.msg.fill(this)
        ClientMsg.Bye -> put("t", "bye")
    }
}.toString()

/** v2 stream messages; the same JSON in both directions. `sid` is a u32 (kept as Long). */
sealed interface StreamMsg {
    val sid: Long

    data class Offer(override val sid: Long, val kind: String, val size: Long, val sha256: String, val name: String? = null, val mime: String? = null) : StreamMsg
    data class Accept(override val sid: Long, val window: Long) : StreamMsg
    data class Decline(override val sid: Long, val reason: String) : StreamMsg
    data class Window(override val sid: Long, val bytes: Long) : StreamMsg
    data class End(override val sid: Long) : StreamMsg
    data class Done(override val sid: Long, val ok: Boolean, val error: String? = null) : StreamMsg
    data class Cancel(override val sid: Long, val reason: String) : StreamMsg
}

private fun StreamMsg.fill(b: JsonObjectBuilder) {
    when (this) {
        is StreamMsg.Offer -> { b.put("t", "offer"); b.put("sid", sid); b.put("kind", kind); b.put("size", size); b.put("sha256", sha256); name?.let { b.put("name", it) }; mime?.let { b.put("mime", it) } }
        is StreamMsg.Accept -> { b.put("t", "accept"); b.put("sid", sid); b.put("window", window) }
        is StreamMsg.Decline -> { b.put("t", "decline"); b.put("sid", sid); b.put("reason", reason) }
        is StreamMsg.Window -> { b.put("t", "window"); b.put("sid", sid); b.put("bytes", bytes) }
        is StreamMsg.End -> { b.put("t", "end"); b.put("sid", sid) }
        is StreamMsg.Done -> { b.put("t", "done"); b.put("sid", sid); b.put("ok", ok); error?.let { b.put("error", it) } }
        is StreamMsg.Cancel -> { b.put("t", "cancel"); b.put("sid", sid); b.put("reason", reason) }
    }
}

fun StreamMsg.toJson(): String = buildJsonObject { fill(this) }.toString()

private val STREAM_TYPES = setOf("offer", "accept", "decline", "window", "end", "done", "cancel")

/** Null when a required field is missing or of the wrong type. */
private fun parseStream(t: String, o: JsonObject): StreamMsg? {
    val sid = o.long("sid")?.takeIf { it in 0..0xFFFF_FFFFL } ?: return null
    return when (t) {
        "offer" -> StreamMsg.Offer(sid, o.str("kind") ?: return null, o.long("size") ?: return null, o.str("sha256") ?: return null, o.str("name"), o.str("mime"))
        "accept" -> StreamMsg.Accept(sid, o.long("window") ?: return null)
        "decline" -> StreamMsg.Decline(sid, o.str("reason") ?: "")
        "window" -> StreamMsg.Window(sid, o.long("bytes") ?: return null)
        "end" -> StreamMsg.End(sid)
        "done" -> StreamMsg.Done(sid, o.bool("ok") ?: return null, o.str("error"))
        "cancel" -> StreamMsg.Cancel(sid, o.str("reason") ?: "")
        else -> null
    }
}

/** Data frame plaintext: 0x00, sid u32 big-endian, 1..[PAYLOAD_MAX] payload bytes. */
fun encodeDataFrame(sid: Long, payload: ByteArray): ByteArray {
    if (sid !in 0..0xFFFF_FFFFL) throw DeckException("bad stream id")
    if (payload.isEmpty() || payload.size > PAYLOAD_MAX) throw DeckException("data frame size out of bounds")
    val b = ByteArray(5 + payload.size)
    b[0] = DATA_TAG
    for (i in 0..3) b[1 + i] = (sid ushr (24 - 8 * i)).toByte()
    System.arraycopy(payload, 0, b, 5, payload.size)
    return b
}

fun decodeDataFrame(plain: ByteArray): ServerMsg.Data {
    if (plain.isEmpty() || plain[0] != DATA_TAG) throw DeckException("not a data frame")
    if (plain.size < 6 || plain.size - 5 > PAYLOAD_MAX) throw DeckException("data frame size out of bounds")
    var sid = 0L
    for (i in 1..4) sid = (sid shl 8) or (plain[i].toLong() and 0xFF)
    return ServerMsg.Data(sid, plain.copyOfRange(5, plain.size))
}

/** A decrypted plaintext: a binary data frame or a JSON message. */
fun parsePlain(plain: ByteArray): ServerMsg =
    if (plain.isNotEmpty() && plain[0] == DATA_TAG) decodeDataFrame(plain)
    else parseServerMsg(String(plain, Charsets.UTF_8))

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
            proto = o.long("proto")?.toInt() ?: 1,
            caps = parseCaps(o),
        )
        "caps" -> ServerMsg.Caps(o.long("proto")?.toInt() ?: 1, parseCaps(o))
        "state" -> ServerMsg.State(parseState(o["state"] as? JsonObject ?: JsonObject(emptyMap())))
        "ack" -> ServerMsg.Ack(o.long("req") ?: 0, o.bool("ok") ?: false, o.str("error"))
        "result" -> ServerMsg.Result(o.long("req") ?: 0, o.bool("ok") ?: false, o.str("message"), o.str("error"))
        "pong" -> ServerMsg.Pong(o.long("req") ?: 0)
        "bye" -> ServerMsg.Bye(o.str("reason") ?: "")
        in STREAM_TYPES -> parseStream(t, o)?.let { ServerMsg.Stream(it) } ?: ServerMsg.Unknown(t)
        else -> ServerMsg.Unknown(t)
    }
}

private fun parseCaps(o: JsonObject): List<String> =
    (o["caps"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } ?: emptyList()

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
