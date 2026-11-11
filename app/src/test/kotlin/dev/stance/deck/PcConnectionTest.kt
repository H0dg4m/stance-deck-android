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
            val v2f = if (v2) "\"proto\":2,\"caps\":[]," else ""
            val hello = """{"t":"hello","schema":1,${v2f}"name":"Fake","hosts":["127.0.0.1"],"port":$port,"buttons":[{"id":"a","label":"A"}],"state":{"version":1,"active_mode":null,"pending":null,"night":null}}"""
            f.write(t.encrypt(hello.toByteArray()))
            if (dropAfterHello > 0) { dropAfterHello--; Thread.sleep(200); s.close(); return }
            // Keep the connection open until the client goes away.
            Thread {
                try {
                    s.soTimeout = 0
                    while (true) {
                        val m = String(t.decrypt(f.read(POST_AUTH_MAX)))
                        received.add(m)
                        if (m.contains("\"t\":\"caps\"")) {
                            for (x in extra) f.write(t.encrypt(x))
                        }
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
            assertEquals("""{"t":"caps","proto":2,"caps":[]}""", pc.received.first())
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
}
