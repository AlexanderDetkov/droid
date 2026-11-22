package com.adetkov.droid

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.net.toUri
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/*
 * Where a page the agent shows goes (`droid show`: the chat, --full or --float), and what a page's calls do there
 * (ChatPageActions). The chat itself is ChatScreen; full screen is PageActivity; floating is FloatPage. Notifications
 * are Notify's (`droid notify`).
 */

internal const val EXTRA_PAGE = "page"

/** The notification that tells the owner about page [pageId] (one waiting on them, or one that couldn't open). */
internal fun pageNote(pageId: String) = "page-$pageId"

/**
 * Whether the agent's command waits on the page at [index] of chat [c] now (`droid show --wait`, or the notification
 * whose button opened it), so what the owner sends from it is the answer (see [awaits]).
 */
fun awaited(app: DroidApp, c: Conv, index: Int): Boolean =
    c.messages.awaits(index, app.runner.working(c.id)) { "${c.id}/$it" in app.runner.noteWaits }

/** Whether the page at [index] of chat [c] may act now: [pageLive], the one test every surface uses. */
fun pageLive(app: DroidApp, c: Conv, index: Int): Boolean =
    c.messages.pageLive(index, app.runner.working(c.id)) { "${c.id}/$it" in app.runner.noteWaits }

/** The same as the store has chat [conv] this moment: for page [pageId], or (null) the answer at [index]. */
fun pageLiveNow(app: DroidApp, conv: String, pageId: String?, index: Int = -1): Boolean {
    val c = app.store.get(conv) ?: return false
    return pageLive(app, c, if (pageId != null) c.pageIndex(pageId) else index)
}

/**
 * What droid.send does, and what the owner types under a page: from a page that may still act ([pageLive]), the answer
 * to it if the agent's command waits on it, or else a message to the chat; either way the page has answered, which its
 * PAGE message keeps (the store is where every surface learns it). From one that may not, a message.
 */
fun pageSend(ctx: Context, conv: String, pageId: String?, text: String, data: String?) {
    val app = ctx.applicationContext as DroidApp
    val c = app.store.get(conv)
    val i = if (c == null || pageId == null) -1 else c.pageIndex(pageId)
    if (pageId != null) Notify.cancel(ctx, conv, pageNote(pageId))
    // (marked as from the page even without data, so the agent, and the owner's bubble, know it was a tap)
    if (c == null || i < 0 || !pageLive(app, c, i)) { startTask(ctx, conv, text, data = data ?: "{}"); return }
    val page = PageInfo.of(c.messages[i])
    val answered = awaited(app, c, i) && if (page.note.isNotEmpty()) Notify.answer(ctx, conv, page.note, pageReply(text, data).put("button", "page"))
        else { app.runner.answerPage(conv, page.id, text, data); true }
    if (!answered) startTask(ctx, conv, text, data = data ?: "{}")
    // a question the agent waited on is answered; the chat's app goes on, live (see currentApp)
    if (answered || i != c.messages.currentApp()) app.store.update(conv) { it.answered(page.id, if (answered) text else "") }
}

/**
 * What the owner sends from a chat's message box: the answer to the page the chat waits on (where it says Your turn),
 * or else a message with [files] (a new chat if [conv] is null). Returns the chat's id (null: it was deleted).
 */
fun ownerSend(ctx: Context, conv: String?, text: String, files: List<Attachment> = emptyList(), via: String? = null): String? {
    val app = ctx.applicationContext as DroidApp
    val c = conv?.let { app.store.get(it) }
    val page = c?.takeIf { files.isEmpty() }?.let { waitingPage(it.messages, app.runner.working(it.id)) }
    if (c == null || page == null) return startTask(ctx, conv, text, files, via = via)
    pageSend(ctx, c.id, PageInfo.of(page).id, text, null)
    return c.id
}

/**
 * A page's calls in chat [conv]: [pageId] for a page the agent showed (null: one in an answer); [isLive]: whether it may
 * still act, as the store has it this moment (see [PageActions.live]; by default [pageLiveNow], for a page the agent
 * showed); [onScreen]: hand over the hidden screen; [onClose]: for a page full screen or floating; [onSent]: it sent.
 */
class ChatPageActions(
    private val ctx: Context, private val conv: String, private val pageId: String?,
    private val isLive: () -> Boolean = { pageId == null || pageLiveNow(ctx.applicationContext as DroidApp, conv, pageId) },
    private val onScreen: () -> Unit = {}, private val onClose: () -> Unit = {}, private val onSent: () -> Unit = {},
) : PageActions {
    private val app = ctx.applicationContext as DroidApp
    private val main = Handler(Looper.getMainLooper())
    override fun send(text: String, data: String?) {
        // answered meanwhile (a send that waited for the unlock, say): a page answers once
        if (!live) return
        pageSend(ctx, conv, pageId, text, data)
        onSent()
    }
    override fun run(command: String) = app.backend.run(command)
    override val live get() = isLive()
    override fun dash(feel: String, words: String) { if (live) app.runner.dash(conv, feel, words) }
    override fun screen() { main.post(onScreen) }
    override fun open(url: String) = openLink(ctx, url)
    override fun close() { main.post(onClose) }
    override fun fetch(path: String): File? {
        val name = MessageDigest.getInstance("SHA-1").digest(path.toByteArray()).take(6).joinToString("") { "%02x".format(it) }
        val f = File(shown, "linux-$name-${File(path).name}")
        return try { if (app.backend.fetch(path, f)) f else null } catch (e: IOException) { null }
    }
    override val shown: File get() = Shown.dir(ctx, conv)
}

/** Opens page [pageId] of chat [conv] full screen, in place of the page there if one is (see [PageActivity]). */
fun pageIntent(ctx: Context, conv: String, pageId: String): Intent = Intent(ctx, PageActivity::class.java)
    .putExtra(EXTRA_CONV, conv).putExtra(EXTRA_PAGE, pageId).setData("droid://page/$conv/$pageId".toUri())
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

/**
 * Puts a page the agent just showed where it asked: full screen, or floating over whatever app is open. One that
 * can't open there (Android doesn't let an app open a screen from the background without the right to draw over
 * apps) becomes a notification that opens it; one in the chat that waits on the owner gets a notification too while
 * the chat isn't on screen. Off the main thread (it may grant Droid that right).
 */
fun presentPage(ctx: Context, conv: String, pageId: String) {
    val app = ctx.applicationContext as DroidApp
    val info = app.store.get(conv)?.page(pageId)?.let(PageInfo::of) ?: return
    val main = Handler(Looper.getMainLooper())
    fun note(text: String, tap: String) = Notify.post(ctx, conv, JSONObject().put("id", pageNote(pageId))
        .put("title", info.title.ifEmpty { app.store.get(conv)?.title.orEmpty() }).put("text", text).put("tap", tap)
        .apply { if (info.wait) put("feel", "needs_you").put("level", "urgent") })
    when (info.where) {
        "full" -> if (app.resumed || canOverlay(ctx)) main.post { ctx.startActivity(pageIntent(ctx, conv, pageId)) } else note("Tap to open it", "page:$pageId")
        "float" -> if (canOverlay(ctx)) main.post { FloatPage.show(ctx, conv, pageId) } else note("Tap to open it", "page:$pageId")
        else -> if (info.wait && !app.isVisible(conv)) note("Droid is waiting for your answer", "chat:")
    }
}

/** Whether Droid may draw over other apps; if not, it grants itself that (appops, as root or the shell user). */
fun canOverlay(ctx: Context): Boolean {
    if (Settings.canDrawOverlays(ctx)) return true
    val app = ctx.applicationContext as DroidApp
    try { app.host.exec("appops set ${ctx.packageName} SYSTEM_ALERT_WINDOW allow").run { outputStream.close(); waitFor() } }
    catch (e: Exception) { Log.w("Droid", "overlay: ${e.message}") }
    return Settings.canDrawOverlays(ctx)
}
