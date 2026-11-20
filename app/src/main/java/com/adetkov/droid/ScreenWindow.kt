package com.adetkov.droid

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Droid's screen in miniature, [width] wide and shaped like the phone, for a turn whose screen is gone: a still with a
 * hairline, its corners those of Dash's frame at that width (see [screenCorner]), so a frame that lets go of its screen
 * becomes exactly this. (While the screen is open, Dash frames it instead: see [DashSlot].)
 */
@Composable
fun ScreenWindow(frame: ImageBitmap, width: Dp, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val ratio = frame.height.toFloat() / frame.width
    val density = LocalDensity.current
    val shape = RoundedCornerShape(with(density) { screenCorner(width.toPx(), density.density).toDp() })
    Box(
        modifier.size(width, width * ratio).clip(shape).background(P.surface)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics { contentDescription = "Droid's screen as it left it" },
    ) {
        Image(frame, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
        Box(Modifier.matchParentSize().border(1.dp, P.hairline, shape))
    }
}

/** Height over width of the phone's screen, which the hidden screen copies. */
@Composable
fun screenRatio(): Float {
    val ctx = LocalContext.current
    return remember {
        val b = ctx.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        maxOf(b.width(), b.height()).toFloat() / minOf(b.width(), b.height()).coerceAtLeast(1)
    }
}

/** The phone's screen in pixels, portrait: the hidden screen has the same size, so the agent's taps are in it. */
@Composable
fun rememberDisplaySize(): androidx.compose.ui.geometry.Size {
    val ctx = LocalContext.current
    return remember {
        val b = ctx.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        androidx.compose.ui.geometry.Size(minOf(b.width(), b.height()).coerceAtLeast(1).toFloat(), maxOf(b.width(), b.height()).coerceAtLeast(1).toFloat())
    }
}

/** An app's name and launcher icon, as the app on Droid's screen. */
class AppInfo(val label: String, val icon: ImageBitmap?)

private val apps = LruCache<String, AppInfo>(64)

@Composable
fun rememberAppInfo(pkg: String?): AppInfo? {
    val ctx = LocalContext.current
    val info by produceState(pkg?.let(apps::get), pkg) {
        value = pkg?.let(apps::get)
        if (pkg != null && value == null) value = withContext(Dispatchers.IO) { loadApp(ctx, pkg) }?.also { apps.put(pkg, it) }
    }
    return info
}

private fun loadApp(ctx: Context, pkg: String): AppInfo? = try {
    val pm = ctx.packageManager
    val ai = pm.getApplicationInfo(pkg, 0)
    AppInfo(pm.getApplicationLabel(ai).toString(), runCatching { pm.getApplicationIcon(ai).toBitmap(96, 96).asImageBitmap() }.getOrNull())
} catch (_: Exception) { null }  // not visible to Droid, or gone

/** "[icon] Gmail": the app on Droid's screen. Nothing until the app is known. */
@Composable
fun AppLabel(pkg: String?, modifier: Modifier = Modifier, iconSize: Dp = 20.dp, style: TextStyle = MaterialTheme.typography.bodyMedium) {
    val info = rememberAppInfo(pkg) ?: return
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        info.icon?.let { Image(it, null, Modifier.size(iconSize)) }
        Text(info.label, Modifier.padding(start = 8.dp), style = style, color = P.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Each turn's last look at Droid's screen, kept small in the app's files (stills/<chat>/<turn>.jpg), so the turn still
 * shows where Droid left off after the screen is gone.
 */
object Stills {
    fun file(ctx: Context, conv: String, turn: String) = File(ctx.filesDir, "stills/$conv/$turn.jpg")

    /** Writes [frame] for the turn; off the main thread. */
    fun save(f: File, frame: Bitmap) {
        try {
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            tmp.outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            tmp.renameTo(f)
        } catch (_: Exception) {}
    }

    fun delete(ctx: Context, conv: String) = File(ctx.filesDir, "stills/$conv").deleteRecursively()
    /** Deletes the chat's stills of turns from message [from] on (named for an index, see [Item.Steps.stillKey]). */
    fun deleteFrom(ctx: Context, conv: String, from: Int) = File(ctx.filesDir, "stills/$conv").listFiles()
        ?.filter { (it.name.drop(1).substringBefore('.').toIntOrNull() ?: -1) >= from }?.forEach { it.delete() }
    fun deleteAll(ctx: Context) = File(ctx.filesDir, "stills").deleteRecursively()
}

/** A turn's still, once there is one; [version] changes when it was rewritten. */
@Composable
fun rememberStill(f: File, version: Any?): ImageBitmap? {
    val bmp by produceState<ImageBitmap?>(null, f, version) {
        value = withContext(Dispatchers.IO) { if (f.exists()) BitmapFactory.decodeFile(f.path)?.asImageBitmap() else null }
    }
    return bmp
}
