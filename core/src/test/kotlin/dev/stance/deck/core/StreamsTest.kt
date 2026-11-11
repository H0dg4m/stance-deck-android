package dev.stance.deck.core

import java.security.MessageDigest
import kotlin.test.*

/** Mirrors the unit tests of `Streams` in stance's src/deck/stream.rs. */
class StreamsTest {
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun sha(s: String) = sha(s.toByteArray())
    private val caps = listOf("file.push", "clip.text")
    private val accept = { _: String, _: Long, _: String? -> Decision.Accept }
    private val defer = { _: String, _: Long, _: String? -> Decision.Defer }
    private val t = 1_000_000L
    private val period = StreamLimits.OFFER_PERIOD_MS
    private val idle = StreamLimits.IDLE_MS

    private fun offer(sid: Long, kind: String, size: Long) = StreamMsg.Offer(sid, kind, size, sha("x"))
    private fun cancel(sid: Long) = StreamMsg.Cancel(sid, "x")
    private fun declined(outs: List<Out>): String = (outs.single() as Out.Send).msg.let { (it as StreamMsg.Decline).reason }
    private fun isAccept(o: Out) = o is Out.Send && o.msg is StreamMsg.Accept

    /** The phone (odd sids) sends [data] to the PC through both state machines. */
    private fun transfer(data: ByteArray, hash: String): Pair<ByteArray, List<Out>> {
        val phone = Streams(caps, false)
        val pc = Streams(caps, true)
        val offer = phone.offer("file.push", data.size.toLong(), hash, "a.txt", null, t)
        val toPhone = ArrayDeque<StreamMsg>()
        val got = java.io.ByteArrayOutputStream()
        val finished = mutableListOf<Out>()
        fun handle(outs: List<Out>) = outs.forEach {
            when (it) {
                is Out.Send -> toPhone.addLast(it.msg)
                is Out.Data -> got.write(it.payload)
                else -> finished.add(it)
            }
        }
        handle(pc.onMsg(offer, t, accept))
        val sid = 1L
        var off = 0
        while (true) {
            while (toPhone.isNotEmpty()) assertTrue(phone.onMsg(toPhone.removeFirst(), t, accept).isEmpty())
            val q = phone.quota(sid).toInt()
            if (q == 0) break
            val n = minOf(q, 16000, data.size - off)
            handle(pc.onData(sid, data.copyOfRange(off, off + n), t))
            off += n
            val end = phone.sent(sid, n.toLong(), t)
            if (end != null) {
                handle(pc.onMsg(end, t, accept))
                while (toPhone.isNotEmpty()) phone.onMsg(toPhone.removeFirst(), t, accept)
                break
            }
        }
        assertEquals(0, phone.open + pc.open)
        return got.toByteArray() to finished
    }

    @Test fun aTransferCrossesManyWindows() {
        val data = ByteArray(700_000) { (it % 251).toByte() }
        val (got, fin) = transfer(data, sha(data))
        assertContentEquals(data, got)
        assertEquals(listOf<Out>(Out.Finished(1, "file.push", true)), fin)
    }

    @Test fun aWrongHashFailsTheStreamNotTheSession() {
        val (_, fin) = transfer(ByteArray(1000) { 7 }, sha("other"))
        assertEquals(listOf<Out>(Out.Finished(1, "file.push", false)), fin)
    }

    @Test fun offersAreChecked() {
        val pc = Streams(caps, true)
        assertEquals("unsupported", declined(pc.onMsg(offer(1, "clip.image", 10), t, accept)))
        assertEquals("too large", declined(pc.onMsg(offer(3, "clip.text", 2L shl 20), t, accept)))
        assertEquals("too large", declined(pc.onMsg(offer(5, "clip.text", 0), t, accept)))
        val bad = StreamMsg.Offer(7, "clip.text", 3, "ABC")
        assertEquals("bad offer", declined(pc.onMsg(bad, t, accept)))
        val no = { _: String, _: Long, _: String? -> Decision.Decline("refused") }
        assertEquals("refused", declined(pc.onMsg(offer(9, "clip.text", 3), t + period * 2, no)))
        // sids only grow: a reused, lower or zero sid is a protocol error; so is the wrong parity
        assertFailsWith<DeckException> { pc.onMsg(offer(10, "clip.text", 3), t, accept) }
        assertFailsWith<DeckException> { pc.onMsg(offer(9, "clip.text", 3), t, accept) }
        assertFailsWith<DeckException> { pc.onMsg(offer(0, "clip.text", 3), t, accept) }
    }

    @Test fun deferredOffersWaitForAnAnswer() {
        val pc = Streams(caps, true)
        assertEquals(emptyList(), pc.onMsg(offer(1, "file.push", 10), t, defer))
        assertEquals(1, pc.open)
        assertFailsWith<DeckException> { pc.onData(1, byteArrayOf(1), t) } // data before accept
        assertEquals(emptyList(), pc.tick(t + idle))
        assertEquals(
            listOf(Out.Send(StreamMsg.Decline(1, "no answer")), Out.Withdrawn(1)),
            pc.tick(t + StreamLimits.CONSENT_WAIT_MS),
        )
        assertNull(pc.accept(1, t))
        pc.onMsg(offer(3, "file.push", 10), t, defer)
        assertEquals(Out.Send(StreamMsg.Accept(3, 10)), pc.accept(3, t))
        assertNull(pc.accept(3, t))
        assertTrue(pc.onData(3, ByteArray(10) { 1 }, t)[0] is Out.Data)
        pc.onMsg(offer(5, "clip.text", 10), t, defer)
        assertEquals(listOf<Out>(Out.Withdrawn(5)), pc.onMsg(cancel(5), t, defer))
        pc.onMsg(offer(7, "clip.text", 10), t + period * 2, defer)
        assertEquals(Out.Send(StreamMsg.Decline(7, "no")), pc.decline(7, "no"))
        assertNull(pc.decline(7, "no"))
        pc.onMsg(offer(9, "clip.text", 10), t + period * 2, defer)
        assertTrue(Out.Withdrawn(9) in pc.closeAll())
    }

    @Test fun aCancelCrossingADeclineIsTolerated() {
        val pc = Streams(caps, true)
        assertEquals("unsupported", declined(pc.onMsg(offer(1, "clip.image", 10), t, accept)))
        assertEquals(emptyList(), pc.onMsg(StreamMsg.Cancel(1, "user"), t, accept))
    }

    @Test fun unsupportedOffersCountTowardTheRateLimit() {
        val pc = Streams(emptyList(), true)
        for (sid in listOf(1L, 3, 5, 7)) assertEquals("unsupported", declined(pc.onMsg(offer(sid, "clip.text", 3), t, accept)))
        assertEquals("too fast", declined(pc.onMsg(offer(9, "clip.text", 3), t, accept)))
    }

    @Test fun offersAreRateLimitedAndOpenStreamsCapped() {
        val pc = Streams(caps, true)
        assertTrue(isAccept(pc.onMsg(offer(1, "clip.text", 3), t, accept)[0]))
        assertTrue(isAccept(pc.onMsg(offer(3, "clip.text", 3), t, accept)[0]))
        assertEquals("busy", declined(pc.onMsg(offer(5, "clip.text", 3), t, accept)))
        assertEquals("busy", declined(pc.onMsg(offer(7, "clip.text", 3), t, accept)))
        assertEquals("too fast", declined(pc.onMsg(offer(9, "clip.text", 3), t, accept)))
        pc.onMsg(cancel(1), t, accept)
        assertTrue(isAccept(pc.onMsg(offer(11, "clip.text", 3), t + period + 1000, accept)[0]))
    }

    @Test fun dataRules() {
        var pc = Streams(caps, true)
        assertFailsWith<DeckException> { pc.onData(1, byteArrayOf(1), t) }
        pc.onMsg(offer(1, "file.push", 600_000), t, accept)
        assertFailsWith<DeckException> { pc.onData(1, byteArrayOf(), t) }
        assertFailsWith<DeckException> { pc.onData(1, ByteArray(PAYLOAD_MAX + 1), t) }
        assertFailsWith<DeckException> { pc.onMsg(StreamMsg.End(1), t, accept) }
        pc = Streams(caps, true)
        pc.onMsg(offer(1, "file.push", 600_000), t, accept)
        var sent = 0L
        var granted = StreamLimits.WINDOW_MAX
        val e = try {
            while (true) {
                val outs = pc.onData(1, ByteArray(16_000), t)
                sent += 16_000
                outs.forEach { if (it is Out.Send && it.msg is StreamMsg.Window) granted += (it.msg as StreamMsg.Window).bytes }
                assertTrue(sent <= granted)
            }
            error("unreachable")
        } catch (e: DeckException) { e }
        assertTrue(e.message!!.contains("credit"))
    }

    @Test fun cancelledSidsTolerateFramesInFlight() {
        val pc = Streams(caps, true)
        pc.onMsg(offer(1, "file.push", 100_000), t, accept)
        pc.onData(1, ByteArray(1000) { 1 }, t)
        assertEquals(listOf<Out>(Out.Finished(1, "file.push", false)), pc.onMsg(StreamMsg.Cancel(1, "user"), t, accept))
        assertEquals(emptyList(), pc.onData(1, ByteArray(1000) { 1 }, t))
        assertEquals(emptyList(), pc.onMsg(StreamMsg.End(1), t, accept))
        // after 8 more closed sids, sid 1 is forgotten and frames for it are errors again
        for (i in 0 until StreamLimits.RECENT_MAX) {
            val sid = 3L + 2 * i
            pc.onMsg(offer(sid, "clip.text", 1), t + period * (i + 1), accept)
            pc.onMsg(cancel(sid), t, accept)
        }
        assertFailsWith<DeckException> { pc.onData(1, byteArrayOf(1), t) }
    }

    @Test fun idleStreamsAreCancelledAndSessionEndFailsOpenOnes() {
        val pc = Streams(caps, true)
        pc.onMsg(offer(1, "file.push", 100_000), t, accept)
        pc.onMsg(offer(3, "clip.text", 10), t, accept)
        pc.onData(3, ByteArray(5) { 1 }, t + idle / 2)
        assertEquals(listOf(Out.Send(StreamMsg.Cancel(1, "idle")), Out.Finished(1, "file.push", false)), pc.tick(t + idle))
        assertEquals(listOf<Out>(Out.Finished(3, "clip.text", false)), pc.closeAll())
    }

    @Test fun senderSideRules() {
        val phone = Streams(caps, false)
        assertFailsWith<DeckException> { phone.offer("clip.image", 5, sha("x"), null, null, t) }
        val sid = phone.offer("clip.text", 5, sha("x"), null, null, t).sid
        assertEquals(1, sid)
        assertEquals(0, phone.quota(sid))
        assertFailsWith<DeckException> { phone.sent(sid, 1, t) }
        // bad accepts / windows / done each end the session (on a fresh copy of the state)
        fun fresh(): Streams = Streams(caps, false).also { it.offer("clip.text", 5, sha("x"), null, null, t) }
        assertFailsWith<DeckException> { fresh().onMsg(StreamMsg.Accept(sid, 6), t, accept) }
        phone.onMsg(StreamMsg.Accept(sid, 3), t, accept)
        assertFailsWith<DeckException> { fresh().apply { onMsg(StreamMsg.Accept(sid, 3), t, accept) }.onMsg(StreamMsg.Accept(sid, 3), t, accept) }
        assertFailsWith<DeckException> { fresh().onMsg(StreamMsg.Window(sid, 3), t, accept) } // window before accept
        assertFailsWith<DeckException> { fresh().onMsg(StreamMsg.Done(sid, true), t, accept) } // done before end
        assertEquals(3, phone.quota(sid))
        assertNull(phone.sent(sid, 3, t))
        phone.onMsg(StreamMsg.Window(sid, 2), t, accept)
        assertEquals(StreamMsg.End(sid), phone.sent(sid, 2, t))
        assertFailsWith<DeckException> { phone.onMsg(StreamMsg.Decline(sid, "x"), t, accept) } // decline after accept
        phone.onMsg(StreamMsg.Done(sid, true), t, accept)
        assertEquals(0, phone.open)
        assertEquals(3, phone.offer("clip.text", 5, sha("x"), null, null, t).sid)
    }

    @Test fun windowCannotExceedSize() {
        val phone = Streams(caps, false)
        val sid = phone.offer("clip.text", 5, sha("x"), null, null, t).sid
        phone.onMsg(StreamMsg.Accept(sid, 3), t, accept)
        assertFailsWith<DeckException> { phone.onMsg(StreamMsg.Window(sid, 3), t, accept) }
    }

    @Test fun ownOpenStreamsAreCapped() {
        val phone = Streams(caps, false)
        phone.offer("clip.text", 5, sha("x"), null, null, t)
        phone.offer("clip.text", 5, sha("x"), null, null, t)
        assertFailsWith<DeckException> { phone.offer("clip.text", 5, sha("x"), null, null, t) }
    }
}
