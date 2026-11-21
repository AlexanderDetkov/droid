package com.adetkov.droid

import org.json.JSONObject

/** Events extracted from one line of Claude Code stream-json output. */
sealed interface Ev {
    /** First stdout line of a run: process id, and the live view's port and the token it expects. */
    data class Agent(val pid: Int, val port: Int, val token: String?) : Ev
    data class Session(val id: String) : Ev
    data class Text(val text: String) : Ev
    data class Step(val text: String, val work: Work = Work.RUN) : Ev
    /** The agent tapped its hidden screen at [x], [y] (the screen's pixels), seen in the command it ran. */
    data class Tap(val x: Int, val y: Int) : Ev
    data class Result(val isError: Boolean, val text: String) : Ev
    /** What the agent told the app with the `droid` command (see assets/setup/bin/droid): its [op] and the rest of [o]. */
    data class Droid(val op: String, val o: JSONObject) : Ev
}

internal const val NEW_SESSION = "New session: the agent doesn't remember this chat's earlier messages"

fun parseLine(line: String): List<Ev> {
    val o = try { JSONObject(line) } catch (e: Exception) { return emptyList() }
    val out = ArrayList<Ev>()
    when (o.optString("type")) {
        "agent" -> {
            out += Ev.Agent(o.optInt("pid", -1), o.optInt("port", -1), o.optString("token").ifEmpty { null })
            // The chat's session wasn't in this Linux (see phone-agent): the agent starts over, and the chat says so.
            if (o.optBoolean("new_session")) out += Ev.Step(NEW_SESSION)
        }
        "system" -> if (o.optString("subtype") == "init") o.optString("session_id").takeIf { it.isNotEmpty() }?.let { out += Ev.Session(it) }
        "assistant" -> {
            val content = o.optJSONObject("message")?.optJSONArray("content") ?: return out
            for (i in 0 until content.length()) {
                val c = content.optJSONObject(i) ?: continue
                when (c.optString("type")) {
                    "text" -> c.optString("text").takeIf { it.isNotBlank() }?.let { out += Ev.Text(it) }
                    "tool_use" -> { out += Ev.Step(stepText(c), workOf(c)); tapIn(c)?.let { out += it } }
                }
            }
        }
        "droid" -> o.optString("op").takeIf { it.isNotEmpty() }?.let { out += Ev.Droid(it, o) }
        "result" -> {
            o.optString("session_id").takeIf { it.isNotEmpty() }?.let { out += Ev.Session(it) }
            out += Ev.Result(o.optBoolean("is_error", false), o.optString("result"))
        }
    }
    return out
}

/** `android input [-d N] tap X Y` (or `input tap X Y`) in a tool's command: where the agent tapped. */
private val TAP = Regex("""\binput\b(?:\s+-d\s+\d+)?(?:\s+touchscreen)?\s+tap\s+(\d+)\s+(\d+)""")

private fun tapIn(tool: JSONObject): Ev.Tap? {
    val cmd = tool.optJSONObject("input")?.optString("command").orEmpty()
    val m = TAP.find(cmd) ?: return null
    // the agent's numbers: one too big for an Int is no tap
    return Ev.Tap(m.groupValues[1].toIntOrNull() ?: return null, m.groupValues[2].toIntOrNull() ?: return null)
}

/** What kind of thing a step does on the phone, for Dash: he looks, types, scrolls or waits as the agent does. */
enum class Work { THINK, LOOK, TAP, TYPE, SCROLL, OPEN, BACK, WAIT, READ, WEB, WRITE, RUN }

private val OPEN = Regex("""\bam\s+start\b|\bmonkey\s+-p\b""")
private val TYPE = Regex("""\binput\b(?:\s+-d\s+\d+)?\s+(?:text\b|keyevent\s+(?:KEYCODE_)?(?:66|67|61|ENTER|DEL|TAB)\b)""")
private val SCROLL = Regex("""\binput\b(?:\s+-d\s+\d+)?(?:\s+touchscreen)?\s+(?:swipe|draganddrop|scroll|roll)\b""")
private val BACK = Regex("""\binput\b(?:\s+-d\s+\d+)?\s+keyevent\b""")
private val LOOK = Regex("""(?:^|[;&|(]\s*)shot\b|\buiautomator\s+dump\b|\bscreencap\b""")
private val WAIT = Regex("""^\s*sleep\s+[\d.]+\s*$""")
private val WEB = Regex("""\b(?:curl|wget)\b""")
private val READ = Regex("""^\s*(?:cat|head|tail|grep|rg|ls|find|less|jq)\b""")

/** [Work] for a tool call: by the tool, and for a command by what it does first that shows (an app opening before the
 *  screenshot after it). */
internal fun workOf(tool: JSONObject): Work {
    val input = tool.optJSONObject("input")
    val file = input?.optString("file_path").orEmpty().substringAfterLast('/')
    return when (tool.optString("name")) {
        "Read" -> if (file.startsWith("shot") || file.endsWith(".png") || file.endsWith(".jpg")) Work.LOOK else Work.READ
        "Write", "Edit", "MultiEdit", "NotebookEdit" -> Work.WRITE
        "Grep", "Glob", "LS" -> Work.READ
        "WebFetch", "WebSearch" -> Work.WEB
        "TodoWrite", "Task", "Agent" -> Work.THINK
        "Bash" -> {
            val cmd = input?.optString("command").orEmpty()
            when {
                OPEN.containsMatchIn(cmd) -> Work.OPEN
                TYPE.containsMatchIn(cmd) -> Work.TYPE
                SCROLL.containsMatchIn(cmd) -> Work.SCROLL
                TAP.containsMatchIn(cmd) -> Work.TAP
                BACK.containsMatchIn(cmd) -> Work.BACK
                LOOK.containsMatchIn(cmd) -> Work.LOOK
                WAIT.containsMatchIn(cmd) -> Work.WAIT
                WEB.containsMatchIn(cmd) -> Work.WEB
                READ.containsMatchIn(cmd) -> Work.READ
                else -> Work.RUN
            }
        }
        else -> Work.RUN
    }
}

private fun stepText(tool: JSONObject): String {
    val input = tool.optJSONObject("input")
    val desc = input?.optString("description").orEmpty()
    if (desc.isNotBlank()) return desc
    val name = tool.optString("name")
    // The owner reads these as what Dash is doing: in plain words, not file names. `shot` names its screenshots shot*.png;
    // a page the agent writes for them is .html.
    val file = input?.optString("file_path").orEmpty().substringAfterLast('/').lowercase()
    if (file.isNotEmpty()) when (name) {
        "Read" -> return when {
            file.startsWith("shot") -> "Looking at the screen"
            file.substringAfterLast('.') in setOf("png", "jpg", "jpeg", "webp", "gif", "heic") -> "Looking at a picture"
            else -> "Checking my notes"
        }
        "Write", "Edit", "MultiEdit" -> return if (file.endsWith(".html") || file.endsWith(".htm")) "Making your page" else "Taking notes"
    }
    when (name) {
        "Grep", "Glob" -> return "Searching my notes"
        "TodoWrite" -> return "Making a plan"
        "WebSearch" -> return "Searching the web"
        "WebFetch" -> return "Reading a web page"
        "Task", "Agent" -> return "Getting help with it"
    }
    val cmd = input?.optString("command").orEmpty().replace(Regex("\\s+"), " ").trim().take(60)
    return if (cmd.isEmpty()) name else "$name $cmd"
}

/** Applies one event to a conversation. Agent text is narration (NOTE) until a result promotes the final answer. */
fun Conv.apply(ev: Ev, now: Long = System.currentTimeMillis()): Conv = when (ev) {
    is Ev.Agent -> this
    is Ev.Session -> copy(sessionId = ev.id)
    is Ev.Text -> add(Kind.NOTE, ev.text, now)
    is Ev.Step -> add(Kind.STEP, ev.text, now)
    is Ev.Tap -> this
    is Ev.Droid -> this  // the runner acts on it (see Runner.droid)
    is Ev.Result ->
        if (ev.isError) add(Kind.STATUS, "Error: " + ev.text.ifBlank { "Failed" }, now) else finishTurn(ev.text, now)
}

/** The result text becomes the turn's answer: the last narration note if it matches, else a new message. */
private fun Conv.finishTurn(result: String, now: Long): Conv {
    val start = messages.indexOfLast { it.kind.opensTurn } + 1
    val turn = messages.subList(start, messages.size)
    val text = result.trim()
    if (turn.any { it.kind == Kind.AGENT && it.text.trim() == text }) return this
    val note = turn.indexOfLast { it.kind == Kind.NOTE }
    return when {
        note >= 0 && (text.isEmpty() || turn[note].text.trim() == text) ->
            copy(messages = messages.toMutableList().also { it[start + note] = it[start + note].copy(kind = Kind.AGENT) })
        text.isNotEmpty() -> add(Kind.AGENT, result, now)
        else -> this
    }
}

/** Where the chroot sees the phone's shared storage: enter.sh bind-mounts /storage/emulated/0 there. */
private const val ANDROID_STORAGE = "/storage/emulated/0/"

/**
 * What the agent is sent for a message: its text, then one line per attached file with the path the agent can read,
 * then what a page sent with it ([data], droid.send's second argument).
 */
@android.annotation.SuppressLint("SdCardPath")  // the path inside the chroot, not Android's
fun agentText(text: String, files: List<Attachment>, data: String? = null, via: String? = null): String {
    // How the owner reached the agent (said aloud and read back, over another app, from the lock screen…), last
    if (via != null) return agentText(text, files, data).let { if (it.isBlank()) "[$via]" else "$it\n\n[$via]" }
    if (data != null) return agentText(text, files) + "\n\n[Sent from your page: $data]"
    if (files.isEmpty()) return text
    val lines = files.joinToString("\n") {
        val path = it.path.replaceFirst(ANDROID_STORAGE, "/sdcard/")
        when (it.source) {
            Source.SCREEN -> "[Screenshot of my screen: $path]"
            Source.CAMERA -> "[Photo I just took: $path]"
            Source.FILE -> "[Attached: $path]"
        }
    }
    return if (text.isBlank()) lines else "$text\n\n$lines"
}

/** One stream-json user message for the agent's stdin (a single line). */
fun userLine(text: String): String = JSONObject()
    .put("type", "user")
    .put("message", JSONObject().put("role", "user").put("content", text))
    .toString()
