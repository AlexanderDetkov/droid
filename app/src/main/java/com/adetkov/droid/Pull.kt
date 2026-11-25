package com.adetkov.droid

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity

/**
 * Scrolling on past an end to go somewhere: up past the top of a chat's latest turn to its History ([down]: the finger
 * moves down), and on past the end of History back to now. It follows the finger, half as far, and goes on ([onGo])
 * when let go past [threshold] pixels; short of that, it springs back. Only drags that reach it through nested
 * scrolling count, so the app on the page (a WebView, not one of them) keeps all of its own.
 */
@Stable
class Pull(private val down: Boolean, private val threshold: Float, private val onGo: () -> Unit) {
    /** How far it's pulled, in pixels (half the finger's way). */
    var pulled by mutableFloatStateOf(0f)
        private set
    val ready: Boolean get() = pulled >= threshold
    val progress: Float get() = (pulled / threshold).coerceIn(0f, 1f)

    val connection = object : NestedScrollConnection {
        // pulled, and the finger goes back: the pull gives way first
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            val d = if (down) available.y else -available.y
            if (pulled <= 0f || d >= 0f) return Offset.Zero
            val used = maxOf(d, -pulled * 2)
            pulled = (pulled + used / 2).coerceAtLeast(0f)
            return Offset(0f, if (down) used else -used)
        }
        // what's left at the end (the top, the bottom) pulls
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            val d = if (down) available.y else -available.y
            if (source != NestedScrollSource.UserInput || d <= 0f) return Offset.Zero
            pulled += d / 2
            return Offset(0f, available.y)
        }
        override suspend fun onPreFling(available: Velocity): Velocity {
            if (pulled <= 0f) return Velocity.Zero
            val go = ready
            pulled = 0f
            if (go) onGo()
            return available
        }
    }
}
