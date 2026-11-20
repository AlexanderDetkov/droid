package com.adetkov.droid

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.IOException

/** The agent's `droid` command (assets/setup/bin/droid), through its Python tests. */
class DroidCommandTest {
    @Test fun droidCommandTests() {
        val run = try {
            val p = ProcessBuilder("python3", "-I", "src/test/python/test_droid.py").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor() to out
        } catch (_: IOException) { null }
        assumeTrue("python3 not installed", run != null)
        val (code, out) = run!!
        assertEquals(out, 0, code)
    }
}
