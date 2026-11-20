package com.adetkov.droid

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTest {
    @Test fun aSliceIsPaddedWithSilencePastWhatWasHeard() {
        val s = Samples().apply { add(floatArrayOf(1f, 2f, 3f)) }
        assertArrayEquals(floatArrayOf(0f, 0f, 1f, 2f, 3f, 0f), s.slice(-2, 4), 0f)
        assertArrayEquals(floatArrayOf(2f), s.slice(1, 2), 0f)
        assertArrayEquals(floatArrayOf(0f, 0f), s.slice(5, 7), 0f)
    }

    @Test fun voiceMatchCountsTheSpeechNotThePadding() {
        // 1.5 s of speech with Voice's padding around it is enough; 0.9 s with it (1.5 s in all) is not.
        assertTrue(VoiceMatch.enoughSpeech(FloatArray(MIC_RATE * 3 / 2 + Voice.PADDING)))
        assertFalse(VoiceMatch.enoughSpeech(FloatArray(MIC_RATE * 3 / 2)))
    }
}
