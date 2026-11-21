package com.adetkov.droid

import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

private const val WARM_FOR = 120_000L

/**
 * The oldest a warm process may be to take a message: phone-agent ends one that got none within FIRST_WAIT (3 min), and
 * may do so as the message comes. Only matters when [WARM_FOR] didn't drop it (Android had the app frozen).
 */
private const val WARM_USABLE = 150_000L

/**
 * How long a run that answered keeps its process and hidden screen. A reply in that time goes on on the same screen,
 * with whatever the agent left open there, and the user can look at it or take it over in Watch. The screen keeps the
 * phone awake (see the README), so not for long; it stays as long as Watch is open on it.
 */
const val HOLD_FOR = 10 * 60_000L

/**
 * What the agent asks of the app, for the app to act on. Unbounded: acting may take a call into Android each (`su`, or
 * Droid's helper), and a burst waits for that rather than being dropped (the agent was told it's done).
 */
private fun <T> requests() = MutableSharedFlow<T>(extraBufferCapacity = Int.MAX_VALUE)

/** Put before the next message when the user used the hidden screen (Watch's take over) since the agent's last turn. */
internal const val TOOK_OVER = "[I used your hidden screen myself since your last turn, so it may have changed.]\n\n"

/**
 * A chat's run: its process id, and the port and token of its live view, once the run reports them. [started]: when the
 * current request was sent. [closesAt]: when a run that answered will close its screen (null while working or watched).
 */
data class RunInfo(
    val pid: Int? = null, val port: Int? = null, val token: String? = null,
    val started: Long = System.currentTimeMillis(), val closesAt: Long? = null,
)

/**
 * What a working chat's agent did last, for Dash: the kind of its latest step, whether the latest thing was it saying
 * something, and a count of both that goes up with each (so each can be reacted to, even two of a kind in a row).
 */
data class Doing(val work: Work, val n: Int = 0, val said: Boolean = false)

/**
 * Something the agent set for later in chat [conv]: at [at], [text] goes to the chat as from the agent itself
 * (`droid later`), or, with [note], that notification is posted (`droid notify --at`).
 */
data class Wake(val conv: String, val at: Long, val text: String, val note: String? = null)

/**
 * One agent process per chat. Messages sent mid-run go to stdin. After a successful `result` with an app on its hidden
 * screen ([inUse]), the process and the screen stay up for [holdFor] (longer while watched): a message in that time goes
 * to the same process, on the same screen. Then, or at once when the screen is unused, stdin is closed and the agent
 * exits; later messages start a fresh `--resume` process.
 * [prewarm] starts the next process early, while the user is still talking; it waits on stdin until used.
 */
class Runner(
    private val store: Store, private val backend: Backend,
    private val model: (Engine) -> String = { "" },
    /** The engine a new chat starts on. */
    private val engine: () -> Engine = { Engine.CLAUDE },
    /** How long a run that answered waits for the next message (0: it ends at once). */
    private val holdFor: Long = HOLD_FOR,
    /** Where a chat keeps copies of what its answers show (see [Shown]). */
    private val shown: (String) -> File = { File("shown", it) },
    /** Whether an app is in front on the run's hidden screen (its port and token; see [screenInUse]). */
    private val inUse: (Int, String) -> Boolean = { _, _ -> true },
) {
    /** Chats working on a request (conversation id -> its run). */
    val running = MutableStateFlow<Map<String, RunInfo>>(emptyMap())
    /**
     * Chats whose run is up: working, or answered and holding its hidden screen; and, with no port, one whose agent is
     * exiting after its answer, until it has. Every chat in [running] is here too.
     */
    val screens = MutableStateFlow<Map<String, RunInfo>>(emptyMap())
    /** (conversation id, text) for each successful result. */
    val results = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 8)
    /** Where each chat's agent taps its screen, as it does: (chat, x, y) in the screen's pixels. For Dash's eyes. */
    val taps = MutableSharedFlow<Triple<String, Int, Int>>(extraBufferCapacity = 16)
    /** What each chat in [running] is doing, as it does it (see [Doing]). */
    val doing = MutableStateFlow<Map<String, Doing>>(emptyMap())
    /** Pages the agent showed (`droid show`): (chat, page id), for the app to put where the page asked to be. */
    val pages = requests<Pair<String, String>>()
    /** Wake-ups the agent asked for (`droid later`), for the app to schedule. */
    val wakes = requests<Wake>()
    /** Notifications the agent posts, updates or takes away (`droid notify`): (chat, its spec), for the app. */
    val notes = requests<Pair<String, JSONObject>>()
    /** Chats whose agent took over (or silenced) their answer's notification this turn (`droid notify --id chat`). */
    val ownNote: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    /** Notifications the agent's command waits on ("chat/id": `droid notify --wait`), until it's answered (see Notify). */
    val noteWaits: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private class Run {
        var proc: Proc? = null
        /** Its process's pid and live view, as the process reported them; null again once it has exited. */
        var pid: Int? = null
        var port: Int? = null
        var token: String? = null
        var session: String? = null
        var stdinOpen = false
        /** The process's latest turn has its result: nothing the chat asked is being worked on in it. */
        var gotResult = false
        var stopped = false
        var deleted = false
        val queue = ArrayList<String>()
        var engine = Engine.CLAUDE
        /** Answered: the process and its screen wait for the next message, until [closer] closes stdin. */
        var idle = false
        var closer: Job? = null
        /** Watch is open on it: a screen held after answering stays while the user looks. */
        var watched = false
        /** The user used the screen since the agent's last turn; the next message says so. */
        var touched = false
    }

    // A bug in what the runner does in the background is logged, not the end of the app (a run's own fails its chat).
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> Log.e("Droid", "runner", e) })
    private val lock = Any()
    private val runs = HashMap<String, Run>()

    /** A process started before its first message, for the chat with this Claude session (null: a new chat) and model. */
    private class Warm(val engine: Engine, val session: String?, val model: String, val proc: Proc, val expiry: Job) {
        private val at = System.currentTimeMillis()
        /** Still up, and not about to give up waiting for its first message. */
        val usable get() = proc.alive && System.currentTimeMillis() - at < WARM_USABLE
    }
    private var warm: Warm? = null
    /** Counts processes started for runs, and [dropWarm]s. A warm process that finished starting after a run began may
     *  have read an older transcript, and one still starting when it was dropped isn't wanted, so either is discarded. */
    private var starts = 0

    /**
     * Starts the process for the next message to [convId] (a new chat if null) ahead of time, saving its ~1 s
     * startup. Unused, it is dropped after [WARM_FOR] or by [dropWarm]. Skipped while the chat has a run.
     */
    fun prewarm(convId: String?) {
        val conv = convId?.let { store.get(it) }
        val session = convId?.let { conv?.sessionId ?: return }  // no session yet: its first run is still starting
        val e = conv?.engine ?: engine()
        val m = model(e)
        val gen = synchronized(lock) {
            if (convId != null && runs[convId] != null) return
            if (warm?.let { it.engine == e && it.session == session && it.model == m && it.usable } == true) return
            starts
        }
        scope.launch {
            val proc = try { backend.start(session, m, e) } catch (_: Exception) { return@launch }
            val expiry = scope.launch { delay(WARM_FOR); dropWarm(proc) }
            val old = synchronized(lock) {
                if (starts != gen) null.also { expiry.cancel(); discard(proc) }  // a run started, or it was dropped, meanwhile
                else warm.also { warm = Warm(e, session, m, proc, expiry) }
            }
            old?.let { it.expiry.cancel(); discard(it.proc) }
        }
    }

    /** Drops the warm process; with [only], just if it is that one. The returned job ends when the process has exited. */
    fun dropWarm(only: Proc? = null): Job? {
        val w = synchronized(lock) {
            if (only == null) starts++  // and one still starting
            warm?.takeIf { only == null || it.proc === only }?.also { warm = null }
        } ?: return null
        w.expiry.cancel()
        return discard(w.proc)
    }

    /** The warm process for [session], if it is still [Warm.usable]. One can exit on its own: phone-agent gives up
     *  after FIRST_WAIT without a message (e.g. while Android had this app frozen, so [WARM_FOR] never fired). */
    private fun takeWarm(engine: Engine, session: String?, model: String): Proc? = synchronized(lock) {
        val w = warm?.takeIf { it.engine == engine && it.session == session && it.model == model } ?: return null
        warm = null
        w.expiry.cancel()
        if (w.usable) w.proc else { discard(w.proc); null }
    }

    /** With stdin closed before any message the agent exits by itself, removing its display. */
    private fun discard(proc: Proc) = scope.launch {
        proc.closeStdin()
        try { proc.readLines {} } catch (_: IOException) {}
        proc.waitFor()
    }

    /**
     * Sends [text] and [files] to a chat (a new chat if [convId] is null), starting or feeding its process; [data]:
     * what a page sent with it (droid.send); [wake]: the agent's own wake-up (`droid later`), not the owner's message.
     * Returns the chat id, or null for a chat that was deleted (its page or notification can still send).
     */
    fun send(convId: String?, text: String, files: List<Attachment> = emptyList(), data: String? = null, wake: Boolean = false): String? {
        val id = if (convId == null) store.create(text.ifBlank { files.firstOrNull()?.name.orEmpty() }, engine()).id
            else store.get(convId)?.id ?: return null
        ownNote -= id  // a new turn: its answer gets its own notification again, unless the agent says otherwise
        val line = if (wake) "[You woke yourself up, as you asked with droid later: $text]" else agentText(text, files, data)
        var start: Run? = null
        val fresh = synchronized(lock) {
            // Under the lock, so a run of this chat that just ended can't mark it stopped after this.
            store.update(id) { it.add(if (wake) Kind.WAKE else Kind.USER, text, files = files, data = data).copy(running = true) }
            val r = runs[id]
            when {
                r == null -> {
                    start = Run().also { runs[id] = it }
                    val info = RunInfo()
                    screens.update { it + (id to info) }
                    running.update { it + (id to info) }
                    true
                }
                // Under the lock that closes stdin: a message is either written or queued, in order.
                r.stdinOpen -> {
                    r.proc?.write(userLine(if (r.touched) TOOK_OVER + line else line))
                    r.touched = false
                    r.gotResult = false; r.idle = false  // after its answer: a new turn in the same process
                    busy(id, r)
                }
                else -> { r.queue.add(line); busy(id, r) }
            }
        }
        // a new request: the agent reads it first (a message mid-turn leaves it at what it was doing)
        if (fresh) doing.update { it + (id to Doing(Work.THINK)) }
        start?.let { r -> scope.launch(failed(id, r)) { loop(id, r, listOf(line)) } }
        return id
    }

    /**
     * Sends the chat's last request again (after it failed or was stopped), in place of that turn. Returns the index that
     * turn starts at, or null if there is none.
     */
    fun retry(id: String): Int? {
        if (id in running.value) return null
        val messages = store.get(id)?.messages ?: return null
        val i = messages.indexOfLast { it.kind.opensTurn }.takeIf { it >= 0 } ?: return null
        store.update(id) { it.copy(messages = it.messages.take(i)) }
        send(id, messages[i].text, messages[i].files, messages[i].data, wake = messages[i].kind == Kind.WAKE)
        return i
    }

    /**
     * Kills the chat's run. A run still setting up has no pid yet; it is killed as soon as it reports one. A run that
     * answered has nothing to stop: its screen is closed instead (the agent exits by itself), and a message waiting for
     * its next process is dropped.
     */
    fun stop(id: String) {
        val proc: Proc?
        val pid: Int?
        synchronized(lock) {
            val r = runs[id] ?: return
            if (r.gotResult) {
                release(id, r)?.closeStdin()
                if (r.queue.isNotEmpty()) {
                    r.queue.clear()
                    running.update { it - id }; doing.update { it - id }; closing(id)
                    store.update(id) { it.add(Kind.STATUS, "Stopped").copy(running = false) }
                }
                return
            }
            r.stopped = true; r.stdinOpen = false; r.queue.clear()
            proc = r.proc; pid = r.pid
        }
        Log.i("Droid", "stop chat=$id pid=$pid")
        if (proc != null && pid != null) scope.launch { proc.kill(pid) }
    }

    /** Closes every screen held by a run that answered; their agents exit, and they leave [screens]. */
    fun closeScreens() = synchronized(lock) { runs.forEach { (id, r) -> release(id, r)?.closeStdin() } }

    /** Watch was opened ([on]) or closed on a chat. A screen held after answering stays while watched, then [holdFor]. */
    fun watching(id: String, on: Boolean) {
        synchronized(lock) {
            val r = runs[id] ?: return
            r.watched = on
            if (r.idle && r.stdinOpen) hold(id, r)
        }
    }

    /** The user used the chat's hidden screen (Watch's take over); the agent is told so with the next message. */
    fun touched(id: String) {
        synchronized(lock) { runs[id]?.touched = true }
    }

    /** Deletes the chat, its attachments and its Claude Code transcript (once its run has exited, if it is running). */
    fun delete(id: String) {
        val conv = store.get(id)
        val session = conv?.sessionId
        val active = synchronized(lock) { runs[id]?.also { it.deleted = true } != null }
        stop(id)
        store.delete(id)
        if (!active && session != null) scope.launch { backend.forget(session, conv.engine) }
        val files = conv?.messages?.flatMap { it.files }.orEmpty()
        if (files.isNotEmpty()) scope.launch {
            // Attachments read by a run that is still stopping are only deleted after it has exited, with the transcript.
            if (active) delay(10_000)
            files.forEach { f -> java.io.File(f.path).let { it.delete(); it.parentFile?.delete() } }
        }
    }

    /**
     * How Dash looks ([feel]: a feeling's name, or a face as JSON) and what he says, as the agent or its page set it:
     * the chat's state from now on (needs_you: the owner's turn). The same again changes nothing.
     */
    fun dash(id: String, feel: String, words: String) {
        val f: Any = feel.trim().let { t -> if (t.startsWith("{")) try { JSONObject(t) } catch (_: JSONException) { t } else t }
        val text = JSONObject().put("feel", f).put("words", words.trim()).toString()
        if (moodOf(text) == null) return
        store.update(id) { c -> if (lastTurn(c.messages).lastOrNull { it.kind == Kind.DASH }?.text == text) c else c.add(Kind.DASH, text) }
    }

    /** The owner answered page [pageId], which the agent's command waits on (`droid show --wait`): it gets [text] and [data]. */
    fun answerPage(id: String, pageId: String, text: String, data: String?) {
        val reply = pageReply(text, data)
        store.update(id) { c ->
            c.copy(messages = c.messages.map { m -> if (m.kind == Kind.PAGE && PageInfo.of(m).id == pageId) m.copy(data = PageInfo.of(m).copy(reply = text).json()) else m })
        }
        scope.launch { try { backend.reply(pageId, reply.toString()) } catch (e: IOException) { Log.w("Droid", "page reply: ${e.message}") } }
    }

    /** Whether chat [id]'s agent is working on a request now (not just holding its screen). */
    fun working(id: String) = id in running.value

    /** What the agent told the app with the `droid` command. */
    private fun droid(id: String, ev: Ev.Droid) {
        val o = ev.o
        when (ev.op) {
            "dash" -> dash(id, o.opt("feel")?.toString().orEmpty(), o.optString("words"))
            // On the run's own thread, so pages shown under one id land in the order shown (and never write one file at once).
            "show" -> showPage(id, o)
            "later" -> o.optLong("at").takeIf { it > 0 }?.let { wakes.tryEmit(Wake(id, it, o.optString("text"))) }
            "notify" -> {
                val note = o.optString("id")
                if (note == Notify.CHAT) ownNote += id
                // A wait ends with its answer, --cancel or the command giving up: not with an update (progress) under its id.
                if (o.optBoolean("wait")) noteWaits += "$id/$note" else if (o.optBoolean("cancel")) noteWaits -= "$id/$note"
                notes.tryEmit(id to o)
            }
            // its command waits on a page or notification (n-ID) again (`droid wait`), or gave up waiting
            "wait", "gave_up" -> waiting(id, o.optString("id"), ev.op == "wait")
            else -> Log.w("Droid", "droid ${ev.op}: not known")
        }
    }

    /** The agent's command waits on [reply] (a page's id, or n- and a notification's), or no longer: an answer there is a message then. */
    private fun waiting(id: String, reply: String, on: Boolean) {
        if (reply.startsWith("n-")) reply.removePrefix("n-").let { if (on) noteWaits += "$id/$it" else noteWaits -= "$id/$it" }
        store.update(id) { it.waitingOn(reply, on) }
    }

    /**
     * A page the agent showed: read from its Linux into the chat (a PAGE message). One shown again under its id
     * replaces it in place, so the agent can keep a page up to date. A waiting command hears at once if it can't be read.
     */
    private fun showPage(id: String, o: JSONObject) {
        val pid = o.optString("id").takeIf { PAGE_ID.matches(it) } ?: UUID.randomUUID().toString().take(8)
        val wait = o.optBoolean("wait")
        val f = File(shown(id), "page-$pid.html")
        val html = try { if (backend.fetch(o.optString("path"), f)) f.readText().also { f.delete() } else null } catch (e: IOException) { null }
        if (html == null) {
            if (wait) try { backend.reply(pid, JSONObject().put("error", "Droid couldn't read ${o.optString("path")}").toString()) } catch (_: IOException) {}
            return
        }
        putPage(id, PageInfo(pid, wait, o.optString("where", "chat").ifEmpty { "chat" }, pageTitle(html)), html)
        pages.tryEmit(id to pid)
    }

    /** Page [info] with [html] in chat [id]: a new PAGE message, or the one with its id shown again (see [withPage]). */
    fun putPage(id: String, info: PageInfo, html: String) = store.update(id) { it.withPage(info, html) }

    /**
     * Marks the chat as working on a request, if it wasn't: a message came after its answer, or its agent went on after
     * it (a message sent mid-turn started another turn). Returns whether it wasn't. Under [lock].
     */
    private fun busy(id: String, r: Run): Boolean {
        r.closer?.cancel(); r.closer = null
        if (id in running.value) return false
        // on its process's screen while that is up (a message queued for the next process shows it until that one starts)
        val info = RunInfo(r.pid, r.port, r.token)
        screens.update { it + (id to info) }
        running.update { it + (id to info) }
        return true
    }

    /** Work after an answer: a message sent mid-turn started another turn. Returns whether the chat works again for it. */
    private fun wentOn(id: String, run: Run): Boolean = synchronized(lock) {
        if (!run.gotResult) return false
        run.gotResult = false; run.idle = false
        busy(id, run)
    }

    /** (Re)starts the countdown that closes a screen held after answering; none while watched. Under [lock]. */
    private fun hold(id: String, r: Run) {
        r.closer?.cancel()
        r.closer = if (r.watched) null else scope.launch {
            delay(holdFor)
            synchronized(lock) { r.closer = null; release(id, r) }?.closeStdin()
        }
        val closesAt = if (r.watched) null else System.currentTimeMillis() + holdFor
        screens.update { m -> m[id]?.let { m + (id to it.copy(closesAt = closesAt)) } ?: m }
    }

    /** Ends the wait of a run that answered: returns its process, whose stdin the caller closes. Under [lock]. */
    private fun release(id: String, r: Run): Proc? {
        if (!r.idle || !r.stdinOpen) return null
        r.stdinOpen = false
        r.closer?.cancel(); r.closer = null
        closing(id)
        return r.proc
    }

    /** The chat's agent exits after its answer: it stays in [screens] until it has, without its screen, which goes. Under [lock]. */
    private fun closing(id: String) = screens.update { m -> m[id]?.let { m + (id to it.copy(port = null, token = null, closesAt = null)) } ?: m }

    /**
     * A bug in handling chat [id]'s run must not take the app down: the chat says the run failed, and its process is
     * ended (it may hold a hidden screen).
     */
    private fun failed(id: String, run: Run) = CoroutineExceptionHandler { _, e ->
        Log.e("Droid", "run of $id", e)
        val (proc, pid) = synchronized(lock) {
            if (runs[id] !== run) return@CoroutineExceptionHandler  // it had ended
            runs.remove(id); running.update { it - id }; screens.update { it - id }; doing.update { it - id }
            run.closer?.cancel(); run.stdinOpen = false
            store.update(id) { it.add(Kind.STATUS, "Failed: ${e.message ?: e}").copy(running = false) }
            run.proc to run.pid
        }
        proc?.closeStdin()
        if (proc != null && pid != null) scope.launch { proc.kill(pid) }
    }

    private fun loop(id: String, run: Run, first: List<String>) {
        var batch: List<String>? = first
        while (batch != null) {
            val session = store.get(id)?.sessionId
            val e = store.get(id)?.engine ?: Engine.CLAUDE
            val m = model(e)
            val proc = try { takeWarm(e, session, m) ?: backend.start(session, m, e) } catch (e: Exception) {
                store.update(id) { it.add(Kind.STATUS, "Failed: ${e.message}") }
                synchronized(lock) { run.queue.clear() }
                batch = next(id, run); continue
            }
            synchronized(lock) {
                starts++
                run.engine = e; run.proc = proc; run.session = session; run.gotResult = false
                run.idle = false; run.touched = false  // a new process has a new screen
                // The request, then what was sent while it started, in order. A stopped run gets nothing (it is killed
                // once it reports its pid); what was sent after the Stop waits for the next.
                run.stdinOpen = !run.stopped
                if (run.stdinOpen) { (batch + run.queue).forEach { proc.write(userLine(it)) }; run.queue.clear() }
            }
            try { proc.readLines { handle(id, run, proc, it) } } catch (_: IOException) {}
            val exit = proc.waitFor()
            val (gotResult, stopped) = synchronized(lock) {
                // Gone: a Stop from now on must not kill its pid, which may be another process's by then.
                run.proc = null; run.pid = null; run.port = null; run.token = null
                run.stdinOpen = false; run.idle = false; run.closer?.cancel(); run.closer = null
                run.gotResult to run.stopped
            }
            if (!gotResult) when {
                stopped -> store.update(id) { it.add(Kind.STATUS, "Stopped") }
                // Clean exit without a result: the last narration is the answer. Anything else is a failure.
                exit == 0 -> store.update(id) { it.apply(Ev.Result(false, "")) }
                else -> store.update(id) { it.add(Kind.STATUS, if (proc.error.isEmpty()) "Failed" else "Failed: ${proc.error}") }
            }
            batch = next(id, run)
        }
        // Unless it was sent a message meanwhile, which started another run. Nothing waits on the owner any more: what
        // they answer now is a message.
        synchronized(lock) {
            if (runs[id] == null) {
                noteWaits.removeIf { it.startsWith("$id/") }
                store.update(id) { it.copy(running = false).waitingOn(null, false) }
            }
        }
        if (synchronized(lock) { run.deleted }) run.session?.let { s -> backend.forget(s, run.engine) }  // deleted while running
    }

    /** The agent's path -> its copy, for each picture or file [text] shows that could be copied. */
    private fun fetchShown(id: String, text: String): Map<String, String> {
        val now = System.currentTimeMillis()
        return acts(text).filterIsInstance<Act.Show>().map { it.path }.distinct().withIndex().mapNotNull { (i, path) ->
            val to = File(shown(id), "$now$i-${File(path).name}")
            try { if (backend.fetch(path, to)) path to to.path else null } catch (e: IOException) { Log.w("Droid", "show $path: $e"); null }
        }.toMap()
    }

    private fun Conv.withCopies(copies: Map<String, String>): Conv {
        val i = messages.indexOfLast { it.kind == Kind.AGENT }
        if (copies.isEmpty() || i < 0) return this
        return copy(messages = messages.toMutableList().also { it[i] = it[i].copy(text = localize(it[i].text, copies)) })
    }

    /** Queued messages for a new process, or null (and the run is removed) if there are none. */
    private fun next(id: String, run: Run): List<String>? = synchronized(lock) {
        if (run.queue.isEmpty()) {
            runs.remove(id); running.update { it - id }; screens.update { it - id }; doing.update { it - id }; null
        } else run.queue.toList().also {
            run.queue.clear(); run.stopped = false
            // a request from now, on the screen its process will report
            val info = RunInfo()
            screens.update { m -> m + (id to info) }; running.update { m -> m + (id to info) }
            doing.update { m -> m + (id to Doing(Work.THINK)) }
        }
    }

    private fun handle(id: String, run: Run, proc: Proc, line: String) {
        for (ev in parseLine(line)) {
            var woke = false
            when (ev) {
                is Ev.Agent -> {
                    val pid = ev.pid.takeIf { it > 0 }
                    val stopped = synchronized(lock) {
                        run.pid = pid; run.port = ev.port.takeIf { it > 0 }; run.token = ev.token
                        val reported = { r: RunInfo -> r.copy(pid = pid, port = run.port, token = run.token) }
                        screens.update { m -> m[id]?.let { m + (id to reported(it)) } ?: m }
                        running.update { m -> m[id]?.let { m + (id to reported(it)) } ?: m }
                        run.stopped
                    }
                    if (stopped && pid != null) scope.launch { proc.kill(pid) }  // Stop was pressed while it was starting
                    continue
                }
                is Ev.Tap -> { taps.tryEmit(Triple(id, ev.x, ev.y)); continue }
                is Ev.Droid -> { droid(id, ev); continue }
                is Ev.Step -> {
                    woke = wentOn(id, run)
                    doing.update { m -> m + (id to Doing(ev.work, (m[id]?.n ?: 0) + 1)) }
                }
                is Ev.Text -> {
                    woke = wentOn(id, run)
                    doing.update { m -> m + (id to Doing(m[id]?.work ?: Work.THINK, (m[id]?.n ?: 0) + 1, said = true)) }
                }
                is Ev.Session -> { synchronized(lock) { run.session = ev.id }; woke = wentOn(id, run) }
                is Ev.Result -> {
                    // An answer keeps the process and its screen for the next message, if an app is there to continue
                    // in; an answer without one, or an error, ends the run.
                    // The answer shows at once: asking the helper takes a moment, and meanwhile its text would read as
                    // the turn's latest step. (One that shows files waits for their copies, so it never points at the
                    // agent's paths; applying the result again below changes nothing.)
                    if (!ev.isError && ev.text.isNotBlank() && acts(ev.text).none { it is Act.Show }) store.update(id) { it.apply(ev) }
                    val keep = holdFor > 0 && !ev.isError && screens.value[id]?.let { r ->
                        val port = r.port; val token = r.token
                        port != null && token != null && inUse(port, token)
                    } == true
                    val held = synchronized(lock) {
                        run.gotResult = true
                        (keep && run.stdinOpen).also { if (it) run.idle = true else run.stdinOpen = false }
                    }
                    if (!held) proc.closeStdin()
                    doing.update { it - id }
                    if (!ev.isError && ev.text.isNotBlank()) results.tryEmit(id to ev.text)
                }
            }
            val answered = ev is Ev.Result
            // What an answer shows is copied out of Droid's Linux now, while it's there, and the answer points at the copies.
            val copies = if (ev is Ev.Result && !ev.isError) fetchShown(id, ev.text) else emptyMap()
            store.update(id) { c -> c.apply(ev).withCopies(copies).let { if (answered) it.copy(running = false) else if (woke) it.copy(running = true) else it } }
            // Only with the answer in the transcript does the chat stop counting as running (its notification reads it),
            // unless a message came meanwhile: to this process (a new turn), or queued for the next one.
            if (answered) {
                val woken = synchronized(lock) {
                    val done = run.gotResult && run.queue.isEmpty()
                    if (done) {
                        // The countdown first: whoever sees the chat stop running sees when its screen closes.
                        if (run.idle && run.stdinOpen) hold(id, run) else closing(id)
                        running.update { it - id }
                    }
                    !done
                }
                if (woken) store.update(id) { it.copy(running = true) }
            }
        }
    }
}
