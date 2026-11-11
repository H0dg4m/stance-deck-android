package dev.stance.deck

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.IOException

/** Android side of transfers: clipboard, app cache for incoming data, public Downloads (MediaStore, API 29+). */
class AndroidHost(private val app: Application) : TransferHost {
    private val dir get() = File(app.cacheDir, "incoming").apply { mkdirs() }

    /** Left-overs of an interrupted transfer are removed at start. */
    fun sweep() { dir.listFiles()?.forEach { it.delete() } }

    // Without MediaStore.Downloads (API < 29) saving would need the legacy storage permission: not offered.
    override val canSaveFiles: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    override fun setClipboard(text: String) {
        Handler(Looper.getMainLooper()).post {
            val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            runCatching { cm.setPrimaryClip(ClipData.newPlainText("stance", text)) }
        }
    }

    override fun newTempFile(): File = File.createTempFile("in", ".part", dir)

    override fun saveDownload(temp: File, name: String): String {
        if (!canSaveFiles) throw IOException("needs Android 10 or newer")
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/stance")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = app.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("cannot create the file")
        try {
            (resolver.openOutputStream(uri) ?: throw IOException("cannot write the file")).use { out -> temp.inputStream().use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        return Environment.DIRECTORY_DOWNLOADS + "/stance/" + name
    }
}

/** Name, size and a reader for a content Uri from the file picker or a share; null if unreadable. */
fun sourceFromUri(ctx: Context, uri: Uri): OutSource? {
    val r = ctx.contentResolver
    var name = "file"
    var size = -1L
    runCatching {
        r.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { c.getString(it)?.let { n -> name = n } }
                c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
            }
        }
    }
    if (size < 0) size = runCatching { r.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L }.getOrDefault(-1L)
    return OutSource(name, size, r.getType(uri)) { r.openInputStream(uri) ?: throw IOException("cannot open") }
}
