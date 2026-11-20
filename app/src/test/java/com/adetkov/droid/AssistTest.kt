package com.adetkov.droid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AssistTest {
    /** Records what a page did. */
    private class Recorder : PageActions {
        val did = mutableListOf<String>()
        override fun send(text: String, data: String?) { did += "send $text" }
        override fun run(command: String): Pair<String, Int> { did += "run $command"; return "ok" to 0 }
        override fun dash(feel: String, words: String) { did += "dash $feel" }
        override fun screen() { did += "screen" }
        override fun open(url: String) { did += "open $url" }
        override fun close() { did += "close" }
        override fun fetch(path: String): File? = null
        override val shown = File("shown")
    }

    @Test fun onTheLockScreenAPageActsOnlyAfterTheUnlock() {
        val page = Recorder()
        val waiting = mutableListOf<() -> Unit>()
        val actions = PanelPageActions(page, locked = { true }) { waiting += it }
        actions.send("buy it", null)
        actions.open("https://example.com")
        actions.screen()
        actions.dash("happy", "")  // only how Dash looks
        val (out, code) = actions.run("rm -rf /sdcard")
        assertEquals(listOf("dash happy"), page.did)
        assertEquals(1, code)
        assertTrue(out.contains("locked"))
        waiting.forEach { it() }  // unlocked: what was asked goes now, but the command doesn't run
        assertEquals(listOf("dash happy", "send buy it", "open https://example.com", "screen"), page.did)
    }

    @Test fun unlockedAPageActsAtOnce() {
        val page = Recorder()
        val actions = PanelPageActions(page, locked = { false }) { it() }
        actions.send("hi", null)
        assertEquals("ok" to 0, actions.run("date"))
        assertEquals(listOf("send hi", "run date"), page.did)
    }
}
