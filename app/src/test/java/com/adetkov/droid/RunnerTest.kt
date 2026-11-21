package com.adetkov.droid

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class RunnerTest {
    /** A run that reports its pid only when [started] is released, then works until killed. */
    private class SlowProc : Proc {
        val started = CountDownLatch(1)
        val killed = CountDownLatch(1)
        @Volatile var killedPid = -1

        override fun write(line: String) {}
        override fun closeStdin() {}
        override fun waitFor() = 130
        override fun kill(pid: Int) { killedPid = pid; killed.countDown() }
        override fun readLines(onLine: (String) -> Unit) {
            started.await()
            onLine("""{"type":"agent","pid":77,"display":5,"port":8705}""")
            onLine("""{"type":"system","subtype":"init","session_id":"0b0b0b0b-0000-0000-0000-000000000000"}""")
            killed.await()
        }
    }

    private class FakeBackend(val proc: SlowProc) : Backend {
        val forgotten = mutableListOf<String>()
        override fun start(sessionId: String?, model: String, engine: Engine): Proc = proc
        override fun forget(sessionId: String, engine: Engine) { synchronized(forgotten) { forgotten += sessionId } }
    }

    /** A run that answers its first message, then exits once stdin is closed. */
    private class AnswerProc : Proc {
        val writes: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val wrote = CountDownLatch(1)
        val closed = CountDownLatch(1)
        @Volatile var up = true
        override val alive get() = up

        override fun write(line: String) { writes += line; wrote.countDown() }
        override fun closeStdin() { closed.countDown() }
        override fun waitFor() = 0
        override fun kill(pid: Int) { closed.countDown() }
        override fun readLines(onLine: (String) -> Unit) {
            onLine("""{"type":"agent","pid":78,"display":6,"port":8706}""")
            if (wrote.await(5, TimeUnit.SECONDS))
                onLine("""{"type":"result","subtype":"success","is_error":false,"result":"ok","session_id":"0c0c0c0c-0000-0000-0000-000000000000"}""")
            closed.await(5, TimeUnit.SECONDS)
        }
    }

    /** Hands out a fresh [AnswerProc] per start. */
    private class CountingBackend : Backend {
        val started: MutableList<AnswerProc> = Collections.synchronizedList(mutableListOf())
        override fun start(sessionId: String?, model: String, engine: Engine): Proc = AnswerProc().also { started += it }
        override fun forget(sessionId: String, engine: Engine) {}
    }

    private fun awaitStarts(backend: CountingBackend, n: Int) {
        val until = System.currentTimeMillis() + 5000
        while (backend.started.size < n && System.currentTimeMillis() < until) Thread.sleep(10)
        assertEquals(n, backend.started.size)
    }

    private fun store() = Store(File.createTempFile("convs", ".json").also { it.delete(); it.deleteOnExit() })

    private fun awaitIdle(runner: Runner) {
        val until = System.currentTimeMillis() + 5000
        while (runner.running.value.isNotEmpty() && System.currentTimeMillis() < until) Thread.sleep(10)
        assertTrue("run did not finish", runner.running.value.isEmpty())
    }

    @Test fun stopWhileStartingKillsOnceThePidIsKnown() {
        val proc = SlowProc()
        val store = store()
        val runner = Runner(store, FakeBackend(proc), holdFor = 0)
        val id = runner.send(null, "hi")!!
        runner.stop(id)  // no pid yet
        proc.started.countDown()
        assertTrue("never killed", proc.killed.await(5, TimeUnit.SECONDS))
        assertEquals(77, proc.killedPid)
        awaitIdle(runner)
        assertEquals("Stopped", store.get(id)!!.messages.last().text)
    }

    @Test fun deletingARunningChatForgetsItsSessionAfterExit() {
        val proc = SlowProc()
        val backend = FakeBackend(proc)
        val store = store()
        val runner = Runner(store, backend, holdFor = 0)
        val id = runner.send(null, "hi")!!
        proc.started.countDown()
        val until = System.currentTimeMillis() + 5000
        while (runner.running.value[id]?.pid == null && System.currentTimeMillis() < until) Thread.sleep(10)
        runner.delete(id)
        awaitIdle(runner)
        assertEquals(null, store.get(id))
        assertEquals(listOf("0b0b0b0b-0000-0000-0000-000000000000"), synchronized(backend.forgotten) { backend.forgotten.toList() })
    }

    @Test fun aPrewarmedProcessTakesTheFirstMessage() {
        val backend = CountingBackend()
        val runner = Runner(store(), backend, holdFor = 0)
        runner.prewarm(null)
        awaitStarts(backend, 1)
        runner.send(null, "hi")
        awaitIdle(runner)
        assertEquals(1, backend.started.size)
        assertEquals(listOf(userLine("hi")), backend.started[0].writes.toList())
    }

    @Test fun aDroppedWarmProcessIsClosedAndNotReused() {
        val backend = CountingBackend()
        val runner = Runner(store(), backend, holdFor = 0)
        runner.prewarm(null)
        awaitStarts(backend, 1)
        runner.dropWarm()
        assertTrue("warm process not closed", backend.started[0].closed.await(5, TimeUnit.SECONDS))
        runner.send(null, "hi")
        awaitIdle(runner)
        assertEquals(2, backend.started.size)
        assertTrue(backend.started[0].writes.isEmpty())
        assertFalse(backend.started[1].writes.isEmpty())
    }

    /** phone-agent ends a warm run that got no message within FIRST_WAIT (e.g. while the app was frozen). */
    @Test fun aWarmProcessThatExitedIsNotGivenTheMessage() {
        val backend = CountingBackend()
        val runner = Runner(store(), backend, holdFor = 0)
        runner.prewarm(null)
        awaitStarts(backend, 1)
        backend.started[0].up = false
        runner.send(null, "hi")
        awaitIdle(runner)
        assertEquals(2, backend.started.size)
        assertTrue(backend.started[0].writes.isEmpty())
        assertEquals(listOf(userLine("hi")), backend.started[1].writes.toList())
    }

    @Test fun anExitedWarmProcessIsReplacedOnTheNextPrewarm() {
        val backend = CountingBackend()
        val runner = Runner(store(), backend, holdFor = 0)
        runner.prewarm(null)
        awaitStarts(backend, 1)
        Thread.sleep(100)  // let it register as the warm process
        backend.started[0].up = false
        runner.prewarm(null)
        awaitStarts(backend, 2)
    }

    /** Answers every message it gets ("ok N" after N messages) and exits once stdin is closed. */
    private class MultiProc : Proc {
        val writes: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val closed = CountDownLatch(1)
        private val inbox = LinkedBlockingQueue<String>()
        private val eof = String()

        override fun write(line: String) { writes += line; inbox.put(line) }
        override fun closeStdin() { closed.countDown(); inbox.put(eof) }
        override fun waitFor() = 0
        override fun kill(pid: Int) = closeStdin()
        override fun readLines(onLine: (String) -> Unit) {
            onLine("""{"type":"agent","pid":79,"display":7,"port":8707,"token":"t"}""")
            while (true) {
                val m = inbox.poll(5, TimeUnit.SECONDS) ?: return
                if (m === eof) return
                onLine("""{"type":"result","subtype":"success","is_error":false,"result":"ok ${writes.size}","session_id":"0d0d0d0d-0000-0000-0000-000000000000"}""")
            }
        }
    }

    /** Hands out a fresh [MultiProc] per start, each once [go] is open (a run still starting until then). */
    private class MultiBackend(val go: CountDownLatch = CountDownLatch(0)) : Backend {
        val started: MutableList<MultiProc> = Collections.synchronizedList(mutableListOf())
        val sessions: MutableList<String?> = Collections.synchronizedList(mutableListOf())
        override fun start(sessionId: String?, model: String, engine: Engine): Proc {
            go.await(5, TimeUnit.SECONDS)
            return MultiProc().also { started += it; sessions += sessionId }
        }
        override fun forget(sessionId: String, engine: Engine) {}
    }

    private fun until(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 5000
        while (!cond() && System.currentTimeMillis() < end) Thread.sleep(10)
        assertTrue(what, cond())
    }

    /** Answered: not running any more, but its screen is still up. */
    private fun answered(runner: Runner, id: String) =
        id !in runner.running.value && id in runner.screens.value && runner.screens.value[id]?.port != null

    @Test fun aReplyWhileTheScreenIsHeldGoesToTheSameRun() {
        val backend = MultiBackend()
        val store = store()
        val runner = Runner(store, backend, holdFor = 60_000)
        val id = runner.send(null, "one")!!
        until("first answer") { answered(runner, id) && store.get(id)!!.messages.last().text == "ok 1" }
        assertFalse(store.get(id)!!.running)
        assertTrue(runner.screens.value[id]!!.closesAt != null)
        runner.send(id, "two")
        until("second answer") { answered(runner, id) && store.get(id)!!.messages.last().text == "ok 2" }
        assertEquals(1, backend.started.size)
        assertEquals(listOf(userLine("one"), userLine("two")), backend.started[0].writes.toList())
        runner.closeScreens()
        until("screen closed") { runner.screens.value.isEmpty() }
    }

    @Test fun anAnswerWithNothingOnTheScreenEndsTheRun() {
        val backend = MultiBackend()
        val store = store()
        val runner = Runner(store, backend, holdFor = 60_000, inUse = { _, _ -> false })
        val id = runner.send(null, "one")!!
        until("run ended") { runner.screens.value.isEmpty() && store.get(id)!!.messages.last().text == "ok 1" }
        assertTrue(backend.started[0].closed.count == 0L)
        runner.send(id, "two")
        until("second run") { backend.started.size == 2 }
        assertEquals(listOf(null, "0d0d0d0d-0000-0000-0000-000000000000"), backend.sessions.toList())
    }

    @Test fun aHeldScreenClosesByItselfAndALaterReplyResumes() {
        val backend = MultiBackend()
        val store = store()
        val runner = Runner(store, backend, holdFor = 200)
        val id = runner.send(null, "one")!!
        until("screen closed") { runner.screens.value.isEmpty() }
        assertTrue(backend.started[0].closed.count == 0L)
        assertEquals("ok 1", store.get(id)!!.messages.last().text)
        runner.send(id, "two")
        until("second answer") { store.get(id)!!.messages.last().text == "ok 1" && backend.started.size == 2 }
        assertEquals(listOf(null, "0d0d0d0d-0000-0000-0000-000000000000"), backend.sessions.toList())
    }

    @Test fun stoppingAHeldScreenClosesItWithoutStopped() {
        val backend = MultiBackend()
        val store = store()
        val runner = Runner(store, backend, holdFor = 60_000)
        val id = runner.send(null, "one")!!
        until("answer") { answered(runner, id) }
        runner.stop(id)
        until("screen closed") { runner.screens.value.isEmpty() }
        assertEquals(Kind.AGENT, store.get(id)!!.messages.last().kind)
        assertEquals("ok 1", store.get(id)!!.messages.last().text)
    }

    @Test fun aWatchedScreenStaysUntilWatchCloses() {
        val backend = MultiBackend()
        val runner = Runner(store(), backend, holdFor = 200)
        val id = runner.send(null, "one")!!
        runner.watching(id, true)
        until("answer") { answered(runner, id) }
        assertEquals(null, runner.screens.value[id]?.closesAt)
        Thread.sleep(600)
        assertTrue("closed while watched", id in runner.screens.value)
        runner.watching(id, false)
        until("screen closed") { runner.screens.value.isEmpty() }
    }

    @Test fun theNextMessageSaysTheUserTookOver() {
        val backend = MultiBackend()
        val runner = Runner(store(), backend, holdFor = 60_000)
        val id = runner.send(null, "one")!!
        until("answer") { answered(runner, id) }
        runner.touched(id)
        runner.send(id, "done, submit it")
        runner.send(id, "thanks")
        until("all written") { backend.started[0].writes.size == 3 }
        assertEquals(userLine(TOOK_OVER + "done, submit it"), backend.started[0].writes[1])
        assertEquals(userLine("thanks"), backend.started[0].writes[2])
        runner.closeScreens()
    }

    @Test fun aMessageSentWhileTheRunStartsGoesToItAfterTheFirst() {
        val backend = MultiBackend(go = CountDownLatch(1))
        val store = store()
        val runner = Runner(store, backend, holdFor = 60_000)
        val id = runner.send(null, "one")!!
        runner.send(id, "two")  // no process yet
        backend.go.countDown()
        until("both answered") { answered(runner, id) && store.get(id)!!.messages.last().text == "ok 2" }
        assertEquals(1, backend.started.size)
        assertEquals(listOf(userLine("one"), userLine("two")), backend.started[0].writes.toList())
        runner.closeScreens()
    }

    /**
     * Prints [before] after its agent line, then answers each message with "re: " its text; once stdin is closed it
     * exits only when [exit] opens (phone-agent's cleanup).
     */
    private class LingerProc(private val answer: CountDownLatch, private val before: List<String> = emptyList()) : Proc {
        val writes: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val exit = CountDownLatch(1)
        @Volatile var killedPid = -1
        private val inbox = LinkedBlockingQueue<String>()
        private val eof = String()

        override fun write(line: String) { writes += line; inbox.put(line) }
        override fun closeStdin() { inbox.put(eof) }
        override fun waitFor() = 0
        override fun kill(pid: Int) { killedPid = pid; exit.countDown() }
        override fun readLines(onLine: (String) -> Unit) {
            onLine("""{"type":"agent","pid":80,"display":8,"port":8708,"token":"t"}""")
            before.forEach(onLine)
            while (true) {
                val m = inbox.poll(5, TimeUnit.SECONDS) ?: return
                if (m === eof) break
                answer.await(5, TimeUnit.SECONDS)
                val text = JSONObject(m).getJSONObject("message").getString("content")
                onLine(JSONObject().put("type", "result").put("is_error", false).put("result", "re: $text").toString())
            }
            exit.await(5, TimeUnit.SECONDS)
        }
    }

    /** Hands out a [LingerProc] per start; the nth answers once [answers]`[n]` opens (at once if there's none). */
    private class LingerBackend(private vararg val answers: CountDownLatch) : Backend {
        val started: MutableList<LingerProc> = Collections.synchronizedList(mutableListOf())
        override fun start(sessionId: String?, model: String, engine: Engine): Proc =
            LingerProc(answers.getOrElse(started.size) { CountDownLatch(0) }).also { started += it }
        override fun forget(sessionId: String, engine: Engine) {}
    }

    @Test fun anAnswerThatEndsTheRunEndsTheWorkWhileItsAgentExits() {
        val backend = LingerBackend()
        val store = store()
        val runner = Runner(store, backend, holdFor = 60_000, inUse = { _, _ -> false })
        val id = runner.send(null, "one")!!
        until("answered") { id !in runner.running.value && store.get(id)!!.messages.last().text == "re: one" }
        assertFalse(store.get(id)!!.running)
        assertTrue("left before its agent exited", id in runner.screens.value)
        assertNull(runner.screens.value[id]!!.port)  // its screen is going
        runner.stop(id)  // nothing to stop: no "Stopped" after the answer, and no kill
        backend.started[0].exit.countDown()
        until("exited") { runner.screens.value.isEmpty() }
        assertEquals("re: one", store.get(id)!!.messages.last().text)
        assertEquals(-1, backend.started[0].killedPid)
    }

    @Test fun aMessageSentWhileTheAgentExitsStartsTheNextRunWorking() {
        val second = CountDownLatch(1)
        val backend = LingerBackend(CountDownLatch(0), second)
        val store = store()
        val runner = Runner(store, backend, holdFor = 60_000, inUse = { _, _ -> false })
        val id = runner.send(null, "one")!!
        until("answered") { id !in runner.running.value && store.get(id)!!.messages.last().text == "re: one" }
        runner.send(id, "two")  // queued: the agent is exiting
        assertTrue(id in runner.running.value)
        backend.started[0].exit.countDown()
        until("second run") { backend.started.size == 2 && backend.started[1].writes.isNotEmpty() }
        assertEquals(listOf(userLine("two")), backend.started[1].writes.toList())
        assertTrue(id in runner.running.value)
        assertTrue(store.get(id)!!.running)
        second.countDown()
        until("second answer") { id !in runner.running.value && store.get(id)!!.messages.last().text == "re: two" }
        backend.started[1].exit.countDown()
        until("exited") { runner.screens.value.isEmpty() }
        assertFalse(store.get(id)!!.running)
    }

    @Test fun aMessageToADeletedChatGoesNowhere() {
        val backend = MultiBackend()
        val store = store()
        val runner = Runner(store, backend, holdFor = 0)
        val id = runner.send(null, "one")!!
        until("ended") { runner.screens.value.isEmpty() }
        runner.delete(id)
        assertNull(runner.send(id, "two"))
        assertTrue(store.convs.value.isEmpty())
        assertEquals(1, backend.started.size)
    }

    /** Prints [lines] after its agent line, then answers and waits for stdin to close. */
    private class ScriptProc(private val lines: List<String>) : Proc {
        val closed = CountDownLatch(1)
        override fun write(line: String) {}
        override fun closeStdin() = closed.countDown()
        override fun waitFor() = 0
        override fun kill(pid: Int) = closed.countDown()
        override fun readLines(onLine: (String) -> Unit) {
            onLine("""{"type":"agent","pid":81,"display":9,"port":8709,"token":"t"}""")
            lines.forEach(onLine)
            onLine("""{"type":"result","is_error":false,"result":"done"}""")
            closed.await(5, TimeUnit.SECONDS)
        }
    }

    private fun show(path: String, id: String) = """{"type":"droid","op":"show","path":"$path","id":"$id"}"""

    @Test fun aBugWhileHandlingARunFailsItsChatNotTheApp() {
        val proc = ScriptProc(listOf(noteWait, show("/root/a.html", "p1")))
        val backend = object : Backend {
            override fun start(sessionId: String?, model: String, engine: Engine): Proc = proc
            override fun forget(sessionId: String, engine: Engine) {}
            override fun fetch(path: String, to: File): Boolean = throw IllegalStateException("boom")
        }
        val store = store()
        val runner = Runner(store, backend, holdFor = 0)
        val id = runner.send(null, "hi")!!
        until("failed") { runner.screens.value.isEmpty() && !store.get(id)!!.running }
        assertEquals("Failed: boom", store.get(id)!!.messages.last().text)
        assertTrue("process left up", proc.closed.await(5, TimeUnit.SECONDS))
        assertTrue("still waits on the owner", runner.noteWaits.isEmpty())
    }

    @Test fun pagesShownUnderOneIdLandInTheOrderShown() {
        val proc = ScriptProc(listOf(show("/a", "p1"), show("/b", "p1")))
        val backend = object : Backend {
            override fun start(sessionId: String?, model: String, engine: Engine): Proc = proc
            override fun forget(sessionId: String, engine: Engine) {}
            override fun fetch(path: String, to: File): Boolean {
                if (path == "/a") Thread.sleep(300)  // the first one slow to copy
                to.parentFile?.mkdirs(); to.writeText("<p>$path</p>"); return true
            }
        }
        val dir = File.createTempFile("shown", "").also { it.delete(); it.deleteOnExit() }
        val store = store()
        val runner = Runner(store, backend, holdFor = 0, shown = { File(dir, it) })
        val id = runner.send(null, "hi")!!
        until("ended") { runner.screens.value.isEmpty() }
        assertEquals(listOf("<p>/b</p>"), store.get(id)!!.messages.filter { it.kind == Kind.PAGE }.map { it.text })
        dir.deleteRecursively()
    }

    /** `droid notify --id n1 --wait`: the agent's command waits on the owner. */
    private val noteWait = """{"type":"droid","op":"notify","id":"n1","wait":true}"""

    @Test fun aMessageSentWhileAStoppedRunEndsIsATurnOfItsOwn() {
        val slow = SlowProc()
        val then = MultiBackend()
        val backend = object : Backend {
            var n = 0
            override fun start(sessionId: String?, model: String, engine: Engine): Proc = if (n++ == 0) slow else then.start(sessionId, model, engine)
            override fun forget(sessionId: String, engine: Engine) {}
        }
        val store = store()
        val runner = Runner(store, backend, holdFor = 0)
        val id = runner.send(null, "one")!!
        runner.stop(id)  // still starting: it ends once it reports its pid
        runner.send(id, "two")
        slow.started.countDown()
        until("answered") { id !in runner.running.value && store.get(id)!!.messages.last().kind == Kind.AGENT }
        val messages = store.get(id)!!.messages
        assertEquals(listOf("one", "Stopped", "two", "ok 1"), messages.map { it.text })
        assertEquals(Outcome.DONE, lastOutcome(messages, false))
    }

    @Test fun anAgentThatHangsAfterItsAnswerIsKilled() {
        val backend = LingerBackend()
        val store = store()
        val runner = Runner(store, backend, holdFor = 60_000, inUse = { _, _ -> false }, exitWithin = 200)
        val id = runner.send(null, "one")!!
        until("killed") { backend.started.firstOrNull()?.killedPid == 80 }
        until("exited") { runner.screens.value.isEmpty() }
        assertEquals("re: one", store.get(id)!!.messages.last().text)
    }

    /** Answers its message, then goes on by itself (to a message it took mid-turn): a step, and [after] ms on an answer. */
    private class GoOnProc(private val after: Long) : Proc {
        private val wrote = CountDownLatch(1)
        @Volatile var killedPid = -1
        override fun write(line: String) = wrote.countDown()
        override fun closeStdin() {}
        override fun waitFor() = 0
        override fun kill(pid: Int) { killedPid = pid }
        override fun readLines(onLine: (String) -> Unit) {
            onLine("""{"type":"agent","pid":82,"display":10,"port":8710,"token":"t"}""")
            wrote.await(5, TimeUnit.SECONDS)
            onLine("""{"type":"result","is_error":false,"result":"first"}""")
            onLine("""{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Bash","input":{"command":"ls","description":"Looking"}}]}}""")
            Thread.sleep(after)
            onLine("""{"type":"result","is_error":false,"result":"second"}""")
        }
    }

    @Test fun anAgentThatGoesOnAfterItsAnswerWorksAgainAndIsntKilled() {
        val proc = GoOnProc(after = 600)
        val backend = object : Backend {
            override fun start(sessionId: String?, model: String, engine: Engine): Proc = proc
            override fun forget(sessionId: String, engine: Engine) {}
        }
        val store = store()
        val runner = Runner(store, backend, holdFor = 60_000, inUse = { _, _ -> false }, exitWithin = 200)
        val id = runner.send(null, "one")!!
        until("working again") { id in runner.running.value && store.get(id)!!.messages.any { it.kind == Kind.STEP } }
        until("exited") { runner.screens.value.isEmpty() }
        assertEquals("second", store.get(id)!!.messages.last().text)
        assertEquals(-1, proc.killedPid)
    }

    @Test fun whatAProcessWaitedOnEndsWithIt() {
        val first = LingerProc(CountDownLatch(0), listOf(noteWait, """{"type":"droid","op":"show","path":"/p.html","id":"p1","wait":true}"""))
        val second = LingerProc(CountDownLatch(0))
        val procs = ArrayDeque(listOf(first, second))
        val backend = object : Backend {
            override fun start(sessionId: String?, model: String, engine: Engine): Proc = synchronized(procs) { procs.removeFirst() }
            override fun forget(sessionId: String, engine: Engine) {}
            override fun fetch(path: String, to: File): Boolean { to.parentFile?.mkdirs(); to.writeText("<p>page</p>"); return true }
        }
        val dir = File.createTempFile("shown", "").also { it.delete(); it.deleteOnExit() }
        val store = store()
        val runner = Runner(store, backend, holdFor = 60_000, inUse = { _, _ -> false }, shown = { File(dir, it) })
        val id = runner.send(null, "one")!!
        until("answered") { id !in runner.running.value && store.get(id)!!.messages.last().text == "re: one" }
        assertEquals(setOf("$id/n1"), runner.noteWaits.toSet())
        assertTrue(PageInfo.of(store.get(id)!!.page("p1")!!).wait)
        runner.send(id, "two")  // queued: the agent is exiting
        first.exit.countDown()
        until("second run") { second.writes.isNotEmpty() }
        assertTrue(runner.noteWaits.isEmpty())
        assertFalse(PageInfo.of(store.get(id)!!.page("p1")!!).wait)
        until("second answer") { store.get(id)!!.messages.last().text == "re: two" }
        second.exit.countDown()
        until("ended") { runner.screens.value.isEmpty() }
        dir.deleteRecursively()
    }

    @Test fun anAnswerIsAppliedOnceWhenAMessageComesAsItLands() {
        val answer = CountDownLatch(1)
        val backend = LingerBackend(answer)
        val store = store()
        lateinit var runner: Runner
        lateinit var id: String
        // Asking whether an app is on the screen takes a moment: the owner sends then.
        runner = Runner(store, backend, holdFor = 60_000, inUse = { _, _ ->
            if (backend.started[0].writes.size == 1) runner.send(id, "two")
            false
        })
        id = runner.send(null, "one")!!
        answer.countDown()
        until("both answered") { id !in runner.running.value && store.get(id)!!.messages.last().text == "re: two" }
        assertEquals(listOf("one", "re: one", "two", "re: two"), store.get(id)!!.messages.map { it.text })
        backend.started[0].exit.countDown()
    }

    @Test fun aChatDeletedWhileItsRunStartsForgetsItsSessionIfTheStartFails() {
        val starting = CountDownLatch(1)
        val go = CountDownLatch(1)
        val forgotten: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val backend = object : Backend {
            var n = 0
            override fun start(sessionId: String?, model: String, engine: Engine): Proc {
                if (n++ == 0) return MultiProc()
                starting.countDown(); go.await(5, TimeUnit.SECONDS)
                throw java.io.IOException("no Linux")
            }
            override fun forget(sessionId: String, engine: Engine) { forgotten += sessionId }
        }
        val runner = Runner(store(), backend, holdFor = 0)
        val id = runner.send(null, "one")!!
        until("first run ended") { runner.screens.value.isEmpty() }
        runner.send(id, "two")
        assertTrue(starting.await(5, TimeUnit.SECONDS))
        runner.delete(id)
        go.countDown()
        until("forgotten") { forgotten.isNotEmpty() }
        assertEquals(listOf("0d0d0d0d-0000-0000-0000-000000000000"), forgotten.toList())
    }

    @Test fun aChatDeletedBeforeItsRunFailsForgetsItsSession() {
        val deleted = CountDownLatch(1)
        val session = """{"type":"system","subtype":"init","session_id":"0e0e0e0e-0000-0000-0000-000000000000"}"""
        val proc = ScriptProc(listOf(session, show("/a", "p1")))
        val forgotten: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val backend = object : Backend {
            override fun start(sessionId: String?, model: String, engine: Engine): Proc = proc
            override fun forget(sessionId: String, engine: Engine) { forgotten += sessionId }
            override fun fetch(path: String, to: File): Boolean { deleted.await(5, TimeUnit.SECONDS); throw IllegalStateException("boom") }
        }
        val store = store()
        val runner = Runner(store, backend, holdFor = 0)
        val id = runner.send(null, "hi")!!
        until("session") { store.get(id)?.sessionId != null }
        runner.delete(id)
        deleted.countDown()
        until("forgotten") { forgotten.isNotEmpty() }
        assertEquals(listOf("0e0e0e0e-0000-0000-0000-000000000000"), forgotten.toList())
    }

    @Test fun writingToARunNeverWaitsOnIt() {
        // stdin as Droid's helper gives it without root: a socket, which may take a while
        val open = CountDownLatch(1)
        val got = ByteArrayOutputStream()
        val closed = CountDownLatch(1)
        val p = object : Process() {
            override fun getOutputStream(): OutputStream = object : OutputStream() {
                override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
                override fun write(b: ByteArray, off: Int, len: Int) { open.await(); synchronized(got) { got.write(b, off, len) } }
                override fun close() { open.await(); closed.countDown() }
            }
            override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
            override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
            override fun waitFor() = 0
            override fun exitValue() = 0
            override fun destroy() {}
        }
        val proc = HostProc(p, RootHost)
        val t = System.currentTimeMillis()
        proc.write("one"); proc.write("two"); proc.closeStdin()
        assertTrue("the caller waited", System.currentTimeMillis() - t < 1000)
        open.countDown()
        assertTrue("never closed", closed.await(5, TimeUnit.SECONDS))
        assertEquals("one\ntwo\n", synchronized(got) { got.toString(Charsets.UTF_8) })
    }
}
