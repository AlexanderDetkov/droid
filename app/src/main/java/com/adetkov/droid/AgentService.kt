package com.adetkov.droid

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Sends a message to a chat (new chat if [convId] is null) and keeps the process alive while it runs. Returns the chat
 * id, or null if the chat was deleted (see [Runner.send]).
 */
fun startTask(ctx: Context, convId: String?, text: String, files: List<Attachment> = emptyList(), data: String? = null, wake: Boolean = false,
              via: String? = null): String? {
    convId?.let { clearDone(ctx, it) }  // its last answer is past now
    val id = (ctx.applicationContext as DroidApp).runner.send(convId, text, files, data, wake, via) ?: return null
    serve(ctx, id)
    return id
}

/** Has [AgentService] keep the process alive while chat [id] runs. */
private fun serve(ctx: Context, id: String) {
    try { ctx.startForegroundService(Intent(ctx, AgentService::class.java).putExtra(EXTRA_CONV, id)) }
    catch (e: ForegroundServiceStartNotAllowedException) {
        // From the background without an exemption (a wake-up on an alarm Android may delay): the task was sent and
        // runs as long as Android lets the app.
        Log.w("Droid", "agent service: ${e.message}")
    }
}

/** Removes the chat's "finished" notification (its answer, or the agent's own in its place). */
fun clearDone(ctx: Context, convId: String) = ctx.getSystemService(NotificationManager::class.java).cancel(nid(convId) + 1)

/**
 * Deletes chat [convId] and all it left: its run and agent session (see [Runner.delete]), its stills and the files its
 * answers showed, its notifications (the app's and the agent's), and what the agent set for later in it.
 */
fun deleteChat(ctx: Context, convId: String) {
    val app = ctx.applicationContext as DroidApp
    app.runner.delete(convId)
    Stills.delete(ctx, convId)
    Shown.delete(ctx, convId)
    clearDone(ctx, convId)
    Notify.forget(ctx, convId)
}

private fun nid(id: String) = id.hashCode() and 0x3fffffff

/** Sends the chat's last request again, as [startTask] does. */
fun retryTask(ctx: Context, convId: String) {
    val turn = (ctx.applicationContext as DroidApp).runner.retry(convId) ?: return
    clearDone(ctx, convId)  // its "Couldn't finish" is past now
    Stills.deleteFrom(ctx, convId, turn)  // the new try is a turn at the same place, without the old one's still
    serve(ctx, convId)
}

/** Takes away the ongoing notifications of runs from a process before this one (Android removes only its foreground one). */
fun clearRunNotifications(ctx: Context) {
    val nm = ctx.getSystemService(NotificationManager::class.java)
    nm.activeNotifications.filter { it.notification.channelId == CH_RUN }.forEach { nm.cancel(it.tag, it.id) }
}

const val EXTRA_CONV = "conv"
const val EXTRA_WATCH = "watch"
/** Opened to take over the agent's screen, as a page handed it over (droid.screen). */
const val EXTRA_HANDOFF = "handoff"
private const val ACTION_STOP = "stop"
/** Which run, and whether working or holding its screen, a Stop or Close screen was shown for. */
private const val EXTRA_RUN = "run"
private const val CH_RUN = "running"
private const val CH_DONE = "done"

/**
 * Foreground service alive while any chat has a run up: one ongoing notification per run (working, or holding its hidden
 * screen after answering), one result notification per finished request. Holding a screen keeps the service up too, so
 * Android doesn't freeze the app and the screen closes on time, and so does an agent exiting after its answer.
 */
class AgentService : Service() {
    // Main thread, like onStartCommand, so the two never race.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val app get() = application as DroidApp
    private val nm get() = getSystemService(NotificationManager::class.java)
    private var loop: Job? = null
    private var fgId: String? = null
    private var lastStartId = 0
    /** Chats working on a request, to notify when it finishes. */
    private val tracked = HashSet<String>()
    /** Chats with an ongoing notification. */
    private val posted = HashSet<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val id = intent?.getStringExtra(EXTRA_CONV)
        // Stop, or a button or the reply field of a notification the agent posted (droid notify). A watch loop stops the
        // service once nothing is up, after it has said how each request ended; without one nothing keeps it.
        if (intent != null && (intent.action == ACTION_STOP || intent.action == Notify.ACTION)) {
            // A Stop from a notification that's out of date (the run has moved on: a reply to its held screen started a
            // turn, or a new run began) would stop what the owner just asked for: it stops only what it was shown for.
            if (intent.action == ACTION_STOP) id?.takeIf { intent.getStringExtra(EXTRA_RUN) == runKey(it) }?.let { app.runner.stop(it) }
            else Notify.act(this, intent)
            if (loop?.isActive != true) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (id == null) { stopSelf(startId); return START_NOT_STICKY }
        nm.createNotificationChannel(NotificationChannel(CH_RUN, "Running chats", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_DONE, "Finished chats", NotificationManager.IMPORTANCE_DEFAULT))
        tracked += id
        if (fgId == null || fgId !in app.runner.screens.value) fgId = id
        startForeground(nid(fgId!!), ongoing(fgId!!), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (loop?.isActive != true) loop = scope.launch { watch() }
        return START_NOT_STICKY
    }

    override fun onDestroy() { scope.cancel() }

    /**
     * Reacts to run/transcript changes (no polling); notification updates are throttled to ~1/s.
     * Reads the current runs rather than the emitted ones: a chat is marked running (on the main thread)
     * before its start command arrives, so "nothing runs" here really means the service can stop.
     */
    private suspend fun watch() {
        combine(app.runner.running, app.runner.screens, app.store.convs, app.runner.doing) { _, _, _, _ -> }.collect {
            val busy = app.runner.running.value.keys
            val runs = app.runner.screens.value.keys
            // With an ongoing notification: working, or holding its screen for a reply (not one whose agent is exiting,
            // its screen gone).
            val shown = app.runner.screens.value.filterValues { it.port != null }.keys + busy
            // A request finished (its run may hold the screen on): its answer, or why it stopped; not over the chat itself.
            for (id in tracked - busy) { tracked -= id; if (!app.isVisible(id)) doneNotification(id)?.let { nm.notify(nid(id) + 1, it) } }
            for (id in posted - shown) { posted -= id; nm.cancel(nid(id)) }
            // Up while any run is, one exiting after its answer too: its agent may go on (to a message sent mid-turn),
            // which a frozen app wouldn't see.
            if (runs.isEmpty()) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(lastStartId)
                return@collect
            }
            if (fgId !in shown) {
                // Only exiting runs: one of them, as finishing up, until they've gone or one goes on.
                fgId = busy.firstOrNull() ?: shown.firstOrNull() ?: runs.first()
                startForeground(nid(fgId!!), ongoing(fgId!!), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            }
            for (id in shown) { posted += id; if (id in busy) tracked += id; nm.notify(nid(id), ongoing(id)) }
            delay(1000)
        }
    }


    private fun open(id: String, watch: Boolean): PendingIntent = PendingIntent.getActivity(
        this, nid(id) * 4 + if (watch) 1 else 0,
        Intent(this, MainActivity::class.java)
            .putExtra(EXTRA_CONV, id).putExtra(EXTRA_WATCH, watch)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),  // over a full-screen page too
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun action(icon: Int, title: String, pi: PendingIntent, unlock: Boolean = false) =
        Notification.Action.Builder(Icon.createWithResource(this, icon), title, pi).setAuthenticationRequired(unlock).build()

    /** Stop for a working run; for one that answered, closes its screen (see [Runner.stop]). */
    private fun stop(id: String): PendingIntent = PendingIntent.getService(
        this, nid(id) * 4 + 2,
        Intent(this, AgentService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_CONV, id).putExtra(EXTRA_RUN, runKey(id)),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun runKey(id: String) = "${app.runner.screens.value[id]?.started}/${id in app.runner.running.value}"

    private fun ongoing(id: String) = when {
        id in app.runner.running.value -> runningNotification(id)
        app.runner.screens.value[id]?.port != null -> heldNotification(id)
        else -> finishingNotification(id)
    }

    /** Dash as the notification's picture, in the pose of what the chat is doing or waits on you for. */
    private fun dash(feel: Feel) = dashPortrait(feel, (64 * resources.displayMetrics.density).toInt(), resources.displayMetrics.density, lightShade(this))

    private fun runningNotification(id: String): Notification {
        val conv = app.store.get(id)
        // a page the agent waits on: it's the owner's turn, mid-task
        val page = conv?.let { waitingPage(it.messages, true) }
        return Notification.Builder(this, CH_RUN)
            .setSmallIcon(R.drawable.ic_notify)
            .setLargeIcon(dash(chatFeel(true, app.runner.doing.value[id], null, waiting = page != null)))
            .apply { if (page != null) setSubText("Your turn") }
            .setContentTitle(conv?.title ?: "Droid")
            .setContentText(page?.let { PageInfo.of(it).title.ifEmpty { "Droid is waiting for your answer" } } ?: conv?.let { latestStatus(it.messages) } ?: "Working…")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setUsesChronometer(true)
            .setShowWhen(true)
            .setWhen(app.runner.running.value[id]?.started ?: System.currentTimeMillis())
            .setContentIntent(open(id, false))
            .addAction(action(R.drawable.ic_eye, "Watch", open(id, true)))
            .addAction(action(R.drawable.ic_stop, "Stop", stop(id)))
            .build()
            .also { it.flags = it.flags or Notification.FLAG_NO_CLEAR or Notification.FLAG_ONGOING_EVENT }
    }

    /** A run that answered and keeps its hidden screen for a reply: counts down to when the screen closes. */
    private fun heldNotification(id: String): Notification {
        val conv = app.store.get(id)
        val closesAt = app.runner.screens.value[id]?.closesAt
        val outcome = conv?.let { lastOutcome(it.messages, false) }
        val mood = conv?.let { lastMood(it.messages) }
        return Notification.Builder(this, CH_RUN)
            .setSmallIcon(R.drawable.ic_notify)
            .setLargeIcon(dash(mood?.feel ?: if (outcome == Outcome.ASKS) Feel.NEEDS_YOU else Feel.IDLE))
            .apply { turnLabel(outcome, mood)?.let { setSubText(it) } }
            .setContentTitle(conv?.title ?: "Droid")
            .setContentText(if (closesAt != null) "Droid's screen stays open for your reply, or for you to take over"
                else "Droid's screen stays open while you watch")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply { if (closesAt != null) setUsesChronometer(true).setChronometerCountDown(true).setShowWhen(true).setWhen(closesAt) else setShowWhen(false) }
            .setContentIntent(open(id, false))
            .addAction(action(R.drawable.ic_eye, "Watch", open(id, true)))
            .addAction(action(R.drawable.ic_close, "Close screen", stop(id)))
            .build()
            .also { it.flags = it.flags or Notification.FLAG_NO_CLEAR or Notification.FLAG_ONGOING_EVENT }
    }

    /**
     * A run whose agent is exiting after its answer, shown only while no other run is up (the service needs a
     * notification): nothing to stop or watch, and it goes once the agent has (see Runner.letExit).
     */
    private fun finishingNotification(id: String): Notification {
        val conv = app.store.get(id)
        val messages = conv?.messages.orEmpty()
        return Notification.Builder(this, CH_RUN)
            .setSmallIcon(R.drawable.ic_notify)
            .setLargeIcon(dash(chatFeel(false, null, lastOutcome(messages, false), lastMood(messages))))
            .setContentTitle(conv?.title ?: "Droid")
            .setContentText("Finishing up…")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(open(id, false))
            .build()
            .also { it.flags = it.flags or Notification.FLAG_NO_CLEAR or Notification.FLAG_ONGOING_EVENT }
    }

    private fun doneNotification(id: String): Notification? {
        if (id in app.runner.ownNote) return null  // the agent took it over, or silenced it (droid notify --id chat)
        val conv = app.store.get(id) ?: return null
        val last = conv.messages.lastOrNull { it.kind == Kind.AGENT || it.kind == Kind.STATUS || it.kind == Kind.NOTE }
        // A failure says what it means, as the chat does (see plainStatus)
        val text = last?.let { m -> if (m.kind == Kind.STATUS) plainStatus(m.text, conv.engine)?.text ?: m.text else m.text } ?: "Done"
        val outcome = lastOutcome(conv.messages, false)
        val mood = lastMood(conv.messages)
        return Notification.Builder(this, CH_DONE)
            .setSmallIcon(R.drawable.ic_notify)
            .setLargeIcon(dash(chatFeel(false, null, outcome, mood)))
            .apply { turnLabel(outcome, mood)?.let { setSubText(it) } }
            .setContentTitle(conv.title)
            .setContentText(plain(text))
            .setStyle(Notification.BigTextStyle().bigText(stripMarkdown(text)))
            .setAutoCancel(true)
            .setContentIntent(open(id, false))
            .build()
    }

    private fun plain(s: String) = stripMarkdown(s).replace(Regex("\\s+"), " ")
}
