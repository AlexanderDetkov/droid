package com.adetkov.droid

import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * `droid look FILE`: the agent's page drawn on its own hidden display as the owner's screen shows an answer (under the
 * room kept for Dash and his words, above the talk bar unless the page ends with a footer), for the agent to take a
 * screenshot of and see what the owner will. Droid draws over that display as it draws a floating page (the right to
 * draw over apps). One at a time; it goes when the command is done with it, or after a minute.
 */
object LookPage {
    private val main = Handler(Looper.getMainLooper())
    private var shown: Triple<String, WindowManager, ViewGroup>? = null

    /** What the agent's command asked ([Look]): draw it and say so (or why not), or take it away. Off the main thread. */
    fun handle(app: DroidApp, l: Look) {
        val html = l.html
        if (html == null) { main.post { hide(l.id) }; return }
        fun reply(error: String?, problems: List<String> = emptyList()) = Thread {
            try {
                app.backend.reply(l.id, JSONObject().apply {
                    if (error == null) put("ok", true).put("problems", org.json.JSONArray(problems)) else put("error", error)
                }.toString())
            } catch (e: IOException) { Log.w("Droid", "look: ${e.message}") }
        }.start()
        if (!canOverlay(app)) { reply("Droid isn't allowed to draw over apps, so it can't draw the page"); return }
        main.post { show(app, l.conv, l.id, l.display, html, l.base) { error, problems -> reply(error, problems) } }
    }

    private fun show(app: DroidApp, conv: String, id: String, display: Int, html: String, base: String?, finish: (String?, List<String>) -> Unit) {
        val done = { error: String? -> finish(error, emptyList()) }
        hide(null)
        val d = app.getSystemService(DisplayManager::class.java).getDisplay(display) ?: return done("Droid can't reach display $display")
        val ctx = try { app.createDisplayContext(d).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null) }
            catch (e: Exception) { return done("Droid can't draw on display $display: ${e.message}") }
        val wm = ctx.getSystemService(WindowManager::class.java)
        val dark = (app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val p = palette(dark)
        val dp = ctx.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val top = px(TOP_DP); val bottom = px(BOTTOM_DP); val talk = px(TALK_DP)
        val root = FrameLayout(ctx).apply { setBackgroundColor(p.bg.toArgb()) }
        root.addView(TextView(ctx).apply {
            text = "Dash and his words"; setTextColor(p.faint.toArgb()); textSize = 15f; gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, top))
        val bar = TextView(ctx).apply {
            text = "Tap to talk"; textSize = 19f; gravity = Gravity.CENTER
            setTextColor(if (dark) 0xFF0D0D0D.toInt() else 0xFFFFFFFF.toInt())
            background = GradientDrawable().apply { cornerRadius = px(30).toFloat(); setColor(p.call.toArgb()) }
            visibility = android.view.View.GONE
        }
        root.addView(bar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, px(60), Gravity.BOTTOM).apply {
            leftMargin = px(16); rightMargin = px(16); bottomMargin = bottom + px(12)
        })
        val web = PageWebView(ctx, pageOrigin("look:$id"))
        web.actions = lookActions(app, conv)
        val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT).apply {
            topMargin = top; bottomMargin = bottom
        }
        root.addView(web, lp)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE,
        ).apply { title = "Droid look" }
        try { wm.addView(root, params) } catch (e: Exception) { return done("Droid can't draw on display $display: ${e.message}") }
        shown = Triple(id, wm, root)
        var told = false
        web.onLoaded = {
            // the page tells whether it ends with a footer once laid out; without one, the talk bar is under it
            main.postDelayed({
                if (shown?.first == id && !web.hasFooter) { lp.bottomMargin = bottom + talk; web.layoutParams = lp; bar.visibility = android.view.View.VISIBLE }
                // laid out where it will be: what the page notices of its own layout (droid.js _lint) goes with the answer
                main.postDelayed({
                    if (shown?.first == id && !told) web.evaluateJavascript("JSON.stringify(window.droid && droid._lint ? droid._lint() : [])") { js ->
                        if (told) return@evaluateJavascript
                        told = true
                        finish(null, lintProblems(js))
                    }
                }, 500)
            }, 700)
        }
        // a page (a document of its own, as an app folder's index.html is) as the chat shows a page; else as an answer
        val page = Regex("<(!doctype|html|head|body)\\b", RegexOption.IGNORE_CASE).containsMatchIn(html.take(4000)) || base != null
        web.show(if (page) pageDocument(ctx, html, dark, PageMode.SCREEN) else answerDocument(ctx, html, dark, PageMode.SCREEN), namedPaths(html), base)
        main.postDelayed({ hide(id) }, 60_000)
    }

    /** What evaluateJavascript gave for droid._lint() (a JSON string of a JSON array), as a list; empty if unreadable. */
    internal fun lintProblems(js: String?): List<String> = try {
        val inner = org.json.JSONTokener(js ?: "").nextValue() as? String ?: return emptyList()
        val a = org.json.JSONArray(inner)
        (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
    } catch (_: org.json.JSONException) { emptyList() }

    /** Takes page [id] away ([id] null: whichever is up). On the main thread. */
    fun hide(id: String?) {
        val s = shown ?: return
        if (id != null && s.first != id) return
        shown = null
        try { s.second.removeView(s.third) } catch (_: Exception) {}
        (0 until s.third.childCount).map { s.third.getChildAt(it) }.filterIsInstance<PageWebView>().forEach { it.destroy() }
    }

    /** A drawn page does nothing (nobody taps it), but loads the files its HTML names, as the chat would. */
    private fun lookActions(app: DroidApp, conv: String) = object : PageActions {
        override fun send(text: String, data: String?) {}
        override fun run(command: String) = "" to 1
        override fun dash(feel: String, words: String) {}
        override fun screen() {}
        override fun open(url: String) {}
        override fun close() {}
        override fun fetch(path: String): File? {
            val f = File(shown, "look-" + File(path).name)
            return try { if (app.backend.fetch(path, f)) f else null } catch (e: IOException) { null }
        }
        override val shown: File get() = Shown.dir(app, conv)
    }

    /** The room above an answer on the owner's screen (status bar, the top bar, Dash and his words), and below it (the system's bar; the talk bar). */
    private const val TOP_DP = 28 + 60 + 104
    private const val BOTTOM_DP = 28
    private const val TALK_DP = 78
}
