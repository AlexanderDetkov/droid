package com.adetkov.droid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.adetkov.droid.adb.Discovery
import kotlin.concurrent.thread

/**
 * Pairs Droid with this phone's Wireless debugging (phones without root). While the owner is in Settings, its
 * notification says what to do next ([STEPS], ticked off as Developer options and Wireless debugging come on) and a tap
 * on it opens the place to do it. Once they open "Pair device with pairing code", Droid finds the dialog's port by mDNS
 * and asks for its six-digit code in the notification: the dialog closes when they leave Settings, so the code can't
 * be typed into Droid's own screen. Then it starts Droid's helper and comes back to Droid. A foreground service, so it
 * keeps listening while Settings is in front.
 */
class PairingService : Service() {
    private val app get() = application as DroidApp
    private val nm by lazy { getSystemService(NotificationManager::class.java) }
    private val main = Handler(Looper.getMainLooper())
    private var discovery: Discovery? = null
    /** Wireless debugging's own announcement, which says it's on where Android won't ([DevSettings]). */
    private var wireless: Discovery? = null
    @Volatile private var port = 0
    @Volatile private var working = false
    private val giveUp = Runnable { finish(null, "No pairing code came within 10 minutes; tap Start to try again.") }
    /** The step in Settings last shown, until the dialog is found. */
    private var shown = -1
    private val watch = object : Runnable {
        override fun run() {
            if (port == 0 && !working) guide()
            main.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            START -> begin()
            CODE -> RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_CODE)?.toString()?.let(::submit)
            CANCEL -> finish(null, null)
        }
        return START_NOT_STICKY
    }

    private fun begin() {
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Pairing", NotificationManager.IMPORTANCE_HIGH))
        // (what an earlier look saw of Wireless debugging may be over: this one's says within a second or two)
        if (discovery == null) DevSettings.wirelessSeen = null
        shown = step()
        val (title, text) = STEPS[shown]
        startForeground(ID, note(title, text, ask = false), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        app.setup.pairingStatus(title, shown)  // the setup screen too: [guide] only says a step when it changes
        if (discovery != null) return
        if (!app.adb.canDiscover) return finish(null, AdbHelper.NO_LOCAL_NETWORK)
        if (!nm.areNotificationsEnabled() || nm.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE)
            return finish(null, "Allow Droid's notifications: the pairing code goes into one.")
        wireless = Discovery(this, Discovery.CONNECT, onLost = { DevSettings.wirelessSeen = false }) { DevSettings.wirelessSeen = true }
            .also { it.start() }
        // A failed attempt closes the dialog; opening it again gives a new port (and code), found here as well.
        discovery = Discovery(this, Discovery.PAIRING) { p ->
            if (p == port || working) return@Discovery
            port = p
            nm.notify(ID, note("Type the code here", "The six digits under Wi-Fi pairing code.", ask = true))
            app.setup.pairingStatus("Enter the code in Droid's notification", CODE_STEP)
        }.also { it.start() }
        main.postDelayed(giveUp, 10 * 60_000L)
        main.post(watch)
    }

    private fun step(): Int = step(this)

    /**
     * Says the next step in the notification and on the setup screen, when it changes; a step done pops the next one up
     * over Settings, where the owner is.
     */
    private fun guide() {
        val step = step()
        if (step == shown) return
        shown = step
        val (title, text) = STEPS[step]
        nm.notify(ID, note(title, text, ask = false, alert = true))
        app.setup.pairingStatus(title, step)
    }

    private fun submit(code: String) {
        if (working || port == 0) return
        working = true
        nm.notify(ID, note("Pairing…", null, ask = false))
        app.setup.pairingStatus("Pairing", CODE_STEP + 1)
        thread {
            try {
                app.adb.pair(port, code.filter(Char::isDigit))
            } catch (e: Exception) {
                Log.w(TAG, "pairing failed", e)
                working = false
                port = 0  // Settings closes the dialog after a failed try; wait for the next one
                shown = 2
                nm.notify(ID, note("Pairing didn't work", "Check the code, or open Pair device with pairing code again.", ask = false))
                app.setup.pairingStatus("That didn't work: open Pair device with pairing code again", 2)
                return@thread
            }
            nm.notify(ID, note("Paired. Starting Droid's helper…", null, ask = false))
            app.setup.pairingStatus("Starting Droid's helper", CODE_STEP + 1)
            val error = try { app.adb.ensure(); null } catch (e: Exception) { e.message ?: e.toString() }
            finish(if (error == null) "Droid is connected" else null, error)
            if (error == null) app.setup.comeBack()  // out of Settings: the install starts on the setup screen
        }
    }

    /** Stops listening; [done] is posted as a short-lived notification, [error] goes to the setup screen. */
    private fun finish(done: String?, error: String?) {
        main.removeCallbacks(giveUp)
        main.removeCallbacks(watch)
        discovery?.stop()
        discovery = null
        wireless?.stop()
        wireless = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (done != null) {
            // Droid has turned USB debugging on: adbd runs while it's on, and the helper ends when adbd does.
            val text = "Leave Developer options and USB debugging on: Droid needs them."
            nm.notify(ID + 1, Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(done).setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(openApp()).setAutoCancel(true).setTimeoutAfter(60_000).build())
        }
        app.setup.pairingStatus(null, error = error)
        stopSelf()
    }

    /** While a step in Settings is next, a tap opens Settings there; otherwise Droid. */
    private fun note(title: String, text: String?, ask: Boolean, alert: Boolean = ask): Notification {
        val open = if (!ask && !working && port == 0) PendingIntent.getActivity(this, 1,
            DevSettings.intent(this, WIRELESS_KEY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) else openApp()
        val d = resources.displayMetrics.density
        val b = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notify).setContentTitle(title)
            .setLargeIcon(dashPortrait(Feel.NEEDS_YOU, (64 * d).toInt(), d, lightShade(this)))  // Dash, waiting on you, as in setup
            .setOngoing(true).setOnlyAlertOnce(!alert).setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Cancel", service(CANCEL, mutable = false)).build())
        if (text != null) b.setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))  // all of it, pulled down
        if (ask) {
            val input = RemoteInput.Builder(KEY_CODE).setLabel("Pairing code").build()
            b.addAction(Notification.Action.Builder(null, "Enter code", service(CODE, mutable = true)).addRemoteInput(input).build())
        }
        return b.build()
    }

    private fun service(action: String, mutable: Boolean) = PendingIntent.getService(this, action.hashCode(),
        Intent(this, PairingService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE)

    private fun openApp() = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)

    override fun onDestroy() {
        main.removeCallbacks(giveUp)
        main.removeCallbacks(watch)
        discovery?.stop()
        wireless?.stop()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "DroidAdb"
        private const val CHANNEL = "pairing"
        private const val ID = 7100
        private const val KEY_CODE = "code"
        private const val START = "start"
        private const val CODE = "code"
        private const val CANCEL = "cancel"
        /** Wireless debugging's place in Developer options. */
        const val WIRELESS_KEY = "toggle_adb_wireless"

        /**
         * What to do in Settings, by [SetupState.pairStep]; then the code goes in the notification ([CODE_STEP]). Settings
         * opens where each is, so a line says only what to tap, its start short enough for a pop-up's one line.
         */
        val STEPS = listOf(
            // (where Build number is: Samsung's in Software information; and Android 17 can't tell Droid they're on)
            "Turn on Developer options" to (if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) "In Software information, tap Build number seven times."
                else "Tap Build number seven times.") + " Already on? Then Settings opens right on them.",
            "Turn on Wireless debugging" to "Tick Always allow, then Allow. That's \"Always allow on this network\", so it stays on after a restart.",
            "Get a pairing code" to "Tap Wireless debugging's name, then Pair device with pairing code.",
        )
        const val CODE_STEP = 3

        /**
         * How far the owner got in Settings, as Droid can tell ([DevSettings]; never assumed: Developer options, or
         * Wireless debugging too, may be on from before): Developer options, then Wireless debugging, then its pairing
         * dialog. Where it can't tell, the earlier step, whose Open Settings goes past it when it's done.
         */
        fun step(ctx: Context): Int = when {
            DevSettings.wireless(ctx) == true -> 2
            DevSettings.developer(ctx) == true -> 1
            else -> 0
        }

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, PairingService::class.java).setAction(START))
        }

        fun cancel(ctx: Context) {
            ctx.startService(Intent(ctx, PairingService::class.java).setAction(CANCEL))
        }
    }
}
