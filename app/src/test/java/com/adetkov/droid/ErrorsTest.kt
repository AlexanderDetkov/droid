package com.adetkov.droid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ErrorsTest {
    private val la = ZoneId.of("America/Los_Angeles")

    @Test fun openCodesFreeLimitPointsToSetup() {
        val p = plainStatus("Error: Free usage limit reached. Free models work in OpenCode (https://opencode.ai); subscribe to OpenCode Go to keep going: https://opencode.ai/go", Engine.OPENCODE)!!
        assertTrue(p.setup)
        assertTrue(p.text, p.text.startsWith("OpenCode's free models are used up for now."))
    }

    @Test fun claudesSignInMessagesBecomeOne() {
        // As Claude Code 2.1.330 says them (strings in its binary)
        for (said in listOf("API Error: 401 Invalid API key · Please run /login", "Your session has expired. Please run /login to sign in again.",
                "OAuth token revoked · Please run /login", "Login expired · Please run /login", "Not logged in · Please run /login")) {
            assertEquals(said, Plain("Claude Code isn't signed in anymore. Sign in again in Setup.", setup = true), plainStatus("Error: $said", Engine.CLAUDE))
        }
        // Another agent's words about its own sign-in aren't Claude's
        assertNull(plainStatus("Error: Not logged in", Engine.CODEX))
    }

    @Test fun consoleCredit() {
        assertTrue(plainStatus("Error: Credit balance is too low", Engine.CLAUDE)!!.setup)
    }

    @Test fun usageLimitSaysWhenItResets() {
        val soon = Instant.now().plusSeconds(60).epochSecond
        val p = plainStatus("Error: Claude AI usage limit reached|$soon", Engine.CLAUDE, la)!!
        assertFalse(p.setup)
        assertTrue(p.text, p.text.startsWith("You've reached your Claude usage limit. It resets at "))
        val later = plainStatus("Error: Claude AI usage limit reached|${Instant.now().plusSeconds(3 * 86400).epochSecond}", Engine.CLAUDE, la)!!
        assertTrue(later.text, Regex("It resets on \\w{3} at ").containsMatchIn(later.text))
    }

    @Test fun wordsThatAlreadySayItStay() {
        assertNull(plainStatus("Error: You've hit your limit · resets 3pm", Engine.CLAUDE))
        assertNull(plainStatus("Failed", Engine.CLAUDE))
    }

    @Test fun helperTroubleKeepsItsWordsAndGetsSetup() {
        // AdbHelper's own messages, without root
        assertEquals(Plain("Pair Droid with Wireless debugging first", setup = true), plainStatus("Failed: Pair Droid with Wireless debugging first", Engine.CLAUDE))
        assertTrue(plainStatus("Failed: Droid's helper stopped right after it started. Check that USB debugging is on", Engine.OPENCODE)!!.setup)
        assertNull(plainStatus("Failed", Engine.OPENCODE))
    }
}
