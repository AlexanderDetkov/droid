package com.adetkov.droid

import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.util.concurrent.LinkedBlockingQueue

/** Debug builds: on an emulator replay a canned stream; on a real phone (or a "real" emulator, below) the real thing. */
fun defaultBackend(app: Context, host: () -> Host): Backend =
    if (isEmulator) FakeBackend(File(app.filesDir, "fake-screen")) else HostBackend(app, host)

/**
 * Debug builds: on an emulator setup counts as done and runs are canned, so the UI can be worked on without a phone;
 * `adb shell setprop debug.droid.real 1` (then restart Droid) makes an emulator behave like a phone without root.
 */
val isEmulator = (Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish")) &&
    runCatching { ProcessBuilder("getprop", "debug.droid.real").start().inputStream.bufferedReader().readText().trim() }.getOrNull() != "1"

/**
 * Debug builds: an assist intent may carry a 16 kHz WAV (test_wav) to hear instead of the microphone. Only a file in
 * Droid's own files dir counts, which other apps can't write to (put it there with `adb shell run-as`).
 */
fun testWav(ctx: Context, i: Intent): String? =
    i.getStringExtra("test_wav")?.let(::File)?.canonicalFile?.takeIf { it.parentFile == ctx.filesDir.canonicalFile }?.path

/** Debug builds: a launch intent may carry test_enroll, comma-separated 16 kHz WAVs in Droid's files dir, to enroll voice match from. */
fun testEnrollWavs(ctx: Context, i: Intent): List<String>? = i.getStringExtra("test_enroll")?.split(',')
    ?.map { File(it).canonicalFile }?.takeIf { fs -> fs.size >= 2 && fs.all { it.parentFile == ctx.filesDir.canonicalFile } }?.map { it.path }

/** [screens]: what FakeScreen shows, and an optional scripted turn. */
class FakeBackend(private val screens: File) : Backend {
    /** Answers to pages (droid show --wait), by page id, for the scripted agent waiting on them. */
    private val replies = java.util.concurrent.ConcurrentHashMap<String, String>()
    override fun start(sessionId: String?, model: String, engine: Engine): Proc =
        FakeProc(sessionId ?: "a7542358-62cc-4a9a-b240-5554ffd5c592", screens, replies)
    override fun reply(pageId: String, json: String) { replies[pageId] = json }
    override val apps: String get() = File(screens, "apps").path
    /** droid.run: on the device itself, as the app. */
    override fun run(command: String, timeoutS: Int): Pair<String, Int> {
        val p = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        return out to p.waitFor()
    }
    override fun forget(sessionId: String, engine: Engine) {}
    // Texts by path; the memory is a small sample of Claude Code's notes and index.
    private val docs = java.util.concurrent.ConcurrentHashMap(mapOf(
        Doc.INSTRUCTIONS.path to "Ask me before sending anything.",
        "$MEMORY_DIR/MEMORY.md" to "- [His writing style](his-writing-style.md) — terse, short sentences; show drafts first\n" +
            "- [Hotel refund](hotel-refund.md) — Casa do Limoeiro charged the folio twice; refund asked 2026-11-18\n",
        "$MEMORY_DIR/his-writing-style.md" to "---\nname: his-writing-style\ndescription: terse, short sentences\nmetadata:\n  type: feedback\n---\n\n" +
            "When writing as him, keep it terse: short sentences, no polished prose. Show him the draft before sending.\n",
        "$MEMORY_DIR/hotel-refund.md" to "---\nname: hotel-refund\ndescription: duplicate charge\nmetadata:\n  type: project\n---\n\n" +
            "Casa do Limoeiro charged USD 628.71 twice on 20 September. Refund requested from Ines on 2026-11-18; no reply yet.\n",
    ))
    override fun read(doc: Doc) = docs[doc.path].orEmpty()
    override fun write(doc: Doc, text: String) { docs[doc.path] = text }
    override fun memory() = notes(docs["$MEMORY_DIR/MEMORY.md"].orEmpty(), docs.keys.filter { it.startsWith("$MEMORY_DIR/") }.map { it.substringAfterLast('/') })
    override fun delete(note: Doc) {
        docs.remove(note.path)
        val index = "$MEMORY_DIR/MEMORY.md"
        docs[index] = docs[index].orEmpty().lines().filterNot { "(${note.path.substringAfterLast('/')})" in it }.joinToString("\n")
    }
    /** The fake agent shows files on the device itself (in its scripts: the app's own files/fake-screen). */
    override fun fetch(path: String, to: File): Boolean =
        File(path).takeIf { it.isFile }?.let { to.parentFile?.mkdirs(); it.copyTo(to, overwrite = true); true } ?: false
}

/** Answers each stdin message with a step, an answer and a result; exits on stdin EOF or kill. */
private class FakeProc(private val sid: String, private val screens: File, private val replies: MutableMap<String, String>) : Proc {
    private val inbox = LinkedBlockingQueue<String>()   // EOF marks end of stdin
    @Volatile private var killed = false
    private val EOF = String()

    override fun write(line: String) { inbox.put(line) }
    override fun closeStdin() { inbox.put(EOF) }
    override fun kill(pid: Int) { killed = true; inbox.put(EOF) }
    override fun waitFor(): Int = if (killed) 130 else 0

    override fun readLines(onLine: (String) -> Unit) {
        val screen = FakeScreen.start(screens)
        try { converse(screen, onLine) } finally { screen.close() }
    }

    private var turn = 0

    private fun converse(screen: FakeScreen, onLine: (String) -> Unit) {
        onLine("""{"type":"agent","pid":4242,"display":16,"port":${screen.port},"token":"${screen.token}"}""")
        onLine("not json")
        onLine("""{"type":"system","subtype":"init","session_id":"$sid"}""")
        while (!killed) {
            val msg = inbox.take()
            if (msg === EOF) return
            val text = JSONObject(msg).getJSONObject("message").getString("content")
            android.util.Log.i("FakeAgent", text)  // what the agent would read: see it with `adb logcat -s FakeAgent`
            val script = FakeScreen.script(screens, ++turn)
            if (script == null) canned(text, onLine) else for (l in script) {
                // A scripted turn (FakeScreen's script.txt): steps, narration and the answer, each after its delay.
                Thread.sleep(l.delayMs)
                if (killed) return
                l.frame?.let { screen.frame = it }
                val t = JSONObject.quote(l.text)
                when (l.kind) {
                    '>' -> onLine("""{"type":"assistant","message":{"content":[{"type":"text","text":$t}]}}""")
                    '!' -> onLine("""{"type":"result","subtype":"error","is_error":true,"result":$t,"session_id":"$sid"}""")
                    '@' -> onLine(l.text)
                    '?' -> {
                        // the agent's `droid show --wait`: until the owner answers the page
                        while (!killed && replies[l.text] == null) Thread.sleep(200)
                        val r = JSONObject.quote("They answered: " + replies.remove(l.text))
                        onLine("""{"type":"assistant","message":{"content":[{"type":"text","text":$r}]}}""")
                    }
                    '=' -> {
                        onLine("""{"type":"assistant","message":{"content":[{"type":"text","text":$t}]}}""")
                        onLine("""{"type":"result","subtype":"success","is_error":false,"result":$t,"session_id":"$sid"}""")
                    }
                    else -> {
                        // "description || command": a step can carry the command it ran (taps show in the chat)
                        val (d, c) = l.text.split(" || ", limit = 2).let { it[0] to it.getOrElse(1) { "x" } }
                        onLine("""{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Bash","input":{"command":${JSONObject.quote(c)},"description":${JSONObject.quote(d)}}}]}}""")
                    }
                }
            }
        }
    }

    private fun canned(text: String, onLine: (String) -> Unit) {
        Thread.sleep(1200)
        onLine("""{"type":"assistant","message":{"content":[{"type":"text","text":"Let me look into that first."}]}}""")
        Thread.sleep(1200)
        onLine("""{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Bash","input":{"command":"x","description":"Working on: ${text.take(30).replace("\"", "")}"}}]}}""")
        Thread.sleep(1500)
        onLine("""{"type":"assistant","message":{"content":[{"type":"text","text":"I have the first rows, scrolling down for the rest."}]}}""")
        Thread.sleep(2500)
        if (killed) return
        val ans = JSONObject.quote("Done with **${text.replace("\"", "")}**.\n\n- first `item`\n- second *item*\n\n1. one\n2. two\n\n```\ncode block\n```\n| # | App | Category |\n|---|---|---|\n| 1 | Maps | Travel |\n| 2 | Mail | Work and a much longer category name to force scrolling sideways on screen |\n\nSee https://example.com for more.")
        onLine("""{"type":"assistant","message":{"content":[{"type":"text","text":$ans}]}}""")
        onLine("""{"type":"result","subtype":"success","is_error":false,"result":$ans,"session_id":"$sid"}""")
    }
}
