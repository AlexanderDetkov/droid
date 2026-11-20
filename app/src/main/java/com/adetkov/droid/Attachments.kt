package com.adetkov.droid

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

private const val MAX_BYTES = 100L shl 20

/**
 * Attachments live in Droid's folder on shared storage, which the agent reads through the chroot's /sdcard
 * (Android/data/<package>/files/uploads/<id>/<name>); other apps can't read it.
 */
private fun newSlot(ctx: Context, name: String): File {
    val root = ctx.getExternalFilesDir("uploads") ?: File(ctx.filesDir, "uploads")
    return File(root, UUID.randomUUID().toString().take(8)).apply { mkdirs() }.resolve(safeName(name))
}

/**
 * A file name the agent's shell can take without quoting surprises, at most 80 characters. The extension is kept
 * apart from the rest, as the agent goes by it (a picture is a .jpg): "写真.jpg" is "file.jpg".
 */
internal fun safeName(name: String): String {
    fun clean(s: String) = s.replace(Regex("[^A-Za-z0-9._-]+"), "_").replace(Regex("_+"), "_").trim('.', '_')
    val dot = name.lastIndexOf('.')
    val ext = name.substring(dot + 1).takeIf { dot > 0 && it.matches(Regex("[A-Za-z0-9]{1,10}")) }
    val base = clean(if (ext != null) name.substring(0, dot) else name)
    val cut = base.take(if (ext == null) 80 else 79 - ext.length).trimEnd('.', '_').ifEmpty { "file" }
    return if (ext == null) cut else "$cut.$ext"
}

/** Copies [input] to [out], unless it is longer than [max] bytes: then false, with what was copied so far left over. */
internal fun copyAtMost(input: InputStream, out: OutputStream, max: Long): Boolean {
    val buf = ByteArray(DEFAULT_BUFFER_SIZE * 8)
    var total = 0L
    while (true) {
        val n = input.read(buf)
        if (n < 0) return true
        total += n
        if (total > max) return false
        out.write(buf, 0, n)
    }
}

/** Copies a picked document into the attachments folder. Null if it can't be read or is too big. */
private fun import(ctx: Context, uri: Uri): Attachment? {
    var f: File? = null
    return try {
        val cr = ctx.contentResolver
        var name: String? = null
        var size = -1L
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) { name = c.getString(0); if (!c.isNull(1)) size = c.getLong(1) }
        }
        if (size > MAX_BYTES) return null
        val mime = cr.getType(uri) ?: "application/octet-stream"
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
        val n = name ?: ("file" + (ext?.let { ".$it" } ?: ""))
        val slot = newSlot(ctx, n).also { f = it }
        // The size a provider reports (if any) is its word: the cap holds for what actually comes.
        val whole = cr.openInputStream(uri)?.use { input -> slot.outputStream().use { copyAtMost(input, it, MAX_BYTES) } }
        if (whole != true) throw IOException("unreadable, or over ${MAX_BYTES shr 20} MB")
        Attachment(n, slot.path, mime)
    } catch (_: Exception) { f?.let(::drop); null }
}

/** Saves the assistant's screenshot of the app it was opened over. Null if it can't be written. */
fun saveScreenshot(ctx: Context, shot: Bitmap) = save(ctx, shot, "Screenshot.png", Source.SCREEN)

/** Saves a picture from Droid's camera (see [Camera]). Null if it can't be written. Slow: call it off the main thread. */
fun savePhoto(ctx: Context, photo: Bitmap) = save(ctx, photo, "photo_${System.currentTimeMillis() / 1000}.jpg", Source.CAMERA)

private fun save(ctx: Context, bitmap: Bitmap, name: String, source: Source): Attachment? {
    val f = newSlot(ctx, name)
    val png = name.endsWith(".png")
    return try {
        // Android hands screenshots over as hardware bitmaps; encoding wants one in memory.
        val b = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
        val ok = f.outputStream().use { b.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, if (png) 100 else 90, it) }
        if (!ok) throw IOException("encode")
        Attachment(name, f.path, if (png) "image/png" else "image/jpeg", source)
    } catch (_: Exception) { drop(f); null }
}

/** Deletes an attachment that was never sent. */
fun discard(a: Attachment) = drop(File(a.path))

/** Deletes an attachment's file and its folder. */
private fun drop(f: File) { f.delete(); f.parentFile?.delete() }

/** The pickers: each adds what was picked through [onAdd]. (The camera is Droid's own, see [Camera].) */
class Pickers(val photos: () -> Unit, val files: () -> Unit)

@Composable
fun rememberPickers(onAdd: (List<Attachment>) -> Unit): Pickers {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    fun importAll(uris: List<Uri>) = scope.launch {
        val got = withContext(Dispatchers.IO) { uris.mapNotNull { import(ctx, it) } }
        if (got.size < uris.size) Toast.makeText(ctx, "Couldn't attach some files (100 MB max)", Toast.LENGTH_SHORT).show()
        if (got.isNotEmpty()) onAdd(got)
    }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { importAll(it) }
    val docs = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { importAll(it) }
    return remember {
        Pickers(
            photos = { photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
            files = { docs.launch(arrayOf("*/*")) },
        )
    }
}

/** Opens the share sheet for [text]. */
fun shareText(ctx: Context, text: String, subject: String? = null) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    if (subject != null) send.putExtra(Intent.EXTRA_SUBJECT, subject)
    ctx.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** A chat as plain text, for sharing. */
fun transcriptText(c: Conv): String = buildString {
    append(c.title).append("\n\n")
    for (m in c.messages) when (m.kind) {
        Kind.USER -> {
            append("You: ").append(m.text)
            m.files.forEach { append("\n[").append(it.name).append(']') }
            append("\n\n")
        }
        Kind.AGENT -> append("Droid: ").append(stripMarkdown(m.text)).append("\n\n")
        else -> {}
    }
}.trimEnd()
