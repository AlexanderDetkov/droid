package com.adetkov.droid

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
 * Droid's screen, big, in place of the chat's page ([ScreenFeed] on 127.0.0.1:[port] with the run's [token]), in Dash's
 * ring under his line; it closes when the stream ends. "Let me do it" (take over) sends the owner's touches to it, for a
 * signature, a login or anything else the agent leaves to them, with Back and their keyboard beside Done. [working]: the
 * agent is working on a request (else it answered and the run is holding its screen). [onTouched]: the owner touched
 * the screen, so the agent should be told.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WatchScreen(
    port: Int, token: String, status: String, working: Boolean, isUp: () -> Boolean,
    onClose: () -> Unit, onTouched: () -> Unit,
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
    modifier: Modifier = Modifier,
) {
    var control by remember { mutableStateOf(startInControl) }  // the owner's touches go to the hidden screen
    LaunchedEffect(startInControl) { if (startInControl) control = true }  // handed over while already watching
    val view = LocalView.current
    DisposableEffect(Unit) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }
    // the chat's keyboard goes: it would type to the chat, not the screen
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    LaunchedEffect(Unit) { focus.clearFocus(); keyboard?.hide() }
    // While the touches are the owner's, their own keyboard types on Droid's screen (which never shows one, see
    // vd-start.sh).
    var sink by remember { mutableStateOf<KeySink?>(null) }
    val imm = remember { view.context.getSystemService(InputMethodManager::class.java) }
    val typing = WindowInsets.isImeVisible && sink?.hasFocus() == true
    // by the window: the sink may be gone already (control ended)
    fun hideKeys() { imm.hideSoftInputFromWindow(view.windowToken, 0); sink?.clearFocus() }
    LaunchedEffect(control) { if (!control) hideKeys() }
    DisposableEffect(Unit) { onDispose { hideKeys() } }
    BackHandler { onClose() }
    val feed = rememberScreenFeed(port, token, isUp = isUp, onEnd = onClose)!!
    val raw = feed.frame
    val f = remember(raw) { raw?.asImageBitmap() }
    val rig = rememberDash("watch")
    // his pose: what the agent does on it while it works, your turn after an answer that asks you, driving while it's yours
    LaunchedEffect(control, working, doing?.work, asks, mood, waiting) {
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
    val words = when {
        control -> "Your turn. Tap Done when you're finished."
        f == null -> if (idle) "Nothing on my screen yet." else "Connecting…"
        working -> mood?.words?.takeIf { it.isNotBlank() } ?: "On it!"
        asks || waiting -> mood?.words?.takeIf { it.isNotBlank() } ?: "Your turn"
        else -> "Here's my screen."
    }
    Column(modifier.fillMaxSize().background(P.bg).testTag("watch")) {
        DashLine(words, size = DashSize.SMALL, rig = rig, sub = status.takeIf { working && !control && f != null })
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp),
            contentAlignment = Alignment.Center) {
            if (f != null) {
                val ratio = f.height / f.width.toFloat()
                RingedScreen(f, minOf(maxWidth, maxHeight / ratio), Modifier.testTag("screen").then(if (control) Modifier.pointerInput(f.width, f.height) {
                    forwardTouches(f.width, f.height, feed::send, onTouched)
                } else Modifier))
            }
        }
        if (control) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Pick(false, { onTouched(); feed.send("k 4") }, Modifier.testTag("back_key"), "Back on my screen") {
                    Icon(painterResource(R.drawable.ic_back), null, Modifier.padding(end = 6.dp).size(18.dp), tint = P.text)
                    PickText("Back", false)
                }
                Pick(typing, { if (typing) hideKeys() else sink?.let { it.requestFocus(); imm.showSoftInput(it, 0) } }, Modifier.testTag("keyboard"),
                    if (typing) "Hide the keyboard" else "Type on my screen") {
                    Icon(painterResource(R.drawable.ic_keyboard), null, Modifier.padding(end = 6.dp).size(18.dp), tint = if (typing) CallText else P.text)
                    PickText("Keyboard", typing)
                }
                AndroidView({ KeySink(it) { m -> onTouched(); feed.send(m) }.also { k -> sink = k } }, Modifier.size(1.dp))
            }
            NextStep("Done", { if (control) { control = false; onHandBack?.invoke() } }, tag = "done_control")  // once
        } else if (f != null) NextStep("Let me do it", { control = true }, tag = "take_over")
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
