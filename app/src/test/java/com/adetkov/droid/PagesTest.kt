package com.adetkov.droid

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.LinkedBlockingQueue

class PagesTest {
    @Test fun theHomeScreenShowsTheNewestHomePage() {
        fun page(id: String, where: String, ts: Long) = Msg(Kind.PAGE, "<p>$id</p>", ts, data = PageInfo(id, where = where).json())
        val a = Conv("a", "a", createdAt = 0, messages = listOf(page("h1", "home", 10), page("c1", "chat", 30)))
        val b = Conv("b", "b", createdAt = 0, messages = listOf(page("h2", "home", 20), page("f1", "full", 40)))
        assertEquals("b" to "h2", homePageOf(listOf(a, b))?.let { (c, m) -> c to PageInfo.of(m).id })
        assertEquals(null, homePageOf(listOf(Conv("c", "c", createdAt = 0, messages = listOf(page("c2", "chat", 5))))))
    }

    @Test fun anAnswerWithHtmlIsAPage() {
        assertTrue(hasHtml("Pick one:\n\n<label class=\"option\"><input type=\"radio\"> Dec 20</label>"))
        assertTrue(hasHtml("Done <b>now</b>."))
        assertFalse(hasHtml("Plain **Markdown** with a < b and x > y."))
        assertFalse(hasHtml("Code: `<div>` stays code."))
        assertFalse(hasHtml("```html\n<div>in a fence</div>\n```"))
        assertFalse(hasHtml("A link <https://example.com> and mail <me@example.com>."))
    }

    @Test fun anAnswerSplitsIntoMarkdownAndWholeHtmlBlocks() {
        val text = """
            Mama wants to know when you're **flying home**.

            <div class="card">
              <p>Fly home on</p>

                <label class="option"><input type="radio" name="d"> Dec 20</label>
            </div>
            More *Markdown* after.
            <script>
            if (a < b) droid.send('x')
            </script>
            <img src="/root/shot.png">
        """.trimIndent()
        val segs = segments(text)
        assertEquals(listOf(false, true, false, true, true), segs.map { it.first })
        // the blank line and the indented line inside the div stay HTML, not a code block
        assertTrue(segs[1].second.startsWith("<div class=\"card\">") && segs[1].second.endsWith("</div>"))
        assertTrue("    <label" in segs[1].second)
        assertEquals("More *Markdown* after.", segs[2].second)
        assertTrue(segs[3].second.contains("if (a < b)"))
        assertEquals("<img src=\"/root/shot.png\">", segs[4].second)
        // inline tags at a line's start are Markdown text
        assertEquals(listOf(false), segments("<b>Bold</b> and **more**").map { it.first })
    }

    @Test fun markdownInsideABlockAfterABlankLineIsMarkdown() {
        // as the agent wrote it on Nov 20 (the earthquake): a card around Markdown
        val segs = segments("Intro.\n\n<div class=\"card\">\n\n**M4.2, near Commerce**\n- **When:** 3:25 a.m.\n  - nested\n\n</div>\n\nAfter.")
        assertEquals(listOf(false to "Intro.", true to "<div class=\"card\">", false to "**M4.2, near Commerce**\n- **When:** 3:25 a.m.\n  - nested",
            true to "</div>", false to "After."), segs)
        // a draft in a text box keeps its blank lines (the David reply on Nov 20 showed <p> tags in it)
        val draft = segments("Here's a warmer version:\n\n<div class=\"card\">\n<p class=\"muted\">To: Housing</p>\n<textarea rows=\"9\">Hi David,\n\nThanks so much!\n\nBest,\nAlex</textarea>\n<button class=\"primary\">Send</button>\n</div>")
        assertEquals(listOf(false, true), draft.map { it.first })
        assertTrue("<textarea rows=\"9\">Hi David,\n\nThanks so much!\n\nBest,\nAlex</textarea>" in draft[1].second)
        // indented Markdown inside markup loses only the markup's indentation
        assertEquals(false to "**Hi**\n- a", segments("<div>\n\n    **Hi**\n    - a\n\n</div>")[1])
    }

    @Test fun aPageReadsAsTextWithoutItsCode() {
        val html = "<title>Fly home</title><style>p{color:red}</style><p>Pick a day</p><script>let x = 1 < 2</script>" +
            "<ul><li>Dec 20 &middot; CA&#36;492</li><li>Dec 24</li></ul><!-- note -->"
        assertEquals("Pick a day\nDec 20 · CA$492\nDec 24", htmlToText(html))
        assertEquals("Fly home", pageTitle(html))
        // what notifications, copying and reading aloud use
        assertEquals("Pick a day\nDec 20 · CA$492\nDec 24", stripMarkdown(html))
    }

    @Test fun aPageMayLoadOnlyTheFilesItNames() {
        val html = """<img src="/root/a.png"><link href='/root/s.css'><div style="background:url(/root/b.jpg)"></div> ![x](/tmp/c.png) <a href="https://x.org/y">"""
        assertEquals(setOf("/root/a.png", "/root/s.css", "/root/b.jpg", "/tmp/c.png"), namedPaths(html))
    }

    @Test fun aPagesOptionsSurviveTheirJson() {
        val p = PageInfo("p1", wait = true, where = "float", title = "Fly \"home\"", reply = "Dec 20")
        assertEquals(p, PageInfo.parse(p.json()))
        assertEquals("q", PageInfo.parse(PageInfo("n-q-1", where = "full", note = "q").json()).note)
        assertEquals(PageInfo(""), PageInfo.parse("nope"))
        assertEquals("chat", PageInfo.parse(JSONObject().put("id", "x").toString()).where)
    }

    @Test fun eachPageHasItsOwnOrigin() {
        val a = pageOrigin("page:c1:p1")
        assertTrue(a.matches(Regex("p[0-9a-f]{20}\\.droid\\.page")))
        assertEquals(a, pageOrigin("page:c1:p1"))
        assertFalse(a == pageOrigin("page:c1:p2"))
    }

    @Test fun theAgentsNotificationsHaveIdsOfTheirOwnButChatIsTheAnswers() {
        val c = "24893a78-1d41-4b39-80fe-0d1ee99dd2dc"
        // "chat" is the answer's own notification (AgentService posts it at nid(chat) + 1)
        assertEquals((c.hashCode() and 0x3fffffff) + 1, Notify.nid(c, Notify.CHAT))
        // the rest never collide with the app's own (0..0x3fffffff + 2), and differ by chat and id
        val a = Notify.nid(c, "dl")
        assertTrue(a >= 0x40000000)
        assertFalse(a == Notify.nid(c, "dl2"))
        assertFalse(a == Notify.nid("other", "dl"))
        assertEquals(a, Notify.nid(c, "dl"))
    }

    @Test fun anAnswerEmbeddedInAScriptCantEndIt() {
        // a comment opened in a script swallows its closing tag: the answer rendered blank
        val segs = segmentsJson("Look: <!-- and <script src=x> and </script> and \u2028")
        val safe = scriptSafe(segs)
        assertFalse('<' in safe || '\u2028' in safe)
        assertEquals(JSONArray(segs).toString(), JSONArray(safe).toString())
    }

    @Test fun whatAWaitingCommandGetsFromAPage() {
        val r = pageReply("Dec 20", """{"d":20}""")
        assertEquals("Dec 20", r.getString("text"))
        assertEquals(20, r.getJSONObject("data").getInt("d"))  // the JSON the page sent, not a string of it
        assertEquals("not json", pageReply("x", "not json").getString("data"))
        assertFalse(pageReply("x", null).has("data"))
    }

    private fun conv(vararg pages: PageInfo) =
        pages.fold(Conv("c", "t", createdAt = 0).add(Kind.USER, "hi", 0)) { c, p -> c.add(Kind.PAGE, "<p>${p.id}</p>", 0, data = p.json()) }

    @Test fun aWaitEndsWithoutTouchingAnAnsweredPage() {
        val c = conv(PageInfo("a", wait = true), PageInfo("b", wait = true), PageInfo("done", wait = true, reply = "yes"))
        assertEquals("<p>a</p>", c.page("a")?.text)
        assertNull(c.page("nope"))
        val gaveUp = c.waitingOn("a", false)
        assertEquals(listOf(false, true, true), listOf("a", "b", "done").map { PageInfo.of(gaveUp.page(it)!!).wait })
        assertEquals(listOf(true, true, true), listOf("a", "b", "done").map { PageInfo.of(gaveUp.waitingOn("a", true).page(it)!!).wait })
        // its run ended: no page waits, and an answered one keeps its answer
        val ended = c.waitingOn(null, false)
        assertEquals(listOf(false, false, true), listOf("a", "b", "done").map { PageInfo.of(ended.page(it)!!).wait })
        assertEquals("yes", PageInfo.of(ended.page("done")!!).reply)
    }

    /** A run whose output the test writes, line by line, until it ends it. */
    private class LinesProc : Proc {
        val lines = LinkedBlockingQueue<String>()
        override fun write(line: String) {}
        override fun closeStdin() {}
        override fun waitFor() = 0
        override fun kill(pid: Int) { lines += END }
        override fun readLines(onLine: (String) -> Unit) { while (true) onLine(lines.take().takeUnless { it == END } ?: return) }
        companion object { const val END = "" }
    }

    private fun until(what: String, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + 5000
        while (!ok() && System.currentTimeMillis() < end) Thread.sleep(10)
        assertTrue(what, ok())
    }

    @Test fun theAppKnowsWhatTheAgentsCommandsWaitOn() {
        val proc = LinesProc()
        val backend = object : Backend {
            override fun start(sessionId: String?, model: String, engine: Engine): Proc = proc
            override fun forget(sessionId: String, engine: Engine) {}
        }
        val store = Store(File.createTempFile("convs", ".json").also { it.delete(); it.deleteOnExit() })
        val runner = Runner(store, backend)
        val id = runner.send(null, "Book it")!!
        runner.putPage(id, PageInfo("p1", wait = true), "<p>Pick</p>")
        fun droid(json: String) = proc.lines.put("""{"type":"droid",$json}""")
        fun pageWaits() = PageInfo.of(store.get(id)!!.page("p1")!!).wait

        droid(""""op":"notify","id":"q","title":"Which?","wait":true""")
        until("waits on the notification") { "$id/q" in runner.noteWaits }
        droid(""""op":"gave_up","id":"n-q"""")
        until("gave up on the notification") { "$id/q" !in runner.noteWaits }
        droid(""""op":"gave_up","id":"p1"""")
        until("gave up on the page") { !pageWaits() }
        droid(""""op":"wait","id":"p1"""")
        until("waits on the page again") { pageWaits() }
        droid(""""op":"notify","id":"r","title":"Sure?","wait":true""")
        until("waits on another") { "$id/r" in runner.noteWaits }
        // an update under its id (progress) leaves the wait; --cancel ends it
        droid(""""op":"notify","id":"r","title":"Sure? 50%"""")
        droid(""""op":"notify","id":"s","title":"Other","wait":true""")
        until("an update leaves the wait") { "$id/s" in runner.noteWaits && "$id/r" in runner.noteWaits }
        droid(""""op":"notify","id":"s","cancel":true""")
        until("cancelled") { "$id/s" !in runner.noteWaits }
        proc.lines.put(LinesProc.END)  // the run ends: nothing waits any more
        until("the run ended") { runner.running.value.isEmpty() && !pageWaits() && runner.noteWaits.isEmpty() }
    }

    @Test fun theChatsAppStaysLiveOtherPagesAnswerOnce() {
        val shown = Conv("c", "t", createdAt = 0).add(Kind.USER, "plan it", 0)
            .add(Kind.PAGE, "<p>Pick</p>", 0, data = PageInfo("p", wait = true).json())
        fun Conv.live(working: Boolean = true, id: String = "p") = messages.pageLive(pageIndex(id), working) { false }
        fun Conv.answerLive(at: Int) = messages.pageLive(at, false) { false }
        assertTrue(shown.live())
        assertTrue(shown.live(working = false))
        // a question the agent's command waited on, answered: done
        assertFalse(shown.answered("p", "Fri").live())
        assertEquals("Fri", PageInfo.of(shown.answered("p", "Fri").page("p")!!).reply)
        // a later turn: the chat's app stays live, the owner's to use
        val later = shown.add(Kind.USER, "actually Monday", 0)
        assertTrue(later.live(working = false))
        assertFalse(shown.live(id = "other"))
        // the agent makes a newer one: that's the app now, and the old one is done
        val newer = later.add(Kind.AGENT, "<b>Done</b>", 0)
        val at = newer.messages.lastIndex
        assertEquals(at, newer.messages.currentApp())
        assertFalse(newer.live(working = false))
        assertTrue(newer.add(Kind.USER, "thanks", 0).answerLive(at))
        // an answer in words doesn't replace it; a newer page does
        assertTrue(newer.add(Kind.USER, "thanks", 0).add(Kind.AGENT, "You're welcome", 0).answerLive(at))
        assertFalse(newer.add(Kind.USER, "thanks", 0).add(Kind.AGENT, "<b>Next</b>", 0).answerLive(at))
    }

    @Test fun aNotificationsPageMayActWhileTheCommandWaitsOnTheNotification() {
        val c = Conv("c", "t", createdAt = 0).add(Kind.USER, "remind me", 0)
            .add(Kind.PAGE, "<p>Ok?</p>", 0, data = PageInfo("n-q-1", where = "full", note = "q").json())
            .add(Kind.WAKE, "check", 0)
        val i = c.pageIndex("n-q-1")
        assertTrue(c.messages.awaits(i, true) { it == "q" })
        assertTrue(c.messages.pageLive(i, true) { it == "q" })
        assertFalse(c.messages.pageLive(i, true) { false })
        assertFalse(c.messages.pageLive(i, false) { it == "q" })
    }

    @Test fun aPageShownAgainInALaterTurnMovesThere() {
        val first = Conv("c", "t", createdAt = 0).add(Kind.USER, "plan it", 0)
            .withPage(PageInfo("p", wait = true), "<p>1</p>", 1).add(Kind.AGENT, "Picked.", 2)
        // in the turn that showed it: in place
        val updated = first.withPage(PageInfo("p", wait = true), "<p>2</p>", 3)
        assertEquals(listOf(Kind.USER, Kind.PAGE, Kind.AGENT), updated.messages.map { it.kind })
        assertEquals("<p>2</p>", updated.page("p")!!.text)
        // answered, then shown again in a later turn: there, at the end, unanswered and live
        val again = first.answered("p", "Fri").add(Kind.USER, "and Monday?", 4).withPage(PageInfo("p", wait = true), "<p>3</p>", 5)
        assertEquals(listOf(Kind.USER, Kind.AGENT, Kind.USER, Kind.PAGE), again.messages.map { it.kind })
        assertEquals(5L, again.page("p")!!.ts)
        assertNull(PageInfo.of(again.page("p")!!).reply)
        assertTrue(again.messages.pageLive(again.pageIndex("p"), false) { false })
    }
}
