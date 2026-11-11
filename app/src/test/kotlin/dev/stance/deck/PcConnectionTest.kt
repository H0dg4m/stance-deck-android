package dev.stance.deck

import dev.stance.deck.core.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/** A tiny in-process PC: IK responder that sends `hello`, then behaves as told. */
private class FakePc(val key: KeyPair, val phone: ByteArray) : AutoCloseable {
    val server = ServerSocket(0, 5, java.net.InetAddress.getLoopbackAddress())
    val port get() = server.localPort
    val accepted = AtomicInteger()
    @Volatile var dropAfterHello = 0   // drop this many connections right after hello
    @Volatile var acceptKnown = true
    @Volatile var v2 = false
    /** Plaintexts sent right after hello (v2 tests), and what the phone sent back. */
    @Volatile var extra: List<ByteArray> = emptyList()
    val received = java.util.concurrent.CopyOnWriteArrayList<String>()
    /** Data frames the phone sent: sid to payload. */
    val data = java.util.concurrent.CopyOnWriteArrayList<Pair<Long, ByteArray>>()
    /** Called for every JSON message the phone sent; `send` writes a plaintext back to the phone. */
    @Volatile var respond: (String, (ByteArray) -> Unit) -> Unit = { _, _ -> }
    @Volatile private var pushFn: ((ByteArray) -> Unit)? = null
    fun push(plain: ByteArray) = pushFn!!.invoke(plain)
    private val thread = Thread {
        try {
            while (true) handle(server.accept())
        } catch (_: Exception) {
        }
    }.apply { isDaemon = true; start() }

    private fun handle(s: Socket) {
        try {
            val f = Framing(s.getInputStream(), s.getOutputStream())
            s.soTimeout = 3000
            val mode = s.getInputStream().read()
            assertEquals(MODE_SESSION.toInt(), mode)
            val hs = HandshakeState(Pattern.IK, false, prologueFor(MODE_SESSION), key)
            hs.readMessage(f.read(PRE_AUTH_MAX))
            if (!acceptKnown || !hs.remoteStatic!!.contentEquals(phone)) { s.close(); return }
            f.write(hs.writeMessage())
            val t = hs.split()
            val n = accepted.incrementAndGet()
            val v2f = if (v2) "\"proto\":2,\"caps\":[\"file.pull\"]," else ""
            val hello = """{"t":"hello","schema":1,${v2f}"name":"Fake","hosts":["127.0.0.1"],"port":$port,"buttons":[{"id":"a","label":"A"}],"state":{"version":1,"active_mode":null,"pending":null,"night":null}}"""
            f.write(t.encrypt(hello.toByteArray()))
            if (dropAfterHello > 0) { dropAfterHello--; Thread.sleep(200); s.close(); return }
            // Keep the connection open until the client goes away.
            val wlock = Any()
            val send = { b: ByteArray -> synchronized(wlock) { f.write(t.encrypt(b)) } }
            pushFn = send
            Thread {
                try {
                    s.soTimeout = 0
                    while (true) {
                        val plain = t.decrypt(f.read(POST_AUTH_MAX))
                        if (plain[0] == DATA_TAG) {
                            val d = decodeDataFrame(plain)
                            data.add(d.sid to d.payload)
                            continue
                        }
                        val m = String(plain)
                        received.add(m)
                        if (m.contains("\"t\":\"caps\"")) {
                            for (x in extra) send(x)
                        }
                        respond(m, send)
                    }
                } catch (_: Exception) {}
            }.apply { isDaemon = true; start() }
        } catch (_: Exception) {
            try { s.close() } catch (_: Exception) {}
        }
    }

    override fun close() { server.close() }
}

class PcConnectionTest {
    private fun waitFor(ms: Long = 15000, what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            if (System.currentTimeMillis() > end) fail("timeout waiting for $what")
            Thread.sleep(50)
        }
    }

    private fun setup(pc: FakePc, phone: KeyPair, scope: CoroutineScope) = PcConnection(
        scope,
        PcRecord(B64Url.encode(pc.key.public), "Fake", listOf("127.0.0.1"), pc.port, ""),
        phone,
    )

    @Test fun connectsAndReceivesHello() {
        val phone = KeyPair.generate()
        FakePc(KeyPair.generate(), phone.public).use { pc ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val c = setup(pc, phone, scope)
            c.start()
            waitFor(what = "connected") { c.view.value.link == Link.Connected && c.view.value.hello != null }
            assertEquals("Fake", c.view.value.hello!!.name)
            assertEquals(listOf("a"), c.view.value.hello!!.buttons.map { it.id })
            c.stop(); scope.cancel()
        }
    }

    @Test fun reconnectsAfterTheServerDrops() {
        val phone = KeyPair.generate()
        FakePc(KeyPair.generate(), phone.public).use { pc ->
            pc.dropAfterHello = 2
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val c = setup(pc, phone, scope)
            val seenWaiting = AtomicInteger()
            val watcher = scope.launch { c.view.collect { if (it.link is Link.Waiting) seenWaiting.incrementAndGet() } }
            c.start()
            // First two connections are dropped, the third stays: 1 s + 2 s of back-off.
            waitFor(20000, "third connection") { pc.accepted.get() >= 3 && c.view.value.link == Link.Connected }
            assertTrue("a waiting state was shown", seenWaiting.get() > 0)
            assertEquals(3, pc.accepted.get())
            watcher.cancel(); c.stop(); scope.cancel()
        }
    }

    @Test fun backsOffWhileNothingListens() {
        val phone = KeyPair.generate()
        val pc = FakePc(KeyPair.generate(), phone.public)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val c = setup(pc, phone, scope)
        pc.close() // nothing listens any more
        c.start()
        waitFor(what = "waiting") { c.view.value.link is Link.Waiting }
        val w = c.view.value.link as Link.Waiting
        assertTrue(w.seconds in 1..15)
        c.stop(); scope.cancel()
    }

    @Test fun unknownDeviceEndsInRefused() {
        val phone = KeyPair.generate()
        FakePc(KeyPair.generate(), KeyPair.generate().public).use { pc ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val c = setup(pc, phone, scope)
            c.start()
            waitFor(what = "refused") { c.view.value.link == Link.Refused }
            // And it stays that way: no retry storm.
            val n = pc.accepted.get()
            Thread.sleep(1500)
            assertEquals(Link.Refused, c.view.value.link)
            assertEquals(n, pc.accepted.get())
            c.stop(); scope.cancel()
        }
    }

    @Test fun v2PcGetsCapsOnceAndStrayStreamsAreDeclined() {
        val phone = KeyPair.generate()
        FakePc(KeyPair.generate(), phone.public).use { pc ->
            pc.v2 = true
            pc.extra = listOf(
                """{"t":"caps","proto":2,"caps":[]}""".toByteArray(),
                """{"t":"offer","sid":2,"kind":"clip.text","size":5,"sha256":"2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"}""".toByteArray(),
                encodeDataFrame(2, "hello".toByteArray()),
                """{"t":"end","sid":2}""".toByteArray(),
            )
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val c = setup(pc, phone, scope)
            c.start()
            waitFor(what = "decline") { pc.received.any { it.contains("\"t\":\"decline\"") } }
            assertEquals("""{"t":"caps","proto":2,"caps":["clip.text","file.push"]}""", pc.received.first())
            assertEquals(1, pc.received.count { it.contains("\"t\":\"caps\"") })
            assertTrue(pc.received.any { it == """{"t":"decline","sid":2,"reason":"unsupported"}""" })
            assertEquals(Link.Connected, c.view.value.link)
            assertEquals(1, pc.accepted.get())
            c.stop(); scope.cancel()
        }
    }

    @Test fun oldPcGetsNoCaps() {
        val phone = KeyPair.generate()
        FakePc(KeyPair.generate(), phone.public).use { pc ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val c = setup(pc, phone, scope)
            c.start()
            waitFor(what = "connected") { c.view.value.hello != null }
            Thread.sleep(500)
            assertTrue(pc.received.none { it.contains("caps") })
            c.stop(); scope.cancel()
        }
    }

    // ---- transfers ----------------------------------------------------------------

    private val HELLO_SHA = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"

    private class FakeHost(val files: Boolean = true) : TransferHost {
        val clip = java.util.concurrent.CopyOnWriteArrayList<String>()
        val saved = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
        val temps = java.util.concurrent.CopyOnWriteArrayList<java.io.File>()
        override val canSaveFiles get() = files
        override fun setClipboard(text: String) { clip.add(text) }
        override fun newTempFile(): java.io.File = java.io.File.createTempFile("stance-test", ".part").also { temps.add(it) }
        override fun saveDownload(temp: java.io.File, name: String): String { saved[name] = temp.readBytes(); return name }
    }

    private class Harness(host: FakeHost, caps: List<String>, extra: List<ByteArray> = emptyList(), pcSetup: (FakePc) -> Unit = {}) : AutoCloseable {
        val phone = KeyPair.generate()
        val pc = FakePc(KeyPair.generate(), phone.public).also {
            it.v2 = true
            it.extra = listOf("""{"t":"caps","proto":2,"caps":[${caps.joinToString(",") { "\"$it\"" }}]}""".toByteArray()) + extra
            pcSetup(it)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val c = PcConnection(scope, PcRecord(B64Url.encode(pc.key.public), "Fake", listOf("127.0.0.1"), pc.port, ""), phone, host)
        val events = java.util.concurrent.CopyOnWriteArrayList<String>()
        init { scope.launch(start = CoroutineStart.UNDISPATCHED) { c.events.collect { events.add(it) } } }
        override fun close() { c.stop(); scope.cancel(); pc.close() }
    }

    @Test fun capsFollowTheHostAndOldHostsDoNotOfferFilePull() {
        val h = Harness(FakeHost(false), emptyList())
        h.use { h.c.start(); waitFor(what = "caps") { h.pc.received.isNotEmpty() }; assertEquals("""{"t":"caps","proto":2,"caps":["clip.text","file.push"]}""", h.pc.received[0]) }
        val h2 = Harness(FakeHost(true), emptyList())
        h2.use { h2.c.start(); waitFor(what = "caps") { h2.pc.received.isNotEmpty() }; assertEquals("""{"t":"caps","proto":2,"caps":["clip.text","file.push","file.pull"]}""", h2.pc.received[0]) }
    }

    @Test fun clipFromThePcIsAcceptedAndSetOnTheClipboard() {
        val host = FakeHost()
        Harness(host, listOf("clip.text"), listOf(
            """{"t":"offer","sid":2,"kind":"clip.text","size":5,"sha256":"$HELLO_SHA","mime":"text/plain"}""".toByteArray(),
            encodeDataFrame(2, "hello".toByteArray()),
            """{"t":"end","sid":2}""".toByteArray(),
        )).use { h ->
            h.c.start()
            waitFor(what = "clipboard") { host.clip.isNotEmpty() }
            assertEquals(listOf("hello"), host.clip)
            assertTrue(h.pc.received.contains("""{"t":"accept","sid":2,"window":5}"""))
            assertTrue(h.pc.received.contains("""{"t":"done","sid":2,"ok":true}"""))
            waitFor(what = "event") { h.events.contains("Clipboard from Fake") }
        }
    }

    @Test fun clipWithABadHashIsNotApplied() {
        val host = FakeHost()
        Harness(host, listOf("clip.text"), listOf(
            """{"t":"offer","sid":2,"kind":"clip.text","size":5,"sha256":"$HELLO_SHA"}""".toByteArray(),
            encodeDataFrame(2, "world".toByteArray()),
            """{"t":"end","sid":2}""".toByteArray(),
        )).use { h ->
            h.c.start()
            waitFor(what = "done") { h.pc.received.any { it.contains("\"t\":\"done\"") } }
            assertTrue(h.pc.received.any { it.contains("\"ok\":false") })
            assertTrue(host.clip.isEmpty())
        }
    }

    @Test fun filePullAsksTheUserThenSavesAfterVerification() {
        val host = FakeHost()
        Harness(host, listOf("file.pull"), listOf(
            """{"t":"offer","sid":2,"kind":"file.pull","size":5,"sha256":"$HELLO_SHA","name":"../.. /a b.txt"}""".toByteArray(),
        )).use { h ->
            h.c.start()
            waitFor(what = "ask") { h.c.view.value.asks.isNotEmpty() }
            val ask = h.c.view.value.asks.single()
            assertEquals("a b.txt", ask.name)
            assertEquals(5, ask.size)
            Thread.sleep(300)
            assertTrue("no answer before the user decides", h.pc.received.none { it.contains("\"accept\"") || it.contains("decline") })
            h.c.answer(ask.sid, true)
            waitFor(what = "accept") { h.pc.received.contains("""{"t":"accept","sid":2,"window":5}""") }
            assertTrue(h.c.view.value.asks.isEmpty())
            h.pc.push(encodeDataFrame(2, "hello".toByteArray()))
            h.pc.push("""{"t":"end","sid":2}""".toByteArray())
            waitFor(what = "saved") { host.saved.containsKey("a b.txt") }
            assertEquals("hello", String(host.saved["a b.txt"]!!))
            assertTrue(h.pc.received.contains("""{"t":"done","sid":2,"ok":true}"""))
            waitFor(what = "temp file removed") { host.temps.all { !it.exists() } }
            assertTrue(h.events.any { it == "Saved a b.txt to Downloads" })
        }
    }

    @Test fun filePullDeclinedByTheUserLeavesNothingBehind() {
        val host = FakeHost()
        Harness(host, listOf("file.pull"), listOf(
            """{"t":"offer","sid":2,"kind":"file.pull","size":5,"sha256":"$HELLO_SHA","name":"a.txt"}""".toByteArray(),
        )).use { h ->
            h.c.start()
            waitFor(what = "ask") { h.c.view.value.asks.isNotEmpty() }
            h.c.answer(2, false)
            waitFor(what = "decline") { h.pc.received.contains("""{"t":"decline","sid":2,"reason":"declined"}""") }
            assertTrue(host.saved.isEmpty() && host.temps.isEmpty())
            assertEquals(Link.Connected, h.c.view.value.link)
        }
    }

    @Test fun filePullWithoutSupportIsDeclinedAndTheAskGoesAwayWhenThePcCancels() {
        val host = FakeHost(false)
        Harness(host, listOf("file.pull"), listOf(
            """{"t":"offer","sid":2,"kind":"file.pull","size":5,"sha256":"$HELLO_SHA","name":"a.txt"}""".toByteArray(),
        )).use { h ->
            h.c.start()
            waitFor(what = "decline") { h.pc.received.contains("""{"t":"decline","sid":2,"reason":"unsupported"}""") }
        }
        Harness(FakeHost(), listOf("file.pull"), listOf(
            """{"t":"offer","sid":2,"kind":"file.pull","size":5,"sha256":"$HELLO_SHA","name":"a.txt"}""".toByteArray(),
        )).use { h ->
            h.c.start()
            waitFor(what = "ask") { h.c.view.value.asks.isNotEmpty() }
            h.pc.push("""{"t":"cancel","sid":2,"reason":"changed my mind"}""".toByteArray())
            waitFor(what = "ask gone") { h.c.view.value.asks.isEmpty() }
        }
    }

    /** The fake PC accepts offers with the full window and collects data frames. */
    private fun accepting(answer: (String, String) -> String?): (FakePc) -> Unit = { pc ->
        pc.respond = { m, send ->
            val o = Regex("\"t\":\"offer\",\"sid\":(\\d+),\"kind\":\"([a-z.]+)\",\"size\":(\\d+)").find(m)
            if (o != null) {
                val (sid, kind, size) = o.destructured
                answer(kind, size)?.let { a -> send(a.replace("SID", sid).replace("SIZE", minOf(size.toLong(), 262144).toString()).toByteArray()) }
            }
            if (m.contains("\"t\":\"end\"")) send("""{"t":"done","sid":1,"ok":true}""".toByteArray())
        }
    }

    @Test fun clipToThePcIsOfferedSentAndAcknowledged() {
        Harness(FakeHost(), listOf("clip.text"), pcSetup = accepting { _, _ -> """{"t":"accept","sid":SID,"window":SIZE}""" }).use { h ->
            h.c.start()
            waitFor(what = "agreed") { h.c.view.value.caps == listOf("clip.text") }
            h.c.sendClip("hi there")
            waitFor(what = "result") { h.events.contains("Clipboard sent") }
            assertTrue(h.pc.received.any { it.startsWith("""{"t":"offer","sid":1,"kind":"clip.text","size":8,"sha256":"${sha256Hex("hi there".toByteArray())}"""") })
            assertEquals("hi there", String(h.pc.data.single().second))
            assertEquals(1L, h.pc.data.single().first)
        }
    }

    @Test fun clipToThePcShowsThePcsRefusal() {
        Harness(FakeHost(), listOf("clip.text"), pcSetup = accepting { _, _ -> """{"t":"decline","sid":SID,"reason":"off"}""" }).use { h ->
            h.c.start()
            waitFor(what = "agreed") { h.c.view.value.caps.isNotEmpty() }
            h.c.sendClip("x")
            waitFor(what = "answer") { h.events.contains("Clipboard sharing is off on the PC") }
            assertTrue(h.pc.data.isEmpty())
        }
    }

    @Test fun sendingWithoutTheCapabilityTellsTheUserWithoutAnOffer() {
        Harness(FakeHost(), emptyList()).use { h ->
            h.c.start()
            waitFor(what = "connected") { h.c.view.value.link == Link.Connected && h.c.view.value.hello != null }
            waitFor(what = "caps reply") { h.pc.received.isNotEmpty() }
            Thread.sleep(300)
            h.c.sendClip("x")
            h.c.sendFile(OutSource("a.bin", 3, null) { java.io.ByteArrayInputStream(ByteArray(3)) })
            waitFor(what = "events") { h.events.size >= 2 }
            assertEquals(listOf("Clipboard sharing is off on the PC", "Receiving files is off on the PC"), h.events.toList())
            assertTrue(h.pc.received.none { it.contains("offer") })
        }
    }

    @Test fun fileToThePcStreamsInCreditAndReportsDone() {
        val bytes = ByteArray(700_000) { (it % 251).toByte() }
        // The fake PC grants 256 KiB, then 256 KiB more per window message as data arrives.
        val granted = java.util.concurrent.atomic.AtomicLong()
        val got = java.util.concurrent.atomic.AtomicLong()
        Harness(FakeHost(), listOf("file.push"), pcSetup = { pc ->
            pc.respond = { m, send ->
                if (m.contains("\"t\":\"offer\"")) { granted.set(262144); send("""{"t":"accept","sid":1,"window":262144}""".toByteArray()) }
                if (m.contains("\"t\":\"end\"")) send("""{"t":"done","sid":1,"ok":true}""".toByteArray())
            }
        }).use { h ->
            // Top the credit up from the test thread while data arrives.
            val topUp = h.scope.launch(Dispatchers.IO) {
                while (isActive) {
                    val have = h.pc.data.sumOf { it.second.size.toLong() }
                    if (have + 131072 > granted.get() && granted.get() < bytes.size) {
                        val more = minOf(262144L, bytes.size - granted.get())
                        granted.addAndGet(more)
                        h.pc.push("""{"t":"window","sid":1,"bytes":$more}""".toByteArray())
                    }
                    delay(5)
                }
            }
            h.c.start()
            waitFor(what = "agreed") { h.c.view.value.caps == listOf("file.push") }
            h.c.sendFile(OutSource("../big.bin", bytes.size.toLong(), "application/octet-stream") { java.io.ByteArrayInputStream(bytes) })
            waitFor(30000, "done") { h.c.view.value.out?.phase == Phase.DONE }
            topUp.cancel()
            val all = java.io.ByteArrayOutputStream().also { o -> h.pc.data.forEach { o.write(it.second) } }.toByteArray()
            assertArrayEquals(bytes, all)
            assertTrue(h.pc.received.any { it.contains("\"name\":\"big.bin\"") && it.contains(sha256Hex(bytes)) })
            assertEquals("Sent big.bin", h.c.view.value.out?.message)
            assertTrue(h.pc.data.all { it.second.size <= 16000 })
        }
    }

    @Test fun fileToThePcDeclinedOnThePc() {
        Harness(FakeHost(), listOf("file.push"), pcSetup = accepting { _, _ -> """{"t":"decline","sid":SID,"reason":"declined"}""" }).use { h ->
            h.c.start()
            waitFor(what = "agreed") { h.c.view.value.caps.isNotEmpty() }
            h.c.sendFile(OutSource("a.bin", 4, null) { java.io.ByteArrayInputStream(ByteArray(4)) })
            waitFor(what = "failed") { h.c.view.value.out?.phase == Phase.FAILED }
            assertEquals("Declined on the PC", h.c.view.value.out?.message)
            assertTrue(h.pc.data.isEmpty())
            h.c.dismissOut()
            assertNull(h.c.view.value.out)
        }
    }

    @Test fun oversizedFilesAreRefusedBeforeAnyOffer() {
        Harness(FakeHost(), listOf("file.push")).use { h ->
            h.c.start()
            waitFor(what = "agreed") { h.c.view.value.caps.isNotEmpty() }
            h.c.sendFile(OutSource("huge.bin", 513L shl 20, null) { throw java.io.IOException() })
            waitFor(what = "event") { h.events.contains("The file is larger than 512 MiB") }
            assertTrue(h.pc.received.none { it.contains("offer") })
        }
    }
}
