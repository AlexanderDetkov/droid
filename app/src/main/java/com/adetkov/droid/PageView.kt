package com.adetkov.droid

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import androidx.core.net.toUri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.layout
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import kotlin.concurrent.thread
import kotlin.math.abs

/** What a page's `droid` calls do (droid.js): the chat, Droid's Linux and its screens, for the page that asked. */
interface PageActions {
    /** droid.send: a message from the owner (or the answer to a page the agent waits on). */
    fun send(text: String, data: String?)
    /** droid.run: a command in Droid's Linux; its output and exit code. Called off the main thread. */
    fun run(command: String): Pair<String, Int>
    /** droid.dash: how Dash looks ([feel]: a name, or a face as JSON) and what he says. */
    fun dash(feel: String, words: String)
    /** droid.screen: the owner takes over the agent's hidden screen. */
    fun screen()
    /** droid.open: a link or another app. */
    fun open(url: String)
    /** droid.close: a page full screen or floating goes away. */
    fun close()
    /** A file the page names in the agent's Linux, copied out (null if there is none). Called off the main thread. */
    fun fetch(path: String): File?
    /** Droid's copies of what its chat's answers showed (files/shown/<chat>), which it may load. */
    val shown: File
    /**
     * Whether the page may still act (send, run, screen, Dash), as the chat has it this moment (see [pageLive]): one
     * answered, or from an earlier turn that nothing waits on, is done.
     */
    val live: Boolean get() = true
}

/**
 * How a page is shown: in the chat's history (as tall as its content, never scrolling itself), as the answer on the
 * owner's screen (under Dash's words, filling the rest, its footer pinned at the bottom), full screen, or floating.
 */
enum class PageMode(val css: String) { INLINE("inline"), SCREEN("screen"), FULL("full"), FLOAT("float") }

/** The page's stylesheet and script, read once. */
private object PageAssets {
    @Volatile private var css: String? = null
    @Volatile private var js: String? = null
    fun css(ctx: Context) = css ?: ctx.assets.open("page/droid.css").use { it.readBytes().toString(Charsets.UTF_8) }.also { css = it }
    fun js(ctx: Context) = js ?: ctx.assets.open("page/droid.js").use { it.readBytes().toString(Charsets.UTF_8) }.also { js = it }
}

private fun hex(c: androidx.compose.ui.graphics.Color) = "#%06X".format(c.toArgb() and 0xFFFFFF)

/** The theme's colors as the stylesheet's variables. */
private fun themeVars(dark: Boolean): String = palette(dark).let { p ->
    ":root{--bg:${hex(p.bg)};--surface:${hex(p.surface)};--raised:${hex(p.raised)};--sheet:${hex(p.sheet)};--hairline:${hex(p.hairline)};" +
        "--text:${hex(p.text)};--muted:${hex(p.muted)};--faint:${hex(p.faint)};--ink:${hex(p.ink)};--on-ink:${hex(p.onInk)};" +
        "--code-bg:${hex(p.codeBg)};--code-header:${hex(p.codeHeader)};--danger:${hex(p.danger)};--success:${hex(p.success)};--call:${hex(p.call)};" +
        "--on-call:${if (p.dark) "#0D0D0D" else "#FFFFFF"}}"
}

/** What every page gets first: the viewport, the theme and mode on <html>, Droid's stylesheet and `droid`. */
private fun preamble(ctx: Context, dark: Boolean, mode: PageMode): String =
    "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
        "<script>document.documentElement.classList.add('${if (dark) "dark" else "light"}','${mode.css}')</script>" +
        "<style>${themeVars(dark)}\n${PageAssets.css(ctx)}</style><script>${PageAssets.js(ctx)}</script>"

/** JSON safe inside a <script>: with no `<` at all, nothing in it can close the element or open a comment there. */
internal fun scriptSafe(json: String) = json.replace("<", "\\u003c").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")

/** An answer as a page: its Markdown and HTML rendered by droid.js (with marked), its scripts run in order. */
fun answerDocument(ctx: Context, text: String, dark: Boolean, mode: PageMode = PageMode.INLINE): String =
    "<!doctype html><html><head><meta charset=\"utf-8\">${preamble(ctx, dark, mode)}<script src=\"/_droid/marked.js\"></script></head>" +
        "<body><main id=\"droid\"></main><script>droid._render(${scriptSafe(segmentsJson(text))})</script></body></html>"

/** A page the agent wrote (`droid show`), as it wrote it, with Droid's preamble at the start of its head. */
fun pageDocument(ctx: Context, html: String, dark: Boolean, mode: PageMode): String {
    val pre = preamble(ctx, dark, mode)
    val head = Regex("<head\\b[^>]*>", RegexOption.IGNORE_CASE).find(html)
    val doc = if (head != null) html.substring(0, head.range.last + 1) + pre + html.substring(head.range.last + 1)
        else Regex("<html\\b[^>]*>", RegexOption.IGNORE_CASE).find(html)?.let { h ->
            html.substring(0, h.range.last + 1) + "<head><meta charset=\"utf-8\">$pre</head>" + html.substring(h.range.last + 1)
        } ?: "<head><meta charset=\"utf-8\">$pre</head><body>$html</body>"
    return if (doc.trimStart().startsWith("<!doctype", ignoreCase = true)) doc else "<!doctype html>$doc"
}

/** The origin a page lives at: one per page, so what it keeps (localStorage) is its own and no other page can read it. */
fun pageOrigin(name: String): String =
    "p" + MessageDigest.getInstance("SHA-1").digest(name.toByteArray()).take(10).joinToString("") { "%02x".format(it) } + ".droid.page"

/** How long after the owner's tap a page may act for them (send, run, screen, open). */
private const val TAP_GRACE = 15_000L

/**
 * What a page may run: scripts of its own origin only (Droid's, inline ones, and files in the agent's Linux it names),
 * so a script from the web never gets `droid`, whose run is a shell in the agent's Linux. Everything else (pictures,
 * styles, fetch, frames) loads from anywhere.
 */
private const val PAGE_CSP = "script-src 'self' 'unsafe-inline' 'unsafe-eval' blob:; object-src 'none'"

/**
 * A page in a WebView, at its own https origin (see [pageOrigin]), served by the app: the document, Droid's script
 * files, the phone's font, the files of Droid's own its chat shows, and files in the agent's Linux it names. Anything
 * else on the web loads as usual, but scripts (see [PAGE_CSP]). Its `droid` calls come through a message channel only
 * pages at a droid.page origin, in their main frame, can use (not a frame they embed).
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class PageWebView(ctx: Context, val origin: String) : WebView(ctx) {
    var doc = ""
    /** Paths in the agent's Linux the page names, which it may load. */
    var named: Set<String> = emptySet()
    var actions: PageActions? = null
    /**
     * Whether its surface shows it live (from the state it collects; what it may do is [PageActions.live], asked fresh
     * each time). Live again (shown again), it is no longer [sent].
     */
    var live = true
        set(v) { field = v; if (v && actions?.live == true) sent = false; markDone() }
    /** It sent, before its surface has heard (the store has the answer at once, the surface a frame later). */
    private var sent = false
    private var dimmed: Boolean? = null
    /** Its content height in px, as it changes. */
    var onHeight: (Int) -> Unit = {}
    var pageHeight = 0; private set
    /** Whether it ends with a footer of buttons, its next step (see droid.js), as that changes. */
    var onFooter: (Boolean) -> Unit = {}
    var hasFooter = false; private set
    /** Its renderer died: it can't be shown again, only replaced. */
    var dead = false; private set
    /** When the owner last tapped or typed in it. Typing comes on the keyboard's thread. */
    @Volatile private var tapped = 0L
    private val main = Handler(Looper.getMainLooper())

    init {
        setBackgroundColor(Color.TRANSPARENT)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        webViewClient = Client()
        webChromeClient = Chrome()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
            WebViewCompat.addWebMessageListener(this, "DroidNative", setOf("https://*.droid.page")) { _, msg, from, mainFrame, reply ->
                if (mainFrame && from.host == origin) onMessage(msg.data ?: return@addWebMessageListener, reply)
            }
    }

    fun show(document: String, paths: Set<String>) {
        doc = document; named = paths
        loadUrl("https://$origin/")
    }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP) tapped = SystemClock.uptimeMillis()
        return super.dispatchTouchEvent(e)
    }

    // What the owner types counts as a tap: a draft typed for longer than the grace still sends from the keyboard.
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val ic = super.onCreateInputConnection(outAttrs) ?: return null
        return object : InputConnectionWrapper(ic, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean { typed(); return super.commitText(text, newCursorPosition) }
            override fun sendKeyEvent(event: KeyEvent?): Boolean { typed(); return super.sendKeyEvent(event) }
            override fun performEditorAction(editorAction: Int): Boolean { typed(); return super.performEditorAction(editorAction) }
        }
    }

    private fun typed() { tapped = SystemClock.uptimeMillis() }

    private fun byTap() = SystemClock.uptimeMillis() - tapped < TAP_GRACE

    /** A done page stays to read; droid.css dims its controls. [again]: a new document, which hasn't been told. */
    private fun markDone(again: Boolean = false) {
        val done = sent || !live
        if (done == dimmed && !again) return
        dimmed = done
        evaluateJavascript("document.documentElement.classList.toggle('droid-done', $done)", null)
    }

    private fun onMessage(data: String, reply: JavaScriptReplyProxy) {
        val o = try { JSONObject(data) } catch (_: JSONException) { return }
        val id = o.optInt("id")
        fun answer(result: Any? = true, error: String? = null) = main.post {
            // (a reply only exists where the listener does: see init)
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) try { reply.postMessage(JSONObject().put("id", id).apply { if (error != null) put("error", error) else put("result", result ?: JSONObject.NULL) }.toString()) }
            catch (e: IllegalStateException) { Log.w("Droid", "page reply: $e") }  // the page went away
        }
        val a = actions
        when (val op = o.optString("op")) {
            "height" -> { pageHeight = (o.optDouble("h") * resources.displayMetrics.density).toInt(); onHeight(pageHeight) }
            "footer" -> { hasFooter = o.optBoolean("on"); onFooter(hasFooter) }
            "dash" -> { a?.dash(o.optString("feel"), o.optString("words")); answer() }
            "close" -> { a?.close(); answer() }
            "send", "run", "screen", "open" -> when {
                a == null -> answer(error = "Not available here")
                !byTap() -> answer(error = "droid.$op works from the owner's tap")
                op != "open" && !a.live -> answer(error = "This page was answered already")
                op == "send" -> {
                    a.send(o.optString("text"), if (o.isNull("data")) null else o.optString("data"))
                    // sent now (not waiting for the unlock): done here at once
                    if (!a.live) { sent = true; markDone() }
                    answer()
                }
                op == "screen" -> { a.screen(); answer() }
                op == "open" -> { a.open(o.optString("url")); answer() }
                else -> thread(isDaemon = true, name = "page-run") {
                    val (out, code) = try { a.run(o.optString("command")) } catch (e: Exception) { (e.message ?: "Failed") to -1 }
                    answer(JSONObject().put("out", out).put("code", code))
                }
            }
        }
    }

    private inner class Client : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
            val host = req.url.host ?: return null
            if (!host.endsWith(".droid.page")) return null
            if (host != origin || req.method != "GET") return missing()
            val path = req.url.path.orEmpty()
            return try {
                when {
                    path.isEmpty() || path == "/" -> served("text/html", "utf-8", ByteArrayInputStream(doc.toByteArray()))
                    path == "/_droid/marked.js" -> served("text/javascript", "utf-8", context.assets.open("page/marked.min.js"))
                    path == "/_droid/font.ttf" -> File(FONT).takeIf { it.canRead() }?.let { served("font/ttf", null, it.inputStream()) } ?: missing()
                    else -> file(path)?.let { served(mime(it.name), null, it.inputStream()) } ?: missing()
                }
            } catch (e: Exception) { Log.w("Droid", "page $path: $e"); missing() }
        }

        /** One of Droid's own copies of what its chat's answers showed, or a file in the agent's Linux that the page names. */
        private fun file(path: String): File? {
            val a = actions ?: return null
            val local = File(path).canonicalFile
            if (local.path.startsWith(a.shown.canonicalPath + "/")) return local.takeIf { it.isFile }
            return if (path in named) a.fetch(path) else null
        }

        override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
            if (req.url.host == origin) return false
            if (req.isForMainFrame && byTap()) browsable(req.url.toString())?.let { actions?.open(it) }
            return req.isForMainFrame
        }

        override fun onPageFinished(view: WebView, url: String?) { markDone(again = true) }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            Log.w("Droid", "page renderer gone (crash=${detail.didCrash()})")
            dead = true
            (parent as? ViewGroup)?.removeView(this@PageWebView)
            return true
        }
    }

    private inner class Chrome : WebChromeClient() {
        // The camera and microphone (Droid holds both permissions): for the page itself, not a frame it embeds, while
        // it may act, and from the owner's tap.
        override fun onPermissionRequest(request: PermissionRequest) {
            val ok = request.resources.filter { it == PermissionRequest.RESOURCE_VIDEO_CAPTURE || it == PermissionRequest.RESOURCE_AUDIO_CAPTURE }
            if (ok.isEmpty() || request.origin.host != origin || actions?.live != true || !byTap()) request.deny() else request.grant(ok.toTypedArray())
        }
    }

    companion object {
        private const val FONT = "/product/fonts/GoogleSansFlex-Regular.ttf"
        private fun served(mime: String?, encoding: String?, data: java.io.InputStream) =
            WebResourceResponse(mime, encoding, 200, "OK", mapOf("Content-Security-Policy" to PAGE_CSP), data)
        private fun missing() = WebResourceResponse("text/plain", "utf-8", 404, "Not found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        private fun mime(name: String) = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: when (name.substringAfterLast('.', "").lowercase()) { "js", "mjs" -> "text/javascript"; "css" -> "text/css"; "json" -> "application/json"; "svg" -> "image/svg+xml"; else -> "application/octet-stream" }
    }
}

/**
 * The open screen's pages, kept while it shows so a page scrolled out of view and back keeps what was typed into it
 * (a page also keeps its localStorage for good). The heights are remembered so a page comes back at its size.
 */
class PageCache {
    private val views = object : LinkedHashMap<String, PageWebView>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PageWebView>): Boolean =
            (size > 8).also { if (it) eldest.value.let { v -> (v.parent as? ViewGroup)?.removeView(v); v.destroy() } }
    }
    private val heights = HashMap<String, Int>()

    fun obtain(ctx: Context, key: String, origin: String, load: (PageWebView) -> Unit): PageWebView =
        views[key]?.takeUnless { it.dead }?.also { (it.parent as? ViewGroup)?.removeView(it) }
            ?: PageWebView(ctx, origin).also { views.remove(key)?.destroy(); views[key] = it; load(it) }

    fun height(key: String) = heights[key] ?: 0
    fun setHeight(key: String, px: Int) { heights[key] = px }

    fun clear() { views.values.forEach { (it.parent as? ViewGroup)?.removeView(it); it.destroy() }; views.clear() }
}

/** The screen's [PageCache]. */
val LocalPageCache = compositionLocalOf<PageCache?> { null }

/**
 * The list a page sits in, which the drags that start on the page scroll (a page in the chat never scrolls itself), with
 * the list's own fling. [onDrag]: the owner dragged it by that much (down is positive); [grew]: a page got taller (it
 * loaded, or changed), so a list following its end keeps up.
 */
class ListDrag(
    private val list: ScrollableState, private val flinging: FlingBehavior, private val scope: CoroutineScope,
    private val onDrag: (Float) -> Unit, val grew: () -> Unit,
) {
    /** The touch a page has: its taps are its own (see [dismissesKeyboard]). */
    internal var pageTouch: PointerId? = null
    internal val moving get() = list.isScrollInProgress
    internal fun by(dy: Float) { onDrag(dy); list.dispatchRawDelta(-dy) }
    internal fun fling(vy: Float) { scope.launch { list.scroll { with(flinging) { performFling(-vy) } } } }
    internal fun stop() { scope.launch { list.stopScroll() } }
}

/** A [ListDrag] for [list] (see there). */
@Composable
fun rememberListDrag(list: ScrollableState, onDrag: (Float) -> Unit = {}, grew: () -> Unit = {}): ListDrag {
    val fling = ScrollableDefaults.flingBehavior()
    val scope = rememberCoroutineScope()
    return remember(list, fling) { ListDrag(list, fling, scope, onDrag, grew) }
}

val LocalListDrag = compositionLocalOf<ListDrag?> { null }

/**
 * Vertical drags that start on a page scroll the list around it instead (the page gets a cancel), and fling it; taps,
 * presses and sideways drags (a slider, a wide table) stay the page's. A touch that catches the list still moving
 * stops it and is the list's, as anywhere in a list: it doesn't press what is under it.
 */
private fun Modifier.passVerticalDrags(drag: ListDrag?): Modifier = if (drag == null) this else pointerInput(drag) {
    val most = viewConfiguration.maximumFlingVelocity
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        drag.pageTouch = down.id
        // By the finger's moves, not its place on the page: the page moves along with the list it scrolls.
        val tracker = VelocityTracker().apply { addPointerInputChange(down) }
        var dragging = drag.moving
        if (dragging) { drag.stop(); down.consume() }
        var moved = Offset.Zero
        while (true) {
            val c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
            tracker.addPointerInputChange(c)
            if (!c.pressed) {
                if (dragging) { c.consume(); if (moved != Offset.Zero) drag.fling(tracker.calculateVelocity(Velocity(most, most)).y) }
                break
            }
            val d = c.positionChange()
            moved += d
            if (!dragging) {
                if (abs(moved.y) > viewConfiguration.touchSlop && abs(moved.y) > abs(moved.x) * 1.2f) dragging = true
                else if (abs(moved.x) > viewConfiguration.touchSlop) break  // sideways: the page's
            }
            if (dragging) { c.consume(); drag.by(d.y) }
        }
    }
}

/**
 * Puts the keyboard away when the owner taps the list or drags it, as in other chat apps; not for a tap on a page in
 * it, which may be on one of the page's fields (a page takes the focus itself when tapped). [view]: the screen's.
 */
fun Modifier.dismissesKeyboard(drag: ListDrag, focus: FocusManager, view: View): Modifier = pointerInput(drag, focus, view) {
    fun dismiss() {
        focus.clearFocus()
        // a field in a page keeps its focus (Compose's doesn't reach into it): only its keyboard goes
        view.context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(view.windowToken, 0)
    }
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var moved = Offset.Zero
        while (true) {
            val c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
            if (!c.pressed) { if (drag.pageTouch != down.id) dismiss(); break }
            moved += c.positionChangeIgnoreConsumed()
            if (abs(moved.y) > viewConfiguration.touchSlop && abs(moved.y) > abs(moved.x)) { dismiss(); break }
            if (abs(moved.x) > viewConfiguration.touchSlop) break
        }
    }
}

/**
 * A page as tall as its content: in the chat ([PageMode.INLINE]), seamless with the answer around it (no frame, the
 * chat's own background), never scrolling itself; floating ([PageMode.FLOAT]), up to [maxHeight] and then scrolling.
 * [key] names it in the [PageCache] (with the theme), [name] its origin; [document] makes its HTML; [live]: whether it
 * shows live, from the state its surface collects (see [PageWebView.live]).
 */
@Composable
fun InlinePage(
    key: String, name: String, dark: Boolean, document: (Context) -> String, named: Set<String>, actions: PageActions,
    modifier: Modifier = Modifier, mode: PageMode = PageMode.INLINE, maxHeight: androidx.compose.ui.unit.Dp = 4000.dp,
    live: Boolean = actions.live,
) {
    val own = remember { PageCache() }
    val cache = LocalPageCache.current ?: own
    DisposableEffect(own) { onDispose { own.clear() } }
    val ck = "$key:${if (dark) "d" else "l"}"
    val density = LocalDensity.current
    var height by remember(ck) { mutableIntStateOf(cache.height(ck)) }
    val shown by animateFloatAsState(if (height > 0) 1f else 0f, tween(180), label = "page")
    val max = with(density) { maxHeight.roundToPx() }
    val drag = LocalListDrag.current
    val inline = mode == PageMode.INLINE
    AndroidView(
        factory = { ctx -> cache.obtain(ctx, ck, pageOrigin(name)) { it.isVerticalScrollBarEnabled = !inline; it.show(document(ctx), named) } },
        modifier = modifier.fillMaxWidth().let { if (inline) it.bleed(EDGE) else it }
            .height(with(density) { height.coerceIn(1, max).toDp() }).alpha(shown)
            .let { if (inline) it.passVerticalDrags(drag) else it },
        update = { v ->
            v.actions = actions
            v.live = live
            v.onHeight = { px -> val more = px > height; height = px; cache.setHeight(ck, px); if (more) drag?.grew?.invoke() }
            if (v.pageHeight > 0 && v.pageHeight != height) height = v.pageHeight
        },
    )
}

/** How far a page in the chat reaches past the text column each side (droid.css pads it back by as much). */
private val EDGE = 2.dp

/** Laid out [by] wider each side than its place, centred on it: the space it takes stays its place's. */
private fun Modifier.bleed(by: androidx.compose.ui.unit.Dp) = layout { m, c ->
    val extra = (by * 2).roundToPx()
    val p = m.measure(c.copy(minWidth = c.maxWidth + extra, maxWidth = c.maxWidth + extra))
    layout(c.maxWidth, p.height) { p.place(-extra / 2, 0) }
}

/**
 * A page filling its place, scrolling itself: the answer on the owner's screen, full screen, or floating over another
 * app. [onFooter]: whether it ends with a footer of buttons (see droid.js), as that changes.
 */
@Composable
fun FillPage(
    key: String, name: String, document: (Context) -> String, named: Set<String>, actions: PageActions, modifier: Modifier = Modifier,
    live: Boolean = actions.live, onFooter: (Boolean) -> Unit = {},
) {
    val cache = remember { PageCache() }
    DisposableEffect(cache) { onDispose { cache.clear() } }
    AndroidView(
        factory = { ctx -> cache.obtain(ctx, key, pageOrigin(name)) { it.isVerticalScrollBarEnabled = true; it.show(document(ctx), named) } },
        modifier = modifier,
        update = { v -> v.actions = actions; v.live = live; v.onFooter = onFooter; onFooter(v.hasFooter) },
    )
}

/**
 * Opens a link or app the way droid.open does: https in the browser, tel:, geo:, mailto:, intent: in their app. Never
 * Droid itself: an intent: link could otherwise open its inner screens with extras of the link's choosing, or hand
 * another app Droid's right to its files.
 */
fun openLink(ctx: Context, url: String) {
    try {
        val i = if (url.startsWith("intent:")) Intent.parseUri(url, Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, url.toUri())
        i.flags = i.flags and GRANTS.inv()
        if (opensDroid(ctx, i)) { Log.w("Droid", "open $url: that's Droid itself"); return }
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        if (e !is ActivityNotFoundException && e !is java.net.URISyntaxException) Log.w("Droid", "open $url: $e")
    }
}

/**
 * A link a page navigated to (not one it opened with droid.open), as Chrome takes one: an intent: link may reach only
 * an app that takes links from the web (BROWSABLE), and names no component or selector of its own, so a page (or a
 * frame it embeds) can't start any app's inner screens with extras of its choosing. Null: one that can't be read.
 */
internal fun browsable(url: String): String? {
    if (!url.startsWith("intent:")) return url
    val i = try { Intent.parseUri(url, Intent.URI_INTENT_SCHEME) } catch (_: Exception) { return null }
    i.addCategory(Intent.CATEGORY_BROWSABLE)
    i.component = null
    i.selector = null
    return i.toUri(Intent.URI_INTENT_SCHEME)
}

/** Whether [i] names Droid, or would open one of its screens. */
private fun opensDroid(ctx: Context, i: Intent): Boolean {
    val own = ctx.packageName
    return listOfNotNull(i, i.selector).any { it.component?.packageName == own || it.`package` == own } ||
        ctx.packageManager.resolveActivity(i, PackageManager.ResolveInfoFlags.of(0))?.activityInfo?.packageName == own
}

private const val GRANTS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
