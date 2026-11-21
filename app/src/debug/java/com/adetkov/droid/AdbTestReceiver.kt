package com.adetkov.droid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.adetkov.droid.adb.Adb
import com.adetkov.droid.adb.AdbKey
import com.adetkov.droid.adb.Discovery
import java.io.File
import kotlin.concurrent.thread

/**
 * Debug builds: drive the Wireless-debugging code from adb, results in logcat (tag DroidAdbTest):
 *   am broadcast -n com.adetkov.droid/.AdbTestReceiver -a pair --es code C [--ei port P]   (port found by mDNS if absent)
 *   ... -a shell --ei port P --es cmd '...' [--es fp F]       ... -a ensure [--es cmd 'id']
 *   ... -a send --es text '...' [--es conv ID]    a request, as if typed in that chat (a new chat without one)
 *   ... -a page --es conv ID --es page PAGE --es text '...' [--es data JSON]   a page's droid.send, as if tapped
 *   ... -a delete --es conv ID     deletes a chat, its files and its agent session, as the chat's menu does
 *   ... -a asr --es wav PATH [--ez fresh true]   transcribes a 16 kHz WAV (fresh: loads the recognizer anew, timed),
 *         and says whether Smart Turn and the transcript think it's finished
 *   ... -a update-speech     Setup's speech-to-text update, as its Update button
 *   ... -a listen --es wav PATH   the voice loop on a 16 kHz WAV, then silence: logs partials, the
 *         final transcript and how long after the WAV's end it came
 */
class AdbTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val key = AdbKey.load(File(context.filesDir, "adb"))
        val helper = AdbHelper(context.applicationContext)
        thread {
            try {
                when (intent.action) {
                    "delete" -> deleteChat(context, intent.getStringExtra("conv")!!)
                    "send" -> Log.i(TAG, "chat " + startTask(context, intent.getStringExtra("conv"), intent.getStringExtra("text").orEmpty()))
                    "page" -> pageSend(context, intent.getStringExtra("conv")!!, intent.getStringExtra("page"),
                        intent.getStringExtra("text").orEmpty(), intent.getStringExtra("data"))
                    "asr" -> {
                        val speech = (context.applicationContext as DroidApp).speech
                        if (intent.getBooleanExtra("fresh", false)) speech.release(wait = true)
                        speech.warmUp()
                        val samples = com.k2fsa.sherpa.onnx.WaveReader.readWave(intent.getStringExtra("wav")!!).samples
                        val text = speech.transcribe(samples)
                        Log.i(TAG, "heard: $text")
                        Log.i(TAG, "finished: ${speech.finished(samples)}, reads finished: ${readsFinished(text)}")
                    }
                    "listen" -> {
                        val app = context.applicationContext as DroidApp
                        val wav = WavMic(intent.getStringExtra("wav")!!)
                        var ended = 0L
                        val mic = object : Mic {  // the WAV, then silence for up to 10 s, as after the last word
                            var silent = 0
                            override fun read(frame: FloatArray): Int {
                                val n = wav.read(frame)
                                if (n == frame.size) return n
                                if (ended == 0L) { ended = System.nanoTime(); Log.i(TAG, "wav ended") }
                                frame.fill(0f, n)
                                Thread.sleep((frame.size - n) * 1000L / MIC_RATE)
                                silent += frame.size
                                return if (silent > MIC_RATE * 10) 0 else frame.size
                            }
                            override fun close() {}
                        }
                        val done = java.util.concurrent.CountDownLatch(1)
                        fun after() = "%d ms after the WAV".format((System.nanoTime() - ended) / 1_000_000)
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            Voice(app.speech, context.getSystemService(android.media.AudioManager::class.java)).start(mic,
                                onPartial = { Log.i(TAG, "partial: $it") },
                                onFinal = { t, _ -> Log.i(TAG, "final (${after()}): $t"); done.countDown() },
                                onNothing = { Log.i(TAG, "nothing (${after()})"); done.countDown() },
                                onError = { Log.i(TAG, "error: $it"); done.countDown() })
                        }
                        done.await(40, java.util.concurrent.TimeUnit.SECONDS)
                    }
                    "update-speech" -> (context.applicationContext as DroidApp).setup.updateSpeechToText()
                    "pair" -> {
                        val port = intent.getIntExtra("port", 0).takeIf { it > 0 } ?: Discovery.find(context, Discovery.PAIRING, 20_000)
                        Log.i(TAG, "pairing port=$port")
                        helper.pair(port!!, intent.getStringExtra("code").orEmpty())
                        Log.i(TAG, "paired")
                    }
                    "shell" -> Log.i(TAG, "output: " + Adb.shell(key, intent.getIntExtra("port", 0), intent.getStringExtra("cmd").orEmpty(), intent.getStringExtra("fp")).output)
                    "ensure" -> {
                        val c = helper.ensure()
                        val p = c.exec(intent.getStringExtra("cmd") ?: "id")
                        p.outputStream.close()
                        Log.i(TAG, "bridge ${c.port}: " + p.inputStream.bufferedReader().readText() + " exit=" + p.waitFor())
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object { const val TAG = "DroidAdbTest" }
}
