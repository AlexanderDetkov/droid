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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.adetkov.droid.adb.Discovery
import kotlin.concurrent.thread

/**
 * Pairs Droid with this phone's Wireless debugging (phones without root). While the owner has Settings' "Pair device
 * with pairing code" dialog open, Droid finds the dialog's port by mDNS and asks for its six-digit code in a
 * notification: the dialog closes when they leave Settings, so the code can't be typed into Droid's own screen. Then
 * it starts Droid's helper. A foreground service, so it keeps listening while Settings is in front.
 */
class PairingService : Service() {
    private val app get() = application as DroidApp
    private val nm by lazy { getSystemService(NotificationManager::class.java) }
    private val main = Handler(Looper.getMainLooper())
    private var discovery: Discovery? = null
    @Volatile private var port = 0
    @Volatile private var working = false
    private val giveUp = Runnable { finish(null, "No pairing code came within 10 minutes; tap Pair to try again.") }

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
        startForeground(ID, note("Waiting for the pairing code", "In Wireless debugging, tap Pair device with pairing code.", ask = false),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (discovery != null) return
        if (!app.adb.canDiscover) return finish(null, AdbHelper.NO_LOCAL_NETWORK)
        if (!nm.areNotificationsEnabled() || nm.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE)
            return finish(null, "Allow Droid's notifications: the pairing code goes into one.")
        // A failed attempt closes the dialog; opening it again gives a new port (and code), found here as well.
        discovery = Discovery(this, Discovery.PAIRING) { p ->
            if (p == port || working) return@Discovery
            port = p
            nm.notify(ID, note("Enter the pairing code", "The six digits Settings shows under Wi-Fi pairing code.", ask = true))
            app.setup.pairingStatus("Enter the code in Droid's notification")
        }.also { it.start() }
        main.postDelayed(giveUp, 10 * 60_000L)
    }

    private fun submit(code: String) {
        if (working || port == 0) return
        working = true
        nm.notify(ID, note("Pairing…", null, ask = false))
        app.setup.pairingStatus("Pairing")
        thread {
            try {
                app.adb.pair(port, code.filter(Char::isDigit))
            } catch (e: Exception) {
                Log.w(TAG, "pairing failed", e)
                working = false
                port = 0  // Settings closes the dialog after a failed try; wait for the next one
                nm.notify(ID, note("Pairing didn't work", "Check the code, or open Pair device with pairing code again.", ask = false))
                app.setup.pairingStatus("That didn't work: open Pair device with pairing code again")
                return@thread
            }
            nm.notify(ID, note("Paired. Starting Droid's helper…", null, ask = false))
            app.setup.pairingStatus("Starting Droid's helper")
            val error = try { app.adb.ensure(); null } catch (e: Exception) { e.message ?: e.toString() }
            finish(if (error == null) "Droid is connected" else null, error)
        }
    }

    /** Stops listening; [done] is posted as a short-lived notification, [error] goes to the setup screen. */
    private fun finish(done: String?, error: String?) {
        main.removeCallbacks(giveUp)
        discovery?.stop()
        discovery = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (done != null) {
            // Droid has turned USB debugging on: adbd runs while it's on, and the helper ends when adbd does.
            val text = "Leave Developer options and USB debugging on: they keep Droid's helper running. Wireless debugging " +
                "can go off; Droid turns it on when it needs it."
            nm.notify(ID + 1, Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(done).setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(openApp()).setAutoCancel(true).setTimeoutAfter(60_000).build())
        }
        app.setup.pairingStatus(null, error)
        stopSelf()
    }

    private fun note(title: String, text: String?, ask: Boolean): Notification {
        val b = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notify).setContentTitle(title)
            .setOngoing(true).setOnlyAlertOnce(!ask).setContentIntent(openApp())
            .addAction(Notification.Action.Builder(null, "Cancel", service(CANCEL, mutable = false)).build())
        if (text != null) b.setContentText(text)
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
        discovery?.stop()
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

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, PairingService::class.java).setAction(START))
        }

        fun cancel(ctx: Context) {
            ctx.startService(Intent(ctx, PairingService::class.java).setAction(CANCEL))
        }
    }
}
