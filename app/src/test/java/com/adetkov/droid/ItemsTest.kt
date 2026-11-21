package com.adetkov.droid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ItemsTest {
    private fun conv(vararg lines: String): Conv =
        lines.fold(Conv("c", "t", createdAt = 0).add(Kind.USER, "task", 0)) { c, l -> parseLine(l).fold(c) { a, e -> a.apply(e, 1) } }

    private fun text(t: String) = """{"type":"assistant","message":{"content":[{"type":"text","text":"$t"}]}}"""
    private fun step(d: String) = """{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Bash","input":{"description":"$d"}}]}}"""
    private fun result(t: String) = """{"type":"result","is_error":false,"result":"$t"}"""

    @Test fun narrationFoldsIntoStepsAndResultIsTheAnswer() {
        val c = conv(text("Scrolling down"), step("Tap"), text("Almost there"), text("All **done**"), result("All **done**"))
        val items = buildItems(c.messages, running = false)
        assertEquals(3, items.size)                              // user, work log, answer
        val log = items[1] as Item.Steps
        assertEquals(listOf("Scrolling down" to true, "Tap" to false, "Almost there" to true), log.lines.map { it.text to it.note })
        assertEquals(false, log.live)
        val answer = (items[2] as Item.Message).msg
        assertEquals(Kind.AGENT, answer.kind)
        assertEquals("All **done**", answer.text)
    }

    @Test fun aStoppedTurnKeepsNoMood() {
        val calls = Msg(Kind.DASH, """{"feel":"needs_you","words":"Pick one"}""", 2)
        assertEquals(true, lastMood(listOf(Msg(Kind.USER, "plan it", 1), calls))?.calls)
        assertEquals(null, lastMood(listOf(Msg(Kind.USER, "plan it", 1), calls, Msg(Kind.STATUS, "Stopped", 3))))
    }

    @Test fun aPageReportedBeforeItsStepStillComesAfterTheWorkRow() {
        // Codex and OpenCode report a step once it's done; its `droid show` page arrives first
        val msgs = listOf(Msg(Kind.USER, "7 or 8?", 1), Msg(Kind.PAGE, "<p>Pick</p>", 2), Msg(Kind.STEP, "Showing a page", 3),
            Msg(Kind.AGENT, "Tap one above.", 4))
        val items = buildItems(msgs, running = false)
        assertEquals(listOf("m0", "steps", "m1", "m3"), items.map { if (it is Item.Steps) "steps" else it.key })
        assertEquals(listOf("Showing a page"), (items[1] as Item.Steps).lines.map { it.text })
    }

    @Test fun aTurnKeepsItsRowWhenItsFirstStepComes() {
        // the same key before and after, so the row (and Dash in it) isn't replaced mid-turn
        val before = buildItems(conv().messages, running = true).filterIsInstance<Item.Steps>().single()
        val after = buildItems(conv(step("Open Gmail")).messages, running = true).filterIsInstance<Item.Steps>().single()
        assertEquals(before.key, after.key)
        assertTrue(before.stillKey != after.stillKey || before.lines.isEmpty())
    }

    @Test fun runningShowsLatestAsLiveStatusAndNoAnswer() {
        val c = conv(step("Open Chrome"), text("Chrome refused to launch, trying again"))
        val items = buildItems(c.messages, running = true)
        assertEquals(2, items.size)
        assertTrue((items[1] as Item.Steps).live)
        assertEquals("Chrome refused to launch, trying again", latestStatus(c.messages))
        assertEquals("Working…", latestStatus(conv().messages))
    }

    @Test fun runWithoutResultKeepsNarrationFoldedAndShowsOnlyStatus() {
        val c = conv(text("first"), text("last words")).add(Kind.STATUS, "Stopped")
        val items = buildItems(c.messages, running = false)
        assertEquals(3, items.size)                                   // user, folded log, status
        assertEquals(listOf("first", "last words"), (items[1] as Item.Steps).lines.map { it.text })
        assertEquals(listOf(Kind.USER, Kind.STATUS), items.filterIsInstance<Item.Message>().map { it.msg.kind })
    }

    @Test fun cleanExitWithoutResultPromotesLastNote() {          // what Runner does on exit 0 with no result
        val c = conv(text("first"), text("last words")).apply(Ev.Result(false, ""), 2)
        val items = buildItems(c.messages, running = false)
        assertEquals(Kind.AGENT, (items.last() as Item.Message).msg.kind)
        assertEquals("last words", (items.last() as Item.Message).msg.text)
        assertEquals(listOf("first"), (items[1] as Item.Steps).lines.map { it.text })
    }

    @Test fun waitingShowsEmptyLiveGroup() {
        val items = buildItems(conv().messages, running = true)
        assertTrue((items.last() as Item.Steps).let { it.live && it.lines.isEmpty() })
    }

    @Test fun titles() {
        assertEquals("Open chrome and search", makeTitle("  open chrome\nand   search?! "))
        assertEquals("New chat", makeTitle("..."))
        assertEquals(61, makeTitle("a".repeat(80)).length)
    }

    @Test fun dayGroups() {
        val noon = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 28, 12, 0, 0) }.timeInMillis
        fun daysAgo(n: Int) = Calendar.getInstance().apply { timeInMillis = noon; add(Calendar.DAY_OF_YEAR, -n) }.timeInMillis
        assertEquals(
            listOf("Today", "Yesterday", "Previous 7 days", "Previous 7 days", "Previous 30 days", "Previous 30 days", "September", "October 2025"),
            listOf(noon, daysAgo(1), daysAgo(2), daysAgo(7), daysAgo(8), daysAgo(30), daysAgo(31), daysAgo(365)).map { dayGroup(it, noon) },
        )
    }

    @Test fun durations() {
        assertEquals(listOf("0s", "59s", "1m 05s", "1h 02m"), listOf(0L, 59_000L, 65_000L, 3_720_000L).map(::duration))
    }

    @Test fun workLogKnowsWhenTheTurnStartedAndEnded() {
        val c = Conv("c", "t", createdAt = 0).add(Kind.USER, "go", 1_000).add(Kind.STEP, "Tap", 5_000).add(Kind.AGENT, "Done", 9_000)
        val log = buildItems(c.messages, running = false)[1] as Item.Steps
        assertEquals(1_000L to 9_000L, log.start to log.end)
    }

    @Test fun attachmentsReachTheAgentAsPaths() {
        val a = Attachment("x.jpg", "/storage/emulated/0/Android/data/com.adetkov.droid/files/uploads/ab/x.jpg", "image/jpeg")
        assertEquals("look\n\n[Attached: /sdcard/Android/data/com.adetkov.droid/files/uploads/ab/x.jpg]", agentText("look", listOf(a)))
        assertEquals("[Attached: /sdcard/Android/data/com.adetkov.droid/files/uploads/ab/x.jpg]", agentText(" ", listOf(a)))
        assertEquals("plain", agentText("plain", emptyList()))
        assertEquals("My_photo_1.jpg", safeName("My photo (1).jpg"))
    }

    @Test fun theAssistantsScreenshotIsLabelledAsTheScreen() {
        val dir = "/storage/emulated/0/Android/data/com.adetkov.droid/files/uploads"
        val shot = Attachment("Screenshot.png", "$dir/cd/screenshot.png", "image/png", Source.SCREEN)
        val doc = Attachment("a.pdf", "$dir/ef/a.pdf", "application/pdf")
        assertEquals("reply to her\n\n[Screenshot of my screen: /sdcard/Android/data/com.adetkov.droid/files/uploads/cd/screenshot.png]\n" +
            "[Attached: /sdcard/Android/data/com.adetkov.droid/files/uploads/ef/a.pdf]", agentText("reply to her", listOf(shot, doc)))
        val photo = Attachment("photo_1.jpg", "$dir/ab/photo_1.jpg", "image/jpeg", Source.CAMERA)
        assertEquals("add this to my calendar\n\n[Photo I just took: /sdcard/Android/data/com.adetkov.droid/files/uploads/ab/photo_1.jpg]",
            agentText("add this to my calendar", listOf(photo)))
    }

    @Test fun attachmentsSurviveARestart() {
        val f = java.io.File.createTempFile("convs", ".json").apply { delete() }
        try {
            val store = Store(f)
            val c = store.create("what's this")
            val shot = Attachment("Screenshot.png", "/x/screenshot.png", "image/png", Source.SCREEN)
            val doc = Attachment("a.pdf", "/x/a.pdf", "application/pdf")
            val photo = Attachment("photo_1.jpg", "/x/photo_1.jpg", "image/jpeg", Source.CAMERA)
            store.update(c.id) { it.add(Kind.USER, "what's this", 1, listOf(shot, doc, photo)) }
            val until = System.currentTimeMillis() + 5_000
            while (!f.exists() && System.currentTimeMillis() < until) Thread.sleep(50)
            assertEquals(listOf(shot, doc, photo), Store(f).get(c.id)!!.messages.single().files)
        } finally { f.delete() }
    }

    @Test fun lastOutcomeSaysWhatTheTurnLeftForYou() {
        fun c(vararg m: Msg) = Conv("c", "t", createdAt = 0, messages = m.toList())
        val ask = Msg(Kind.USER, "hi", 1)
        assertEquals(null, lastOutcome(c(ask).messages, running = true))
        assertEquals(Outcome.DONE, lastOutcome(c(ask, Msg(Kind.AGENT, "Done.", 2)).messages, false))
        // only the agent makes it the owner's turn (droid dash needs_you), never a question mark
        assertEquals(Outcome.DONE, lastOutcome(c(ask, Msg(Kind.AGENT, "Which one?", 2)).messages, false))
        val calls = Msg(Kind.DASH, """{"feel":"needs_you","words":"Pick a date"}""", 2)
        assertEquals(Outcome.ASKS, lastOutcome(c(ask, calls, Msg(Kind.AGENT, "Mama wants a date.", 3)).messages, false))
        assertEquals(Outcome.DONE, lastOutcome(c(ask, Msg(Kind.DASH, """{"feel":"oops","words":""}""", 2), Msg(Kind.AGENT, "No.", 3)).messages, false))
        assertEquals(Outcome.FAILED, lastOutcome(c(ask, Msg(Kind.STATUS, "Error: boom", 2)).messages, false))
        assertEquals(Outcome.FAILED, lastOutcome(c(ask, Msg(Kind.AGENT, "Done.", 2), Msg(Kind.STATUS, "Interrupted", 3)).messages, false))
        assertEquals(Outcome.STOPPED, lastOutcome(c(ask, Msg(Kind.STATUS, "Stopped", 2)).messages, false))
        // a new request: what the turn before left no longer counts
        assertEquals(null, lastOutcome(c(Msg(Kind.AGENT, "Which one?", 0), ask).messages, false))
        // a stop wins over what Dash was set to
        assertEquals(Outcome.STOPPED, lastOutcome(c(ask, calls, Msg(Kind.STATUS, "Stopped", 3)).messages, false))
    }

    @Test fun theAgentSetsDashsMoodAndWords() {
        val ask = Msg(Kind.USER, "hi", 1)
        val oops = Msg(Kind.DASH, """{"feel":"oops","words":"Not possible until Dec 28"}""", 2)
        assertEquals(Mood(Feel.OOPS, "Not possible until Dec 28"), lastMood(listOf(ask, oops)))
        assertFalse(lastMood(listOf(ask, oops))!!.calls)
        // a later setting replaces it; a new request starts without one
        val yay = Msg(Kind.DASH, """{"feel":"celebrate","words":""}""", 3)
        assertEquals(Feel.CELEBRATE, lastMood(listOf(ask, oops, yay))!!.feel)
        assertEquals(null, lastMood(listOf(oops, ask)))
        // a face of its own: numbers on a named feeling; it calls only if that one does
        val face = moodOf("""{"feel":{"feel":"needs_you","smile":0.6},"words":"Your pick"}""")!!
        assertTrue(face.calls)
        assertEquals(.6f, face.feel.smile, 1e-6f)
        assertEquals(null, moodOf("""{"feel":"no-such-feeling"}"""))
        assertEquals(null, moodOf("not json"))
        // the same text is the same Mood, so Dash isn't told twice
        assertTrue(moodOf(oops.text) === moodOf(oops.text))
    }

    @Test fun theLabelBesideDashSaysWhoseTurnItIs() {
        assertEquals("Your turn · Pick a date", turnLabel(Outcome.ASKS, Mood(Feel.NEEDS_YOU, "Pick a date")))
        assertEquals("Your turn", turnLabel(Outcome.ASKS, Mood(Feel.NEEDS_YOU, "")))
        assertEquals("Not possible until Dec 28", turnLabel(Outcome.DONE, Mood(Feel.OOPS, "Not possible until Dec 28")))
        assertEquals(null, turnLabel(Outcome.DONE, Mood(Feel.CELEBRATE, "")))
        assertEquals(null, turnLabel(Outcome.DONE, null))
        assertEquals("Couldn't finish", turnLabel(Outcome.FAILED, Mood(Feel.OOPS, "x")))
    }

    @Test fun aPageTheAgentWaitsOnMakesItYourTurnMidTask() {
        val ask = Msg(Kind.USER, "hi", 1)
        val page = Msg(Kind.PAGE, "<p>Pick</p>", 2, data = PageInfo("p1", wait = true, title = "Fly home").json())
        assertEquals(page, waitingPage(listOf(ask, page), running = true))
        assertEquals(null, waitingPage(listOf(ask, page), running = false))
        val answered = page.copy(data = PageInfo("p1", wait = true, reply = "Dec 20").json())
        assertEquals(null, waitingPage(listOf(ask, answered), running = true))
        // a message, a wake-up or an attachment meanwhile starts a turn, but the agent's command still waits on it
        assertEquals(page, waitingPage(listOf(ask, page, Msg(Kind.USER, "and a hotel?", 3)), running = true))
        // unless its run ended (the app died mid-run, say), and the wait was left behind
        assertEquals(null, waitingPage(listOf(ask, page, Msg(Kind.STATUS, "Interrupted", 3), Msg(Kind.USER, "again", 4)), running = true))
    }

    @Test fun aWakeUpStartsATurnAndDashMessagesDontShow() {
        val msgs = listOf(Msg(Kind.USER, "email them", 1), Msg(Kind.AGENT, "Sent.", 2),
            Msg(Kind.WAKE, "Check for a reply", 3), Msg(Kind.STEP, "Open Outlook", 4), Msg(Kind.DASH, """{"feel":"oops","words":""}""", 5),
            Msg(Kind.AGENT, "No reply yet.", 6))
        val items = buildItems(msgs, running = false)
        assertEquals(listOf("m0", "m1", "m2", "t2:3", "m5"), items.map { it.key })
        assertEquals(Feel.OOPS, lastMood(msgs)!!.feel)
    }

    @Test fun aMessageSentMidTurnKeepsItsTurnLiveAfterTheAnswerItCameBefore() {
        // the second request went in while the agent worked on the first, whose answer then came after it
        val msgs = listOf(Msg(Kind.USER, "email Ann", 1), Msg(Kind.STEP, "Open Gmail", 2), Msg(Kind.USER, "cc Bob too", 3),
            Msg(Kind.AGENT, "Sent.", 4))
        fun live(m: List<Msg>) = buildItems(m, running = true).filterIsInstance<Item.Steps>().any { it.live }
        assertFalse(live(msgs))
        // the agent goes on with the second
        assertTrue(live(msgs + Msg(Kind.STEP, "Open Sent", 5)))
        assertFalse(live(msgs + Msg(Kind.STEP, "Open Sent", 5) + Msg(Kind.AGENT, "Sent again with Bob.", 6)))
    }

    @Test fun aRetryInPlaceIsANewRow() {
        fun steps(vararg m: Msg) = buildItems(m.toList(), running = false).filterIsInstance<Item.Steps>().single()
        val failed = steps(Msg(Kind.USER, "go", 1), Msg(Kind.STEP, "Tap", 2), Msg(Kind.STATUS, "Failed", 3))
        val retried = steps(Msg(Kind.USER, "go", 10), Msg(Kind.STEP, "Tap", 11))
        assertNotEquals(failed.key, retried.key)
        // the same still's name, though: retrying deletes the stills from there on
        assertEquals(failed.stillKey, retried.stillKey)
    }
}
