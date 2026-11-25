package com.adetkov.droid

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Small rotating arc (the stock indicator renders as dots at this size). */
@Composable
fun Spinner(modifier: Modifier = Modifier, size: Dp = 12.dp, color: Color = Muted, stroke: Dp = 1.5.dp) {
    val angle by rememberInfiniteTransition(label = "spin").animateFloat(
        0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "angle")
    Canvas(modifier.size(size)) {
        val w = stroke.toPx()
        drawArc(color, angle, 270f, false, Offset(w / 2, w / 2), Size(this.size.width - w, this.size.height - w),
            style = Stroke(w, cap = StrokeCap.Round))
    }
}

/** A round, borderless icon button. */
@Composable
fun IconBtn(
    res: Int, desc: String?, onClick: () -> Unit, modifier: Modifier = Modifier,
    tint: Color = P.text, size: Dp = 40.dp, iconSize: Dp = 22.dp, enabled: Boolean = true,
) {
    Box(
        modifier.size(size).clip(CircleShape).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(painterResource(res), desc, Modifier.size(iconSize), tint = if (enabled) tint else tint.copy(alpha = .35f)) }
}

/** Text that a light band sweeps across, for "Working". */
@Composable
fun ShimmerText(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    val base = P.muted
    val hi = P.text
    val x by rememberInfiniteTransition(label = "shimmer").animateFloat(
        -1f, 2f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "x")
    val widthPx = with(LocalDensity.current) { 120.dp.toPx() }
    Text(text, modifier, style = style.copy(brush = Brush.linearGradient(
        listOf(base, base, hi, base, base),
        start = Offset(x * widthPx - widthPx / 2, 0f), end = Offset(x * widthPx + widthPx / 2, 0f),
    )))
}

/** A sheet in Droid's style: everything that goes deeper (a menu, his steps, what to attach) is one. [title] heads it quietly. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionSheet(onDismiss: () -> Unit, title: String? = null, content: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val close: () -> Unit = { scope.launch { state.hide() }.invokeOnCompletion { if (!state.isVisible) onDismiss() } }
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = state, containerColor = P.sheet, contentColor = P.text,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { BottomSheetDefaults.DragHandle(color = P.hairline, width = 36.dp, height = 4.dp) },
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            if (title != null) Text(title, Modifier.padding(horizontal = 24.dp).padding(bottom = 6.dp),
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), color = P.muted,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            content(close)
        }
    }
}

/** One row in an [ActionSheet]: a row of the kit, its icon on a grey tile. [line]: a hairline above it. */
@Composable
fun SheetRow(icon: Int, label: String, onClick: () -> Unit, danger: Boolean = false, tag: String? = null, line: Boolean = false) {
    Box(Modifier.padding(horizontal = 20.dp)) {
        KitRow(label, if (tag != null) Modifier.testTag(tag) else Modifier, lead = { IconTile(icon, if (danger) P.danger else P.text) },
            titleColor = if (danger) P.danger else P.text, line = line, onClick = onClick)
    }
}

/**
 * Asks once, in the same sheet as everything else: the question, what it means, one big button ([danger]: red, for
 * what can't be undone), and [dismiss] under it ([onSecondary]: what that does besides closing, e.g. "Type instead").
 */
@Composable
fun ConfirmDialog(
    title: String, text: String?, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit,
    danger: Boolean = false, dismiss: String = "Cancel", onSecondary: (() -> Unit)? = null,
) {
    ActionSheet(onDismiss) { close ->
        SheetQuestion(title, text)
        // (dark's red is light: dark words on it)
        SheetButton(confirm, if (danger) P.danger else P.call, if (danger) (if (P.dark) Color(0xFF0D0D0D) else Color.White) else OnCall, "confirm") { onConfirm(); close() }
        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(56.dp).clip(RoundedCornerShape(28.dp))
            .clickable { close(); onSecondary?.invoke() }.testTag("dismiss"), contentAlignment = Alignment.Center) {
            Text(dismiss, style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold))
        }
    }
}

/** A one-line text to change in a sheet, e.g. renaming a chat: the text starts selected, so typing replaces it. */
@Composable
fun TextDialog(title: String, initial: String, confirm: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    ActionSheet(onDismiss) { close ->
        fun submit() { if (value.text.isNotBlank()) { onConfirm(value.text.trim()); close() } }
        Column(Modifier.imePadding()) {
            SheetQuestion(title, null)
            BasicTextField(
                value, { value = it }, Modifier.fillMaxWidth().padding(horizontal = 20.dp).focusRequester(focus).testTag("dialog_text")
                    .clip(RoundedCornerShape(18.dp)).background(P.surface).padding(horizontal = 18.dp, vertical = 15.dp),
                singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = P.text, fontSize = 18.sp), cursorBrush = SolidColor(P.call),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { submit() }),
            )
            Spacer(Modifier.height(14.dp))
            SheetButton(confirm, P.call, OnCall, "confirm", enabled = value.text.isNotBlank()) { submit() }
        }
    }
}

/** A sheet's question: its words big, what it means quietly under them. */
@Composable
private fun SheetQuestion(title: String, text: String?) {
    Text(title, Modifier.padding(horizontal = 24.dp).padding(top = 4.dp, bottom = if (text == null) 14.dp else 6.dp),
        style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold))
    if (text != null) Text(text, Modifier.padding(horizontal = 24.dp).padding(bottom = 20.dp),
        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 23.sp), color = P.muted)
}

/** A sheet's one big button. */
@Composable
private fun SheetButton(label: String, bg: Color, fg: Color, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(60.dp).clip(RoundedCornerShape(30.dp))
        .background(if (enabled) bg else P.raised).clickable(enabled = enabled, onClick = onClick).testTag(tag), contentAlignment = Alignment.Center) {
        Text(label, style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold), color = if (enabled) fg else P.faint)
    }
}

/** A circle with the first letter of [name]. */
@Composable
fun Avatar(name: String?, size: Dp = 32.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(P.raised), contentAlignment = Alignment.Center) {
        val initial = name?.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()
        if (initial != null) Text(initial.toString(), style = MaterialTheme.typography.titleSmall, color = P.text)
        else Icon(painterResource(R.drawable.ic_logo), null, Modifier.size(size * .5f), tint = P.text)
    }
}

/** Section label in lists ("Today", "Appearance"). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier, style = MaterialTheme.typography.labelMedium, color = P.muted)


private val thumbs = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.byteCount
}

/** An attached image, decoded off the main thread at about [px] pixels, cropped to fill its box. */
@Composable
fun Thumb(path: String, px: Int, modifier: Modifier) {
    val key = "$path@$px"
    val bmp by produceState(thumbs.get(key), key) {
        value = thumbs.get(key) ?: withContext(Dispatchers.IO) { decodeScaled(path, px)?.also { thumbs.put(key, it) } }
    }
    Box(modifier.background(P.raised)) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.matchParentSize(), contentScale = ContentScale.Crop) }
    }
}

private fun decodeScaled(path: String, px: Int): Bitmap? = try {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, o)
    var sample = 1
    while (minOf(o.outWidth, o.outHeight) / (sample * 2) >= px) sample *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
} catch (_: Exception) { null }
