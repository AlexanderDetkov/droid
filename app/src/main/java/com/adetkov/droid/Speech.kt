package com.adetkov.droid

import android.util.Log
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsPocketModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.k2fsa.sherpa.onnx.WaveData
import com.k2fsa.sherpa.onnx.WaveReader
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread

const val MIC_RATE = 16000

/**
 * The on-device speech models (sherpa-onnx), downloaded into [dir] by [Setup.downloadVoice]:
 * Parakeet Unified EN 0.6B for speech-to-text ([ASR]; a phone set up before it has Parakeet TDT 0.6B v2 until
 * [Setup.updateSpeechToText]), Smart Turn for whether the user is done ([Turn]), Pocket TTS for the voice, Silero for
 * voice activity. The big ones load on first use and are freed by [release]; speech-to-text and the voice each have
 * their own lock so a run can't free them mid-use.
 */
class Speech(val dir: File) {
    private val asrLock = Any()
    private val turnLock = Any()
    private val ttsLock = Any()
    private var asr: OfflineRecognizer? = null
    private var turn: Turn? = null
    private var tts: OfflineTts? = null
    private var voice: WaveData? = null

    val installed get() = FILES.all { dir.resolve(it).isFile }
    /** Whether speech-to-text is [ASR], not the model before it (which has no marker), and Smart Turn is there. */
    val current get() = dir.resolve(MARKER).let { it.isFile && it.readText().trim() == ASR } && dir.resolve(TURN).isFile

    /** The thread every screen's [Voice] transcribes on: one for the app, as one utterance is heard at a time. */
    val transcriber: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "asr").apply { isDaemon = true } }
    /** Smart Turn's thread, beside [transcriber]: a pause is judged while it's transcribed. */
    val turner: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "turn").apply { isDaemon = true } }

    private fun path(name: String) = dir.resolve(name).path

    fun vad() = Vad(config = VadModelConfig(
        sileroVadModelConfig = SileroVadModelConfig(model = path("silero_vad.onnx"), windowSize = VAD_WINDOW),
        sampleRate = MIC_RATE, numThreads = 1,
    ))

    /** Loads the recognizer (and Smart Turn) ahead of [transcribe], so it is ready by the time the user stops talking. */
    fun warmUp() { synchronized(asrLock) { recognizer() }; synchronized(turnLock) { turn() } }

    /** The probability that [samples] (ending a moment after the last word) are a finished turn, or null without Smart Turn. */
    fun finished(samples: FloatArray): Float? = synchronized(turnLock) {
        val t = turn() ?: return null
        val start = System.nanoTime()
        t.complete(samples).also { Log.i(TAG, "turn %.2f in %d ms".format(it, (System.nanoTime() - start) / 1_000_000)) }
    }

    /** Loads the voice ahead of [synthesize], e.g. while the agent works on a spoken request. */
    fun warmUpVoice() { synchronized(ttsLock) { synthesizer() } }

    /**
     * Transcribes [samples], by greedy search: beam search (which hotwords would need) is 2.5–4× slower on a Pixel 6a,
     * and each transcription is on the way to sending (see the README).
     */
    fun transcribe(samples: FloatArray): String = synchronized(asrLock) {
        val r = recognizer()
        val start = System.nanoTime()
        val stream = r.createStream()
        try {
            stream.acceptWaveform(samples, MIC_RATE)
            r.decode(stream)
            r.getResult(stream).text.trim()
        } finally {
            stream.release()
            Log.i(TAG, "transcribed %.1f s in %d ms".format(samples.size / MIC_RATE.toFloat(), (System.nanoTime() - start) / 1_000_000))
        }
    }

    /** Speaks [text], handing audio and its sample rate to [onAudio] as it is made; [onAudio] returns false to stop. */
    fun synthesize(text: String, onAudio: (FloatArray, Int) -> Boolean) = synchronized(ttsLock) {
        val t = synthesizer()
        val ref = voice!!
        val rate = t.sampleRate()
        // An explicit class, not a lambda: the JNI side looks up invoke([F)Ljava/lang/Integer;, which an
        // invokedynamic lambda doesn't have.
        val callback = object : (FloatArray) -> Int {
            override fun invoke(samples: FloatArray) = if (onAudio(samples, rate)) 1 else 0
        }
        t.generateWithConfigAndCallback(text, GenerationConfig(referenceAudio = ref.samples, referenceSampleRate = ref.sampleRate), callback)
        Unit
    }

    /** Frees both models off the main thread (or on this one, if [wait]), waiting for any use in progress. */
    fun release(wait: Boolean = false) {
        val free = {
            synchronized(asrLock) { asr?.release(); asr = null }
            synchronized(turnLock) { turn?.release(); turn = null }
            synchronized(ttsLock) { tts?.release(); tts = null; voice = null }
        }
        if (wait) free() else thread(isDaemon = true) { free() }
    }

    private fun recognizer(): OfflineRecognizer = asr ?: timed("speech recognizer") {
        OfflineRecognizer(config = OfflineRecognizerConfig(modelConfig = OfflineModelConfig(
            transducer = OfflineTransducerModelConfig(
                encoder = path("parakeet/encoder.int8.onnx"), decoder = path("parakeet/decoder.int8.onnx"),
                joiner = path("parakeet/joiner.int8.onnx"),
            ),
            tokens = path("parakeet/tokens.txt"), modelType = "nemo_transducer", numThreads = THREADS,
        )))
    }.also { asr = it }

    private fun turn(): Turn? = turn ?: dir.resolve(TURN).takeIf { it.isFile }?.let { timed("turn model") { Turn(it, THREADS) } }
        ?.also { turn = it }

    private fun synthesizer(): OfflineTts = tts ?: timed("voice") {
        voice = WaveReader.readWave(path("pocket/voice.wav"))
        OfflineTts(config = OfflineTtsConfig(model = OfflineTtsModelConfig(
            pocket = OfflineTtsPocketModelConfig(
                lmFlow = path("pocket/lm_flow.int8.onnx"), lmMain = path("pocket/lm_main.int8.onnx"),
                encoder = path("pocket/encoder.onnx"), decoder = path("pocket/decoder.int8.onnx"),
                textConditioner = path("pocket/text_conditioner.onnx"), vocabJson = path("pocket/vocab.json"),
                tokenScoresJson = path("pocket/token_scores.json"),
            ),
            numThreads = THREADS,
        )))
    }.also { tts = it }

    private fun <T> timed(what: String, load: () -> T): T {
        val start = System.nanoTime()
        return load().also { Log.i(TAG, "loaded $what in ${(System.nanoTime() - start) / 1_000_000} ms") }
    }

    companion object {
        /** The speech-to-text model: sherpa-onnx's name for it, its archive's and the marker's. */
        const val ASR = "sherpa-onnx-nemo-parakeet-unified-en-0.6b-int8-non-streaming"
        /** Names the model in `parakeet/`: [ASR], or none for Parakeet TDT 0.6B v2 (same files, other weights). */
        const val MARKER = "parakeet/model.txt"
        /** Pipecat's Smart Turn v3.2 (int8), pinned to a revision of its Hugging Face repo and checked against its SHA-256. */
        const val TURN = "smart-turn-v3.2-cpu.onnx"
        const val TURN_URL = "https://huggingface.co/pipecat-ai/smart-turn-v3/resolve/f766f81d3cfdf7737ac64aad813d91bbfd56bf93/$TURN"
        const val TURN_SHA256 = "2bb026316b14a660486a75b1733cd3fbab8c2fd0314dc9af7be49f8cca967e4f"
        const val VAD_WINDOW = 512  // 32 ms at 16 kHz
        private const val THREADS = 4
        private const val TAG = "Droid"
        private val FILES = listOf(
            "silero_vad.onnx",
            "parakeet/encoder.int8.onnx", "parakeet/decoder.int8.onnx", "parakeet/joiner.int8.onnx", "parakeet/tokens.txt",
            "pocket/lm_flow.int8.onnx", "pocket/lm_main.int8.onnx", "pocket/encoder.onnx", "pocket/decoder.int8.onnx",
            "pocket/text_conditioner.onnx", "pocket/vocab.json", "pocket/token_scores.json", "pocket/voice.wav",
        )
    }
}
