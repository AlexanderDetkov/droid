package com.adetkov.droid

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesTest {
    @Test fun newer() {
        assertTrue(Updates.isNewer("v1.0", "0.9"))
        assertTrue(Updates.isNewer("0.9.1", "0.9"))
        assertTrue(Updates.isNewer("v0.10", "0.9"))  // numbers, not text
        assertTrue(Updates.isNewer("1.2.10", "1.2.9"))
    }

    @Test fun notNewer() {
        assertFalse(Updates.isNewer("v0.9", "0.9"))
        assertFalse(Updates.isNewer("0.9.0", "0.9"))
        assertFalse(Updates.isNewer("0.8.5", "0.9"))
        assertFalse(Updates.isNewer("v0.9-beta", "0.9"))
        assertFalse(Updates.isNewer("nightly", "0.9"))
        assertFalse(Updates.isNewer("", "0.9"))
    }
}
