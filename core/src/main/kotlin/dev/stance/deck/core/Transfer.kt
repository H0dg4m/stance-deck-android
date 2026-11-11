package dev.stance.deck.core

import java.io.InputStream
import java.security.MessageDigest

const val NAME_BYTES = 120
const val EXT_BYTES = 16
const val CLIP_TEXT_MAX = 1 shl 20
const val FILE_MAX = 512L shl 20

private fun invisible(c: Char) = c == '\u00AD' || c == '\u061C' || c == '\u180E' || c in '\u200B'..'\u200F' ||
    c in '\u202A'..'\u202E' || c in '\u2060'..'\u206F' || c == '\uFEFF' || c in '\uFFF9'..'\uFFFB'

/**
 * A file name from the PC, safe to create in Downloads (mirrors `clean_name` in stance's
 * transfer.rs): last path component, no control/invisible/bidi characters, no leading dots,
 * dashes or spaces, no trailing dots or spaces, at most 120 bytes keeping a short extension.
 */
fun cleanFileName(raw: String): String {
    val last = raw.substringAfterLast('/').substringAfterLast('\\')
    val kept = last.filter { !it.isISOControl() && !invisible(it) }
    var t = kept.trimStart('.', '-', ' ').trimEnd('.', ' ')
    if (t.isEmpty()) t = "file"
    if (t.toByteArray().size <= NAME_BYTES) return t
    val i = t.lastIndexOf('.')
    val (stem, ext) = if (i > 0 && t.substring(i).toByteArray().size <= EXT_BYTES + 1) t.substring(0, i) to t.substring(i) else t to ""
    val budget = NAME_BYTES - ext.toByteArray().size
    val sb = StringBuilder()
    var used = 0
    var k = 0
    while (k < stem.length) {
        val cp = stem.codePointAt(k)
        val s = String(Character.toChars(cp))
        val n = s.toByteArray().size
        if (used + n > budget) break
        sb.append(s); used += n; k += s.length
    }
    return sb.toString().trimEnd('.', ' ') + ext
}

fun humanSize(n: Long): String = when {
    n >= 1 shl 20 -> "%.1f MB".format(java.util.Locale.ROOT, n / (1 shl 20).toDouble())
    n >= 1 shl 10 -> "%.0f KB".format(java.util.Locale.ROOT, n / 1024.0)
    else -> "$n B"
}

fun sha256Hex(input: InputStream): String {
    val md = MessageDigest.getInstance("SHA-256")
    val buf = ByteArray(64 * 1024)
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        md.update(buf, 0, n)
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

fun sha256Hex(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

/** What the PC answered to our offer, in words for the person. */
fun declineMessage(kind: String, reason: String): String {
    val r = cleanText(reason, 60)
    return when (r) {
        "off" -> if (kind == "clip.text") "Clipboard sharing is off on the PC" else "File receiving is off on the PC"
        "declined" -> "Declined on the PC"
        "no answer" -> "No answer on the PC (timed out)"
        "cooldown" -> "The PC paused offers from this phone for a few minutes (too many declined)"
        "no space" -> "Not enough free space on the PC"
        "too large" -> "Too large for the PC"
        "busy" -> "The PC is busy with another transfer"
        "too fast" -> "Too many offers at once"
        "unsupported" -> "The PC does not accept this"
        else -> "Declined: $r"
    }
}
