package com.adetkov.droid

import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Whether the user has finished talking, from how they sound: Pipecat's Smart Turn v3.2 (BSD-2-Clause), a Whisper Tiny
 * encoder with a classifier, on the last 8 s of what they said. It runs on sherpa-onnx's onnxruntime through
 * libturn.so (src/main/cpp). [Speech] keeps one and uses it on its transcribing thread.
 */
class Turn(model: File, threads: Int) {
    private val handle = open(model.path, threads)

    /** The probability that [audio] (16 kHz, ending a moment after the last word) is a finished turn. */
    fun complete(audio: FloatArray): Float = run(handle, turnFeatures(audio))

    fun release() = close(handle)

    companion object {
        init {
            System.loadLibrary("onnxruntime")  // sherpa-onnx's, which libturn.so finds
            System.loadLibrary("turn")
        }
        @JvmStatic private external fun open(path: String, threads: Int): Long
        @JvmStatic private external fun run(handle: Long, features: FloatArray): Float
        @JvmStatic private external fun close(handle: Long)
    }
}

/**
 * Whether [text], a transcript, reads as finished: it doesn't trail off or end on a word people go on from ("and",
 * "the", "um", "to", ...) unless it asks something, and, if [stop], it ends a sentence (. ? !). Parakeet ends most
 * clips with a period, fragments included, so this only vetoes [Turn]; on its own it would cut people off.
 */
internal fun readsFinished(text: String, stop: Boolean = true): Boolean {
    val t = text.trim().trimEnd(' ', '"', '\'', '”', '’', ')')
    if (t.endsWith("...") || t.endsWith("…")) return false  // trailing off
    val end = t.lastOrNull() ?: return false
    if (stop && end !in ".?!") return false
    val last = Regex("[\\w'%$]+").findAll(t.lowercase()).lastOrNull()?.value ?: return false
    return end == '?' || last !in TRAILING
}

/** Words after which an English speaker is almost never done (from the turn evaluation, see docs/how-it-works.md). */
private val TRAILING = """and but so or because cause nor then if when while although though unless until since whereas
um uh er erm ah eh hmm mm uhm like well also plus
the a an my your his her its our their some any each every
to of in on at for with from by about into onto over under between through than as via
is are was were am be been being have has had do does did will would can could should shall may might must
i we you he she they it's i'm i'd i'll we're you're there's what's which who whom whose
just very""".split(Regex("\\s+")).toSet()

private const val N_FFT = 400
private const val HOP = 160
private const val BINS = N_FFT / 2 + 1
private const val MELS = 80
/** Smart Turn's window: 8 s. */
private const val WINDOW = 8 * MIC_RATE
private const val FRAMES = WINDOW / HOP

/**
 * Smart Turn's input for [audio]: Whisper's log-mel features (80 × 800, row by row) of its last 8 s, padded in front
 * with silence, exactly as Pipecat's inference.py makes them (transformers' WhisperFeatureExtractor, normalized).
 */
internal fun turnFeatures(audio: FloatArray): FloatArray {
    // the last 8 s, zeros in front; then zero mean and unit variance over all of it, the padding included
    val x = FloatArray(WINDOW)
    val n = min(audio.size, WINDOW)
    audio.copyInto(x, WINDOW - n, audio.size - n, audio.size)
    var mean = 0.0
    for (v in x) mean += v
    mean /= WINDOW
    var variance = 0.0
    for (v in x) variance += (v - mean) * (v - mean)
    variance /= WINDOW
    val scale = 1 / sqrt(variance + 1e-7)
    // centered frames: 200 samples of reflection at each end
    val half = N_FFT / 2
    val padded = FloatArray(WINDOW + N_FFT)
    for (i in padded.indices) {
        val j = i - half
        val k = when { j < 0 -> -j; j >= WINDOW -> 2 * (WINDOW - 1) - j; else -> j }
        padded[i] = ((x[k] - mean) * scale).toFloat()
    }
    val out = FloatArray(MELS * FRAMES)
    val frame = FloatArray(N_FFT)
    val power = FloatArray(BINS)
    // Frames wholly in the padding are all alike: worked out once.
    val firstSound = WINDOW - n + half  // the first padded index that isn't padding
    var padding: FloatArray? = null
    var top = Float.NEGATIVE_INFINITY
    for (f in 0 until FRAMES) {
        val start = f * HOP
        val mel = if (start + N_FFT <= firstSound && padding != null) padding else {
            for (i in 0 until N_FFT) frame[i] = padded[start + i] * Dft.window[i]
            for (k in 0 until BINS) {
                var re = 0f
                var im = 0f
                val c = Dft.cos[k]
                val s = Dft.sin[k]
                for (i in 0 until N_FFT) { re += frame[i] * c[i]; im += frame[i] * s[i] }
                power[k] = re * re + im * im
            }
            FloatArray(MELS) { m ->
                val w = Dft.mel[m]
                var e = 0f
                for (k in 0 until BINS) e += w[k] * power[k]
                log10(max(e, 1e-10f))
            }.also { if (start + N_FFT <= firstSound) padding = it }
        }
        for (m in 0 until MELS) { out[m * FRAMES + f] = mel[m]; top = max(top, mel[m]) }
    }
    for (i in out.indices) out[i] = (max(out[i], top - 8f) + 4f) / 4f
    return out
}

/** Tables for [turnFeatures]: the periodic Hann window, the DFT's, and Whisper's mel filters (Slaney). */
private object Dft {
    val window = FloatArray(N_FFT) { (0.5 - 0.5 * cos(2 * PI * it / N_FFT)).toFloat() }
    val cos = Array(BINS) { k -> FloatArray(N_FFT) { i -> cos(2 * PI * ((k.toLong() * i) % N_FFT) / N_FFT).toFloat() } }
    val sin = Array(BINS) { k -> FloatArray(N_FFT) { i -> sin(2 * PI * ((k.toLong() * i) % N_FFT) / N_FFT).toFloat() } }
    val mel: Array<FloatArray> = run {
        fun toMel(hz: Double) = if (hz < 1000) 3 * hz / 200 else 15 + ln(hz / 1000) * 27 / ln(6.4)
        fun toHz(mel: Double) = if (mel < 15) 200 * mel / 3 else 1000 * exp((mel - 15) * ln(6.4) / 27)
        val top = toMel(MIC_RATE / 2.0)
        val edges = DoubleArray(MELS + 2) { toHz(top * it / (MELS + 1)) }
        Array(MELS) { m ->
            val norm = 2 / (edges[m + 2] - edges[m])
            FloatArray(BINS) { k ->
                val hz = k * (MIC_RATE / 2.0) / (BINS - 1)
                val down = (hz - edges[m]) / (edges[m + 1] - edges[m])
                val up = (edges[m + 2] - hz) / (edges[m + 2] - edges[m + 1])
                (max(0.0, min(down, up)) * norm).toFloat()
            }
        }
    }
}
