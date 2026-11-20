package com.adetkov.droid

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.k2fsa.sherpa.onnx.WaveReader
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * Speech-to-text on the phone: records until the user stops talking (Silero VAD), showing a live transcript,
 * then transcribes the whole utterance with Parakeet. Call from the main thread; callbacks arrive there.
 */
class Voice(private val speech: Speech) {
    /** True from [start] until a callback ends the session (it includes the final transcription). */
    var listening by mutableStateOf(false)
        private set
    /** Smoothed input loudness 0..1, for the listening animation. Read it in a draw or layer block, not in composition. */
    var level by mutableFloatStateOf(0f)
        private set

    private val main = Handler(Looper.getMainLooper())
    private val asr = speech.transcriber
    private var session: Session? = null

    /**
     * Listens on [mic] (the microphone if null). Exactly one of the callbacks ends the session, unless it is cancelled.
     * [onFinal] gets the transcript and the utterance's audio. [onPause] comes each time the user pauses, a quarter of a
     * second after their last word (the camera keeps its picture from then, see [Camera.mark]).
     */
    fun start(
        mic: Mic?, onPartial: (String) -> Unit, onFinal: (String, FloatArray) -> Unit, onNothing: () -> Unit, onError: (String) -> Unit,
        onPause: () -> Unit = {},
    ) {
        cancel()
        if (!speech.installed) { onError("Set up voice in Droid: Settings → Setup"); return }
        listening = true
        session = Session(mic, onPartial, onFinal, onNothing, onError, onPause).also { thread(name = "mic") { it.run() } }
    }

    /** Stops listening now and sends what was heard so far. */
    fun finish() { session?.finishing = true }

    /** Stops listening and drops the utterance; no callback follows. */
    fun cancel() {
        session?.cancelled = true
        session = null
        end()
    }

    private fun end() { listening = false; level = 0f }

    private inner class Session(
        private val source: Mic?, val onPartial: (String) -> Unit, val onFinal: (String, FloatArray) -> Unit,
        val onNothing: () -> Unit, val onError: (String) -> Unit, val onPause: () -> Unit,
    ) {
        @Volatile var finishing = false
        @Volatile var cancelled = false
        @Volatile private var decoding = false
        /** The latest live transcript and the sample index it covers audio up to. */
        @Volatile private var partial: Pair<Int, String>? = null

        /** Runs [f] on the main thread if this is still the live session. */
        private fun ui(f: () -> Unit) { main.post { if (session === this && !cancelled) f() } }
        /** Ends the session with [f]. */
        private fun close(f: () -> Unit) = ui { session = null; end(); f() }

        fun run() {
            val mic = source ?: AudioMic.open() ?: return close { onError("The microphone is busy") }
            asr.execute { speech.warmUp() }
            val vad = speech.vad()
            val audio = Samples()
            val frame = FloatArray(Speech.VAD_WINDOW)
            var speechFrames = 0      // frames the VAD called speech
            var firstSpeech = -1      // sample index where speech started
            var lastSpeech = 0        // sample index just past the last speech frame
            var lastPartial = 0
            var paused = false        // onPause was called for the current pause
            try {
                while (!cancelled && !finishing) {
                    if (mic.read(frame) < frame.size) break
                    audio.add(frame)
                    val rms = sqrt(frame.fold(0f) { a, x -> a + x * x } / frame.size)
                    val loud = (rms * 12f).coerceIn(0f, 1f)
                    main.post { if (session === this) level += (loud - level) * .5f }
                    if (vad.compute(frame) > SPEECH_PROB) {
                        speechFrames++
                        if (firstSpeech < 0) firstSpeech = audio.size - frame.size
                        lastSpeech = audio.size
                    }
                    val heard = speechFrames >= MIN_SPEECH_FRAMES
                    val silence = audio.size - lastSpeech
                    if (silence == 0) paused = false
                    else if (heard && !paused && silence >= PAUSE) { paused = true; ui(onPause) }
                    if (!heard && audio.size > NO_SPEECH_TIMEOUT) break
                    if (heard && silence > END_SILENCE) break
                    if (audio.size > MAX_LENGTH) break
                    // Live transcript: re-transcribe the utterance about once a second, and once soon after each pause,
                    // so that by the end of the silence the transcript usually covers everything said (no final pass).
                    val due = audio.size - lastPartial >= MIC_RATE || (silence >= PAUSE && lastPartial < lastSpeech)
                    if (heard && !decoding && due && audio.size < PARTIALS_UP_TO) {
                        val end = audio.size
                        lastPartial = end
                        decoding = true
                        val clip = audio.slice(firstSpeech - LEAD, end)
                        asr.execute {
                            val text = try { speech.transcribe(clip) } catch (e: Exception) { "" }
                            partial = end to text
                            decoding = false
                            if (text.isNotEmpty()) ui { onPartial(text) }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("Droid", "voice input failed", e)
                return close { onError("Voice input failed") }
            } finally {
                mic.close()
                vad.release()
            }
            if (cancelled) return
            if (speechFrames < MIN_SPEECH_FRAMES) return close(onNothing)
            // A live transcript that already covers everything said is the final one.
            val clip = audio.slice(firstSpeech - LEAD, lastSpeech + TAIL)
            asr.execute {
                val done = partial?.takeIf { it.first >= lastSpeech }?.second
                val text = done ?: try { speech.transcribe(clip) } catch (e: Exception) {
                    Log.w("Droid", "transcription failed", e)
                    return@execute close { onError("Couldn't transcribe that") }
                }
                close { if (text.isEmpty()) onNothing() else onFinal(text, clip) }
            }
        }
    }

    companion object {
        private const val SPEECH_PROB = 0.5f
        private const val MIN_SPEECH_FRAMES = 8              // ~0.25 s of speech before it counts
        private const val END_SILENCE = MIC_RATE * 6 / 5     // stop 1.2 s after the user stops talking
        private const val PAUSE = MIC_RATE / 4               // a pause this long gets a fresh transcript
        private const val NO_SPEECH_TIMEOUT = MIC_RATE * 7   // give up if nothing is said for 7 s
        private const val MAX_LENGTH = MIC_RATE * 120
        private const val PARTIALS_UP_TO = MIC_RATE * 30     // longer dictation just waits for the end
        private const val LEAD = MIC_RATE * 3 / 10           // audio kept before speech starts...
        private const val TAIL = MIC_RATE * 3 / 10           // ...and after it ends
        /** What an utterance's audio (onFinal's) holds besides the speech: always this many samples, [LEAD] and [TAIL]. */
        const val PADDING = LEAD + TAIL
    }
}

/**
 * This screen's [Voice] and [Speaker]. Listening is dropped when the screen is left (Android silences the mic in
 * the background), and both stop when the screen goes away.
 */
@Composable
fun rememberVoice(): Pair<Voice, Speaker> {
    val ctx = LocalContext.current
    val speech = (ctx.applicationContext as DroidApp).speech
    val voice = remember { Voice(speech) }
    val speaker = remember { Speaker(ctx, speech) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) voice.cancel() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); voice.cancel(); speaker.shutdown() }
    }
    return voice to speaker
}

/** Returns a runner for actions that need the mic: it runs them now, or once the user grants the permission. */
@Composable
fun rememberWithMic(denied: ((String) -> Unit)? = null) = rememberWith(Manifest.permission.RECORD_AUDIO, denied)

/**
 * Returns a runner for actions that need [permission]: it runs them now, or once the user grants it. A refusal is
 * told to [denied] (a toast without it): once the owner has said no for good, Android doesn't even ask.
 */
@Composable
fun rememberWith(permission: String, denied: ((String) -> Unit)? = null): (() -> Unit) -> Unit {
    val ctx = LocalContext.current
    val waiting = remember { arrayOfNulls<() -> Unit>(1) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = waiting[0]
        waiting[0] = null
        if (granted) action?.invoke()
        else deniedNotice(permission).let { denied?.invoke(it) ?: Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() }
    }
    return { action ->
        if (ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED) action()
        else { waiting[0] = action; ask.launch(permission) }
    }
}

/** What to tell the owner when Android won't let Droid use [permission]. */
private fun deniedNotice(permission: String): String {
    val what = when (permission) {
        Manifest.permission.RECORD_AUDIO -> "the microphone"
        Manifest.permission.CAMERA -> "the camera"
        else -> "this permission"
    }
    return "Droid needs $what for this. Allow it in Android's Settings → Apps → Droid → Permissions"
}

/** A growable float buffer. */
internal class Samples {
    private var data = FloatArray(MIC_RATE * 8)
    var size = 0
        private set

    fun add(xs: FloatArray) {
        if (size + xs.size > data.size) data = data.copyOf(maxOf(data.size * 2, size + xs.size))
        xs.copyInto(data, size)
        size += xs.size
    }

    /** Samples [from] until [to]; zeros where that is outside what was heard, so speech always has its full padding. */
    fun slice(from: Int, to: Int): FloatArray {
        val out = FloatArray(maxOf(0, to - from))
        val a = from.coerceIn(0, size)
        val b = to.coerceIn(0, size)
        if (b > a) data.copyInto(out, a - from, a, b)
        return out
    }
}

/** 16 kHz mono float audio. */
interface Mic {
    /** Blocks until [frame] is full; returns the number of samples read (fewer at the end). */
    fun read(frame: FloatArray): Int
    fun close()
}

private class AudioMic(private val rec: AudioRecord) : Mic {
    override fun read(frame: FloatArray): Int {
        var n = 0
        while (n < frame.size) {
            val r = rec.read(frame, n, frame.size - n, AudioRecord.READ_BLOCKING)
            if (r <= 0) break
            n += r
        }
        return n
    }

    override fun close() { rec.stop(); rec.release() }

    companion object {
        /** Null if the microphone can't be opened (another app or a call holds it). The caller checks the permission. */
        @SuppressLint("MissingPermission")
        fun open(): Mic? {
            val format = AudioFormat.ENCODING_PCM_FLOAT
            val size = maxOf(AudioRecord.getMinBufferSize(MIC_RATE, AudioFormat.CHANNEL_IN_MONO, format), MIC_RATE * 4)
            val rec = try {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, MIC_RATE, AudioFormat.CHANNEL_IN_MONO, format, size)
            } catch (e: Exception) { return null }
            if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return null }
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) { rec.release(); return null }
            return AudioMic(rec)
        }
    }
}

/** Plays a 16 kHz WAV file at real-time pace in place of the microphone (debug builds, for testing). */
class WavMic(path: String) : Mic {
    private val samples = WaveReader.readWave(path).also { require(it.sampleRate == MIC_RATE) { "need 16 kHz" } }.samples
    private var pos = 0

    override fun read(frame: FloatArray): Int {
        val n = minOf(frame.size, samples.size - pos)
        samples.copyInto(frame, 0, pos, pos + n)
        pos += n
        Thread.sleep(n * 1000L / MIC_RATE)
        return n
    }

    override fun close() {}
}
