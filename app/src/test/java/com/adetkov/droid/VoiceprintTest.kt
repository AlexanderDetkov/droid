package com.adetkov.droid

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class VoiceprintTest {
    @Test fun normalizes() {
        assertArrayEquals(floatArrayOf(.6f, .8f), Voiceprint.normalize(floatArrayOf(3f, 4f)), 1e-6f)
        assertThrows(IllegalArgumentException::class.java) { Voiceprint.normalize(floatArrayOf(0f, 0f)) }
    }

    @Test fun averagesDirectionsNotLengths() {
        // A loud and a quiet recording count the same: each is normalized before averaging.
        val avg = Voiceprint.average(listOf(floatArrayOf(10f, 0f), floatArrayOf(0f, .1f)))
        assertArrayEquals(floatArrayOf(.70710677f, .70710677f), avg, 1e-6f)
    }

    @Test fun similarityIsCosineOfNormalized() {
        val a = Voiceprint.normalize(floatArrayOf(1f, 0f))
        assertEquals(1f, Voiceprint.similarity(a, a), 1e-6f)
        assertEquals(0f, Voiceprint.similarity(a, Voiceprint.normalize(floatArrayOf(0f, 2f))), 1e-6f)
        assertEquals(-1f, Voiceprint.similarity(a, Voiceprint.normalize(floatArrayOf(-3f, 0f))), 1e-6f)
        assertThrows(IllegalArgumentException::class.java) { Voiceprint.similarity(a, floatArrayOf(1f, 0f, 0f)) }
    }

    @Test fun storesWithItsModel() {
        val v = floatArrayOf(.1f, -.25f, 3.5e-4f)
        val text = Voiceprint.encode("titanet.onnx", v)
        assertArrayEquals(v, Voiceprint.decode("titanet.onnx", text), 0f)
        assertNull("a voiceprint from another model is never compared", Voiceprint.decode("resnet.onnx", text))
        assertNull(Voiceprint.decode("titanet.onnx", "titanet.onnx\n"))
    }
}
