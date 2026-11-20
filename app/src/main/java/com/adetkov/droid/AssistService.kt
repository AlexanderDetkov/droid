package com.adetkov.droid

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.getAndUpdate
import kotlin.concurrent.thread

/**
 * Droid as Android's digital assistant. Long-press power shows a session, which opens the panel ([AssistActivity]).
 * Over an app, Android also hands the session a screenshot of that app, taken before the panel appears (Android's
 * "Use screenshot" assistant setting); it goes to the panel as an attachment through [DroidApp.assistShot].
 */
class AssistService : VoiceInteractionService()

class AssistSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AssistSession(this)
}

/** Has no window of its own: it opens the panel and passes on the screenshot. */
private class AssistSession(ctx: Context) : VoiceInteractionSession(ctx) {
    private val app = ctx.applicationContext as DroidApp
    private val main = Handler(Looper.getMainLooper())
    private val giveUp = Runnable { hide() }
    private var wantShot = false
    private var shown = false  // from onShow until onHide
    private var early: Bitmap? = null  // a screenshot (or Android's null) that came before onShow
    private var earlyAt = 0L  // when it came; 0: none

    override fun onCreate() {
        super.onCreate()
        setUiEnabled(false)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        shown = true
        main.removeCallbacks(giveUp)
        app.assistShot.getAndUpdate { null }?.let(::discard)  // one the panel never picked up
        // Over Droid itself (the panel already up, or the app open) the screenshot would only show Droid, and over the
        // home screen there's no app to see.
        wantShot = showFlags and SHOW_WITH_SCREENSHOT != 0 && !app.resumed && !overHome(args)
        startAssistantActivity(Intent(context, AssistActivity::class.java))
        val shot = early.also { early = null }
        val came = earlyAt.also { earlyAt = 0L }
        // One left over from a show that never came isn't of this screen.
        if (came != 0L && SystemClock.uptimeMillis() - came < 2_000) return takeShot(shot)
        // Hiding cancels a screenshot still on its way, so wait for it (or give up on it).
        if (wantShot) main.postDelayed(giveUp, 5_000) else hide()
    }

    /** Whether only the home screen is showing, from the apps Android lists as on screen (none listed: not known). */
    @android.annotation.SuppressLint("InlinedApi")  // Android 14 doesn't send the list: not known
    private fun overHome(args: Bundle?): Boolean {
        val onScreen = args?.getParcelableArrayList(KEY_FOREGROUND_ACTIVITIES, ComponentName::class.java)
        if (onScreen.isNullOrEmpty()) return false
        val home = context.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            ?.activityInfo?.packageName ?: return false
        return onScreen.all { it.packageName == home }
    }

    override fun onHide() {
        super.onHide()
        shown = false
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        // With "Use screenshot" off, Android sends its null screenshot before the show itself. Hiding then would drop
        // the show still queued (a hide removes it), and the panel would never open: keep it for onShow instead.
        if (!shown) { early = screenshot; earlyAt = SystemClock.uptimeMillis(); return }
        main.removeCallbacks(giveUp)
        takeShot(screenshot)
    }

    private fun takeShot(screenshot: Bitmap?) {
        // null: the app doesn't allow it, the lock screen, or "Use screenshot" is off
        if (!wantShot || screenshot == null) return hide()
        wantShot = false
        thread(name = "screenshot") {
            val a = saveScreenshot(context, screenshot)
            main.post { app.assistShot.value = a; hide() }
        }
    }
}

/**
 * Android wants an assistant to name a speech recognizer too. Droid recognizes speech itself, in the panel, so this
 * one only declines; it isn't offered to other apps (no intent filter), and the phone's voice input stays as it was.
 */
class AssistRecognizer : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        try { listener?.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY) } catch (_: Exception) {}
    }
    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}
