package com.adetkov.droid

import org.json.JSONException
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** One line inside a turn's collapsible work log: a tool step or an agent narration note. */
class Line(val text: String, val note: Boolean, val ts: Long = 0)

/** Transcript rows: messages, plus one work-log group per turn (steps and narration). */
sealed interface Item {
    val key: String
    class Message(val index: Int, val msg: Msg) : Item { override val key = "m$index" }
    /**
     * A turn's work log; [start] is when the request was sent, [end] when its last step or its answer came. Its [key] is
     * the turn's ([turn]: where it starts, and when, so a retry in its place is a new row), so it stays the same row, and
     * the same Dash, when its first step comes; [stillKey] names its saved still.
     */
    class Steps(val stillKey: String, val lines: List<Line>, val live: Boolean, val start: Long = 0, val end: Long = 0, val turn: Int = 0) : Item {
        override val key = "t$turn:$start"
    }
}

/**
 * Per turn (a user message and what follows): steps and narration notes fold into one group at the
 * position of the first of them, or of a page shown before them (a page comes from a step, `droid show`, which Codex
 * and OpenCode report only once it's done); the answer stays a message (a result-less run keeps all narration folded).
 * The last turn is "live" while [running] and it has no answer since its latest work: a message sent mid-turn can go
 * in before the answer it came during, and the agent then works on it after that answer.
 */
fun buildItems(messages: List<Msg>, running: Boolean): List<Item> {
    val out = ArrayList<Item>()
    var from = 0
    while (from < messages.size) {
        var to = from + 1
        while (to < messages.size && !messages[to].kind.opensTurn) to++
        val turn = from until to
        val answer = turn.lastOrNull { messages[it].kind == Kind.AGENT }
        val live = running && to == messages.size &&
            (answer == null || turn.any { it > answer && (messages[it].kind == Kind.STEP || messages[it].kind == Kind.NOTE) })
        val start = messages[from].ts
        // The turn's last work; a status ("Interrupted") may come much later, when the app starts again.
        val end = turn.lastOrNull { messages[it].kind != Kind.STATUS }?.let { messages[it].ts } ?: start
        var group = -1
        for (i in turn) {
            val m = messages[i]
            when {
                m.kind == Kind.STEP || m.kind == Kind.NOTE -> {
                    val line = Line(if (m.kind == Kind.NOTE) plainLine(m.text) else m.text, m.kind == Kind.NOTE, m.ts)
                    if (group < 0) { group = out.size; out += Item.Steps("s$i", listOf(line), live, start, end, from) }
                    else (out[group] as Item.Steps).let { out[group] = Item.Steps(it.stillKey, it.lines + line, live, start, end, from) }
                }
                m.kind == Kind.DASH -> {}  // Dash shows it, on the turn's work row
                m.kind == Kind.PAGE && group < 0 -> {
                    group = out.size; out += Item.Steps("s$i", emptyList(), live, start, end, from)
                    out += Item.Message(i, m)
                }
                else -> out += Item.Message(i, m)
            }
        }
        if (live && group < 0) out += Item.Steps("w$from", emptyList(), true, start, end, from)
        from = to
    }
    return out
}

private fun plainLine(s: String) = stripMarkdown(s).replace(Regex("\\s+"), " ")

/** How the last turn ended, for what Dash does after it. */
enum class Outcome { ASKS, DONE, FAILED, STOPPED }

/** How the agent set Dash (`droid dash`): his [feel], and the [words] beside him. */
data class Mood(val feel: Feel, val words: String) {
    /** Whether it's the owner's turn: he calls them (needs_you, or a face of the agent's own built on it). */
    val calls get() = feel.call
}

private val moods = object : LinkedHashMap<String, Mood?>(64, .75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Mood?>?) = size > 64
}

/** A DASH message's [Mood] (null for one this Droid can't read). The same text gives the same Mood, so Dash keeps it. */
fun moodOf(text: String): Mood? = synchronized(moods) {
    moods.getOrPut(text) {
        try {
            val o = JSONObject(text)
            val feel = when (val f = o.opt("feel")) {
                is JSONObject -> Feel.face(f)
                is String -> Feel.of(f) ?: f.takeIf { it.trimStart().startsWith("{") }?.let { Feel.face(JSONObject(it)) }
                else -> null
            }
            feel?.let { Mood(it, o.optString("words").trim().take(120)) }
        } catch (_: JSONException) { null }
    }
}

/** The last turn's messages: everything after the request (or wake-up) that started it. */
fun lastTurn(messages: List<Msg>): List<Msg> = messages.takeLastWhile { !it.kind.opensTurn }

/** How the agent last set Dash in the turn that holds message [index] (the home page's greeting, say), if it did. */
fun moodAt(messages: List<Msg>, index: Int): Mood? {
    if (index !in messages.indices) return null
    val from = (index downTo 0).firstOrNull { messages[it].kind.opensTurn } ?: 0
    val to = (index + 1 until messages.size).firstOrNull { messages[it].kind.opensTurn } ?: messages.size
    return (from until to).lastOrNull { messages[it].kind == Kind.DASH }?.let { moodOf(messages[it].text) }
}

/**
 * Asking Dash for the day's home page: when the owner comes to the home screen, if he hasn't made one today (since 4
 * in the morning) and wasn't asked in the last 3 hours (it may be making it, or have failed), and the owner wants it.
 */
object HomeDay {
    const val REQUEST = "Make my home page for today"
    const val VIA = "Droid asked you for this by itself, as I opened it: put the page on my home screen, and greet me with Dash's words"
    fun dayStart(now: Long): Long = Calendar.getInstance().apply {
        timeInMillis = now - 4 * 3600_000L
        set(Calendar.HOUR_OF_DAY, 4); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    fun due(on: Boolean, madeAt: Long?, askedAt: Long, now: Long = System.currentTimeMillis()): Boolean =
        on && (madeAt == null || madeAt < dayStart(now)) && now - askedAt > 3 * 3600_000L
    /** "this morning", "this afternoon", "this evening", "yesterday", or the day's name. */
    fun madeWhen(ts: Long, now: Long = System.currentTimeMillis()): String {
        val c = Calendar.getInstance().apply { timeInMillis = ts }
        val today = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        return when {
            ts >= today -> when (c.get(Calendar.HOUR_OF_DAY)) { in 0..11 -> "this morning"; in 12..16 -> "this afternoon"; else -> "this evening" }
            ts >= today - 86_400_000L -> "yesterday"
            else -> "on " + SimpleDateFormat("EEEE", Locale.getDefault()).format(c.time)
        }
    }
}

/**
 * A chat on the home screen, as one of Dash's apps: its [name], his [words] about it, how he looks ([feel]), whether
 * it's the owner's turn ([calls]) or he's at it ([working]), and when it last changed ([at]).
 */
data class AppTile(val conv: String, val name: String, val words: String, val feel: Feel, val calls: Boolean, val working: Boolean, val at: Long)

/**
 * The home screen's apps: the chats of the last week (not [hidden], Dash's own home page), the owner's turn first, then
 * what he's at, then the latest; at most [max].
 */
fun appTiles(convs: List<Conv>, running: Set<String>, hidden: String?, now: Long = System.currentTimeMillis(), max: Int = 6,
             within: Long = 7 * 86_400_000L): List<AppTile> =
    convs.filter { it.id != hidden && it.messages.isNotEmpty() && now - it.updatedAt < within }.map { c ->
        val working = c.id in running
        val outcome = lastOutcome(c.messages, working)
        val mood = lastMood(c.messages)
        val calls = !working && (outcome == Outcome.ASKS || mood?.calls == true)
        val failed = outcome == Outcome.FAILED
        val words = when {
            working -> latestStatus(c.messages)
            mood != null && mood.words.isNotBlank() -> mood.words
            failed -> "Couldn't finish"
            else -> ""
        }
        val feel = when { working -> Feel.THINKING; mood != null -> mood.feel; calls -> Feel.NEEDS_YOU; failed -> Feel.CONFUSED; else -> Feel.CONTENT }
        AppTile(c.id, appName(c), words, feel, calls, working, c.updatedAt)
    }.sortedWith(compareByDescending<AppTile> { it.calls }.thenByDescending { it.working }.thenByDescending { it.at }).take(max)

/** How the agent last set Dash in the last turn, if it did. */
fun lastMood(messages: List<Msg>): Mood? = lastTurn(messages).let { turn ->
    // a turn the owner stopped keeps no mood it was given on the way (no "Your turn" for what they stopped)
    if (turn.any { it.kind == Kind.STATUS && it.text == "Stopped" }) null
    else turn.lastOrNull { it.kind == Kind.DASH }?.let { moodOf(it.text) }
}

/**
 * The page the agent's command waits on (`droid show --wait`, not answered yet), in any turn while the chat runs (a
 * message or a wake-up meanwhile doesn't end the wait): Dash calls the owner to it. Null if there is none.
 */
fun waitingPage(messages: List<Msg>, running: Boolean): Msg? =
    if (!running) null else messages.indices.lastOrNull { messages.awaits(it, true) { false } }?.let(messages::get)

/**
 * The last turn's [Outcome]: null while it runs (or before any). It's the owner's turn ([Outcome.ASKS]) only when the
 * agent says so, with `droid dash needs_you`; the app never guesses it from the answer.
 */
fun lastOutcome(messages: List<Msg>, running: Boolean): Outcome? {
    if (running) return null
    val turn = lastTurn(messages)
    val status = turn.lastOrNull { it.kind == Kind.STATUS }
    val answer = turn.lastOrNull { it.kind == Kind.AGENT }
    return when {
        status != null && status.text == "Stopped" -> Outcome.STOPPED
        status != null && (answer == null || status.ts >= answer.ts) -> Outcome.FAILED
        lastMood(messages)?.calls == true -> Outcome.ASKS
        answer != null -> Outcome.DONE
        else -> null
    }
}

/**
 * The line beside Dash after a turn, and a notification's header: "Your turn" when it's the owner's turn, the agent's
 * words when it gave some, "Couldn't finish" for a failure; null for none.
 */
fun turnLabel(outcome: Outcome?, mood: Mood?): String? = when {
    outcome == Outcome.FAILED -> "Couldn't finish"
    outcome == Outcome.STOPPED -> null
    mood == null -> if (outcome == Outcome.ASKS) "Your turn" else null
    mood.calls -> if (mood.words.isEmpty()) "Your turn" else "Your turn · ${mood.words}"
    else -> mood.words.ifEmpty { null }
}

/** What's read aloud of an answer: an app (a page) is to look at, so Dash's few words; an answer in words, itself. */
fun spokenAnswer(conv: Conv?, answer: String): String =
    if (hasHtml(answer)) conv?.let { lastMood(it.messages) }?.words?.takeIf { it.isNotBlank() } ?: answer else answer

/** Current status of the last turn: its latest step or narration note, or what the agent had Dash say. */
fun latestStatus(messages: List<Msg>): String =
    lastTurn(messages).lastOrNull { it.kind == Kind.STEP || it.kind == Kind.NOTE || (it.kind == Kind.DASH && moodOf(it.text)?.words?.isNotEmpty() == true) }
        ?.let { when (it.kind) { Kind.NOTE -> plainLine(it.text); Kind.DASH -> moodOf(it.text)!!.words; else -> it.text } } ?: "Working…"

/** The drawer's date group for a chat last active at [ts]: Today, Yesterday, Previous 7 / 30 days, then by month. */
fun dayGroup(ts: Long, now: Long = System.currentTimeMillis()): String {
    val c = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    val today = c.timeInMillis
    val year = c.get(Calendar.YEAR)
    c.add(Calendar.DAY_OF_YEAR, -1); val yesterday = c.timeInMillis
    c.add(Calendar.DAY_OF_YEAR, -6); val week = c.timeInMillis
    c.add(Calendar.DAY_OF_YEAR, -23); val month = c.timeInMillis
    return when {
        ts >= today -> "Today"
        ts >= yesterday -> "Yesterday"
        ts >= week -> "Previous 7 days"
        ts >= month -> "Previous 30 days"
        else -> {
            val then = Calendar.getInstance().apply { timeInMillis = ts }
            SimpleDateFormat(if (then.get(Calendar.YEAR) == year) "MMMM" else "MMMM yyyy", Locale.getDefault()).format(then.time)
        }
    }
}

/** "12s", "4m 05s", "1h 02m". */
fun duration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "%dm %02ds".format(s / 60, s % 60)
        else -> "%dh %02dm".format(s / 3600, s % 3600 / 60)
    }
}
