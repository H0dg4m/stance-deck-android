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

    @Test fun imagesAreSniffedFromContentOnly() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0)
        assertEquals("image/png", sniffImage(png))
        assertEquals("image/jpeg", sniffImage(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertEquals("image/gif", sniffImage("GIF89a....".toByteArray()))
        assertEquals("image/gif", sniffImage("GIF87a....".toByteArray()))
        assertEquals("image/webp", sniffImage("RIFF\u0001\u0002\u0003\u0004WEBPVP8 ".toByteArray(Charsets.ISO_8859_1)))
        assertNull(sniffImage("RIFF\u0001\u0002\u0003\u0004WAVEfmt ".toByteArray(Charsets.ISO_8859_1)))
        assertNull(sniffImage("BM......".toByteArray()))          // BMP
        assertNull(sniffImage("<svg xmlns=...>".toByteArray()))
        assertNull(sniffImage("GIF90a".toByteArray()))
        assertNull(sniffImage(byteArrayOf(0x89.toByte(), 0x50, 0x4E)))  // truncated
        assertNull(sniffImage(ByteArray(0)))
        assertEquals(32L shl 20, IMAGE_MAX)
    }

    @Test fun declineWords() {
        assertEquals("Clipboard images are off on the PC", declineMessage("clip.image", "off"))
        assertEquals("Clipboard sharing is off on the PC", declineMessage("clip.text", "off"))
        assertEquals("Declined on the PC", declineMessage("file.push", "declined"))
    }
}
