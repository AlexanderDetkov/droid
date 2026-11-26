package com.adetkov.droid

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/*
 * Pages: what the agent builds for the owner. An answer can hold any HTML, CSS and JS among its Markdown, and the agent
 * can show a page of its own at any time (`droid show`). Either renders as one web document styled like the app
 * (PageView.kt); a page talks back through `droid` (assets/page/droid.js): send, run, dash, screen, close.
 */

/**
 * A page the agent showed with `droid show` (a PAGE message, its HTML in the text): [id] names it (and the answer file
 * a waiting command reads), [wait]: the agent's command waits for the page's answer, [where]: chat, full, float, home
 * (the home screen, under Dash) or notify, [title]: its <title>, [reply]: what the owner answered (null until then; empty when it went to the chat as a
 * message). [note]: for a page a notification's button opens (`page:`), that notification's id: while the agent waits
 * on the notification, the page answers it. [base]: the app folder it was shown from (see [appBase]), whose files it
 * loads by relative path; [path]: the file itself, read again when the app refreshes. [past]: where it was shown in an
 * earlier turn, before it was shown again (see [withPage]): in the history only, its HTML dropped.
 */
data class PageInfo(
    val id: String, val wait: Boolean = false, val where: String = "chat", val title: String = "", val reply: String? = null,
    val note: String = "", val base: String = "", val path: String = "", val past: Boolean = false,
) {
    fun json(): String = JSONObject().put("id", id).put("wait", wait).put("where", where).put("title", title)
        .apply { if (reply != null) put("reply", reply); if (note.isNotEmpty()) put("note", note); if (base.isNotEmpty()) put("base", base).put("path", path); if (past) put("past", true) }.toString()

    companion object {
        fun of(m: Msg): PageInfo = parse(m.data)
        fun parse(json: String?): PageInfo = try {
            JSONObject(json ?: "{}").let { o ->
                PageInfo(o.optString("id"), o.optBoolean("wait"), o.optString("where", "chat").ifEmpty { "chat" }, o.optString("title"),
                    if (o.has("reply")) o.optString("reply") else null, o.optString("note"), o.optString("base"), o.optString("path"), o.optBoolean("past"))
            }
        } catch (_: JSONException) { PageInfo("") }
    }
}

/** The PAGE message [pageId] (its latest, if shown again), or null. */
fun Conv.page(pageId: String): Msg? = messages.getOrNull(pageIndex(pageId))

/** Where the PAGE message [pageId] is in the chat (its latest), or -1. */
fun Conv.pageIndex(pageId: String): Int = messages.indexOfLast { it.kind == Kind.PAGE && PageInfo.of(it).let { p -> p.id == pageId && !p.past } }

/**
 * Whether the agent's command waits on the PAGE at [index] of these messages now, while its chat's run [working]: one
 * shown with --wait (or waited on again, `droid wait`) that isn't answered, in any turn, unless a status after it says
 * its run ended (an app that died mid-run leaves the wait behind); or one a notification's button opens, while the
 * command waits on that notification ([noteWaited], by the notification's id).
 */
fun List<Msg>.awaits(index: Int, working: Boolean, noteWaited: (String) -> Boolean): Boolean {
    val m = getOrNull(index)?.takeIf { it.kind == Kind.PAGE } ?: return false
    val p = PageInfo.of(m)
    if (!working || p.reply != null || p.past) return false
    return if (p.note.isNotEmpty()) noteWaited(p.note) else p.wait && (index + 1 until size).none { this[it].kind == Kind.STATUS }
}

/**
 * The chat's app: the newest page the agent made for the owner in it (an answer with HTML, or a page it showed in the
 * chat), which is what the owner's screen shows of the chat and stays live across turns; -1 for none.
 */
fun List<Msg>.currentApp(): Int = indexOfLast { (it.kind == Kind.AGENT && hasHtml(it.text)) || (it.kind == Kind.PAGE && PageInfo.of(it).let { p -> !p.past && (p.where == "chat" || p.where == "home") }) }

/**
 * The chat's app from before the turn that starts at [start], which that turn works on: if it was still on screen when
 * the turn began. An answer in words in a later turn than the app's drops it, and so does a failure; the few words that
 * end the app's own turn (it was shown, then "Here it is.") keep it. Null for none.
 */
fun List<Msg>.appBefore(start: Int): Int? = currentApp().takeIf { at ->
    at in 0..start && (at + 1 until start).none { i ->
        this[i].let { (it.kind == Kind.AGENT && it.text.isNotBlank() && !hasHtml(it.text) && (at + 1 until i).any { j -> this[j].kind.opensTurn }) ||
            (it.kind == Kind.STATUS && it.text != "Stopped") }
    }
}

/**
 * The one test of whether the page at [index] may still act (send, run, screen, Dash). The chat's app ([currentApp])
 * may, for as long as it is that: what it sends comes to the agent as a message and it goes on, live. Other pages answer
 * once: a PAGE the agent showed may while it isn't answered and it is in the chat's latest turn, or the agent's command
 * still waits on it ([awaits]); an answer with HTML, while it is in the latest turn.
 */
fun List<Msg>.pageLive(index: Int, working: Boolean, noteWaited: (String) -> Boolean): Boolean {
    val m = getOrNull(index) ?: return false
    if (m.kind == Kind.PAGE && PageInfo.of(m).reply != null) return false
    return index > indexOfLast { it.kind.opensTurn } || index == currentApp() || awaits(index, working, noteWaited)
}

/**
 * The chat with page [info] shown, [html] its content: a new PAGE message, or the one with its id shown again (live
 * again, unanswered): in place while it is in the latest turn, else at the end, in the turn that shows it. The earlier
 * showing stays where it was, [PageInfo.past], so its turn in the history keeps it and nothing after it moves: the
 * chat's pictures are kept by message index (see [Stills]). Moving it left every later picture one message off.
 */
fun Conv.withPage(info: PageInfo, html: String, now: Long = System.currentTimeMillis()): Conv {
    val i = pageIndex(info.id)
    if (i < 0) return add(Kind.PAGE, html, now, data = info.json())
    val m = messages[i].copy(text = html, data = info.json())
    if (i > messages.indexOfLast { it.kind.opensTurn }) return copy(messages = messages.toMutableList().also { it[i] = m })
    val was = messages[i].let { it.copy(text = "", data = PageInfo.of(it).copy(wait = false, past = true).json()) }
    return copy(messages = messages.toMutableList().also { it[i] = was } + m.copy(ts = now), updatedAt = now)
}

/** The chat with page [pageId]'s latest copy now holding [html] (its app refreshed itself): in place, its title read again. */
fun Conv.withPageText(pageId: String, html: String): Conv {
    val i = pageIndex(pageId)
    if (i < 0) return this
    val m = messages[i]
    return copy(messages = messages.toMutableList().also { it[i] = m.copy(text = html, data = PageInfo.of(m).copy(title = pageTitle(html)).json()) })
}

/** The chat with page [pageId] answered: [reply] is what the agent's command got, or empty for a message (the chat shows that). */
fun Conv.answered(pageId: String, reply: String): Conv {
    val i = pageIndex(pageId)
    if (i < 0) return this
    return copy(messages = messages.toMutableList().also { it[i] = it[i].copy(data = PageInfo.of(it[i]).copy(reply = reply).json()) })
}

/**
 * The chat with the agent's command waiting on page [pageId] again (`droid wait`), or no longer ([on] false: it gave
 * up; [pageId] null: any page, as its run ended). A page already answered stays as it is.
 */
fun Conv.waitingOn(pageId: String?, on: Boolean): Conv = copy(messages = messages.map { m ->
    val p = if (m.kind == Kind.PAGE) PageInfo.of(m) else null
    if (p == null || p.past || p.reply != null || p.wait == on || (pageId != null && p.id != pageId)) m else m.copy(data = p.copy(wait = on).json())
})

/** What a waiting command gets from a page (droid.send): its [text], and its [data] as the JSON it is. */
fun pageReply(text: String, data: String?): JSONObject = JSONObject().put("text", text).apply {
    if (data != null) put("data", try { JSONTokener(data).nextValue() } catch (_: JSONException) { data })
}

/** A page id as the agent may give it: it names a file in its Linux, so only these characters. */
val PAGE_ID = Regex("[A-Za-z0-9_-]{1,64}")

private val TAG = Regex("""<(/?)([a-zA-Z][a-zA-Z0-9-]*)(?:\s[^<>]*)?/?>""")
private val FENCE = Regex("""^ {0,3}(```|~~~)""")
private val INLINE_CODE = Regex("`[^`\n]*`")

/** Whether an answer holds HTML outside its code, and so renders as a page rather than as plain Markdown. */
fun hasHtml(text: String): Boolean {
    if ('<' !in text) return false
    var fenced = false
    for (line in text.lineSequence()) {
        if (FENCE.containsMatchIn(line)) { fenced = !fenced; continue }
        if (!fenced && TAG.containsMatchIn(INLINE_CODE.replace(line, ""))) return true
    }
    return false
}

private val BLOCK_START = Regex("""^ {0,3}<(!--|[a-zA-Z][a-zA-Z0-9-]*)""")
/** Tags that start a line of Markdown text rather than a block of HTML. */
private val INLINE = setOf("a", "abbr", "b", "bdi", "bdo", "br", "cite", "code", "data", "dfn", "em", "i", "kbd", "mark", "q", "s",
    "samp", "small", "span", "strong", "sub", "sup", "time", "u", "var", "wbr", "del", "ins")
private val VOID = setOf("area", "base", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "param")
/** Blocks whose insides are never tags of their own. */
private val RAW = setOf("script", "style", "textarea", "pre", "template", "title")

/**
 * An answer cut into Markdown and HTML, in order (true: HTML). A block of HTML runs from a line that starts with a tag
 * to the line that closes it. Inside it, a part after a blank line is Markdown again unless it starts with a tag (as
 * CommonMark has it: `<div class="card">`, a blank line, `**bold**` and a list, `</div>`), and the agent's indented
 * markup stays markup (CommonMark would make an indented line after a blank one code).
 */
fun segments(text: String): List<Pair<Boolean, String>> {
    val lines = text.lines()
    val out = ArrayList<Pair<Boolean, String>>()
    val md = StringBuilder()
    fun flush() { if (md.isNotBlank()) out += false to md.toString().trim('\n'); md.clear() }
    var fenced = false
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        if (FENCE.containsMatchIn(line)) fenced = !fenced
        val name = if (fenced) null else BLOCK_START.find(line)?.groupValues?.get(1)?.lowercase()
        if (name == null || name in INLINE) { md.append(line).append('\n'); i++; continue }
        val end = blockEnd(lines, i, name)
        flush()
        val block = lines.subList(i, end + 1)
        if (name in RAW || name in VOID || name == "!--") out += true to block.joinToString("\n") else out += blockSegments(block)
        i = end + 1
    }
    flush()
    return out
}

private val RAW_OPEN = Regex("""<(script|style|textarea|pre|template|title)\b[^>]*>""", RegexOption.IGNORE_CASE)

/**
 * A block of HTML in parts after blank lines: those that start with a tag stay HTML, the rest are Markdown. What's
 * inside a raw element (a draft in a <textarea>, code in a <pre>, a <script>) stays whole, blank lines and all.
 */
private fun blockSegments(block: List<String>): List<Pair<Boolean, String>> {
    val out = ArrayList<Pair<Boolean, String>>()
    val part = ArrayList<String>()
    var raw: Regex? = null  // the close of a raw element open on an earlier line
    fun flush() {
        if (part.isEmpty()) return
        val html = part.first().trimStart().startsWith("<")
        // Markdown loses the indentation the markup around it had (not its own: a nested list stays nested)
        val indent = if (html) 0 else part.minOf { it.length - it.trimStart().length }
        val text = part.joinToString("\n") { it.drop(indent) }
        if (html && out.lastOrNull()?.first == true) out[out.lastIndex] = true to out.last().second + "\n\n" + text else out += html to text
        part.clear()
    }
    for (line in block) {
        if (raw != null) { part += line; if (raw.containsMatchIn(line)) raw = null; continue }
        if (line.isBlank()) { flush(); continue }
        part += line
        RAW_OPEN.findAll(line).lastOrNull()?.let { m ->
            val close = Regex("</${m.groupValues[1]}\\s*>", RegexOption.IGNORE_CASE)
            if (!close.containsMatchIn(line.substring(m.range.last + 1))) raw = close
        }
    }
    flush()
    return out
}

private fun blockEnd(lines: List<String>, from: Int, name: String): Int {
    if (name == "!--") return (from until lines.size).firstOrNull { "-->" in lines[it] } ?: lines.lastIndex
    if (name in VOID) return from
    val close = Regex("</$name\\s*>", RegexOption.IGNORE_CASE)
    if (name in RAW) return (from until lines.size).firstOrNull { close.containsMatchIn(lines[it]) } ?: lines.lastIndex
    val open = Regex("<$name(?=[\\s/>])[^>]*?(/?)>", RegexOption.IGNORE_CASE)
    var depth = 0
    for (j in from until lines.size) {
        depth += open.findAll(lines[j]).count { it.groupValues[1].isEmpty() }
        depth -= close.findAll(lines[j]).count()
        if (depth <= 0) return j
    }
    return lines.lastIndex
}

private val HIDDEN = Regex("""<(script|style|template|head|title)\b[^>]*>.*?</\1\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
private val BREAK = Regex("""<(br|/p|/div|/li|/tr|/h[1-6]|/section|/label|/button|/option|/table|/ul|/ol|/form|/blockquote|/pre)\b[^>]*>""", RegexOption.IGNORE_CASE)
private val ENTITY = Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);")

/**
 * What an answer with HTML says, as Markdown text: without its scripts, styles and tags, a line per block. For
 * copying, reading aloud, notifications and search, which can't show the page itself.
 */
fun htmlToText(text: String): String {
    val noCode = HIDDEN.replace(COMMENT.replace(text, ""), "")
    val lines = BREAK.replace(noCode) { "\n" }.let { TAG.replace(it, "") }
    val decoded = ENTITY.replace(lines) { m ->
        val e = m.groupValues[1]
        when {
            // (a number that's no character, past U+10FFFF, stays as written: Character.toChars would throw)
            e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)?.takeIf(Character::isValidCodePoint)?.let { String(Character.toChars(it)) } ?: m.value
            e.startsWith("#") -> e.substring(1).toIntOrNull()?.takeIf(Character::isValidCodePoint)?.let { String(Character.toChars(it)) } ?: m.value
            else -> when (e) { "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"; "nbsp" -> " "; "mdash" -> "—"; "ndash" -> "–"; "hellip" -> "…"; "middot" -> "·"; else -> m.value }
        }
    }
    return decoded.lines().joinToString("\n") { it.trim() }.replace(BLANK_LINES, "\n\n").trim()
}

private val BLANK_LINES = Regex("\n{3,}")
private val PAGE_SPACES = Regex("\\s+")
private val TITLE = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val NAMED_PATH = Regex("""(?:src|href|data|poster)\s*=\s*["'](/[^"'<>]+)["']|url\(\s*["']?(/[^"')]+)""", RegexOption.IGNORE_CASE)
private val MD_PATH = Regex("""\]\((/[^)\s]+)\)""")

/** The text of an HTML page's <title>, if it has one. */
fun pageTitle(html: String): String =
    TITLE.find(html)
        ?.groupValues?.get(1)?.let(::htmlToText)?.replace(PAGE_SPACES, " ")?.trim().orEmpty()

/**
 * The origin (see [pageOrigin]) a chat's app and its answers live at: one for the whole chat, so what the app keeps
 * (localStorage: a draft, a pick, a timer's end) carries over to its next version. A page the agent showed elsewhere
 * than the chat ([info], where it isn't "chat": a question it waits on in a notification, say) keeps its own.
 */
fun chatOrigin(conv: String, info: PageInfo? = null): String =
    if (info == null || info.where == "chat") "app:$conv" else "page:$conv:${info.id}"

/** A chat's name on the owner's screen: the owner's own, if they renamed it; else its app's, as the agent named it (its <title>), or the chat's. */
fun appName(c: Conv): String = if (c.named) c.title
    else c.messages.currentApp().takeIf { it >= 0 }?.let { pageTitle(c.messages[it].text) }?.ifBlank { null } ?: c.title

/** The JSON the page script renders an answer from: its segments, each [isHtml, text]. */
internal fun segmentsJson(text: String): String = JSONArray().apply {
    segments(text).forEach { (html, t) -> put(JSONArray().put(html).put(t)) }
}.toString()

/** Every absolute path a page names (in an attribute or a url()), which it may load from the agent's Linux. */
internal fun namedPaths(html: String): Set<String> =
    NAMED_PATH.findAll(html)
        .map { m -> m.groupValues[1].ifEmpty { m.groupValues[2] } }.toSet() +
        // and a Markdown image or link: ![caption](/root/x.png)
        MD_PATH.findAll(html).map { it.groupValues[1] }
