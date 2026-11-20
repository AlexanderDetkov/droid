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
 * Parakeet TDT 0.6B v2 for speech-to-text, Pocket TTS for the voice, Silero for voice activity.
 * The big two load on first use and are freed by [release]; each has its own lock so a run can't free them mid-use.
 */
class Speech(val dir: File) {
    private val asrLock = Any()
    private val ttsLock = Any()
    private var asr: OfflineRecognizer? = null
    private var tts: OfflineTts? = null
    private var voice: WaveData? = null

    val installed get() = FILES.all { dir.resolve(it).isFile }

    /** The thread every screen's [Voice] transcribes on: one for the app, as one utterance is heard at a time. */
    val transcriber: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "asr").apply { isDaemon = true } }

    private fun path(name: String) = dir.resolve(name).path

    fun vad() = Vad(config = VadModelConfig(
        sileroVadModelConfig = SileroVadModelConfig(model = path("silero_vad.onnx"), windowSize = VAD_WINDOW),
        sampleRate = MIC_RATE, numThreads = 1,
    ))

    /** Loads the recognizer ahead of [transcribe], so it is ready by the time the user stops talking. */
    fun warmUp() { synchronized(asrLock) { recognizer() } }

    /** Loads the voice ahead of [synthesize], e.g. while the agent works on a spoken request. */
    fun warmUpVoice() { synchronized(ttsLock) { synthesizer() } }

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

    /** Frees both models off the main thread, waiting for any use in progress. */
    fun release() {
        thread(isDaemon = true) {
            synchronized(asrLock) { asr?.release(); asr = null }
            synchronized(ttsLock) { tts?.release(); tts = null; voice = null }
        }
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
