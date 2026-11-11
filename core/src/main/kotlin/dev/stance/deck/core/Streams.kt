package dev.stance.deck.core

import java.security.MessageDigest

/**
 * Streams inside one deck session (protocol v2, docs/adr/0003 and 0004 in stance): offers,
 * credit-based flow control, size and hash checks. Pure state, equivalent to `Streams` in
 * stance's src/deck/stream.rs: the session loop feeds it what arrived and sends what it
 * returns. A [DeckException] is a protocol error: the session ends ([closeAll] reports
 * every open stream as failed). Time is passed in milliseconds (any monotonic clock).
 */
object StreamLimits {
    const val WINDOW_MAX = 256L * 1024
    const val IDLE_MS = 30_000L
    const val OPEN_MAX = 2
    const val OFFERS_MAX = 4
    const val OFFER_PERIOD_MS = 10_000L
    const val RECENT_MAX = 8
    const val NAME_MAX = 255
    const val MIME_MAX = 127
    const val CONSENT_WAIT_MS = 60_000L
    val KNOWN_CAPS = listOf("clip.text", "clip.image", "file.push", "file.pull")

    /** Size cap per kind; null for names outside the protocol. */
    fun sizeCap(kind: String): Long? = when (kind) {
        "clip.text" -> 1L shl 20
        "clip.image" -> 32L shl 20
        "file.push", "file.pull" -> 512L shl 20
        else -> null
    }
}

/** The receiving side's answer to a valid offer. */
sealed interface Decision {
    data object Accept : Decision
    data class Decline(val reason: String) : Decision
    /** Ask a person: the offer waits until [Streams.accept] / [Streams.decline], a cancel, or 60 s. */
    data object Defer : Decision
}

/** What the session does next. */
sealed interface Out {
    /** Send this stream message to the peer. */
    data class Send(val msg: StreamMsg) : Out

    /** Payload of an accepted incoming stream, in order. */
    class Data(val sid: Long, val payload: ByteArray) : Out {
        override fun equals(other: Any?) = other is Data && other.sid == sid && other.payload.contentEquals(payload)
        override fun hashCode() = sid.hashCode() * 31 + payload.contentHashCode()
    }

    /** An incoming stream is over. [ok]: all bytes arrived and the SHA-256 matched; else delete its data. */
    data class Finished(val sid: Long, val kind: String, val ok: Boolean) : Out

    /** A deferred offer went away before it was accepted (cancelled, or nobody answered). */
    data class Withdrawn(val sid: Long) : Out
}

private class Inbound(
    val kind: String, val size: Long, val sha256: String,
    var received: Long = 0, var granted: Long, val hasher: MessageDigest = MessageDigest.getInstance("SHA-256"),
    var last: Long, var pending: Boolean,
)

private class Outbound(val size: Long, var sent: Long = 0, var granted: Long = 0, var accepted: Boolean = false, var ended: Boolean = false, var last: Long)

class Streams(agreed: List<String>, private val peerOdd: Boolean) {
    private val agreed = agreed.filter { it in StreamLimits.KNOWN_CAPS }
    private val inbound = LinkedHashMap<Long, Inbound>()
    private val outbound = LinkedHashMap<Long, Outbound>()
    private val recent = ArrayDeque<Long>()
    private val offers = ArrayDeque<Long>()
    private var peerLast = 0L
    private var nextOwn = if (peerOdd) 2L else 1L

    val open: Int get() = inbound.size + outbound.size

    private fun fail(m: String): Nothing = throw DeckException(m)

    private fun close(sid: Long) {
        recent.addLast(sid)
        while (recent.size > StreamLimits.RECENT_MAX) recent.removeFirst()
    }

    private fun isHex64(s: String) = s.length == 64 && s.all { it in '0'..'9' || it in 'a'..'f' }

    /** A stream message from the peer. [decide] is asked only for a valid offer within the limits. */
    fun onMsg(msg: StreamMsg, now: Long, decide: (kind: String, size: Long, name: String?) -> Decision): List<Out> {
        when (msg) {
            is StreamMsg.Offer -> {
                val sid = msg.sid
                if (sid == 0L || (sid % 2 == 1L) != peerOdd || sid <= peerLast) fail("bad stream id")
                peerLast = sid
                while (offers.isNotEmpty() && now - offers.first() > StreamLimits.OFFER_PERIOD_MS) offers.removeFirst()
                // Every offer counts toward the rate limit, even unsupported ones.
                var defer = false
                val refused: String? = if (offers.size >= StreamLimits.OFFERS_MAX) "too fast" else {
                    offers.addLast(now)
                    if (msg.kind !in agreed) "unsupported"
                    else if (msg.size <= 0 || msg.size > (StreamLimits.sizeCap(msg.kind) ?: 0)) "too large"
                    else if (!isHex64(msg.sha256) || (msg.name?.let { it.toByteArray().size > StreamLimits.NAME_MAX } == true) ||
                        (msg.mime?.let { it.toByteArray().size > StreamLimits.MIME_MAX } == true)) "bad offer"
                    else if (inbound.size >= StreamLimits.OPEN_MAX) "busy"
                    else when (val d = decide(msg.kind, msg.size, msg.name)) {
                        is Decision.Decline -> d.reason
                        Decision.Accept -> null
                        Decision.Defer -> { defer = true; null }
                    }
                }
                if (refused != null) {
                    // Remembered: the peer may cancel before the decline arrives.
                    close(sid)
                    return listOf(Out.Send(StreamMsg.Decline(sid, refused)))
                }
                val window = if (defer) 0L else minOf(msg.size, StreamLimits.WINDOW_MAX)
                inbound[sid] = Inbound(msg.kind, msg.size, msg.sha256, granted = window, last = now, pending = defer)
                if (defer) return emptyList()
                return listOf(Out.Send(StreamMsg.Accept(sid, window)))
            }
            is StreamMsg.End -> {
                val s = inbound[msg.sid] ?: return if (msg.sid in recent) emptyList() else fail("unknown stream")
                if (s.received != s.size) fail("end before all data")
                inbound.remove(msg.sid); close(msg.sid)
                val ok = hex(s.hasher.digest()) == s.sha256
                return listOf(
                    Out.Send(StreamMsg.Done(msg.sid, ok, if (ok) null else "checksum mismatch")),
                    Out.Finished(msg.sid, s.kind, ok),
                )
            }
            is StreamMsg.Cancel -> {
                inbound.remove(msg.sid)?.let { s ->
                    close(msg.sid)
                    return listOf(if (s.pending) Out.Withdrawn(msg.sid) else Out.Finished(msg.sid, s.kind, false))
                }
                if (outbound.remove(msg.sid) != null) { close(msg.sid); return emptyList() }
                return if (msg.sid in recent) emptyList() else fail("unknown stream")
            }
            is StreamMsg.Accept -> {
                val o = outbound[msg.sid] ?: return if (msg.sid in recent) emptyList() else fail("unknown stream")
                if (o.accepted || msg.window == 0L || msg.window > StreamLimits.WINDOW_MAX || msg.window > o.size) fail("bad accept")
                o.accepted = true; o.granted = msg.window; o.last = now
                return emptyList()
            }
            is StreamMsg.Window -> {
                val o = outbound[msg.sid] ?: return if (msg.sid in recent) emptyList() else fail("unknown stream")
                val total = o.granted + msg.bytes
                if (!o.accepted || msg.bytes <= 0 || msg.bytes > StreamLimits.WINDOW_MAX || total > o.size) fail("bad window")
                o.granted = total; o.last = now
                return emptyList()
            }
            is StreamMsg.Decline -> {
                val o = outbound[msg.sid]
                if (o == null) return if (msg.sid in recent) emptyList() else fail("unknown stream")
                if (o.accepted) fail("decline after accept")
                outbound.remove(msg.sid); close(msg.sid)
                return emptyList()
            }
            is StreamMsg.Done -> {
                val o = outbound[msg.sid]
                if (o == null) return if (msg.sid in recent) emptyList() else fail("unknown stream")
                if (!o.ended) fail("done before end")
                outbound.remove(msg.sid); close(msg.sid)
                return emptyList()
            }
        }
    }

    /** A data frame from the peer. */
    fun onData(sid: Long, payload: ByteArray, now: Long): List<Out> {
        val s = inbound[sid] ?: return if (sid in recent) emptyList() else fail("data for unknown stream")
        if (s.pending) fail("data before accept")
        if (payload.isEmpty() || payload.size > PAYLOAD_MAX) fail("bad data frame length")
        val received = s.received + payload.size
        if (received > s.granted || received > s.size) fail("data beyond credit")
        s.received = received
        s.hasher.update(payload)
        s.last = now
        val out = mutableListOf<Out>(Out.Data(sid, payload))
        // Keep at least half a window in flight while bytes remain.
        val outstanding = s.granted - s.received
        if (outstanding < StreamLimits.WINDOW_MAX / 2 && s.granted < s.size) {
            val bytes = minOf(s.size - s.granted, StreamLimits.WINDOW_MAX - outstanding)
            s.granted += bytes
            out.add(Out.Send(StreamMsg.Window(sid, bytes)))
        }
        return out
    }

    /** Our own offer. The caller sends the returned message, then data as credit allows ([quota], [sent]). */
    fun offer(kind: String, size: Long, sha256: String, name: String?, mime: String?, now: Long): StreamMsg.Offer {
        if (kind !in agreed) fail("not negotiated")
        if (outbound.size >= StreamLimits.OPEN_MAX) fail("busy")
        if (size <= 0 || size > (StreamLimits.sizeCap(kind) ?: 0) || !isHex64(sha256)) fail("bad offer")
        val sid = nextOwn
        if (sid + 2 > 0xFFFF_FFFFL) fail("out of stream ids")
        nextOwn = sid + 2
        outbound[sid] = Outbound(size, last = now)
        return StreamMsg.Offer(sid, kind, size, sha256, name, mime)
    }

    /** Bytes we may send on our stream now. */
    fun quota(sid: Long): Long = outbound[sid]?.takeIf { it.accepted && !it.ended }?.let { it.granted - it.sent } ?: 0

    /** Records [n] bytes sent; returns `end` once all `size` bytes are out. */
    fun sent(sid: Long, n: Long, now: Long): StreamMsg.End? {
        val o = outbound[sid] ?: fail("unknown stream")
        if (!o.accepted || o.ended || n > o.granted - o.sent) fail("send beyond credit")
        o.sent += n; o.last = now
        if (o.sent == o.size) { o.ended = true; return StreamMsg.End(sid) }
        return null
    }

    /** Answers a deferred offer; null if [sid] is not waiting (any more). */
    fun accept(sid: Long, now: Long): Out? {
        val s = inbound[sid]?.takeIf { it.pending } ?: return null
        val window = minOf(s.size, StreamLimits.WINDOW_MAX)
        s.pending = false; s.granted = window; s.last = now
        return Out.Send(StreamMsg.Accept(sid, window))
    }

    /** Declines a deferred offer. */
    fun decline(sid: Long, reason: String): Out? {
        inbound[sid]?.takeIf { it.pending } ?: return null
        inbound.remove(sid); close(sid)
        return Out.Send(StreamMsg.Decline(sid, reason))
    }

    /** Ends one of our streams or an accepted incoming one early. */
    fun cancel(sid: Long, reason: String): List<Out> {
        val msg = Out.Send(StreamMsg.Cancel(sid, reason))
        inbound.remove(sid)?.let { s ->
            close(sid)
            return listOf(msg, if (s.pending) Out.Withdrawn(sid) else Out.Finished(sid, s.kind, false))
        }
        if (outbound.remove(sid) != null) { close(sid); return listOf(msg) }
        return emptyList()
    }

    /** Cancels streams idle for 30 s; declines offers nobody answered within 60 s. */
    fun tick(now: Long): List<Out> {
        val out = mutableListOf<Out>()
        for (sid in inbound.filter { it.value.pending && now - it.value.last >= StreamLimits.CONSENT_WAIT_MS }.keys.sorted()) {
            inbound.remove(sid); close(sid)
            out.add(Out.Send(StreamMsg.Decline(sid, "no answer"))); out.add(Out.Withdrawn(sid))
        }
        for (sid in inbound.filter { !it.value.pending && now - it.value.last >= StreamLimits.IDLE_MS }.keys.sorted()) {
            val s = inbound.remove(sid)!!; close(sid)
            out.add(Out.Send(StreamMsg.Cancel(sid, "idle"))); out.add(Out.Finished(sid, s.kind, false))
        }
        for (sid in outbound.filter { now - it.value.last >= StreamLimits.IDLE_MS }.keys.sorted()) {
            outbound.remove(sid); close(sid)
            out.add(Out.Send(StreamMsg.Cancel(sid, "idle")))
        }
        return out
    }

    /** The session ended: every open incoming stream failed. */
    fun closeAll(): List<Out> {
        val out = inbound.entries.sortedBy { it.key }.map { (sid, s) -> if (s.pending) Out.Withdrawn(sid) else Out.Finished(sid, s.kind, false) }
        inbound.clear(); outbound.clear()
        return out
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}
