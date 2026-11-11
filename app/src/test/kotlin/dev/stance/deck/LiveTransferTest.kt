package dev.stance.deck

import dev.stance.deck.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * The app's PcConnection against a real `stance deck serve --loopback` (stance 0.7): clipboard and
 * files in both directions. Needs `-Dstance.binary=/path/to/stance` (or STANCE_BIN); skipped without.
 * The server runs on loopback with isolated XDG directories, no Hyprland variables and fake
 * `wl-copy` / `notify-send` (STANCE_TEST_TOOLS, like tests/fake.sh in stance), so nothing touches
 * the real desktop.
 */
class LiveTransferTest {
    private val bin = System.getProperty("stance.binary").orEmpty()

    private class Env(val dir: File, val port: Int, val bin: String) {
        val tools = File(dir, "tools")
        val dl = File(dir, "dl")
        val answer = File(dir, "answer")

        fun builder(vararg args: String): ProcessBuilder {
            val pb = ProcessBuilder(listOf(File(bin).absolutePath) + args)
            val e = pb.environment()
            e.remove("HYPRLAND_INSTANCE_SIGNATURE"); e.remove("WAYLAND_DISPLAY")
            e["HOME"] = dir.path
            e["XDG_CONFIG_HOME"] = File(dir, "c").path
            e["XDG_STATE_HOME"] = File(dir, "s").path
            e["XDG_RUNTIME_DIR"] = File(dir, "r").path
            e["STANCE_TEST_TOOLS"] = tools.path
            return pb
        }

        fun stance(vararg args: String, stdin: String? = null, env: Map<String, String> = emptyMap(), stdinFile: File? = null): String {
            val pb = builder(*args).redirectErrorStream(true)
            pb.environment().putAll(env)
            if (stdinFile != null) pb.redirectInput(stdinFile)
            val p = pb.start()
            if (stdinFile != null) {} else if (stdin != null) p.outputStream.use { it.write(stdin.toByteArray()) } else p.outputStream.close()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor(20, TimeUnit.SECONDS)
            return out
        }
    }

    private fun waitFor(ms: Long = 15000, what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            if (System.currentTimeMillis() > end) fail("timeout waiting for $what")
            Thread.sleep(50)
        }
    }

    /** A small valid-looking PNG (signature + marker byte so each direction differs). */
    private fun png(marker: Int) = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(2000) { (it + marker).toByte() }

    private class FakeHost : TransferHost {
        val clip = java.util.concurrent.CopyOnWriteArrayList<String>()
        val images = java.util.concurrent.CopyOnWriteArrayList<Pair<String, ByteArray>>()
        override fun setClipboardImage(bytes: ByteArray, mime: String) { images.add(mime to bytes) }
        val saved = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
        override val canSaveFiles = true
        override fun setClipboard(text: String) { clip.add(text) }
        override fun newTempFile(): File = File.createTempFile("stance-live", ".part")
        override fun saveDownload(temp: File, name: String): String { saved[name] = temp.readBytes(); return name }
    }

    @Test fun clipboardAndFilesBothWays() {
        assumeTrue("set -Dstance.binary or STANCE_BIN to run live interop", bin.isNotEmpty() && File(bin).canExecute())
        // Short path: unix socket paths are limited to ~100 bytes.
        val dir = Files.createTempDirectory("sd").toFile()
        listOf("c", "s", "r", "tools", "dl").forEach { File(dir, it).mkdirs() }
        File(dir, "r").setReadable(false, false); File(dir, "r").setReadable(true, true)
        File(dir, "r").setWritable(false, false); File(dir, "r").setWritable(true, true)
        File(dir, "r").setExecutable(false, false); File(dir, "r").setExecutable(true, true)
        val env = Env(dir, ServerSocket(0).use { it.localPort }, bin)
        val clipboard = File(dir, "clipboard")
        File(env.tools, "wl-copy").apply { writeText("#!/bin/sh\ncat > '${clipboard.path}'\n"); setExecutable(true) }
        File(env.tools, "notify-send").apply {
            writeText("#!/bin/sh\ncase \"\$*\" in *--wait*) cat '${env.answer.path}' 2>/dev/null;; esac\n"); setExecutable(true)
        }
        val server = env.builder("deck", "serve", "--loopback", "--port", env.port.toString()).redirectErrorStream(true).start()
        val log = StringBuilder()
        Thread { server.inputStream.bufferedReader().forEachLine { synchronized(log) { log.appendLine(it) } } }.apply { isDaemon = true; start() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var conn: PcConnection? = null
        try {
            waitFor(what = "server (${synchronized(log) { log }})") { synchronized(log) { log.contains("listening on") } && File(dir, "r/stance/deck.sock").exists() }
            env.stance("deck", "transfer", "put", "--data",
                """{"clip_to_phone":true,"clip_from_phone":"notify","clip_images":true,"files_from_phone":"ask","download_dir":"${env.dl.path}"}""")
            env.stance("clip", "config", "put", "--data", """{"enabled":true}""")

            val qr = parseQr(Json.parseToJsonElement(env.stance("deck", "pair", "--json")).jsonObject["data"]!!.jsonObject["payload"]!!.jsonPrimitive.content)
            val phone = KeyPair.generate()
            Deck.pair(qr, phone, "Live phone").close()
            val host = FakeHost()
            val c = PcConnection(scope, PcRecord(B64Url.encode(qr.pcPublic), "Live PC", listOf("127.0.0.1"), env.port, ""), phone, host)
            conn = c
            val events = java.util.concurrent.CopyOnWriteArrayList<String>()
            scope.launch(start = CoroutineStart.UNDISPATCHED) { c.events.collect { events.add(it) } }
            c.start()
            waitFor(what = "all capabilities") { c.view.value.caps.toSet() == setOf(CAP_CLIP, CAP_IMAGE, CAP_PUSH, CAP_PULL) }

            // clip to the PC
            c.sendClip("from android ä€")
            waitFor(what = "clip sent") { events.contains("Clipboard sent") }
            waitFor(what = "wl-copy") { clipboard.exists() && clipboard.readText() == "from android ä€" }

            // file to the PC, accepted in the (fake) notification
            val bytes = ByteArray(300_000) { (it * 7 % 251).toByte() }
            env.answer.writeText("accept")
            c.sendFile(OutSource("up.bin", bytes.size.toLong(), null) { ByteArrayInputStream(bytes) })
            waitFor(20000, "file done") { c.view.value.out?.phase == Phase.DONE }
            waitFor(what = "file saved") { File(env.dl, "up.bin").exists() }
            assertArrayEquals(bytes, File(env.dl, "up.bin").readBytes())
            c.dismissOut()

            // file to the PC, declined on the PC
            env.answer.writeText("decline")
            c.sendFile(OutSource("no.bin", 100, null) { ByteArrayInputStream(ByteArray(100)) })
            waitFor(20000, "file declined") { c.view.value.out?.phase == Phase.FAILED }
            assertEquals("Declined on the PC", c.view.value.out?.message)
            assertFalse(File(env.dl, "no.bin").exists())
            assertEquals(Link.Connected, c.view.value.link)

            // file from the PC: `stance deck send`, the phone user accepts
            val down = ByteArray(200_000) { (it % 13).toByte() }
            val src = File(dir, "down.bin").apply { writeBytes(down) }
            env.stance("deck", "send", src.path, "--to", "Live phone")
            waitFor(what = "ask") { c.view.value.asks.isNotEmpty() }
            val ask = c.view.value.asks.single()
            assertEquals("down.bin", ask.name)
            assertEquals(down.size.toLong(), ask.size)
            c.answer(ask.sid, true)
            waitFor(20000, "file received") { host.saved.containsKey("down.bin") }
            assertArrayEquals(down, host.saved["down.bin"])
            waitFor(what = "saved event") { events.contains("Saved down.bin to Downloads") }

            // clip from the PC: a new copy on the PC's shelf goes to the phone
            env.stance("clip", "ingest", stdin = "from the pc", env = mapOf("CLIPBOARD_STATE" to "data"))
            waitFor(what = "clip from the PC") { host.clip.contains("from the pc") }
            assertTrue(events.contains("Clipboard from Live PC"))

            // image to the PC (stored by the fake wl-copy as the clipboard content)
            val pngUp = png(1)
            c.sendClipImage(OutSource("x", pngUp.size.toLong(), "text/plain") { ByteArrayInputStream(pngUp) })
            waitFor(what = "image sent") { events.contains("Image sent") }
            waitFor(what = "wl-copy image") { clipboard.exists() && clipboard.readBytes().contentEquals(pngUp) }

            // image from the PC: a different image (the PC does not echo the phone's own back within 30 s)
            val pngDown = png(2)
            val img = File(dir, "down.png").apply { writeBytes(pngDown) }
            env.stance("clip", "ingest", "--image", stdin = null, env = mapOf("CLIPBOARD_STATE" to "data"), stdinFile = img)
            waitFor(what = "image from the PC") { host.images.isNotEmpty() }
            assertEquals("image/png", host.images.single().first)
            assertArrayEquals(pngDown, host.images.single().second)
            assertTrue(events.contains("Image from Live PC"))
        } finally {
            conn?.stop()
            scope.cancel()
            server.destroy()
            if (!server.waitFor(5, TimeUnit.SECONDS)) server.destroyForcibly()
            dir.deleteRecursively()
        }
    }
}
