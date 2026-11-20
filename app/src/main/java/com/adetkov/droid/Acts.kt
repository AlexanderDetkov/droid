package com.adetkov.droid

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What an answer shows besides its text, as Markdown (assets/setup/system.md tells the agent): a picture or file,
 * `![caption](path)`. Links an earlier Droid made buttons of, a reply `[label](reply:text)` and a hand-over of its
 * screen `[label](screen:)`, now read as their labels (pages do both now: droid.send, droid.screen).
 */
sealed interface Act {
    data class Show(val path: String, val caption: String) : Act
    data class Reply(val label: String, val text: String) : Act
    data class Screen(val label: String) : Act
}

// groups: 1 "!" (a picture), 2 label, 3 target
private val LINK = Regex("""(!?)\[([^\]\n]*)]\(([^)\n]*)\)""")
private val SCHEME = Regex("""^[a-zA-Z][a-zA-Z0-9+.-]*:""")

private fun act(m: MatchResult): Act? {
    val bang = m.groupValues[1].isNotEmpty()
    val label = m.groupValues[2].trim()
    val target = m.groupValues[3].trim()
    return when {
        target.startsWith("reply:") -> Act.Reply(label, target.removePrefix("reply:").trim().ifEmpty { label })
        target.startsWith("screen:") -> Act.Screen(label)
        target.startsWith("/") || target.startsWith("~/") || (bang && target.isNotEmpty() && !SCHEME.containsMatchIn(target)) ->
            Act.Show(target, label)
        else -> null
    }
}

/** What Done sends after the owner took over a screen the agent handed them (droid.screen). */
const val HANDED_BACK = "I'm done on your screen."

/** The acts in an answer, in order. */
fun acts(text: String): List<Act> = LINK.findAll(text).mapNotNull(::act).toList()

/**
 * An answer as it reads: an old reply or screen link reads as its label ("Or [edit it yourself](screen:) on my
 * screen." reads "Or edit it yourself on my screen."). Each picture goes on a line of its own, where it shows; a file
 * linked within a sentence stays there.
 */
fun answerText(text: String): String = text.lines().joinToString("\n") { line ->
    LINK.replace(line) { m ->
        when (act(m)) {
            is Act.Reply, is Act.Screen -> m.groupValues[2]
            is Act.Show -> if (m.groupValues[1].isNotEmpty()) "\u0000\n" + m.value + "\n\u0000" else m.value
            null -> m.value
        }
    }
}.replace(Regex("[ \t]*\u0000\n"), "\n").replace(Regex("\n\u0000[ \t]*"), "\n").replace(Regex("[ \t]+\n"), "\n").trim()

/** [text] with the files it shows pointing at their copies ([copies]: as the agent wrote the path -> the copy's). */
fun localize(text: String, copies: Map<String, String>): String = LINK.replace(text) { m ->
    copies[m.groupValues[3].trim()]?.let { "${m.groupValues[1]}[${m.groupValues[2]}]($it)" } ?: m.value
}

/** A line that is just a picture or file to show (see [answerText]). */
internal val SHOW_LINE = Regex("""^\s*!\[([^\]\n]*)]\(([^)\n]+)\)\s*$""")

/**
 * Copies of what answers showed, per chat (files/shown/<chat>/), taken when the answer came: the agent's files may be
 * gone by the time anyone looks (`shot` overwrites its picture, and the run's /tmp copy goes with the run).
 */
object Shown {
    fun dir(ctx: Context, conv: String) = File(ctx.filesDir, "shown/$conv")
    fun delete(ctx: Context, conv: String) = dir(ctx, conv).deleteRecursively()
    fun deleteAll(ctx: Context) = File(ctx.filesDir, "shown").deleteRecursively()
}

private val IMAGE = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic")

/** A picture the agent showed, at its own shape, or any other file as a chip; tapping it opens it. */
@Composable
fun ShownFile(path: String, caption: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val f = File(path)
    // Its size first (just the header), so the chat lays out once and keeps following to the end.
    val ratio = remember(path) {
        if (f.extension.lowercase() !in IMAGE) null
        else BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(path, it) }
            .takeIf { it.outWidth > 0 && it.outHeight > 0 }?.let { it.outWidth / it.outHeight.toFloat() }
    }
    val bmp by produceState<ImageBitmap?>(null, path) {
        if (ratio != null) value = withContext(Dispatchers.IO) { decodeAbout(path, 1080)?.asImageBitmap() }
    }
    var viewing by remember(path) { mutableStateOf(false) }
    if (ratio != null) Column(modifier) {
        val shape = RoundedCornerShape(14.dp)
        Box(Modifier.widthIn(max = 300.dp).heightIn(max = 420.dp).aspectRatio(ratio).clip(shape).background(P.surface)
            .border(1.dp, P.hairline, shape).clickable { viewing = true }.testTag("shown_image")) {
            bmp?.let { Image(it, caption.ifBlank { null }, Modifier.matchParentSize(), contentScale = ContentScale.Fit) }
        }
        if (caption.isNotBlank()) Text(caption, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = P.muted)
        if (viewing) bmp?.let { PictureViewer(it, f, caption) { viewing = false } }
    } else Row(
        modifier.height(56.dp).widthIn(max = 280.dp).clip(RoundedCornerShape(14.dp)).background(P.surface)
            .border(1.dp, P.hairline, RoundedCornerShape(14.dp)).clickable(enabled = f.isFile) { openFile(ctx, f) }
            .padding(horizontal = 12.dp).testTag("shown_file"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(P.raised), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_doc), null, Modifier.size(18.dp), tint = P.text)
        }
        Column(Modifier.padding(start = 10.dp)) {
            Text(caption.ifBlank { f.name.substringAfter('-') }, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (f.isFile) f.extension.uppercase().ifEmpty { "FILE" } else "Not available",
                style = MaterialTheme.typography.labelSmall, color = P.muted)
        }
    }
}

/**
 * A picture full screen, in Droid itself: a phone may have no default viewer, and then every tap asked which app to
 * open it with. Pinch or double-tap to zoom; a tap or Back closes it; share sends the file on.
 */
@Composable
private fun PictureViewer(bmp: ImageBitmap, f: File, caption: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        var scale by remember { mutableFloatStateOf(1f) }
        var pan by remember { mutableStateOf(Offset.Zero) }
        var size by remember { mutableStateOf(IntSize.Zero) }
        // Panned only as far as the zoomed picture reaches.
        fun clamp(o: Offset) = Offset(size.width * (scale - 1) / 2, size.height * (scale - 1) / 2).let { m ->
            Offset(o.x.coerceIn(-m.x, m.x), o.y.coerceIn(-m.y, m.y))
        }
        Box(Modifier.fillMaxSize().background(Color.Black).onSizeChanged { size = it }
            .pointerInput(Unit) { detectTransformGestures { _, p, zoom, _ -> scale = (scale * zoom).coerceIn(1f, 5f); pan = clamp(pan + p) } }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { scale = if (scale > 1f) 1f else 2.5f; pan = Offset.Zero }, onTap = { onClose() })
            }.testTag("viewer")) {
            // Inset between the buttons and the caption, so a screenshot of a phone reads as a picture, not as that app.
            Image(bmp, caption.ifBlank { null }, Modifier.fillMaxSize().systemBarsPadding().padding(top = 60.dp, bottom = 56.dp)
                .padding(horizontal = 12.dp).graphicsLayer { scaleX = scale; scaleY = scale; translationX = pan.x; translationY = pan.y })
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
                ViewerButton(R.drawable.ic_close, "Close", onClose)
                Spacer(Modifier.weight(1f))
                ViewerButton(R.drawable.ic_share, "Share") { shareFile(ctx, f) }
            }
            if (caption.isNotBlank() && scale == 1f) Text(caption, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp),
                color = Color.White.copy(alpha = .85f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ViewerButton(icon: Int, desc: String, onClick: () -> Unit) =
    IconBtn(icon, desc, onClick, Modifier.clip(CircleShape).background(Color(0x99000000)), tint = Color.White, size = 44.dp)  // seen on any picture

private fun mimeOf(f: File) = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"

/** A copy's content URI, or null for a file that isn't one (an answer whose copy failed keeps the agent's path). */
private fun uriOf(ctx: Context, f: File) = try { FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f) } catch (_: IllegalArgumentException) { null }

/** The share sheet for a copy in files/shown. */
fun shareFile(ctx: Context, f: File) {
    val uri = uriOf(ctx, f) ?: return
    val send = Intent(Intent.ACTION_SEND).setType(mimeOf(f)).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Opens a copy in files/shown with whatever app handles its type. */
fun openFile(ctx: Context, f: File) {
    val uri = (if (f.isFile) uriOf(ctx, f) else null) ?: return
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeOf(f))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(ctx, "No app opens ${f.extension.uppercase().ifEmpty { "this file" }}", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Decoded at about [px] wide (never smaller), so a phone-sized screenshot doesn't sit in memory at full size; and at
 * most 8 million pixels, so a page-long one stays drawable (Android refuses bitmaps over 100 MB).
 */
private fun decodeAbout(path: String, px: Int) = try {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, o)
    var sample = 1
    while (o.outWidth / (sample * 2) >= px || o.outWidth.toLong() * o.outHeight / (sample.toLong() * sample) > 8_000_000) sample *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
} catch (_: Throwable) { null }  // OutOfMemoryError too
