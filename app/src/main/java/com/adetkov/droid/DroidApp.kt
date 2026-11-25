package com.adetkov.droid

import android.app.Application
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
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
        set(v) { field = v; if (v) main.removeCallbacks(releaseVoiceMatch) }
    @Volatile var openChat: String? = null
    /** The assistant's screenshot of the app it opened over, on its way from [AssistSession] to the panel. */
    val assistShot = MutableStateFlow<Attachment?>(null)
    /** The app the assistant was opened over (its name), or null: the home screen, Droid, or not known (Android 14). */
    @Volatile var assistOver: String? = null
    fun isVisible(id: String) = resumed && openChat == id

    /**
     * A bug in background work (a reader, a writer, a picture saved, an executor's task) is logged, never the end of the
     * app: an uncaught exception on any thread but the main one would otherwise kill the process, and with it every
     * chat's run (the agent going on without the app, its chat "Interrupted"). The main thread's still end it: the
     * screen can't go on safely after one.
     */
    private fun keepGoingOnBackgroundErrors() {
        val fatal = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            if (t === Looper.getMainLooper().thread) fatal?.uncaughtException(t, e)
            else android.util.Log.e("Droid", "background error on ${t.name}", e)
        }
    }

    override fun onCreate() {
        super.onCreate()
        keepGoingOnBackgroundErrors()
        prefs = Prefs(this).also { it.applyTheme() }
        store = Store(filesDir.resolve("conversations.json"))
        adb = AdbHelper(this)
        backend = defaultBackend(this) { host }
        // New chats go to the agent picked in Settings, or else to one that can take them (see SetupState.engineFor)
        runner = Runner(store, backend, { prefs.model(it) }, { setup.state.value.engineFor(prefs.engine.value) },
            shown = { Shown.dir(this, it) }, inUse = ::screenInUse, inFront = ::appInFront, holdScreen = ::holdScreen)
        speech = Speech(filesDir.resolve("models"))
        voiceMatch = VoiceMatch(this)
        // Voice match's misses start over at any unlock, not just one into Droid (this process lives on: the assistant
        // service is bound).
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) = voiceMatch.resetIfUnlocked()
        }, IntentFilter(Intent.ACTION_USER_PRESENT), RECEIVER_NOT_EXPORTED)
        clearRunNotifications(this)  // no run is up in a new process
        setup = Setup(this).also { it.refreshOnStart() }
        // What the agent asks of the app with its `droid` command: pages to put somewhere, wake-ups, notifications.
        // Each request in its own guard: one that fails (a full list of alarms, a spec it can't use) is logged, and the
        // next is still heard (an error ending a listener would drop every later request without a word).
        val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        fun <T> listen(requests: kotlinx.coroutines.flow.Flow<T>, handle: (T) -> Unit) = bg.launch {
            requests.collect { r -> try { handle(r) } catch (e: Exception) { android.util.Log.e("Droid", "agent request", e) } }
        }
        listen(runner.pages) { (conv, page) -> presentPage(this, conv, page) }
        listen(runner.wakes) { Later.schedule(this, it) }
        listen(runner.notes) { (conv, spec) -> Notify.prepare(this, conv, spec) }
        listen(runner.says) { (_, text) -> main.post { say(text) } }
        listen(runner.looks) { l -> LookPage.handle(this, l) }
        bg.launch { Later.restore(this@DroidApp) }  // this process may be the phone starting again
        bg.launch { Updates.check(this@DroidApp) }
    }

    /**
     * Says [text] out loud for the agent (`droid say`), with Droid's own voice when it's set up, else the phone's
     * text-to-speech, so it can talk to anyone. Main thread.
     */
    private fun say(text: String) {
        if (speech.installed) return voice.speak("say", text)  // (its voice is freed once unused a while: see Speech.used)
        tts?.let { it.speak(text, TextToSpeech.QUEUE_ADD, null, "say"); return }
        tts = TextToSpeech(this) { ok -> if (ok == TextToSpeech.SUCCESS) tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "say") }
    }
    private val voice by lazy { Speaker(this, speech) }
    private var tts: TextToSpeech? = null

    // The speech models free themselves once unused a while (see Speech.used). Voice match's goes a while after the app
    // leaves the screen, so asking again soon doesn't wait for it to load.
    private val main = Handler(Looper.getMainLooper())
    private val releaseVoiceMatch = Runnable { voiceMatch.release() }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            main.removeCallbacks(releaseVoiceMatch)
            main.postDelayed(releaseVoiceMatch, KEEP_VOICE_MATCH_MS)
        }
    }

    private companion object {
        const val KEEP_VOICE_MATCH_MS = 5 * 60_000L
    }
}
