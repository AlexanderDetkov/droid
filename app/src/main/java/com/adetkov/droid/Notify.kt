package com.adetkov.droid

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.os.Bundle
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Notifications the agent posts (`droid notify`, assets/setup/bin/droid): all Android lets a notification be, as the
 * agent wants it. Its spec (JSON, as the command sends it): id, title, text, image (a path in its Linux), buttons
 * [{label, do}], reply (a text field's hint), progress {n, max} or {busy}, ongoing, level (quiet | urgent), private,
 * feel (Dash's look), tap (what tapping it does), at (later), wait (its command waits for what the owner does with
 * it), cancel.
 *
 * What a button or the tap does ("do"): a message to the agent (any text), or `run:COMMAND` (in its Linux, no turn),
 * `open:URL` (a link, tel:, geo:, intent:...), `page:PATH` (an HTML page full screen), `chat:` (this chat), `screen:`
 * (the agent's hidden screen, handed over), `dismiss:`. A command waiting on the notification hears which, or the
 * reply typed, or that it was swiped away; a page's button lets the page answer instead.
 */
object Notify {
    private const val CH = "agent"
    private const val CH_QUIET = "agent-quiet"
    private const val CH_URGENT = "agent-urgent"
    const val ACTION = "note"
    const val EXTRA_NOTE = "note"
    const val EXTRA_DO = "do"
    const val EXTRA_LABEL = "label"
    const val EXTRA_ONGOING = "ongoing"
    /** When this posting of the notification was made: an act on it counts once (see [act]). */
    private const val EXTRA_POSTED = "posted"
    private const val REPLY = "reply"
    private const val CONV = "droid.conv"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The Android id of notification [id] in chat [conv]. "chat" is the chat's own, its answer's: the agent may take it over. */
    fun nid(conv: String, id: String): Int =
        if (id == CHAT) (conv.hashCode() and 0x3fffffff) + 1 else 0x40000000 or ("$conv/$id".hashCode() and 0x3fffffff)

    /**
     * A notification the agent asked for, off the main thread: what it names in the agent's Linux (its picture, the
     * pages its buttons open) is copied out now, while the run is up; then it's posted, or kept for its time.
     */
    fun prepare(ctx: Context, conv: String, o: JSONObject) {
        val app = ctx.applicationContext as DroidApp
        val id = o.optString("id").takeIf { PAGE_ID.matches(it) } ?: return
        if (o.optBoolean("cancel")) { cancel(ctx, conv, id); Later.dropNote(ctx, conv, id); return }
        val dir = File(ctx.filesDir, "notes/$conv").apply { mkdirs() }
        o.optString("image").takeIf { it.isNotEmpty() }?.let { path ->
            val f = File(dir, "$id-${hash(path)}")
            try { if (app.backend.fetch(path, f)) o.put("imageFile", f.path) } catch (e: IOException) { Log.w("Droid", "note image: ${e.message}") }
        }
        // a page it opens goes into the chat (as one shown full screen), so it opens from there and stays in the chat
        fun page(what: String): String {
            if (!what.startsWith("page:")) return what
            val path = what.removePrefix("page:")
            val pid = "n-$id-${hash(path)}"
            val f = File(dir, "$pid.html")
            val html = try { if (app.backend.fetch(path, f)) f.readText().also { f.delete() } else null } catch (e: IOException) { null }
            if (html == null) return "chat:"
            app.runner.putPage(conv, PageInfo(pid, where = "full", title = pageTitle(html), note = id), html)
            return "page:$pid"
        }
        o.optJSONArray("buttons")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { b -> b.put("do", page(b.optString("do"))) } }
        o.optString("tap").takeIf { it.isNotEmpty() }?.let { o.put("tap", page(it)) }
        val at = o.optLong("at")
        if (at > System.currentTimeMillis() + 1000) Later.schedule(ctx, Wake(conv, at, o.optString("title"), note = o.toString())) else post(ctx, conv, o)
    }

    fun cancel(ctx: Context, conv: String, id: String) = ctx.getSystemService(NotificationManager::class.java).cancel(nid(conv, id))

    /** A chat was deleted: its notifications go, and so do those it set for later, and the files they kept. */
    fun forget(ctx: Context, conv: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.activeNotifications.filter { it.notification.extras.getString(CONV) == conv }.forEach { nm.cancel(it.id) }
        Later.dropChat(ctx, conv)
        File(ctx.filesDir, "notes/$conv").deleteRecursively()
    }

    @Volatile private var channelsMade = false

    /** Its channels, made once a process (Android keeps them). */
    private fun channels(nm: NotificationManager) {
        if (channelsMade) return
        nm.createNotificationChannel(NotificationChannel(CH, "From Droid", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_QUIET, "From Droid, quietly", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_URGENT, "From Droid, urgent", NotificationManager.IMPORTANCE_HIGH))
        nm.deleteNotificationChannel("pages")  // pages as notifications, before droid notify
        channelsMade = true
    }

    /** Posts (or updates, under the same id) the notification [o] in chat [conv]. */
    fun post(ctx: Context, conv: String, o: JSONObject) {
        val app = ctx.applicationContext as DroidApp
        val c = app.store.get(conv) ?: return
        val id = o.optString("id")
        val nm = ctx.getSystemService(NotificationManager::class.java)
        channels(nm)
        val ch = when (o.optString("level")) { "quiet" -> CH_QUIET; "urgent" -> CH_URGENT; else -> CH }
        val feel = o.optString("feel").takeIf { it.isNotEmpty() }?.let(Feel::of) ?: lastMood(c.messages)?.feel ?: Feel.IDLE
        val d = ctx.resources.displayMetrics.density
        val title = o.optString("title").ifEmpty { c.title }
        val text = o.optString("text")
        val ongoing = o.optBoolean("ongoing")
        val posted = System.currentTimeMillis()
        fun pi(i: Int, label: String, what: String, mutable: Boolean = false) = pending(ctx, conv, id, i, label, what, ongoing, posted, mutable)
        val b = Notification.Builder(ctx, ch)
            .addExtras(Bundle().apply { putString(CONV, conv) })  // whose it is, for when the chat is deleted
            .setSmallIcon(R.drawable.ic_notify)
            .setLargeIcon(dashPortrait(feel, (64 * d).toInt(), d))
            .setContentTitle(title)
            .setOnlyAlertOnce(true)   // updated under its id (progress), it doesn't sound again
            .setShowWhen(true)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setContentIntent(pi(-1, "tap", o.optString("tap").ifEmpty { "chat:" }))
            .setDeleteIntent(pi(-2, SWIPED, ""))
        if (text.isNotEmpty()) b.setContentText(text.replace(Regex("\\s+"), " "))
        if (feel.call) b.setSubText("Your turn")
        val image = o.optString("imageFile").takeIf { it.isNotEmpty() }?.let { picture(it) }
        if (image != null) b.setStyle(Notification.BigPictureStyle().bigPicture(image).apply { if (text.isNotEmpty()) setSummaryText(text) })
        else if (text.isNotEmpty()) b.setStyle(Notification.BigTextStyle().bigText(text))
        o.optJSONObject("progress")?.let { p ->
            if (p.optBoolean("busy")) b.setProgress(0, 0, true) else b.setProgress(p.optInt("max", 100).coerceAtLeast(1), p.optInt("n"), false)
        }
        if (o.optBoolean("private")) b.setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(Notification.Builder(ctx, ch).setSmallIcon(R.drawable.ic_notify).setContentTitle("Droid").build())
        val buttons = o.optJSONArray("buttons")
        for (i in 0 until (buttons?.length() ?: 0)) {
            val bt = buttons!!.optJSONObject(i) ?: continue
            val label = bt.optString("label").ifEmpty { continue }
            val what = bt.optString("do").ifEmpty { label }
            b.addAction(Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_send), label, pi(i, label, what))
                .setAuthenticationRequired(what != "dismiss:").build())  // nothing acts from the lock screen
        }
        o.optString("reply").takeIf { o.has("reply") }?.let { hint ->
            val input = RemoteInput.Builder(REPLY).setLabel(hint.ifEmpty { "Reply" }).build()
            b.addAction(Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_send), "Reply", pi(9, "Reply", "", mutable = true))
                .addRemoteInput(input).setAuthenticationRequired(true).build())
        }
        nm.notify(nid(conv, id), b.build())
    }

    /** A picture for the notification, at most about the screen's width (Android drops one that's too big). */
    private fun picture(path: String) = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        var sample = 1
        while (o.outWidth / (sample * 2) >= 1080) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (_: Throwable) { null }

    /**
     * What the tap ([i] -1), a button, the reply field, or swiping it away ([i] -2) does: what opens a screen goes
     * through [NoteActivity] (Android lets only an activity start one), the rest through AgentService. Both [act].
     */
    private fun pending(ctx: Context, conv: String, id: String, i: Int, label: String, what: String, ongoing: Boolean, posted: Long, mutable: Boolean): PendingIntent {
        val data = "droid://note/$conv/$id/$i".toUri()
        val screen = what.startsWith("open:") || what.startsWith("page:") || what == "chat:" || what == "screen:"
        val intent = Intent(ctx, if (screen) NoteActivity::class.java else AgentService::class.java).setData(data)
            .putExtra(EXTRA_CONV, conv).putExtra(EXTRA_NOTE, id).putExtra(EXTRA_DO, what).putExtra(EXTRA_LABEL, label)
            .putExtra(EXTRA_ONGOING, ongoing).putExtra(EXTRA_POSTED, posted)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return if (screen) PendingIntent.getActivity(ctx, 0, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags)
            else PendingIntent.getService(ctx, 0, intent.setAction(ACTION), flags)
    }

    /**
     * The owner acted on a notification: it goes (unless it stays, ongoing), its command runs, and the agent's command
     * waiting on it hears what was done (a page's button: the page answers it instead). With nothing waiting, a message
     * button or a reply is a message to the chat. A screen to open is [NoteActivity]'s.
     */
    fun act(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext as DroidApp
        val conv = intent.getStringExtra(EXTRA_CONV) ?: return
        val id = intent.getStringExtra(EXTRA_NOTE) ?: return
        if (app.store.get(conv) == null) return  // a chat deleted meanwhile
        // A notification answers once, as a page does: a second tap before it has gone (or one Android delivers twice)
        // doesn't send or run it again. One the agent made ongoing stays, a control to use as often as the owner likes.
        val ongoing = intent.getBooleanExtra(EXTRA_ONGOING, false)
        if (!ongoing && !acted.add("$conv/$id@${intent.getLongExtra(EXTRA_POSTED, 0)}")) return
        val what = intent.getStringExtra(EXTRA_DO).orEmpty()
        val label = intent.getStringExtra(EXTRA_LABEL).orEmpty()
        val typed = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(REPLY)?.toString()
        when {
            // a reply stays on screen a moment with what was sent (Android keeps a replied-to notification it was
            // asked to take away until the shade closes, so it is replaced with one that ends by itself instead)
            typed != null -> sent(ctx, conv, id, typed)
            !ongoing || what == "dismiss:" -> cancel(ctx, conv, id)
        }
        if (what.startsWith("run:")) scope.launch {
            try { app.backend.run(what.removePrefix("run:")).let { (out, code) -> Log.i("Droid", "note run exit=$code ${out.take(200)}") } }
            catch (e: Exception) { Log.w("Droid", "note run: ${e.message}") }
        }
        if (what.startsWith("page:")) return
        val reply = JSONObject().put("button", if (typed != null) "reply" else label).put("text", typed ?: what)
        val message = typed ?: what.takeUnless { ACTIONS.any { what.startsWith(it) } }
        if (!answer(ctx, conv, id, reply) && !message.isNullOrBlank()) startTask(ctx, conv, message)
    }

    /** The postings acted on (chat/id@posted), so each answers once. */
    private val acted: MutableSet<String> = java.util.Collections.synchronizedSet(LinkedHashSet())

    /** Notification [id] shows [typed] as sent, without its buttons, and goes a moment later. */
    private fun sent(ctx: Context, conv: String, id: String, typed: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val n = nm.activeNotifications.firstOrNull { it.id == nid(conv, id) }?.notification ?: return cancel(ctx, conv, id)
        nm.notify(nid(conv, id), Notification.Builder.recoverBuilder(ctx, n).setActions().setRemoteInputHistory(arrayOf(typed))
            .setOngoing(false).setOnlyAlertOnce(true).setTimeoutAfter(2500).build())
    }

    /** If the agent's command waits on notification [id] in chat [conv], it gets [reply] (true); it waits for one only. */
    fun answer(ctx: Context, conv: String, id: String, reply: JSONObject): Boolean {
        val app = ctx.applicationContext as DroidApp
        if (!app.runner.noteWaits.remove("$conv/$id") || !app.runner.working(conv)) return false
        scope.launch { try { app.backend.reply("n-$id", reply.toString()) } catch (e: IOException) { Log.w("Droid", "note reply: ${e.message}") } }
        return true
    }

    private fun hash(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).take(4).joinToString("") { "%02x".format(it) }

    /** The id of the chat's own notification (its answer's), which the agent may take over or silence. */
    const val CHAT = "chat"
    /** What a waiting command hears as the button when the notification was swiped away. */
    private const val SWIPED = "dismissed"
    /** What a button or the tap does besides a message to the agent. */
    private val ACTIONS = listOf("run:", "open:", "page:", "chat:", "screen:", "dismiss:")
}

/**
 * Where a notification's tap or button opens a screen: the chat, the agent's screen, a page, or a link or app; the rest
 * is as for any act on it ([Notify.act]). No window of its own.
 */
class NoteActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val conv = intent.getStringExtra(EXTRA_CONV)
        val what = intent.getStringExtra(Notify.EXTRA_DO).orEmpty()
        if (conv != null) when {
            what.startsWith("open:") -> openLink(this, what.removePrefix("open:"))
            what.startsWith("page:") -> startActivity(pageIntent(this, conv, what.removePrefix("page:")))
            what == "screen:" -> openChat(this, conv, handoff = true)
            else -> openChat(this, conv, handoff = false)
        }
        Notify.act(this, intent)
        finish()
    }
}
