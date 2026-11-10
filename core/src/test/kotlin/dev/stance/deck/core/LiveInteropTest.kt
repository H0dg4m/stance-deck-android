package dev.stance.deck.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.*

private fun builder(bin: String, dir: File, vararg args: String): ProcessBuilder {
    val pb = ProcessBuilder(listOf(File(bin).absolutePath) + args)
    val e = pb.environment()
    e.remove("HYPRLAND_INSTANCE_SIGNATURE"); e.remove("WAYLAND_DISPLAY")
    e["HOME"] = dir.path
    e["XDG_CONFIG_HOME"] = File(dir, "c").path
    e["XDG_STATE_HOME"] = File(dir, "s").path
    e["XDG_RUNTIME_DIR"] = File(dir, "r").path
    return pb
}


/**
 * Talks to a real `stance deck serve --loopback` (interop proof). Needs a built
 * binary: `./gradlew :core:test -Dstance.binary=../target/debug/stance` or STANCE_BIN.
 * The server runs with isolated XDG dirs and without Hyprland variables, so nothing
 * touches the real desktop; the test never presses a button.
 */
class LiveInteropTest {
    private val bin = System.getProperty("stance.binary").orEmpty()

    private class Env(val dir: File, val port: Int, val server: Process, val bin: String) {
        fun stance(vararg args: String): String {
            val p = builder(bin, dir, *args).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor(20, TimeUnit.SECONDS)
            return out
        }
    }

    private fun <T> withServer(block: (Env) -> T): T {
        assumeTrue(bin.isNotEmpty() && File(bin).canExecute(), "set -Dstance.binary or STANCE_BIN to run live interop")
        // Short path: unix socket paths are limited to ~100 bytes.
        val dir = Files.createTempDirectory("sd").toFile()
        listOf("c", "s", "r").forEach { File(dir, it).mkdirs() }
        File(dir, "r").setReadable(false, false); File(dir, "r").setReadable(true, true)
        File(dir, "r").setWritable(false, false); File(dir, "r").setWritable(true, true)
        File(dir, "r").setExecutable(false, false); File(dir, "r").setExecutable(true, true)
        val port = ServerSocket(0).use { it.localPort }
        val server = builder(bin, dir, "deck", "serve", "--loopback", "--port", port.toString())
            .redirectErrorStream(true).start()
        val log = StringBuilder()
        val reader = Thread { server.inputStream.bufferedReader().forEachLine { synchronized(log) { log.appendLine(it) } } }
        reader.isDaemon = true; reader.start()
        try {
            val end = System.currentTimeMillis() + 15000
            while (System.currentTimeMillis() < end) {
                if (synchronized(log) { log.contains("listening on") } && File(dir, "r/stance/deck.sock").exists()) break
                Thread.sleep(50)
            }
            assertTrue(File(dir, "r/stance/deck.sock").exists(), "server did not start: $log")
            return block(Env(dir, port, server, bin))
        } finally {
            server.destroy()
            if (!server.waitFor(5, TimeUnit.SECONDS)) server.destroyForcibly()
            dir.deleteRecursively()
        }
    }

    private fun payload(env: Env): String =
        Json.parseToJsonElement(env.stance("deck", "pair", "--json")).jsonObject["data"]!!.jsonObject["payload"]!!.jsonPrimitive.content

    private fun hello(s: Session): ServerMsg.Hello {
        val end = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < end) {
            val m = s.recv(500)
            if (m is ServerMsg.Hello) return m
        }
        fail("no hello")
    }

    @Test
    fun pairSessionUnpair() {
        withServer { env ->
        val qr = parseQr(payload(env))
        assertEquals(env.port, qr.port)
        val phone = KeyPair.generate()

        // Pairing: XXpsk3 + pair message, then hello.
        val h1 = Deck.pair(qr, phone, "Kotlin test phone").use { s ->
            assertContentEquals(qr.pcPublic, s.remoteStatic)
            hello(s).also { assertEquals(SCHEMA, it.schema) }
        }
        assertTrue(h1.buttons.isNotEmpty())
        assertTrue(env.stance("deck", "devices").contains("Kotlin test phone"))

        // The pairing code is single use.
        val e = assertFailsWith<DeckException> { Deck.pair(qr, KeyPair.generate(), "second") }
        assertEquals(DeckException.Kind.REFUSED, e.kind)

        // Session: IK, hello, ping/pong.
        Deck.connect("127.0.0.1", env.port, phone, qr.pcPublic).use { s ->
            hello(s)
            s.send(ClientMsg.Ping(7))
            var pong: ServerMsg.Pong? = null
            val end = System.currentTimeMillis() + 5000
            while (pong == null && System.currentTimeMillis() < end) pong = s.recv(500) as? ServerMsg.Pong
            assertEquals(7, pong?.req)
        }

        // Unknown device is refused.
        val bad = assertFailsWith<DeckException> { Deck.connect("127.0.0.1", env.port, KeyPair.generate(), qr.pcPublic) }
        assertEquals(DeckException.Kind.REFUSED, bad.kind)

        // Unpair: the live session ends and new ones are refused.
        Deck.connect("127.0.0.1", env.port, phone, qr.pcPublic).use { s ->
            hello(s)
            env.stance("deck", "unpair", "Kotlin test phone")
            var ended = false
            val end = System.currentTimeMillis() + 8000
            while (!ended && System.currentTimeMillis() < end) {
                try { if (s.recv(300) is ServerMsg.Bye) ended = true } catch (_: DeckException) { ended = true }
            }
            assertTrue(ended, "session survived unpair")
        }
        val after = assertFailsWith<DeckException> { Deck.connect("127.0.0.1", env.port, phone, qr.pcPublic) }
        assertEquals(DeckException.Kind.REFUSED, after.kind)
        }
    }

    @Test
    fun wrongPcKeyIsRejectedByThePhone() {
        withServer { env ->
        val qr = parseQr(payload(env))
        val forged = qr.copy(pcPublic = KeyPair.generate().public)
        assertFailsWith<DeckException> { Deck.pair(forged, KeyPair.generate(), "x") }
        }
    }
}
