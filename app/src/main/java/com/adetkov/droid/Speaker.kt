package com.adetkov.droid

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.Executors

/** Reads answers aloud with Pocket TTS on the phone, a sentence at a time so speech starts quickly. Main thread only. */
class Speaker(private val ctx: Context, private val speech: Speech) {
    /** Key of the text currently being spoken, if any. */
    var speakingKey by mutableStateOf<String?>(null)
        private set
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "tts").apply { isDaemon = true } }
    private val audio = ctx.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private var job: Job? = null

    /** Speaks [markdown] under [key], in place of anything being spoken (a newer answer of the same chat, say). */
    fun speak(key: String, markdown: String) {
        stop()
        if (!speech.installed) { Toast.makeText(ctx, "Voice isn't set up: Settings → Setup", Toast.LENGTH_SHORT).show(); return }
        val text = stripMarkdown(markdown, spoken = true)
        if (text.isBlank()) return
        speakingKey = key
        val j = Job(key, text)
        job = j
        worker.execute { j.run() }
    }

    /** A read-aloud button: stops [key] if it is being spoken, else speaks it. */
    fun toggle(key: String, markdown: String) = if (speakingKey == key) stop() else speak(key, markdown)

    /** Loads the voice in the background so a coming [speak] starts sooner. */
    fun prepare() { if (speech.installed) worker.execute { speech.warmUpVoice() } }

    fun stop() {
        job?.cancel()
        job = null
        speakingKey = null
    }

    fun shutdown() { stop(); worker.shutdown() }

    private inner class Job(private val key: String, private val text: String) {
        @Volatile private var cancelled = false
        @Volatile private var track: AudioTrack? = null

        fun cancel() {
            cancelled = true
            track?.let { try { it.pause(); it.flush() } catch (_: IllegalStateException) {} }
        }

        fun run() {
            val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build()
            audio.requestAudioFocus(focus)
            var written = 0L
            var rate = 0
            try {
                for (sentence in sentences(text)) {
                    if (cancelled) break
                    speech.synthesize(sentence) { samples, r ->
                        if (cancelled) return@synthesize false
                        rate = r
                        val t = track ?: open(r).also { track = it; if (!cancelled) it.play() }
                        t.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                        written += samples.size
                        !cancelled
                    }
                }
                // Let the buffered tail play out. (Not via stop(), which resets the head position this waits on.)
                val t = track
                if (t != null && !cancelled) {
                    val deadline = System.currentTimeMillis() + written * 1000 / rate + 1000
                    while (!cancelled && t.playbackHeadPosition < written && System.currentTimeMillis() < deadline) Thread.sleep(50)
                }
            } catch (e: Exception) {
                Log.w("Droid", "speech failed", e)
            } finally {
                Log.i("Droid", "spoke ${written} samples, played ${track?.playbackHeadPosition}, cancelled=$cancelled")
                track?.release()
                track = null
                audio.abandonAudioFocusRequest(focus)
                main.post { if (job === this) { job = null; speakingKey = null } }
            }
        }

        private fun open(rate: Int) = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(rate * 4 * 30)  // half a minute of audio, so synthesis runs ahead of playback
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .apply { startThresholdInFrames = rate / 5 }  // by default playback waits until that whole buffer is full
    }
}

/**
 * Splits text into sentences and lines (ending each in a stop), merging short ones so each synthesis call has some
 * context. A long first sentence is cut at its first comma, so speech starts sooner.
 */
internal fun sentences(text: String): List<String> {
    // A stop only ends a sentence before a capital or digit, so "6:45 a.m. on Friday" stays whole.
    val parts = text.split(Regex("(?<=[.!?…])\\s+(?=[A-Z0-9\"“(])|\\n+")).map { it.trim() }.filter { it.isNotEmpty() }
        .map { if (it.last() in ".!?…:;,") it else "$it." }.toMutableList()
    val comma = parts.firstOrNull()?.takeIf { it.length > 80 }?.indexOf(", ", 20) ?: -1
    if (comma > 0) parts[0].let { parts[0] = it.substring(0, comma + 1); parts.add(1, it.substring(comma + 2)) }
    val out = mutableListOf<String>()
    for (p in parts) if (out.size > 1 && out.last().length < 40) out[out.size - 1] = out.last() + " " + p else out += p
    return out
}
