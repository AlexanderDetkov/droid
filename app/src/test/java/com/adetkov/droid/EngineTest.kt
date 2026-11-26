package com.adetkov.droid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class EngineTest {
    private val uuid = "01a1ddd8-1dbf-7762-afe9-090ed9db7961"
    private val ses = "ses_e22272822ffepBB7CC0N4KibaD"

    @Test fun claudeCommandIsUnchanged() {
        assertEquals("DROID_APK='/a.apk' ${RootHost.base}/enter.sh phone-agent --resume $uuid --model opus", startCommand("/a.apk", uuid, "opus", Engine.CLAUDE))
        assertEquals("DROID_APK='/a.apk' ${RootHost.base}/enter.sh phone-agent", startCommand("/a.apk", null, "", Engine.CLAUDE))
    }

    @Test fun otherEnginesPassTheirName() {
        assertEquals("DROID_APK='/a.apk' ${RootHost.base}/enter.sh phone-agent --engine codex --resume $uuid", startCommand("/a.apk", uuid, "", Engine.CODEX))
        assertEquals("DROID_APK='/a.apk' ${RootHost.base}/enter.sh phone-agent --engine opencode --resume $ses --model anthropic/claude-sonnet-4-5",
            startCommand("/a.apk", ses, "anthropic/claude-sonnet-4-5", Engine.OPENCODE))
    }

    @Test fun openCodesDefaultIsLeftToTheAdapter() {
        // droid-engine picks a free model that sees images from OpenCode's own list, each run (they come and go)
        assertEquals("DROID_APK='/a' ${RootHost.base}/enter.sh phone-agent --engine opencode", startCommand("/a", null, "", Engine.OPENCODE))
    }

    @Test fun sessionIdsAreCheckedPerEngineBeforeReachingAShell() {
        // An OpenCode id isn't a Claude session, and a model name can't carry a shell command.
        assertEquals("DROID_APK='/a' ${RootHost.base}/enter.sh phone-agent", startCommand("/a", ses, "", Engine.CLAUDE))
        assertEquals("DROID_APK='/a' ${RootHost.base}/enter.sh phone-agent --engine codex", startCommand("/a", "x; reboot", "", Engine.CODEX))
        assertEquals("DROID_APK='/a' ${RootHost.base}/enter.sh phone-agent --engine codex", startCommand("/a", null, "m; reboot", Engine.CODEX))
        assertEquals("DROID_APK='/a' ${RootHost.base}/enter.sh phone-agent", startCommand("/a", null, "gpt-5", Engine.CLAUDE))
    }

    @Test fun devIsWritableOnlyWhenTheOwnerSaysSo() {
        assertEquals("DROID_DEV=rw DROID_APK='/a' ${RootHost.base}/enter.sh phone-agent", startCommand("/a", null, "", Engine.CLAUDE, devWritable = true))
        assertFalse("DROID_DEV" in startCommand("/a", null, "", Engine.CLAUDE))
    }

    @Test fun shellHostCommandsUseItsOwnFolder() {
        assertEquals("DROID_APK='/a' $SHELL_BASE/enter.sh phone-agent", startCommand("/a", null, "", Engine.CLAUDE, SHELL_BASE))
        assertTrue(forgetCommand(uuid, Engine.CLAUDE, SHELL_BASE)!!.startsWith("rm -rf $SHELL_BASE/rootfs/root/.claude/projects/-root/$uuid.jsonl"))
        assertEquals("$SHELL_BASE/enter.sh opencode session delete $ses", forgetCommand(ses, Engine.OPENCODE, SHELL_BASE))
    }

    @Test fun forgetFindsEachEnginesSession() {
        assertTrue(forgetCommand(uuid, Engine.CLAUDE)!!.contains("/root/.claude/projects/-root/$uuid.jsonl"))
        assertTrue(forgetCommand(uuid, Engine.CODEX)!!.contains("/root/.codex/sessions"))
        assertTrue(forgetCommand(ses, Engine.OPENCODE)!!.endsWith("opencode session delete $ses"))
        assertNull(forgetCommand("../../x", Engine.CLAUDE))
        assertNull(forgetCommand("$ses; reboot", Engine.OPENCODE))
        assertNull(forgetCommand(ses, Engine.CODEX))
    }

    @Test fun modelNames() {
        assertTrue(Engine.OPENCODE.validModel("anthropic/claude-sonnet-4-5"))
        assertTrue(Engine.OPENCODE.validModel("ollama/qwen3:30b"))
        assertFalse(Engine.OPENCODE.validModel("a b"))
        assertFalse(Engine.CLAUDE.validModel("gpt-5"))
        assertTrue(Engine.CLAUDE.validModel("opus"))
        assertEquals("openai/gpt-5", Engine.OPENCODE.model("openai/gpt-5").label)
        assertEquals("Default", Engine.CLAUDE.model("gone").label)
    }

    @Test fun deviceAuthOutput() {
        // Recorded from `codex login --device-auth` 0.169.0 (colors and all); the code in it has expired.
        val out = File("src/test/resources/engines/codex-device-auth.txt").readText()
        val (url, code) = parseDeviceAuth(out)
        assertEquals("https://auth.openai.com/codex/device", url)
        assertTrue(code!!.matches(Regex("[A-Z0-9]{4}-[A-Z0-9]{4,5}")))
        // Before the code is printed there is a page but no code yet.
        assertEquals("https://auth.openai.com/codex/device" to null, parseDeviceAuth(out.substringBefore("2. Enter")))
    }

    @Test fun conversationsRememberTheirEngine() {
        val f = File.createTempFile("convs", ".json")
        val s = Store(f)
        val c = s.create("hello", Engine.OPENCODE)
        assertEquals(Engine.OPENCODE, s.get(c.id)!!.engine)
        assertEquals(Engine.CLAUDE, s.create("old style").engine)
        f.delete()
    }
}

/** The adapter's output for recorded Codex and OpenCode runs, through the app's own stream parser. */
class AdapterTest {
    private val setup = File("src/main/assets/setup/bin")
    private val samples = File("src/test/resources/engines")

    private fun python(vararg args: String): Pair<Int, String>? = try {
        val p = ProcessBuilder("python3", "-I", *args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor() to out
    } catch (_: java.io.IOException) { null }

    private fun replay(engine: String, sample: String): Conv {
        val (code, out) = python(File(setup, "droid-engine").path, "--replay", engine, File(samples, sample).path)
            .also { assumeTrue("python3 not installed", it != null) }!!
        assertEquals(out, 0, code)
        return out.lines().filter { it.isNotBlank() }.fold(Conv("c", "t", createdAt = 0).add(Kind.USER, "hi", 0)) { c, l ->
            parseLine(l).fold(c) { acc, ev -> acc.apply(ev, 1) }
        }
    }

    @Test fun codexRun() {
        val c = replay("codex", "codex-new.jsonl")
        assertEquals("01a1ddd8-1dbf-7762-afe9-090ed9db7961", c.sessionId)
        assertEquals(listOf(Kind.USER, Kind.NOTE, Kind.STEP, Kind.AGENT), c.messages.map { it.kind })
        assertEquals("ls", c.messages[2].text)
        assertTrue(c.messages.last().text.contains("PINEAPPLE-7319"))
    }

    @Test fun codexFailure() {
        val c = replay("codex", "codex-error.jsonl")
        assertEquals(Kind.STATUS, c.messages.last().kind)
        assertTrue(c.messages.last().text.startsWith("Error: The 'nonexistent-model-xyz' model"))
    }

    @Test fun openCodeRun() {
        val c = replay("opencode", "opencode-new.jsonl")
        assertEquals("ses_e22272822ffepBB7CC0N4KibaD", c.sessionId)
        assertEquals(listOf("ls", "Looking at the screen"), c.messages.filter { it.kind == Kind.STEP }.map { it.text })
        assertEquals(Kind.AGENT, c.messages.last().kind)
    }

    @Test fun openCodeFailure() {
        val c = replay("opencode", "opencode-error.jsonl")
        assertEquals("Error: Model not found: opencode/nonexistent-model.", c.messages.last().text)
    }

    @Test fun adapterScriptTests() {
        val (code, out) = python("src/test/python/test_droid_engine.py").also { assumeTrue("python3 not installed", it != null) }!!
        assertEquals(out, 0, code)
    }
}
