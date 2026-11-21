package com.adetkov.droid

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.util.lerp
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.geometry.Rect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Animatable
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.flow.Flow
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.ui.viewinterop.AndroidView
import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager

/**
 * Droid's screen, full size ([ScreenFeed] on 127.0.0.1:[port] with the run's [token]), framed by Dash, the controls in a
 * bar below it so nothing covers it: it grows out of the chat's frame and shrinks back into it, and closes when the
 * stream ends. "Let me do it" (take over) sends the user's touches back, for a signature, a login
 * or anything else the agent leaves to the user; Dash goes pale and closes his eyes while the touches are theirs. [working]: the
 * agent is working on a request (else it answered and the run is holding its screen). [onTouched]: the user touched
 * the screen, so the agent should be told.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WatchScreen(
    port: Int, token: String, status: String, working: Boolean, isUp: () -> Boolean,
    onStop: () -> Unit, onClose: () -> Unit, onTouched: () -> Unit,
    /** Start with the owner's touches going to the screen (the agent handed it over). */
    startInControl: Boolean = false,
    /** Done gives the screen back to the agent that handed it over (null: Done only ends taking over). */
    onHandBack: (() -> Unit)? = null,
    /** Where the agent taps its screen, in the screen's pixels. */
    taps: Flow<Pair<Int, Int>>? = null,
    /** What the agent is doing, while [working]; and whether its answer asked the owner something. */
    doing: Doing? = null, asks: Boolean = false,
    /** How the agent set Dash, and whether a page waits on the owner: Watch's Dash is the chat's, in the same pose. */
    mood: Mood? = null, waiting: Boolean = false,
) {
    var overlays by remember { mutableStateOf(true) }
    var control by remember { mutableStateOf(startInControl) }  // the user's touches go to the hidden screen
    LaunchedEffect(startInControl) { if (startInControl) control = true }  // handed over while already watching
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        // Black whatever the theme: light status and navigation bar icons while it shows.
        val bars = (view.context as? android.app.Activity)?.window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        val light = bars?.isAppearanceLightStatusBars ?: false
        bars?.isAppearanceLightStatusBars = false
        bars?.isAppearanceLightNavigationBars = false
        onDispose {
            view.keepScreenOn = false
            bars?.isAppearanceLightStatusBars = light
            bars?.isAppearanceLightNavigationBars = light
        }
    }
    // It grows out of the chat's frame and shrinks back into it: one object at two sizes.
    val scope = rememberCoroutineScope()
    val enter = remember { Animatable(0f) }
    val origin = remember { ScreenOrigin.rect }
    val placeholder = remember { ScreenOrigin.image }
    var target by remember { mutableStateOf<Rect?>(null) }
    var closing by remember { mutableStateOf(false) }
    // Dash is the frame: periwinkle while Droid drives, pale with his eyes closed while the touches are yours. He is the
    // chat's Dash, grown: he takes up where that one is, and hands back where he got to, so neither change shows.
    val from = remember { ScreenOrigin.rig?.takeIf { it.frame.x > .9f && it.present.x > .9f } }
    val rig = rememberDash(Unit, from = from) { it.frame.snap(1f) }
    fun handBack() { from?.follow(rig, false); ScreenOrigin.taken.value = false }
    fun leave(then: () -> Unit) {
        if (closing) return
        closing = true
        scope.launch { enter.animateTo(0f, spring(dampingRatio = .92f, stiffness = 420f)); handBack(); then() }
    }
    // the chat's keyboard goes: it would cover the bar
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    LaunchedEffect(Unit) { focus.clearFocus(); keyboard?.hide() }
    // While the touches are the owner's, their own keyboard types on Droid's screen (which never shows one, see
    // vd-start.sh): the screen and the bar move up above it.
    var sink by remember { mutableStateOf<KeySink?>(null) }
    val imm = remember { view.context.getSystemService(InputMethodManager::class.java) }
    val typing = WindowInsets.isImeVisible && sink?.hasFocus() == true
    // by the window: the sink may be gone already (control ended)
    fun hideKeys() { imm.hideSoftInputFromWindow(view.windowToken, 0); sink?.clearFocus() }
    LaunchedEffect(control) { if (!control) hideKeys() }
    DisposableEffect(Unit) { onDispose { hideKeys() } }
    LaunchedEffect(Unit) { ScreenOrigin.taken.value = true; enter.animateTo(1f, spring(dampingRatio = .86f, stiffness = 340f)) }
    DisposableEffect(Unit) { onDispose { if (ScreenOrigin.taken.value) handBack() } }
    BackHandler { leave(onClose) }
    val feed = rememberScreenFeed(port, token, isUp = isUp, onEnd = { leave(onClose) })!!
    val raw = feed.frame
    val f = remember(raw) { raw?.asImageBitmap() } ?: placeholder
    val app = rememberAppInfo(feed.app)?.label
    // his pose: what the agent does on it while it works, your turn after an answer that asks you
    LaunchedEffect(control, working, doing?.work, asks, mood, waiting) {
        rig.pale.t = if (control) 1f else 0f
        rig.feel(if (control) Feel.DRIVING else chatFeel(working, doing, if (asks) Outcome.ASKS else null, mood, waiting))
    }
    LaunchedEffect(doing?.n) { if (doing != null && doing.n > 0 && working && !control) rig.react(doing) }
    val display = rememberDisplaySize()
    LaunchedEffect(taps) { taps?.collect { (x, y) -> rig.tap(x / display.width, y / display.height) } }
    // Connected but nothing drawn on the hidden display yet (the server only sends when a frame exists).
    var idle by remember { mutableStateOf(false) }
    LaunchedEffect(feed.connected, f) {
        idle = false
        if (feed.connected && f == null) { delay(1500); idle = true }
    }
    val chrome = Color(0x33FFFFFF)
    Box(Modifier.fillMaxSize()
        .clickable(remember { MutableInteractionSource() }, null, enabled = !control) { overlays = !overlays }) {
        Box(Modifier.matchParentSize().graphicsLayer { alpha = enter.value.coerceIn(0f, 1f) }.background(Color.Black))
        when {
            f != null -> BoxWithConstraints(
                // nothing covers the screen and nothing covers him: his head rises above it, clear of the status bar and
                // the camera's cutout (with the room it needs at its tallest), the controls sit in the bar below it
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .navigationBarsPadding().imePadding()
                    .padding(start = 12.dp, end = 12.dp, top = WATCH_HEAD, bottom = BAR + 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                val ratio = f.height / f.width.toFloat()
                val w = minOf(maxWidth, maxHeight / ratio)
                val shown = { val t = target; val o = origin; val e = enter.value.coerceIn(-.05f, 1.05f)
                    if (t != null && o != null && t.width > 0f) lerp(o.width / t.width, 1f, e) else 1f }
                val grow = Modifier.onGloballyPositioned { target = it.boundsInWindow() }.graphicsLayer {
                    val t = target; val o = origin; val e = enter.value.coerceIn(-.05f, 1.05f)
                    if (t != null && o != null && t.width > 0f && t.height > 0f) {
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = lerp(o.width / t.width, 1f, e); scaleY = lerp(o.height / t.height, 1f, e)
                        translationX = lerp(o.left - t.left, 0f, e); translationY = lerp(o.top - t.top, 0f, e)
                    } else { alpha = e.coerceIn(0f, 1f); scaleX = lerp(.92f, 1f, e); scaleY = lerp(.92f, 1f, e) }
                }
                DashScreen(rig, f, w, ratio,
                    grow.then(if (control) Modifier.testTag("screen").pointerInput(f.width, f.height) {
                        forwardTouches(f.width, f.height, feed::send, onTouched)
                    } else Modifier),
                    edge = 4.dp, hook = false, label = "Droid's screen",
                    // at the chat's size it looks exactly like the chat's frame, hook and all; the hook tucks away as it grows
                    scale = shown, hookAmt = { (1f - enter.value).coerceIn(0f, 1f) }, onLight = false)  // Watch is black in either theme
            }
            idle -> Column(Modifier.align(Alignment.Center).padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                DashMini(if (working) Feel.WORKING else Feel.IDLE, 72.dp, Modifier.padding(bottom = 12.dp), label = null)
                Text("Nothing on Droid's screen yet", color = Color(0xFFB0B0B0), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                Text(if (working) "The agent is working without opening an app" else "The agent left no app open on it", Modifier.padding(top = 6.dp), color = Color(0xFF7A7A7A),
                    style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            }
            else -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                DashMini(Feel.THINKING, 72.dp, label = "Dash, connecting")
                Text("Connecting", Modifier.padding(top = 10.dp), color = Color(0xFFB0B0B0), style = MaterialTheme.typography.bodyMedium)
            }
        }
        // The bar: close, what's going on (and in which app), and what you can do.
        Box(Modifier.align(Alignment.BottomCenter).graphicsLayer { alpha = enter.value.coerceIn(0f, 1f) }) {
            AnimatedVisibility(overlays || control, enter = fadeIn(), exit = fadeOut()) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 10.dp).height(BAR - 20.dp)
                    .clip(RoundedCornerShape(28.dp)).background(Color(0xE61C1C1C)).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ leave(onClose) }, Modifier.size(40.dp).testTag("close"),
                        colors = IconButtonDefaults.iconButtonColors(containerColor = chrome, contentColor = Color.White)) {
                        Icon(painterResource(R.drawable.ic_close), "Close", Modifier.size(20.dp))
                    }
                    if (control) IconButton({ onTouched(); feed.send("k 4") }, Modifier.padding(start = 8.dp).size(40.dp).testTag("back_key"),
                        colors = IconButtonDefaults.iconButtonColors(containerColor = chrome, contentColor = Color.White)) {
                        Icon(painterResource(R.drawable.ic_back), "Back", Modifier.size(20.dp))
                    }
                    if (control) IconButton({ if (typing) hideKeys() else sink?.let { it.requestFocus(); imm.showSoftInput(it, 0) } },
                        Modifier.padding(start = 8.dp).size(40.dp).testTag("keyboard"),
                        colors = IconButtonDefaults.iconButtonColors(containerColor = if (typing) Color.White else chrome,
                            contentColor = if (typing) Color.Black else Color.White)) {
                        Icon(painterResource(R.drawable.ic_keyboard), if (typing) "Hide the keyboard" else "Type", Modifier.size(20.dp))
                    }
                    if (control) AndroidView({ KeySink(it) { m -> onTouched(); feed.send(m) }.also { k -> sink = k } }, Modifier.size(1.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        if (!control && app != null) Text(app, color = Color(0xFF9A9A9A), style = MaterialTheme.typography.labelMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(when {
                            control -> if (working) "Droid is working on this screen too" else "Your touches go to Droid's screen"
                            working -> status
                            asks -> "Waiting for your reply"
                            else -> "Stays open for your reply"  // done: kept open in case there's more, as the chat says
                        }, color = Color(0xFFE6E6E6), style = MaterialTheme.typography.bodyMedium,
                            maxLines = if (!control && app != null) 1 else 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (control) Pill("Done", Modifier.testTag("done_control")) { if (control) { control = false; onHandBack?.invoke() } }  // once
                    else {
                        if (f != null) Pill("Let me do it", Modifier.testTag("take_over")) { control = true }
                        if (working) Box(Modifier.padding(start = 8.dp).size(40.dp).clip(CircleShape).background(Color.White)
                            .clickable { leave(onStop) }.testTag("stop"), contentAlignment = Alignment.Center) {
                            Icon(painterResource(R.drawable.ic_stop), "Stop", Modifier.size(16.dp), tint = Color.Black)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The owner's keyboard for Droid's screen: what they type goes there as text and key presses ([send]: "t TEXT",
 * "k KEYCODE"), as from a hardware keyboard. It holds no text of its own, so the keyboard sends Delete as a key.
 */
@android.annotation.SuppressLint("ViewConstructor")  // made in code only, never from a layout
private class KeySink(ctx: Context, private val send: (String) -> Unit) : View(ctx) {
    init { isFocusable = true; isFocusableInTouchMode = true; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }  // (unseen: screen readers skip it)

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(out: EditorInfo): InputConnection {
        // No suggestions or corrections: each key goes as it's typed, as a password field's would.
        out.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        out.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return object : BaseInputConnection(this, false) {
            var composing = ""  // what the keyboard is still composing, already typed on Droid's screen

            fun replaceComposing(text: String) {
                val keep = composing.commonPrefixWith(text).length
                repeat(composing.codePointCount(keep, composing.length)) { key(KeyEvent.KEYCODE_DEL) }  // one Backspace a character
                type(text.substring(keep))
            }
            override fun setComposingText(text: CharSequence, pos: Int): Boolean { replaceComposing(text.toString()); composing = text.toString(); return true }
            override fun commitText(text: CharSequence, pos: Int): Boolean { replaceComposing(text.toString()); composing = ""; return true }
            override fun finishComposingText(): Boolean { composing = ""; return true }
            override fun deleteSurroundingText(before: Int, after: Int): Boolean {
                repeat(before) { key(KeyEvent.KEYCODE_DEL) }; repeat(after) { key(KeyEvent.KEYCODE_FORWARD_DEL) }; return true
            }
            override fun deleteSurroundingTextInCodePoints(before: Int, after: Int) = deleteSurroundingText(before, after)
            override fun sendKeyEvent(event: KeyEvent): Boolean { if (event.action == KeyEvent.ACTION_DOWN) press(event); return true }
            override fun performEditorAction(action: Int): Boolean { key(KeyEvent.KEYCODE_ENTER); return true }
        }
    }

    /** A hardware keyboard's keys go there too (Back stays Droid's). */
    override fun onKeyDown(keyCode: Int, event: KeyEvent) = press(event) || super.onKeyDown(keyCode, event)

    /** A key: its character typed, or an editing key pressed; false for any other (it isn't the screen's). */
    private fun press(e: KeyEvent): Boolean {
        val c = e.unicodeChar
        when {
            c != 0 && !e.isCtrlPressed -> type(String(Character.toChars(c)))
            e.keyCode in EDIT_KEYS -> key(e.keyCode)
            else -> return false
        }
        return true
    }

    private fun key(code: Int) = send("k $code")
    // (a \r is a line break too: the helper reads its commands by line, so one inside a "t" would start a command)
    private fun type(text: String) = text.split(Regex("\r\n|\r|\n")).forEachIndexed { i, line ->
        if (i > 0) key(KeyEvent.KEYCODE_ENTER)
        if (line.isNotEmpty()) send("t $line")
    }
}

private val EDIT_KEYS = setOf(KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_TAB,
    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
    KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END, KeyEvent.KEYCODE_ESCAPE)

/** Watch's bar, with its margins. */
private val BAR = 76.dp
/** Room above Watch's screen for Dash's head at full size (his antenna tucks away there), and a little air. */
private val WATCH_HEAD = (headRoomDp(1.6f, hook = false) + 4f).dp

@Composable
private fun Pill(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(50)).background(Color.White).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(label, color = Color.Black, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Sends one finger's touches on the frame to the hidden screen, in its pixels: [w]×[h] drawn with ContentScale.Fit in
 * this element. A touch that starts outside the frame is ignored; one that leaves it is held at its edge.
 */
private suspend fun PointerInputScope.forwardTouches(w: Int, h: Int, send: (String) -> Unit, onTouched: () -> Unit) {
    fun toScreen(p: Offset, clamp: Boolean): IntOffset? {
        val scale = min(size.width / w.toFloat(), size.height / h.toFloat())
        val x = ((p.x - (size.width - w * scale) / 2) / scale).roundToInt()
        val y = ((p.y - (size.height - h * scale) / 2) / scale).roundToInt()
        if (!clamp && (x !in 0 until w || y !in 0 until h)) return null
        return IntOffset(x.coerceIn(0, w - 1), y.coerceIn(0, h - 1))
    }
    awaitEachGesture {
        val down = awaitFirstDown()
        val start = toScreen(down.position, clamp = false) ?: return@awaitEachGesture
        down.consume()
        onTouched()
        send("d ${start.x} ${start.y}")
        var last = start
        var lifted = false
        try {
            while (true) {
                val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break  // finger gone: cancelled
                val p = toScreen(c.position, clamp = true)!!
                c.consume()
                if (!c.pressed) { send("u ${p.x} ${p.y}"); lifted = true; break }
                if (p != last) { send("m ${p.x} ${p.y}"); last = p }
            }
        } finally {
            if (!lifted) send("c")  // never leave a finger down on the hidden screen, nor tap where it was
        }
    }
}
