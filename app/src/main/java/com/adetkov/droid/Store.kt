package com.adetkov.droid

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/**
 * NOTE = agent narration while working; AGENT = a turn's final answer. What the agent tells the app (the `droid`
 * command in its Linux): DASH = how Dash looks and what he says (JSON: feel, words); PAGE = a page it showed (the HTML,
 * with its options in [Msg.data]); WAKE = the agent woke itself up (`droid later`), which starts a turn like a USER message.
 */
enum class Kind { USER, AGENT, NOTE, STEP, STATUS, DASH, PAGE, WAKE }

/** Whether a message of this kind starts a turn: the owner's request, or the agent waking itself up. */
val Kind.opensTurn get() = this == Kind.USER || this == Kind.WAKE

/** Where an attachment came from, which the agent is told: a picked file, a screenshot of the app the assistant was
 * opened over, or a picture from Droid's camera. */
enum class Source { FILE, SCREEN, CAMERA }

/** A file sent with a message: a copy in Droid's attachments folder, which the agent reads through /sdcard. */
data class Attachment(val name: String, val path: String, val mime: String, val source: Source = Source.FILE) {
    val isImage get() = mime.startsWith("image/")
}

/**
 * [data]: what a page sent with a message (droid.send's data, the agent gets it too), or a PAGE's options and reply,
 * as JSON.
 */
data class Msg(val kind: Kind, val text: String, val ts: Long, val files: List<Attachment> = emptyList(), val data: String? = null)

data class Conv(
    val id: String,
    val title: String,
    val sessionId: String? = null,
    val messages: List<Msg> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long = createdAt,
    val running: Boolean = false,
    val pinned: Boolean = false,
    /** The agent this chat runs on, for good: its session only exists in that engine. */
    val engine: Engine = Engine.CLAUDE,
) {
    fun add(kind: Kind, text: String, now: Long = System.currentTimeMillis(), files: List<Attachment> = emptyList(), data: String? = null) =
        copy(messages = messages + Msg(kind, text, now, files, data), updatedAt = now)
}

/**
 * All conversations, persisted as one JSON file: written off the main thread [wait] ms after a change, with what changed
 * meanwhile, or at once ([saveNow]).
 */
class Store(private val file: File, private val wait: Long = 400) {
    private var interrupted = false
    val convs = MutableStateFlow(load())
    private val saveRequests = Channel<Unit>(Channel.CONFLATED)
    private val urgent = Channel<Unit>(Channel.CONFLATED)

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            for (r in saveRequests) {
                withTimeoutOrNull(wait) { urgent.receive() }  // a change that can't wait (see saveNow) cuts the wait short
                // A failed write (e.g. disk full) must not end the loop; the next change retries.
                try { write() } catch (_: IOException) {}
            }
        }
        if (interrupted) save()
    }

    fun get(id: String) = convs.value.firstOrNull { it.id == id }

    fun create(firstPrompt: String, engine: Engine = Engine.CLAUDE): Conv {
        val now = System.currentTimeMillis()
        val c = Conv(UUID.randomUUID().toString(), makeTitle(firstPrompt), createdAt = now, engine = engine)
        convs.update { it + c }
        save()
        return c
    }

    fun update(id: String, f: (Conv) -> Conv) {
        convs.update { list -> list.map { if (it.id == id) f(it) else it } }
        save()
    }

    fun rename(id: String, title: String) = update(id) { it.copy(title = title.trim()) }

    /** Pinning doesn't count as activity: the chat keeps its place in the date groups. */
    fun setPinned(id: String, pinned: Boolean) = update(id) { it.copy(pinned = pinned) }

    fun delete(id: String) {
        convs.update { list -> list.filter { it.id != id } }
        save()
    }

    private fun save() { saveRequests.trySend(Unit) }

    /** Writes the chats now rather than after the usual wait: what changed exists nowhere else (a wake-up's message). */
    fun saveNow() { saveRequests.trySend(Unit); urgent.trySend(Unit) }

    private fun write() {
        val arr = JSONArray()
        for (c in convs.value) arr.put(JSONObject().apply {
            put("id", c.id); put("title", c.title); put("sessionId", c.sessionId)
            put("createdAt", c.createdAt); put("updatedAt", c.updatedAt); put("running", c.running)
            if (c.pinned) put("pinned", true)
            if (c.engine != Engine.CLAUDE) put("engine", c.engine.id)
            put("messages", JSONArray().also { a ->
                c.messages.forEach { m ->
                    val o = JSONObject().put("k", m.kind.name).put("t", m.text).put("ts", m.ts)
                    if (m.data != null) o.put("d", m.data)
                    if (m.files.isNotEmpty()) o.put("f", JSONArray().also { fa ->
                        m.files.forEach { f ->
                            fa.put(JSONObject().put("n", f.name).put("p", f.path).put("m", f.mime).apply {
                                when (f.source) { Source.SCREEN -> put("s", true); Source.CAMERA -> put("c", true); Source.FILE -> {} }
                            })
                        }
                    })
                    a.put(o)
                }
            })
        })
        writeWhole(file, arr.toString())
    }

    /** The saved chats. One that can't be read is left out, not the others with it; the file is kept aside first. */
    private fun load(): List<Conv> {
        if (!file.exists()) return emptyList()
        val arr = try { JSONArray(file.readText()) } catch (_: Exception) { keepAside(file); return emptyList() }
        val list = (0 until arr.length()).mapNotNull { i -> try { conv(arr.getJSONObject(i)) } catch (_: JSONException) { null } }
        if (list.size < arr.length()) keepAside(file)
        // A run still marked running means the process died mid-task.
        return list.map {
            if (!it.running) it else { interrupted = true; it.add(Kind.STATUS, "Interrupted").copy(running = false) }
        }
    }

    private fun conv(o: JSONObject): Conv {
        val ms = o.getJSONArray("messages")
        return Conv(
            id = o.getString("id"), title = o.getString("title"),
            sessionId = if (o.isNull("sessionId")) null else o.getString("sessionId"),
            messages = (0 until ms.length()).mapNotNull { j ->
                val m = ms.getJSONObject(j)
                val fa = m.optJSONArray("f")
                val files = if (fa == null) emptyList() else (0 until fa.length()).map { k ->
                    fa.getJSONObject(k).let { f ->
                        val source = when { f.optBoolean("s") -> Source.SCREEN; f.optBoolean("c") -> Source.CAMERA; else -> Source.FILE }
                        Attachment(f.getString("n"), f.getString("p"), f.getString("m"), source)
                    }
                }
                val kind = runCatching { Kind.valueOf(m.getString("k")) }.getOrNull() ?: return@mapNotNull null  // from a newer Droid
                Msg(kind, m.getString("t"), m.getLong("ts"), files, if (m.isNull("d")) null else m.optString("d").ifEmpty { null })
            },
            createdAt = o.getLong("createdAt"), updatedAt = o.getLong("updatedAt"),
            running = o.getBoolean("running"), pinned = o.optBoolean("pinned"), engine = Engine.of(o.optString("engine")),
        )
    }
}

/** Replaces [file] with [text] whole: written beside it and onto the disk first, so a crash leaves the old or the new. */
internal fun writeWhole(file: File, text: String) {
    val tmp = File(file.path + ".tmp")
    FileOutputStream(tmp).use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
    if (!tmp.renameTo(file)) throw IOException("Could not replace ${file.name}")
}

/** Copies [file], which couldn't all be read, to `.bad` beside it, before a save leaves out what couldn't be read. */
internal fun keepAside(file: File) {
    try { file.copyTo(File(file.path + ".bad"), overwrite = true) } catch (_: IOException) {}
}

/** Chat title from the first prompt: one line, first letter capitalised, trailing punctuation dropped. */
fun makeTitle(prompt: String): String {
    val t = prompt.replace(Regex("\\s+"), " ").trim().trimEnd { it in ".,;:!?…-–— " }.take(60).trimEnd()
    return t.replaceFirstChar { it.uppercase() }.ifEmpty { "New chat" } + if (prompt.trim().length > 60) "…" else ""
}
