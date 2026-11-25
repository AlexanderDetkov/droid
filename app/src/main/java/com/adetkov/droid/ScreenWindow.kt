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

/** The phone's screen in pixels, portrait: the hidden screen has the same size, so the agent's taps are in it. */
@Composable
fun rememberDisplaySize(): androidx.compose.ui.geometry.Size {
    val ctx = LocalContext.current
    return remember {
        val b = ctx.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        androidx.compose.ui.geometry.Size(minOf(b.width(), b.height()).coerceAtLeast(1).toFloat(), maxOf(b.width(), b.height()).coerceAtLeast(1).toFloat())
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

/** A turn's still, once there is one; [version] changes when it was rewritten. Decoded no bigger than [width] px needs (a JPEG has no alpha). */
@Composable
fun rememberStill(f: File, version: Any?, width: Int = Int.MAX_VALUE): ImageBitmap? {
    val bmp by produceState<ImageBitmap?>(null, f, version, width) {
        value = withContext(Dispatchers.IO) {
            if (!f.exists()) return@withContext null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(f.path, it) }
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= width) sample *= 2
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 })?.asImageBitmap()
        }
    }
    return bmp
}
