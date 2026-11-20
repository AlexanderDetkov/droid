package com.adetkov.droid

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** One running agent process. */
interface Proc {
    /** Writes one line to stdin (no-op if closed). Neither waits on the process, so any thread may call them. */
    fun write(line: String)
    /** Closes stdin, after what was written. */
    fun closeStdin()
    /** Blocks, delivering stdout lines until EOF. */
    fun readLines(onLine: (String) -> Unit)
    /** Waits for exit and returns the exit code. */
    fun waitFor(): Int
    /** Kills the run by its phone-agent pid (killing the local `su` client, or the helper's command, would not stop it). */
    fun kill(pid: Int)
    /** False once the process has exited, e.g. a warm one that gave up waiting for its first message. */
    val alive: Boolean get() = true
    /** The run's last line on stderr, which says why a run failed without a result (`su` refused, proot, ...). */
    val error: String get() = ""
}

/** A text file in Droid's Linux that the owner reads and edits ([path] from the Linux's root). */
data class Doc(val path: String, val label: String) {
    companion object {
        /** The owner's instructions: Claude Code's CLAUDE.md, which Codex and OpenCode get too (see phone-agent). */
        val INSTRUCTIONS = Doc("root/.claude/CLAUDE.md", "the instructions")
    }
}

/**
 * Claude Code's own memory for Droid's tasks (they all run in /root): one note per file and an index, MEMORY.md, all
 * written and kept by Claude Code itself. Droid only shows them to the owner, to read, change or delete.
 */
const val MEMORY_DIR = "root/.claude/projects/-root/memory"

/** One of Claude Code's memory notes, with the title and line about it from its index. */
data class Note(val doc: Doc, val title: String, val about: String)

/** The notes in Claude Code's memory: the index's entries (`- [Title](file.md) — about`) in its order, then any it misses. */
internal fun notes(index: String, files: List<String>): List<Note> {
    val names = files.map { it.trim() }.filter { it.endsWith(".md") && it != "MEMORY.md" && '/' !in it }.toSet()
    val listed = Regex("""^\s*[-*]\s*\[([^\]]+)]\(([^)\s/]+\.md)\)\s*(?:[—–-]+\s*)?(.*)$""", RegexOption.MULTILINE).findAll(index)
        .map { it.groupValues }.filter { it[2] in names }.distinctBy { it[2] }
        .map { Note(Doc("$MEMORY_DIR/${it[2]}", it[1]), it[1], it[3].trim()) }.toList()
    val rest = (names - listed.map { it.doc.path.substringAfterLast('/') }.toSet()).sorted().map {
        val title = it.removeSuffix(".md").replace('-', ' ').replaceFirstChar(Char::uppercase)
        Note(Doc("$MEMORY_DIR/$it", title), title, "")
    }
    return listed + rest
}

/** A note's text split into the frontmatter its writer keeps for itself (kept as is) and the body the owner reads. */
internal fun frontmatter(text: String): Pair<String, String> {
    if (!text.startsWith("---\n")) return "" to text
    val end = text.indexOf("\n---\n", 3).takeIf { it >= 0 } ?: return "" to text
    return text.substring(0, end + 5) to text.substring(end + 5).trimStart('\n')
}

interface Backend {
    /** Starts a run on [engine], resuming [sessionId] if given, with that engine's model name ("" for its default). */
    fun start(sessionId: String?, model: String = "", engine: Engine = Engine.CLAUDE): Proc
    /** Deletes a session's transcript from the engine's own storage. */
    fun forget(sessionId: String, engine: Engine = Engine.CLAUDE)
    /** [doc]'s text, "" if there is none yet. */
    fun read(doc: Doc): String = ""
    fun write(doc: Doc, text: String) {}
    /** Claude Code's memory notes (see [MEMORY_DIR]). */
    fun memory(): List<Note> = emptyList()
    /** Deletes a memory note, and its line in the index, so the index never points at nothing. */
    fun delete(note: Doc) {}
    /** Copies a file the agent showed ([path] in Droid's Linux) to [to]; false if it isn't there or is too big. */
    fun fetch(path: String, to: File): Boolean = false
    /** Runs [command] with sh in Droid's Linux, in /root, for at most [timeoutS]: its output (stdout and stderr) and exit code. */
    fun run(command: String, timeoutS: Int = 120): Pair<String, Int> = "" to 127
    /** Hands the owner's answer ([json]) to the agent's command waiting on page [pageId] (`droid show --wait`). */
    fun reply(pageId: String, json: String) {}
}

/**
 * Runs tasks in Droid's Linux through [host] (root's chroot, or proot through Droid's helper on phones without root),
 * read when each call happens, since setup may switch it. The app's APK holds the hidden-display helper.
 */
class HostBackend(private val app: Context, private val host: () -> Host) : Backend {
    private val apk = app.applicationInfo.sourceDir
    private fun asset(name: String) = app.assets.open(name).use { it.readBytes().toString(Charsets.UTF_8) }
    /** The Linux whose owner's instructions this process checked (see [migrate]). */
    @Volatile private var migrated: String? = null
    /** The Linux this process wrote its scripts to (see [start]). */
    @Volatile private var scripts: String? = null

    override fun start(sessionId: String?, model: String, engine: Engine): Proc {
        val h = host()
        try {
            // The app's instructions for the agent, as they are for this phone, where phone-agent gives them to every
            // engine. Written at each start (one quick call), so a Linux reinstalled meanwhile has them too.
            put(h, "root/.droid/system.md", systemInstructions(asset("setup/system.md"), root = h === RootHost))
            put(h, "etc/resolv.conf", resolvConf(networkDns(app), asset("setup/resolv.conf")))  // this network's DNS first
            if (migrated != h.base) { migrate(h); migrated = h.base }
            // The scripts the agent's side of the app lives in, as this app has them (Setup's Update installs them
            // too), once per process: a newer app's `droid` command and runs work without an Update.
            if (scripts != h.base) {
                SCRIPTS.forEach { put(h, "usr/local/bin/$it", asset("setup/bin/$it"), "755") }
                VD_SCRIPTS.forEach { put(h, "${h.base}/$it", asset("setup/$it"), "755") }
                scripts = h.base
            }
        } catch (e: IOException) { Log.w("Droid", "system instructions: ${e.message}") }
        return HostProc(h.exec(startCommand(apk, sessionId, model, engine, h.base), mergeStderr = false), h)
    }

    /**
     * Replaces the owner's instructions if they are still the old default, which held what system.md now says. Only
     * once phone-agent passes system.md on (it comes with Setup's Update): until then the old default is all it has.
     */
    private fun migrate(h: Host) {
        val passesThemOn = h.exec("grep -q droid/system.md ${h.rootfs}/usr/local/bin/phone-agent").run { outputStream.close(); waitFor() } == 0
        if (passesThemOn && isOldDefaultInstructions(read(Doc.INSTRUCTIONS))) write(Doc.INSTRUCTIONS, asset("setup/CLAUDE.md"))
    }

    override fun forget(sessionId: String, engine: Engine) {
        val h = host()
        val cmd = forgetCommand(sessionId, engine, h.base) ?: return
        try { h.exec(cmd).run { outputStream.close(); waitFor() } } catch (_: IOException) {}
    }

    override fun read(doc: Doc): String {
        val h = host()
        val f = "${h.rootfs}/${doc.path}"
        val p = h.exec("[ ! -e $f ] || cat $f", mergeStderr = false)
        p.outputStream.close()
        val text = p.inputStream.bufferedReader(Charsets.UTF_8).readText()
        if (p.waitFor() != 0) throw IOException("Could not read ${doc.label}")
        return text
    }

    override fun write(doc: Doc, text: String) = put(host(), doc.path, text)

    override fun memory(): List<Note> {
        val h = host()
        val p = h.exec("cd ${h.rootfs}/$MEMORY_DIR 2>/dev/null || exit 0; cat MEMORY.md 2>/dev/null; echo; echo $FILES; ls -1", mergeStderr = false)
        p.outputStream.close()
        val out = p.inputStream.bufferedReader(Charsets.UTF_8).readText()
        if (p.waitFor() != 0) throw IOException("Could not read Claude's memory")
        val (index, files) = out.substringBeforeLast(FILES) to out.substringAfterLast(FILES, "").lines()
        return notes(index, files)
    }

    override fun delete(note: Doc) {
        val h = host()
        val f = "${h.rootfs}/${note.path}"
        val index = "${f.substringBeforeLast('/')}/MEMORY.md"
        val line = shellQuote("(${note.path.substringAfterLast('/')})")
        val p = h.exec("rm -f ${shellQuote(f)} && if [ -f $index ]; then grep -vF $line $index > $index.$$; mv $index.$$ $index; fi")
        p.outputStream.close()
        if (p.waitFor() != 0) throw IOException("Could not delete ${note.label}")
    }

    /**
     * Replaced by rename (from a name of this call's own), so a run starting meanwhile never reads a half-written file.
     * Only into an installed Linux: a folder made in a missing one would break the next install.
     */
    /** Writes [path] in the Linux (or, absolute, beside it), if the Linux is installed. */
    private fun put(h: Host, path: String, text: String, mode: String = "644") {
        val f = if (path.startsWith("/")) path else "${h.rootfs}/$path"
        val p = h.exec("[ -d ${h.rootfs}/root ] && mkdir -p ${f.substringBeforeLast('/')} && cat > $f.$$ && chmod $mode $f.$$ && mv $f.$$ $f")
        p.outputStream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        if (p.waitFor() != 0) throw IOException("Could not write $path")
    }

    /**
     * Read inside Droid's Linux, where the agent named it: its /tmp, home and /sdcard are all there. At most one byte
     * past the limit is read (a link's size is its own, not its target's), and for at most 30 s (/proc has files that
     * never end).
     */
    override fun fetch(path: String, to: File): Boolean {
        val h = host()
        val read = "[ -f \"\$1\" ] && timeout 30 head -c ${MAX_SHOWN + 1} \"\$1\""
        val p = h.exec("${h.base}/enter.sh /bin/sh -c '$read' sh ${shellQuote(linuxPath(path))}", mergeStderr = false)
        p.outputStream.close()
        to.parentFile?.mkdirs()
        var ok = false
        try {
            p.inputStream.use { inp -> to.outputStream().use { inp.copyTo(it) } }
            ok = p.waitFor() == 0 && to.length() in 1..MAX_SHOWN
        } finally { if (!ok) to.delete() }
        return ok
    }

    override fun run(command: String, timeoutS: Int): Pair<String, Int> {
        val h = host()
        val p = h.exec("${h.base}/enter.sh /bin/sh -c ${shellQuote("cd /root && exec timeout $timeoutS sh -c \"\$1\"")} sh ${shellQuote(command)}")
        p.outputStream.close()
        val out = p.inputStream.use { inp ->
            val head = inp.readNBytes(MAX_OUTPUT)
            val rest = ByteArray(8192)
            while (inp.read(rest) >= 0) { /* drained, so the command can finish */ }
            String(head, Charsets.UTF_8)
        }
        return out to p.waitFor()
    }

    override fun reply(pageId: String, json: String) {
        if (PAGE_ID.matches(pageId)) put(host(), "root/.droid/replies/$pageId.json", json)
    }
}

/** The scripts in assets/setup/bin that runs depend on, which [HostBackend.start] keeps up to date. */
private val SCRIPTS = listOf("droid", "phone-agent", "droid-engine", "android", "shot", "droid-bridge", "droid-install-engine")
/** Run in Android by phone-agent, from beside the Linux (see install.sh). */
private val VD_SCRIPTS = listOf("vd-start.sh", "vd-stop.sh")

/** The most of a command's output a page gets back (droid.run). */
private const val MAX_OUTPUT = 1_000_000

/** Separates the index from the file list in [HostBackend.memory]'s output. */
private const val FILES = "--droid-memory-files--"

/** The largest file an answer can show (25 MB). */
private const val MAX_SHOWN = 25_000_000

/** A path as the agent wrote it, made absolute in its Linux: ~ is /root, and so is where it works. */
internal fun linuxPath(path: String): String = when {
    path.startsWith("~/") -> "/root/" + path.removePrefix("~/")
    path.startsWith("/") -> path
    else -> "/root/$path"
}

internal fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"

/** The app's instructions ([template]: assets/setup/system.md) for this phone: Droid's Linux with root, or without. */
internal fun systemInstructions(template: String, root: Boolean): String =
    template.replace("{where}", if (root) "in a chroot with root" else "under proot")
        .replace("{as}", if (root) "as root"
            else "as adb's shell user: it can use apps and change settings, but not read other apps' private data")

/**
 * The default CLAUDE.md before system.md existed: it described the phone itself. An owner who never changed it gets
 * the new default; edited ones are left alone (their phone notes then repeat what system.md says).
 */
internal fun isOldDefaultInstructions(text: String): Boolean =
    MessageDigest.getInstance("SHA-256").digest(text.trim().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) } ==
        "9bd4eba46497828d598d774c4ad4c26eff8b0b130056fc9a34d77b4535dc3cc6"

/** The host command that starts a run. Claude gets no --engine, so an older phone-agent in the rootfs still works. */
internal fun startCommand(apk: String, sessionId: String?, model: String, engine: Engine, base: String = RootHost.base): String {
    val e = if (engine == Engine.CLAUDE) "" else " --engine ${engine.id}"
    val resume = sessionId?.takeIf { engine.sessionId.matches(it) }?.let { " --resume $it" }.orEmpty()
    val m = model.takeIf { engine.validModel(it) && it.isNotEmpty() }?.let { " --model $it" }.orEmpty()
    return "DROID_APK='$apk' $base/enter.sh phone-agent$e$resume$m"
}

/**
 * The host command that deletes a session where its engine keeps it, or null for an id that isn't one (ids go into a
 * shell command). Claude: a transcript under ~/.claude/projects. Codex: a rollout file under ~/.codex/sessions, named
 * with the id. OpenCode: a row in its database, deleted by its own CLI.
 */
internal fun forgetCommand(sessionId: String, engine: Engine, base: String = RootHost.base): String? {
    if (!engine.sessionId.matches(sessionId)) return null
    val project = "$base/rootfs/root/.claude/projects/-root"
    return when (engine) {
        Engine.CLAUDE -> "rm -rf $project/$sessionId.jsonl $project/$sessionId"
        Engine.CODEX -> "find $base/rootfs/root/.codex/sessions -name '*$sessionId*.jsonl' -delete"
        Engine.OPENCODE -> "$base/enter.sh opencode session delete $sessionId"
    }
}

/** A run's process on [host]: `su`'s client, or a command in Droid's helper. */
internal class HostProc(private val p: Process, private val host: Host) : Proc {
    private val stdin: OutputStream = p.outputStream
    @Volatile override var error = ""
    private val stderr = thread(isDaemon = true) {
        try { p.errorStream.bufferedReader().forEachLine { if (it.isNotBlank()) error = it.trim().take(300) } } catch (_: IOException) {}
    }
    /**
     * Writes to stdin happen here, one at a time and in order: without root stdin is a socket to Droid's helper, and the
     * caller may be the main thread. Its one thread ends when idle, so a process nobody closes leaves none behind.
     */
    private val writer = ThreadPoolExecutor(1, 1, 5, TimeUnit.SECONDS, LinkedBlockingQueue()) { Thread(it, "droid-stdin").apply { isDaemon = true } }
        .apply { allowCoreThreadTimeOut(true) }

    override fun write(line: String) = queue { stdin.write((line + "\n").toByteArray(Charsets.UTF_8)); stdin.flush() }

    override fun closeStdin() = queue { stdin.close() }

    private fun queue(io: () -> Unit) = writer.execute { try { io() } catch (_: IOException) {} }

    override fun readLines(onLine: (String) -> Unit) = p.inputStream.bufferedReader(Charsets.UTF_8).forEachLine(onLine)

    override fun waitFor(): Int = p.waitFor().also { stderr.join(1000) }

    override val alive: Boolean get() = p.isAlive

    override fun kill(pid: Int) {
        val code = try { host.exec("kill $pid").run { outputStream.close(); waitFor() } } catch (_: IOException) { -1 }
        Log.i("Droid", "kill pid=$pid exit=$code")
    }
}
