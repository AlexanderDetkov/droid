package com.adetkov.droid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsTest {
    private val signedIn = SetupState(
        checked = true, access = Access.ADB, paired = true, helper = true, installed = true, linux = true, signedIn = true,
        email = "someone@example.com", plan = "max", others = mapOf(Engine.OPENCODE to EngineStatus(true, true, "Installed, 2 provider keys")),
    )

    @Test fun nothingPrivateGoesIn() {
        val report = diagnosticsReport("1.0", emptyList(), setupFacts(signedIn))
        assertFalse(report, "someone@example.com" in report)
        assertFalse(report, "example.com" in report)
        assertTrue(report, "Claude: signed in, max" in report)
    }

    @Test fun saysHowFarSetupGot() {
        val facts = setupFacts(signedIn).toMap()
        assertEquals("Wireless debugging, helper running", facts["Access"])
        assertEquals("installed", facts["Linux"])
        assertEquals("not installed", facts["Codex"])
        assertEquals("ready", facts["OpenCode"])
        assertEquals("Claude Code", facts["New chats"])
        assertEquals("allowed", facts["Input"])
        assertNull(facts["Last error"])

        val stuck = setupFacts(SetupState(checked = true, access = Access.ADB, paired = true, helperError = "No Wi-Fi", error = "Install failed\nexit 1")).toMap()
        assertEquals("Wireless debugging, paired, helper not running (No Wi-Fi)", stuck["Access"])
        assertEquals("no agent ready (picked: Claude Code)", stuck["New chats"])
        assertNull(stuck["Input"])  // never reached Android, so never tried
        assertEquals("Install failed\nexit 1", stuck["Last error"])
    }

    @Test fun anAgentOtherThanThePickedOneTakesNewChats() {
        val facts = setupFacts(signedIn.copy(signedIn = false)).toMap()
        assertEquals("OpenCode (picked: Claude Code)", facts["New chats"])
    }

    @Test fun oneLinePerFact() {
        val report = diagnosticsReport("0.9", listOf("Phone" to "Google Pixel 6a"), listOf("Last error" to "a\nb"))
        assertEquals("Droid: 0.9\nPhone: Google Pixel 6a\nLast error: a b", report)
    }

    @Test fun readsGetprop() {
        val props = parseProps("[ro.build.version.oneui]: [60101]\n[ro.product.model]: [SM-S921B]\n[empty]: []\n")
        assertEquals("60101", props["ro.build.version.oneui"])
        assertEquals("", props["empty"])
        assertEquals("One UI 60101", skin(props))
        assertEquals("HyperOS OS2.0", skin(mapOf("ro.mi.os.version.name" to "OS2.0", "ro.miui.ui.version.name" to "V816")))
        assertNull(skin(mapOf("ro.miui.ui.version.name" to "")))
    }

    @Test fun knowsWhenInputIsRefused() {
        assertTrue(inputRefused("java.lang.SecurityException: Injecting input events requires the caller (or the source of the instrumentation, if any) to have the INJECT_EVENTS permission."))
        assertFalse(inputRefused(""))
        assertFalse(inputRefused("Error: Unknown command: keyevent"))
    }
}
