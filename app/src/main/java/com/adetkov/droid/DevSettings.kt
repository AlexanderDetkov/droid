package com.adetkov.droid

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings

/**
 * What Droid knows of Developer options and Wireless debugging, which pairing goes by. Android 17 tells apps 0 for
 * Developer options' and USB debugging's settings whatever they are, and some of its builds for Wireless debugging's
 * too, so there a 0 means "can't tell"; a 1 is still the truth. What can't be read is seen instead: Wireless debugging
 * by this phone's own mDNS service ([Discovery.CONNECT], see [wireless]), Developer options by whether their page stays
 * open ([DevOptionsActivity]) or by adbd running (USB or Wireless debugging is on, so they are).
 */
object DevSettings {
    /** Settings.Global.ADB_WIFI_ENABLED, hidden. */
    const val ADB_WIFI = "adb_wifi_enabled"

    /** Android hides these settings from apps (17 on; [settingsHiddenForTest] makes a debug build act so). */
    val hidden: Boolean get() = Build.VERSION.SDK_INT >= 37 || settingsHiddenForTest

    /** Setting [key] (Settings.Global) on, off, or null where Android won't say. */
    fun read(ctx: Context, key: String): Boolean? {
        val on = !settingsHiddenForTest && Settings.Global.getInt(ctx.contentResolver, key, 0) == 1
        return if (on) true else if (hidden) null else false
    }

    /** Developer options as [DevOptionsActivity] last saw them, in this process. */
    @Volatile var developerSeen: Boolean? = null

    /** Wireless debugging's mDNS service as last seen (by [PairingService] or [AdbHelper]): announced or gone. */
    @Volatile var wirelessSeen: Boolean? = null

    /**
     * Developer options on, off, or null when it can't be told: as read, where Android says; else on while adbd runs or
     * Wireless debugging is seen (both need them on); else as [DevOptionsActivity] last saw them.
     */
    fun developer(ctx: Context): Boolean? =
        read(ctx, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED) ?: if (adbdRunning() || wireless(ctx) == true) true else developerSeen

    /** Wireless debugging on, off, or null when it can't be told: as read, where Android says, else as last seen. */
    fun wireless(ctx: Context): Boolean? = read(ctx, ADB_WIFI) ?: wirelessSeen

    /**
     * adbd runs only while USB or Wireless debugging is on, which only Developer options turn on. Readable where the
     * settings aren't (not on an emulator, where adbd always runs, nor in [settingsHiddenForTest]).
     */
    private fun adbdRunning(): Boolean = !settingsHiddenForTest && !isEmulator &&
        runCatching { ProcessBuilder("getprop", "init.svc.adbd").start().inputStream.bufferedReader().readText().trim() == "running" }
            .getOrDefault(false)

    /** Opens Developer options at setting [key] (scrolled to, where the phone supports it), or About phone if they're off. */
    fun open(ctx: Context, key: String? = null) {
        ctx.startActivity(intent(ctx, key).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** What [open] starts, for a notification to start it. */
    fun intent(ctx: Context, key: String? = null): Intent =
        Intent(ctx, DevOptionsActivity::class.java).putExtra(DevOptionsActivity.EXTRA_KEY, key)

    internal fun developerOptions(key: String?) = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        .apply { key?.let { putExtra(":settings:fragment_args_key", it) } }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** About phone at Build number, which turns Developer options on (Samsung keeps it in Software information). */
    internal fun aboutPhone() = Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)
        .putExtra(":settings:fragment_args_key", "build_number").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/**
 * Opens Developer options or, while they're off, About phone at Build number ([DevSettings.open]). Whether they're on
 * shows in how Settings takes it: while they're off it closes their page at once, so this comes straight back to the
 * front; while they're on the page stays, and this is out of sight. What it sees goes to [DevSettings.developerSeen].
 * Settings opens in a task of its own, as anywhere else; this has no window to see, and ends as soon as it knows.
 */
class DevOptionsActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var openedAt = 0L
    private var paused = false
    /** Out of sight a moment after Settings opened: their page stayed, so they're on. */
    private val stayed = Runnable {
        DevSettings.developerSeen = true
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return finish()  // brought back after Android ended Droid: nothing to watch
        // Off for sure (Android still says, before 17): straight to where they're turned on
        if (DevSettings.read(this, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED) == false) return go(DevSettings.aboutPhone())
        openedAt = SystemClock.uptimeMillis()
        try {
            startActivity(DevSettings.developerOptions(intent.getStringExtra(EXTRA_KEY)))
        } catch (_: ActivityNotFoundException) {
            go(DevSettings.aboutPhone())
        }
    }

    override fun onPause() {
        super.onPause()
        if (openedAt > 0) paused = true
    }

    override fun onStop() {
        super.onStop()
        if (openedAt > 0 && !isFinishing) main.postDelayed(stayed, SETTLE_MS)
    }

    override fun onResume() {
        super.onResume()
        if (!paused || isFinishing) return  // (first shown, before Settings opened)
        main.removeCallbacks(stayed)
        // Back in front: right away, Settings closed their page (they're off); later, the owner came back
        if (SystemClock.uptimeMillis() - openedAt < BOUNCE_MS) {
            DevSettings.developerSeen = false
            go(DevSettings.aboutPhone())
        } else finish()
    }

    override fun onDestroy() {
        main.removeCallbacks(stayed)
        super.onDestroy()
    }

    private fun go(i: Intent) {
        try { startActivity(i) } catch (_: ActivityNotFoundException) { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        finish()
    }

    companion object {
        const val EXTRA_KEY = "key"
        /** Settings closes Developer options' page within this when they're off (even on a slow phone). */
        private const val BOUNCE_MS = 2000L
        /** Out of sight this long: Settings' page is up and staying. */
        private const val SETTLE_MS = 700L
    }
}
