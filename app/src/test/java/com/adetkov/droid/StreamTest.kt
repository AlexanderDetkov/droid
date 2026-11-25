package com.adetkov.droid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamTest {
    private val sid = "a7542358-62cc-4a9a-b240-5554ffd5c592"
    private val sample = listOf(
        "garbage line",
        """{"type":"agent","pid":19257,"display":16,"port":8716}""",
        """{"type":"system","subtype":"init","session_id":"$sid","tools":[]}""",
        """{"message":{"content":[{"type":"text","text":"Hello there, friend!"}]},"type":"assistant","session_id":"$sid"}""",
        """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"android getprop ro.product.model","description":"Get the phone's model name"}}]}}""",
        """{"type":"rate_limit_event"}""",
        """{"type":"user","message":{"content":[{"type":"tool_result","content":"Pixel 6a"}]}}""",
        """{"type":"assistant","message":{"content":[{"type":"thinking","thinking":"hmm"}]}}""",
        """{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Read","input":{"file_path":"/tmp/shot-12.png"}}]}}""",
        """{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Bash","input":{"command":"android input tap 100 200 && android input swipe 1 2 3 4 5 6 7 8 9 10 11 12 13"}}]}}""",
        """{"type":"assistant","message":{"content":[{"type":"text","text":"The phone reports its model as **Pixel 6a**."}]}}""",
        """{"type":"result","subtype":"success","is_error":false,"result":"The phone reports its model as **Pixel 6a**.","session_id":"$sid","duration_ms":3119}""",
    )

    private fun run(lines: List<String>, start: Conv = Conv("c", "t", createdAt = 0).add(Kind.USER, "hi", 0)) =
        lines.fold(start) { c, l -> parseLine(l).fold(c) { acc, ev -> acc.apply(ev, 1) } }

    @Test fun fileStepsSayWhatTheyAreForInPlainWords() {
        fun step(tool: String, path: String) = run(listOf(
            """{"type":"assistant","message":{"content":[{"type":"tool_use","name":"$tool","input":{"file_path":"$path"}}]}}""",
        )).messages.last().text
        assertEquals("Checking my notes", step("Read", "/root/.claude/projects/-root/memory/MEMORY.md"))
        assertEquals("Looking at the screen", step("Read", "/tmp/shot-26.png"))
        assertEquals("Taking notes", step("Write", "/root/notes.md"))
        assertEquals("Making your page", step("Write", "/root/week.html"))
        assertEquals("Looking at a picture", step("Read", "/sdcard/DCIM/IMG_1.JPG"))
    }

    @Test fun tapsComeFromCommands() {
        fun taps(cmd: String) = parseLine("""{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Bash","input":{"command":${org.json.JSONObject.quote(cmd)}}}]}}""")
            .filterIsInstance<Ev.Tap>()
        assertEquals(listOf(Ev.Tap(540, 222)), taps("android input -d 4 tap 540 222"))
        assertEquals(listOf(Ev.Tap(100, 200)), taps("android input tap 100 200 && android input swipe 1 2 3 4"))
        assertEquals(listOf(Ev.Tap(7, 8)), taps("input touchscreen tap 7 8"))
        assertEquals(emptyList<Ev.Tap>(), taps("android input swipe 1 2 3 4 500"))
        assertEquals(emptyList<Ev.Tap>(), taps("echo tap 1 2"))
        assertEquals(listOf(Ev.Tap(540, 1880)), taps("tap 540 1880"))
        assertEquals(emptyList<Ev.Tap>(), taps("tap Send"))  // by name: where isn't in the command
        assertEquals(emptyList<Ev.Tap>(), taps("android input tap 99999999999 2"))  // no Int: no tap, and no crash
    }

    @Test fun stepsSayWhatKindOfWorkTheyAre() {
        fun work(tool: String, input: String) = parseLine("""{"type":"assistant","message":{"content":[{"type":"tool_use","name":"$tool","input":$input}]}}""")
            .filterIsInstance<Ev.Step>().single().work
        fun bash(cmd: String) = work("Bash", """{"command":${org.json.JSONObject.quote(cmd)},"description":"x"}""")
        assertEquals(Work.OPEN, bash("android 'am start --display 4 -n com.google.android.gm/.Main' && sleep 2 && shot"))
        assertEquals(Work.TYPE, bash("android 'input -d 4 text hello'"))
        assertEquals(Work.TYPE, bash("android 'input -d 4 keyevent 66'"))
        assertEquals(Work.SCROLL, bash("android 'input -d 4 swipe 540 1600 540 600 300'"))
        assertEquals(Work.TAP, bash("android 'input -d 4 tap 912 472' && sleep 1 && shot"))
        assertEquals(Work.BACK, bash("android 'input -d 4 keyevent 4'"))
        assertEquals(Work.TAP, bash("tap 'Send'"))
        assertEquals(Work.TAP, bash("tap Compose && shot --text"))
        assertEquals(Work.TYPE, bash("fill 'Привет, Sam 👋'"))
        assertEquals(Work.LOOK, work("Read", """{"file_path":"/tmp/screen.png"}"""))
        assertEquals(Work.LOOK, bash("shot"))
        assertEquals(Work.LOOK, bash("sleep 1; shot"))
        assertEquals(Work.LOOK, bash("android 'uiautomator dump /sdcard/ui.xml'"))
        assertEquals(Work.WAIT, bash("sleep 3"))
        assertEquals(Work.WEB, bash("curl -s https://example.com"))
        assertEquals(Work.READ, bash("cat ~/notes.md"))
        assertEquals(Work.RUN, bash("android getprop ro.product.model"))
        assertEquals(Work.LOOK, work("Read", """{"file_path":"/tmp/shot-3.png"}"""))
        assertEquals(Work.READ, work("Read", """{"file_path":"/root/notes.md"}"""))
        assertEquals(Work.WRITE, work("Edit", """{"file_path":"/root/notes.md"}"""))
        assertEquals(Work.WEB, work("WebSearch", """{"query":"x"}"""))
    }

    @Test fun newSessionIsAStep() {
        val evs = parseLine("""{"type":"agent","pid":1,"display":16,"port":8716,"token":"ab","new_session":true}""")
        assertEquals(listOf(Ev.Agent(1, 8716, "ab"), Ev.Step(NEW_SESSION)), evs)
        assertEquals(listOf(Ev.Agent(1, 8716, "ab")), parseLine("""{"type":"agent","pid":1,"display":16,"port":8716,"token":"ab","new_session":false}"""))
    }

    @Test fun sampleStream() {
        val c = run(sample)
        assertEquals(sid, c.sessionId)
        assertEquals(
            listOf(
                Kind.USER to "hi",
                Kind.NOTE to "Hello there, friend!",
                Kind.STEP to "Get the phone's model name",
                Kind.STEP to "Looking at the screen",
                Kind.STEP to "Bash android input tap 100 200 && android input swipe 1 2 3 4 5 6",
                Kind.AGENT to "The phone reports its model as **Pixel 6a**.",
            ),
            c.messages.map { it.kind to it.text },
        )
    }

    @Test fun resultDifferingFromLastTextIsAdded() {
        val c = run(listOf("""{"type":"result","is_error":false,"result":"Done.","session_id":"$sid"}"""))
        assertEquals(Kind.AGENT, c.messages.last().kind)
        assertEquals("Done.", c.messages.last().text)
    }

    @Test fun errorResultBecomesStatus() {
        val c = run(listOf("""{"type":"result","is_error":true,"result":"boom"}"""))
        assertEquals(Kind.STATUS, c.messages.last().kind)
        assertTrue(c.messages.last().text.contains("boom"))
    }

    @Test fun nonJsonIgnored() {
        assertTrue(parseLine("hello").isEmpty())
        assertTrue(parseLine("").isEmpty())
        assertTrue(parseLine("42").isEmpty())
    }

    @Test fun agentLine() {
        assertEquals(listOf<Ev>(Ev.Agent(19257, 8716, null)), parseLine("""{"port":8716,"display":16,"type":"agent","pid":19257}"""))
        assertEquals(listOf<Ev>(Ev.Agent(5, 8705, "ab12")), parseLine("""{"type":"agent","pid":5,"display":5,"port":8705,"token":"ab12"}"""))
        // the agent line adds no message
        assertEquals(1, run(listOf("""{"type":"agent","pid":1,"display":2,"port":3}""")).messages.size)
    }

    @Test fun displayFailureResult() {
        val c = run(listOf("""{"type":"result","subtype":"error","is_error":true,"result":"The hidden display did not start."}"""))
        assertEquals(Kind.STATUS, c.messages.last().kind)
        assertEquals("Error: The hidden display did not start.", c.messages.last().text)
    }

    @Test fun laterResultsForFollowUps() {
        val c = run(listOf(
            """{"type":"result","is_error":false,"result":"First.","session_id":"$sid"}""",
            """{"type":"result","is_error":false,"result":"Second.","session_id":"$sid"}""",
        ))
        assertEquals(listOf("First.", "Second."), c.messages.filter { it.kind == Kind.AGENT }.map { it.text })
    }

    @Test fun userLineIsOneJsonLine() {
        val line = userLine("say \"hi\"\nthen 'bye' \\ ünï")
        assertTrue(!line.contains('\n'))
        val o = org.json.JSONObject(line)
        assertEquals("user", o.getString("type"))
        assertEquals("user", o.getJSONObject("message").getString("role"))
        assertEquals("say \"hi\"\nthen 'bye' \\ ünï", o.getJSONObject("message").getString("content"))
    }

    @Test fun theAgentsDroidCommandComesThroughAsEvents() {
        val ev = parseLine("""{"type":"droid","op":"dash","feel":"oops","words":"Not yet"}""").single() as Ev.Droid
        assertEquals("dash", ev.op)
        assertEquals("oops", ev.o.getString("feel"))
        assertEquals(emptyList<Ev>(), parseLine("""{"type":"droid"}"""))
        // nothing in the chat by itself: the runner acts on it
        val c = Conv("c", "t", createdAt = 0).add(Kind.USER, "hi", 0)
        assertEquals(c, c.apply(ev, 1))
    }

    @Test fun whatAPageSentGoesToTheAgentWithTheMessage() {
        assertEquals("Fly home Sun, Dec 20\n\n[Sent from your page: {\"day\":\"Sun, Dec 20\"}]",
            agentText("Fly home Sun, Dec 20", emptyList(), """{"day":"Sun, Dec 20"}"""))
        assertEquals("hi", agentText("hi", emptyList(), null))
    }

    @Test fun howTheOwnerAskedComesLast() {
        assertEquals("Set a timer\n\n[I said this aloud, and your answer is read aloud to me]",
            agentText("Set a timer", emptyList(), via = "I said this aloud, and your answer is read aloud to me"))
        assertEquals("ok\n\n[Sent from your page: {}]\n\n[I replied in your notification]",
            agentText("ok", emptyList(), "{}", "I replied in your notification"))
        assertEquals("[I typed this on the lock screen]", agentText("", emptyList(), via = "I typed this on the lock screen"))
    }
}
