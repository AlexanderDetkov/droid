package com.adetkov.droid

import android.app.Application
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class DroidApp : Application() {
    lateinit var store: Store
    lateinit var runner: Runner
    lateinit var backend: Backend
    lateinit var speech: Speech
    lateinit var voiceMatch: VoiceMatch
    lateinit var setup: Setup
    lateinit var prefs: Prefs
    /** Without root: pairing with Wireless debugging and Droid's helper. */
    lateinit var adb: AdbHelper
    private val shellHost by lazy { ShellHost(adb) }
    /** How Droid reaches Android now: as root, or through its helper as the shell user (see [Setup]). */
    val host: Host get() = if (setup.state.value.access == Access.ADB) shellHost else RootHost
    /** UI visibility, so a finished chat the user is looking at doesn't notify. */
    @Volatile var resumed = false
        set(v) { field = v; if (v) main.removeCallbacks(releaseSpeech) }
    @Volatile var openChat: String? = null
    /** The assistant's screenshot of the app it opened over, on its way from [AssistSession] to the panel. */
    val assistShot = MutableStateFlow<Attachment?>(null)
    fun isVisible(id: String) = resumed && openChat == id

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this).also { it.applyTheme() }
        store = Store(filesDir.resolve("conversations.json"))
        adb = AdbHelper(this)
        backend = defaultBackend(this) { host }
        runner = Runner(store, backend, { prefs.model(it) }, { prefs.engine.value }, shown = { Shown.dir(this, it) }, inUse = ::screenInUse)
        speech = Speech(filesDir.resolve("models"))
        voiceMatch = VoiceMatch(this)
        // Voice match's misses start over at any unlock, not just one into Droid (this process lives on: the assistant
        // service is bound).
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) = voiceMatch.resetIfUnlocked()
        }, IntentFilter(Intent.ACTION_USER_PRESENT), RECEIVER_NOT_EXPORTED)
        clearRunNotifications(this)  // no run is up in a new process
        setup = Setup(this).also { it.refresh() }
        // What the agent asks of the app with its `droid` command: pages to put somewhere, wake-ups, notifications.
        val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        bg.launch { runner.pages.collect { (conv, page) -> presentPage(this@DroidApp, conv, page) } }
        bg.launch { runner.wakes.collect { Later.schedule(this@DroidApp, it) } }
        bg.launch { runner.notes.collect { (conv, spec) -> Notify.prepare(this@DroidApp, conv, spec) } }
        bg.launch { Later.restore(this@DroidApp) }  // this process may be the phone starting again
        bg.launch { Updates.check(this@DroidApp) }
    }

    // The speech models take ~1 GB. Keep them for a while after the app leaves the screen, so asking again soon
    // doesn't wait ~3 s for them to load; then give the memory back.
    private val main = Handler(Looper.getMainLooper())
    private val releaseSpeech = Runnable { speech.release(); voiceMatch.release() }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            main.removeCallbacks(releaseSpeech)
            main.postDelayed(releaseSpeech, KEEP_SPEECH_MS)
        }
    }

    private companion object {
        const val KEEP_SPEECH_MS = 5 * 60_000L
    }
}
