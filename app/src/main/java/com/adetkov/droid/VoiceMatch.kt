package com.adetkov.droid

import android.app.KeyguardManager
import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.sqrt

/**
 * Voice match: a request spoken on the lock screen goes without the fingerprint when it sounds like the owner.
 * A speaker-embedding model (NVIDIA TitaNet small, via sherpa-onnx) turns speech into a vector; the voiceprint is the
 * average of the enrollment sentences', and speech matches when its cosine similarity to the voiceprint is at least
 * [THRESHOLD]. It checks how a voice sounds, not that the owner is speaking: a recording or a clone can pass.
 * After [MAX_MISSES] misses in a row it stays off until the phone is next unlocked.
 */
class VoiceMatch(ctx: Context) {
    /** The model, kept apart from [Speech.dir], which a voice redownload replaces wholesale. */
    val model: File = ctx.filesDir.resolve("speaker/$MODEL")
    private val printFile = ctx.filesDir.resolve("voiceprint.txt")
    private val sp = ctx.getSharedPreferences("voice_match", 0)
    private val keyguard = ctx.getSystemService(KeyguardManager::class.java)
    private val lock = Any()
    private var extractor: SpeakerEmbeddingExtractor? = null
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "voice-match").apply { isDaemon = true } }

    /** Whether matching requests on the lock screen skip the unlock. */
    val enabled = MutableStateFlow(sp.getBoolean("enabled", false))
    /** The owner's voiceprint, or null before enrollment. */
    val print = MutableStateFlow(load())
    /** Misses in a row on the lock screen since the last unlock. */
    val misses = MutableStateFlow(sp.getInt("misses", 0))

    val installed get() = model.isFile
    /** Whether a request heard on the lock screen now may skip the unlock if it matches. */
    val available get() = enabled.value && print.value != null && installed && misses.value < MAX_MISSES

    fun setEnabled(v: Boolean) { enabled.value = v; sp.edit { putBoolean("enabled", v) } }

    /**
     * Misses start over if the phone is unlocked now. It asks the keyguard rather than trusting the caller: Android
     * briefly resumes the app's other activities behind the lock screen, e.g. when the assistant panel closes.
     */
    fun resetIfUnlocked() { if (misses.value != 0 && !keyguard.isKeyguardLocked) setMisses(0) }

    private fun setMisses(n: Int) { misses.value = n; sp.edit { putInt("misses", n) } }

    /** Loads the model in the background, ahead of [verify]. */
    fun warmUp() { if (installed) worker.execute { try { synchronized(lock) { extractor() } } catch (e: Exception) { Log.w(TAG, "voice match load failed", e) } } }

    /**
     * Whether [samples] (16 kHz, a request heard on the lock screen) may go without unlocking. A miss counts towards
     * [MAX_MISSES]; a match starts the count over. Blocks for a fraction of a second: call it off the main thread.
     */
    fun verify(samples: FloatArray): Verdict {
        val p = print.value
        if (!available || p == null) return Verdict.UNAVAILABLE
        if (!enoughSpeech(samples)) return Verdict.TOO_SHORT
        val s = try { Voiceprint.similarity(embed(samples), p) } catch (e: Exception) {
            Log.w(TAG, "voice match failed", e)
            return Verdict.UNAVAILABLE
        }
        val ok = s >= THRESHOLD
        Log.i(TAG, "voice match %.3f (needs %.2f): %s".format(s, THRESHOLD, if (ok) "match" else "miss ${misses.value + 1}"))
        setMisses(if (ok) 0 else misses.value + 1)
        return if (ok) Verdict.MATCH else Verdict.NO_MATCH
    }

    /** How much [samples] sound like the voiceprint, -1..1 (for trying it out; nothing is counted). Null if too short. */
    fun score(samples: FloatArray): Float? {
        val p = print.value ?: return null
        if (!enoughSpeech(samples)) return null
        return Voiceprint.similarity(embed(samples), p)
    }

    /**
     * A voiceprint from enrollment recordings, and how well the least typical recording matches the others (below
     * [THRESHOLD] means one sounded off, e.g. noise). Nothing is kept until [keep]. Slow: call it off the main thread.
     */
    fun voiceprint(clips: List<FloatArray>): Pair<FloatArray, Float> {
        require(clips.size >= 2)
        val es = clips.map(::embed)
        return Voiceprint.average(es) to es.indices.minOf { i -> Voiceprint.similarity(es[i], Voiceprint.average(es.filterIndexed { j, _ -> j != i })) }
    }

    /** Keeps [p] as the owner's voiceprint. A first one turns voice match on; a new one leaves it as the owner set it. */
    fun keep(p: FloatArray) {
        val first = print.value == null
        printFile.writeText(Voiceprint.encode(MODEL, p))
        print.value = p
        setMisses(0)
        if (first) setEnabled(true)
    }

    /** [voiceprint] and [keep] in one, off the main thread (debug builds' test enrollment). */
    fun enroll(clips: List<FloatArray>): Float = voiceprint(clips).also { keep(it.first) }.second

    /** Deletes the voiceprint and turns voice match off. */
    fun forget() {
        printFile.delete()
        print.value = null
        setEnabled(false)
        setMisses(0)
    }

    /** Frees the model, waiting for any use in progress (off the main thread). */
    fun release() { worker.execute { synchronized(lock) { extractor?.release(); extractor = null } } }

    private fun embed(samples: FloatArray): FloatArray = synchronized(lock) {
        val x = extractor()
        val start = System.nanoTime()
        val stream = x.createStream()
        try {
            stream.acceptWaveform(samples, MIC_RATE)
            stream.inputFinished()
            Voiceprint.normalize(x.compute(stream))
        } finally {
            stream.release()
            Log.i(TAG, "voice embedding of %.1f s in %d ms".format(samples.size / MIC_RATE.toFloat(), (System.nanoTime() - start) / 1_000_000))
        }
    }

    private fun extractor(): SpeakerEmbeddingExtractor = extractor ?: run {
        val start = System.nanoTime()
        SpeakerEmbeddingExtractor(config = SpeakerEmbeddingExtractorConfig(model = model.path, numThreads = 2)).also {
            Log.i(TAG, "loaded voice match in ${(System.nanoTime() - start) / 1_000_000} ms")
        }
    }.also { extractor = it }

    private fun load(): FloatArray? = try {
        printFile.takeIf { it.isFile }?.readText()?.let { Voiceprint.decode(MODEL, it) }
    } catch (e: Exception) { null }

    enum class Verdict { MATCH, NO_MATCH, TOO_SHORT, UNAVAILABLE }

    companion object {
        const val MODEL = "nemo_en_titanet_small.onnx"
        const val MODEL_SHA256 = "ad4a1802485d8b34c722d2a9d04249662f2ece5d28a7a039063ca22f515a789e"
        const val MODEL_MB = 38
        /** Cosine similarity a voice needs to count as the owner's. */
        const val THRESHOLD = 0.5f
        const val MAX_MISSES = 3
        /** Shorter speech says too little about the voice; it goes to the fingerprint without counting a miss. */
        const val MIN_SPEECH = MIC_RATE * 3 / 2
        private const val TAG = "Droid"

        /** Whether an utterance from [Voice] holds [MIN_SPEECH] of speech: the padding around it doesn't count. */
        fun enoughSpeech(samples: FloatArray) = samples.size - Voice.PADDING >= MIN_SPEECH
    }
}

/** Voiceprint arithmetic and storage, kept free of Android for unit tests. */
object Voiceprint {
    fun normalize(v: FloatArray): FloatArray {
        val n = sqrt(v.fold(0.0) { a, x -> a + x * x }).toFloat()
        require(n > 0f) { "zero embedding" }
        return FloatArray(v.size) { v[it] / n }
    }

    /** The normalized mean of normalized vectors. */
    fun average(vs: List<FloatArray>): FloatArray {
        val sum = FloatArray(vs[0].size)
        vs.forEach { v -> require(v.size == sum.size); normalize(v).forEachIndexed { i, x -> sum[i] += x } }
        return normalize(sum)
    }

    /** Cosine similarity of two normalized vectors. */
    fun similarity(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "voiceprint is from another model" }
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    /** The model's name on the first line, then the numbers: a voiceprint from another model is never compared. */
    fun encode(model: String, v: FloatArray) = model + "\n" + v.joinToString(",") + "\n"

    fun decode(model: String, text: String): FloatArray? {
        val lines = text.lines()
        if (lines.firstOrNull() != model) return null
        val xs = lines.getOrNull(1)?.takeIf { it.isNotBlank() }?.split(',')?.map { it.toFloatOrNull() ?: return null } ?: return null
        return xs.toFloatArray()
    }
}
