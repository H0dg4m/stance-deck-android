package dev.stance.deck.core

import kotlin.test.*

class TransferTest {
    @Test fun namesAreCleaned() {
        assertEquals("a.txt", cleanFileName("../../etc/a.txt"))
        assertEquals("a.txt", cleanFileName("C:\\x\\a.txt"))
        assertEquals("rc", cleanFileName("...--  rc. "))
        assertEquals("file", cleanFileName("..."))
        assertEquals("file", cleanFileName("a/"))
        assertEquals("ab.txt", cleanFileName("a\u0000\u202Eb.txt"))
        val long = cleanFileName("é".repeat(200) + ".tar.gz")
        assertTrue(long.toByteArray().size <= NAME_BYTES)
        assertTrue(long.endsWith(".gz"))
    }

    @Test fun sizesAndHash() {
        assertEquals("12 B", humanSize(12)); assertEquals("2 KB", humanSize(2048)); assertEquals("1.5 MB", humanSize(3L shl 19))
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", sha256Hex("hello".toByteArray()))
    }

    @Test fun declineWords() {
        assertEquals("Clipboard sharing is off on the PC", declineMessage("clip.text", "off"))
        assertEquals("Declined on the PC", declineMessage("file.push", "declined"))
    }
}
