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
        // A fixed now (it once ran a minute before midnight in LA, and "a minute from now" was tomorrow), and one just
        // before midnight: a reset after it is another day's.
        for (now in listOf(Instant.parse("2026-11-25T20:00:00Z"), Instant.parse("2026-11-26T07:59:30Z"))) {
            val soon = now.plusSeconds(20).epochSecond
            val p = plainStatus("Error: Claude AI usage limit reached|$soon", Engine.CLAUDE, la, now)!!
            assertFalse(p.setup)
            assertTrue(p.text, p.text.startsWith("You've reached your Claude usage limit. It resets at "))
            val later = plainStatus("Error: Claude AI usage limit reached|${now.plusSeconds(3 * 86400).epochSecond}", Engine.CLAUDE, la, now)!!
            assertTrue(later.text, Regex("It resets on \\w{3} at ").containsMatchIn(later.text))
        }
        val pastMidnight = Instant.parse("2026-11-26T07:59:30Z")
        val p = plainStatus("Error: Claude AI usage limit reached|${pastMidnight.plusSeconds(60).epochSecond}", Engine.CLAUDE, la, pastMidnight)!!
        assertTrue(p.text, Regex("It resets on \\w{3} at ").containsMatchIn(p.text))  // Thursday: past midnight in LA
    }

    @Test fun wordsThatAlreadySayItStay() {
        assertNull(plainStatus("Error: You've hit your limit · resets 3pm", Engine.CLAUDE))
        assertNull(plainStatus("Failed", Engine.CLAUDE))
    }

    @Test fun helperTroubleKeepsItsWordsAndGetsSetup() {
        // AdbHelper's own messages, without root
        // (helper: once it runs again, the turn is worth trying again)
        assertEquals(Plain("Pair Droid with Wireless debugging first", setup = true, helper = true), plainStatus("Failed: Pair Droid with Wireless debugging first", Engine.CLAUDE))
        assertTrue(plainStatus("Failed: Droid's helper stopped right after it started. Check that USB debugging is on", Engine.OPENCODE)!!.setup)
        assertNull(plainStatus("Failed", Engine.OPENCODE))
    }

    /** A task whose hidden display didn't start sends the owner to Setup, which checks it again and shows why. */
    @Test fun aDisplayThatDidNotStartPointsToSetup() {
        for (said in listOf("The hidden display did not start.", "The hidden display did not start: java.lang.SecurityException: Requires ADD_TRUSTED_DISPLAY.")) {
            val p = plainStatus("Error: $said", Engine.CODEX)!!
            assertTrue(p.setup)
            assertFalse(p.helper)
        }
    }

    /** An install the network failed says so in plain words, not its exit code; any other failure keeps its own words. */
    @Test fun aDownloadThatFailedSaysCheckTheInternet() {
        assertEquals("I couldn't download what I need. Check your internet connection, then try again.",
            installFailed("Could not install OpenCode (exit 56).", 56, "").message!!.lineSequence().first())
        assertEquals("I couldn't download what I need. Check your internet connection, then try again.",
            installFailed("Setup failed (exit 1).", 1, "curl: (56) Recv failure: Connection reset by peer\nDownload failed").message!!.lineSequence().first())
        assertEquals("Setup failed (exit 1).", installFailed("Setup failed (exit 1).", 1, "apk: no space left").message!!.lineSequence().first())
    }
}
