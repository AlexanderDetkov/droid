package com.adetkov.droid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class TurnTest {
    /** The signal turn_features.txt was made from (by transformers' WhisperFeatureExtractor, as Pipecat's inference.py). */
    private fun signal(n: Int): FloatArray {
        var s = 12345L
        return FloatArray(n) { i ->
            s = (s * 1103515245L + 12345L) % 2147483648L
            val noise = s / 2147483648.0 - 0.5
            val t = i / 16000.0
            (0.3 * sin(2 * PI * 220 * t) + 0.2 * sin(2 * PI * 1375 * t + 0.5) * (i.toDouble() / n) + 0.05 * noise).toFloat()
        }
    }

    @Test fun featuresMatchWhisper() {
        val lines = javaClass.classLoader!!.getResource("turn_features.txt").readText().lines().filter { it.isNotBlank() }
        var x = FloatArray(0)
        var worst = 0f
        for (line in lines) {
            val w = line.split(" ")
            if (w[0] == "n") {
                x = turnFeatures(signal(w[1].toInt()))
                assertEquals(w[5].toFloat(), x.average().toFloat(), 2e-3f)
            } else {
                val frame = w[1].toInt()
                for (m in 0 until 80) worst = maxOf(worst, abs(x[m * 800 + frame] - w[2 + m].toFloat()))
            }
        }
        assertTrue("worst difference $worst", worst < 2e-3f)
    }

    @Test fun aTranscriptReadsFinishedOnlyAtASentenceEnd() {
        assertTrue(readsFinished("Set an alarm for seven."))
        assertTrue(readsFinished("What are you looking at?"))
        assertTrue(readsFinished("Text Mama I'll be late!"))
        assertFalse(readsFinished("Could you suggest a reading list for someone interested in"))
        assertFalse(readsFinished("Rinse your um mouth with."))
        assertFalse(readsFinished("I was hoping you could maybe..."))
        assertFalse(readsFinished("Book a table for two and."))
        assertFalse(readsFinished("Cancel operation"))
        assertFalse(readsFinished(""))
        assertTrue(readsFinished("Make new schedule for tomorrow meeting", stop = false))
        assertFalse(readsFinished("Remind me to", stop = false))
        assertFalse(readsFinished("Text Mama that um", stop = false))
    }
}
