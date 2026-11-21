package com.adetkov.droid

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.IOException

/** The agent's `android` and `shot` commands (assets/setup/bin): `android`'s display default and `shot --text`, through their Python tests. */
class AndroidCommandTest {
    @Test fun androidCommandTests() = python("src/test/python/test_android.py")

    @Test fun shotTextTests() = python("src/test/python/test_shot.py")

    private fun python(test: String) {
        val run = try {
            val p = ProcessBuilder("python3", "-I", test).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor() to out
        } catch (_: IOException) { null }
        assumeTrue("python3 not installed", run != null)
        val (code, out) = run!!
        assertEquals(out, 0, code)
    }
}
