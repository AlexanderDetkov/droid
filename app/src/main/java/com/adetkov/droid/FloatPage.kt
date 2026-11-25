package com.adetkov.droid

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * A page floating over whatever app is open (`droid show --float`), rising from the bottom edge like the assistant
 * panel, with Dash peeking over its top as its handle. Touches beside it go to the app below; it takes the keyboard
 * only once touched, so the app below keeps it until then. Close, Back, the page itself (droid.send, droid.close), or
 * its answer from elsewhere (the chat) take it away. One at a time: a new one replaces it, and the same one shown again
 * stays, updated in place.
 */
object FloatPage {
    // the application's context only: no activity is kept
    @SuppressLint("StaticFieldLeak")
    private var shown: Window? = null

    /** Shows page [pageId] of chat [conv]; on the main thread, with Droid allowed to draw over apps. */
    fun show(ctx: Context, conv: String, pageId: String) {
        if (shown?.page == conv to pageId) return  // it follows its chat, so it is updated in place already
        hide()
        val app = ctx.applicationContext
        val wm = app.getSystemService(WindowManager::class.java)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            title = "Droid page"
        }
        val w = Window(app, wm, params, conv to pageId)
        w.compose.setContent { DroidTheme { FloatCard(conv, pageId, ::hide) } }
        // the right to draw over apps can go between the check and here: then it's a notification's to open (Surfaces)
        try { wm.addView(w.root, params) } catch (e: RuntimeException) {
            // (never attached: nothing has composed, and its lifecycle never started)
            android.util.Log.w("Droid", "float page: ${e.message}")
            return
        }
        w.owner.lifecycle.currentState = Lifecycle.State.RESUMED
        shown = w
    }

    fun hide() {
        val w = shown ?: return
        shown = null
        w.owner.lifecycle.currentState = Lifecycle.State.DESTROYED
        try { w.wm.removeView(w.root) } catch (_: IllegalArgumentException) {}
    }

    /**
     * The overlay of [page] (chat, page id): its view (focusable once touched), and what Compose needs from an owner
     * outside an activity.
     */
    @SuppressLint("ClickableViewAccessibility")
    private class Window(ctx: Context, val wm: WindowManager, val params: WindowManager.LayoutParams, val page: Pair<String, String>) {
        val owner = Owner()
        val compose = ComposeView(ctx)
        val root = object : FrameLayout(ctx) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) { hide(); return true }
                return super.dispatchKeyEvent(event)
            }
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_OUTSIDE -> focusable(false)
                    MotionEvent.ACTION_DOWN -> focusable(true)
                }
                return super.dispatchTouchEvent(ev)
            }
        }

        init {
            root.addView(compose)
            root.setViewTreeLifecycleOwner(owner)
            root.setViewTreeSavedStateRegistryOwner(owner)
        }

        private fun focusable(on: Boolean) {
            val flags = if (on) params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                else params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            if (flags == params.flags) return
            params.flags = flags
            try { wm.updateViewLayout(root, params) } catch (_: IllegalArgumentException) {}
        }
    }

    private class Owner : SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val saved = SavedStateRegistryController.create(this).apply { performRestore(null) }
        override val lifecycle: LifecycleRegistry get() = registry
        override val savedStateRegistry get() = saved.savedStateRegistry
    }
}

@Composable
private fun FloatCard(conv: String, pageId: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as DroidApp
    val convs by app.store.convs.collectAsState()
    val running by app.runner.running.collectAsState()  // whether the agent still waits on it changes with its run
    val c = convs.firstOrNull { it.id == conv }
    val i = c?.pageIndex(pageId) ?: -1
    if (c == null || i < 0) { LaunchedEffect(Unit) { onClose() }; return }
    val m = c.messages[i]
    val info = PageInfo.of(m)
    // as the chat and its run are now (both collected above, so this follows them)
    val waits = remember(c, running) { awaited(app, c, i) }
    val live = remember(c, running) { pageLive(app, c, i) }
    CloseWhenAnswered(info.reply != null, onClose)
    val dark = P.dark
    val actions = remember(conv, pageId) {
        ChatPageActions(ctx, conv, pageId, onScreen = { openChat(ctx, conv, handoff = true); onClose() }, onClose = onClose, onSent = onClose)
    }
    val feel = if (waits) Feel.NEEDS_YOU else lastMood(c.messages)?.feel ?: Feel.IDLE
    val max = LocalConfiguration.current.screenHeightDp.dp * .62f
    Box(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp)) {
        // Dash, his head over the card's top edge: he is behind it, so it covers the rest of him
        DashMini(feel, 56.dp, Modifier.align(Alignment.TopCenter), label = null)
        Column(Modifier.padding(top = 46.dp).fillMaxWidth().shadow(16.dp, RoundedCornerShape(28.dp)).clip(RoundedCornerShape(28.dp)).background(P.sheet)) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 6.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(listOf("Droid", info.title.ifEmpty { c.title }).joinToString(" · "), Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall, color = P.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconBtn(R.drawable.ic_close, "Close", onClose, tint = P.muted, size = 40.dp, iconSize = 20.dp)
            }
            key(m.text, dark) {
                InlinePage("float:$pageId:${m.text.hashCode()}", "page:$conv:$pageId", dark, { cx -> pageDocument(cx, m.text, dark, PageMode.FLOAT) },
                    namedPaths(m.text), actions, mode = PageMode.FLOAT, maxHeight = max, live = live)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
