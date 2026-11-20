package com.adetkov.droid

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.io.File

sealed interface Block {
    data class Para(val text: String) : Block
    data class Heading(val text: String, val level: Int = 1) : Block
    /** A list item; [depth] counts nesting levels (two spaces or a tab each). */
    data class Item(val marker: String, val text: String, val depth: Int = 0) : Block
    data class Quote(val text: String) : Block
    data class Code(val text: String, val lang: String = "") : Block
    data class Table(val header: List<String>, val rows: List<List<String>>) : Block
    data object Rule : Block
    /** A picture or file the agent shows (a line of its own: see [answerText]). */
    data class Show(val path: String, val caption: String) : Block
}

private val BULLET = Regex("""^(\s*)[-*•+]\s+(.*)$""")
private val NUMBER = Regex("""^(\s*)(\d+)[.)]\s+(.*)$""")
private val TABLE_SEP = Regex("""^\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)*\|?\s*$""")
private val HEADING = Regex("""^(#{1,6})\s+(.*?)(?:\s+#+)?\s*$""")  // a closing run of #s needs a space before it ("C#" stays)
private val RULE = Regex("""^\s*([-*_])(\s*\1){2,}\s*$""")
private val QUOTE = Regex("""^\s*>\s?(.*)$""")

private fun depth(indent: String) = indent.replace("\t", "  ").length / 2

/** Splits text into paragraphs, headings, list items, quotes, rules, tables and fenced code blocks. */
fun parseBlocks(text: String): List<Block> {
    val out = ArrayList<Block>()
    val para = StringBuilder()
    fun flush() { if (para.isNotBlank()) out += Block.Para(para.toString().trim()); para.clear() }
    val lines = text.lines()
    var i = 0
    while (i < lines.size) {
        val l = lines[i]
        if (l.trimStart().startsWith("```")) {
            flush()
            val lang = l.trimStart().removePrefix("```").trim()
            val code = ArrayList<String>()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) { code += lines[i]; i++ }
            out += Block.Code(code.joinToString("\n"), lang)
            i++
            continue
        }
        if ('|' in l && i + 1 < lines.size && '|' in lines[i + 1] && TABLE_SEP.matches(lines[i + 1])) {
            flush()
            val rows = ArrayList<List<String>>()
            i += 2
            while (i < lines.size && '|' in lines[i]) { rows += cells(lines[i]); i++ }
            out += Block.Table(cells(l), rows)
            continue
        }
        if (QUOTE.matches(l)) {
            flush()
            val quote = ArrayList<String>()
            while (i < lines.size) { quote += (QUOTE.matchEntire(lines[i]) ?: break).groupValues[1]; i++ }
            out += Block.Quote(quote.joinToString("\n").trim())
            continue
        }
        SHOW_LINE.matchEntire(l)?.let { m ->
            flush(); out += Block.Show(m.groupValues[2].trim(), m.groupValues[1].trim()); i++; continue
        }
        val r = RULE.matches(l)
        val b = if (r) null else BULLET.matchEntire(l)
        val n = NUMBER.matchEntire(l)
        val h = HEADING.matchEntire(l)
        when {
            r -> { flush(); out += Block.Rule }
            b != null -> { flush(); out += Block.Item("•", b.groupValues[2], depth(b.groupValues[1])) }
            n != null -> { flush(); out += Block.Item(n.groupValues[2] + ".", n.groupValues[3], depth(n.groupValues[1])) }
            h != null -> { flush(); out += Block.Heading(h.groupValues[2], h.groupValues[1].length) }
            l.isBlank() -> flush()
            else -> { if (para.isNotEmpty()) para.append('\n'); para.append(l) }
        }
        i++
    }
    flush()
    return out
}

private fun cells(row: String): List<String> = row.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

// groups: 1 code, 2 bold, 3 link text, 4 link url (or a file the agent shows), 5 *italic*, 6 _italic_, 7 bare url, 8 ~~strike~~
private val INLINE = Regex(
    """`([^`\n]+)`|\*\*(.+?)\*\*|\[([^\]\n]+)]\((https?://[^)\s]+|~?/[^)\n]+)\)|(?<![\w*])\*([^*\s][^*\n]*?)\*(?![\w*])|(?<![\w/])_([^_\s][^_\n]*?)_(?![\w/])|(https?://[^\s)<]+)|~~(.+?)~~"""
)

private class InlineStyle(val code: SpanStyle, val link: TextLinkStyles, val open: (String) -> Unit)

private fun AnnotatedString.Builder.appendInline(s: String, st: InlineStyle) {
    var pos = 0
    for (m in INLINE.findAll(s)) {
        append(s.substring(pos, m.range.first))
        pos = m.range.last + 1
        val g = m.groups
        when {
            g[1] != null -> withStyle(st.code) { append(" " + g[1]!!.value + " ") }
            g[2] != null -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { appendInline(g[2]!!.value, st) }
            g[3] != null -> {
                val url = g[4]!!.value
                withLink(if (url.startsWith("http")) LinkAnnotation.Url(url, st.link)
                    else LinkAnnotation.Clickable(url, st.link) { st.open(url) }) { append(g[3]!!.value) }
            }
            g[5] != null -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInline(g[5]!!.value, st) }
            g[6] != null -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInline(g[6]!!.value, st) }
            g[8] != null -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { appendInline(g[8]!!.value, st) }
            else -> {
                val url = g[7]!!.value.trimEnd('.', ',', ';', ':', '!', '?')
                withLink(LinkAnnotation.Url(url, st.link)) { append(url) }
                append(g[7]!!.value.substring(url.length))
            }
        }
    }
    append(s.substring(pos))
}

@Composable
private fun inlineStyle(): InlineStyle {
    val p = P
    val ctx = LocalContext.current
    return remember(p, ctx) {
        InlineStyle(
            SpanStyle(fontFamily = FontFamily.Monospace, background = p.codeHeader, fontSize = 14.sp),
            TextLinkStyles(SpanStyle(color = p.text, textDecoration = TextDecoration.Underline)),
        ) { path -> openFile(ctx, File(path)) }
    }
}

/**
 * Plain text, markdown markers removed: for reading elsewhere (sharing, notifications, search) links, code, list markers
 * and tables stay, with a blank line between paragraphs; [spoken] (read aloud) leaves out code and says "link" for a URL.
 */
fun stripMarkdown(text: String, spoken: Boolean = false): String {
    if (hasHtml(text)) return stripMarkdown(htmlToText(text), spoken)  // an answer with a page: what it says
    fun inline(s: String) = stripInline(s, spoken)
    val parts = parseBlocks(answerText(text)).mapNotNull { b ->
        when (b) {
            is Block.Para -> inline(b.text)
            is Block.Heading -> inline(b.text)
            is Block.Item -> if (spoken) inline(b.text) else "  ".repeat(b.depth) + "${b.marker} " + inline(b.text)
            is Block.Quote -> inline(b.text)
            is Block.Code -> b.text.takeUnless { spoken }
            is Block.Rule -> null
            is Block.Table -> (listOf(b.header) + b.rows).joinToString("\n") { r -> r.joinToString(if (spoken) ", " else " | ") { inline(it) } }
            is Block.Show -> b.caption.takeIf { it.isNotBlank() }
        }?.let { b to it }
    }
    return parts.withIndex().joinToString("") { (i, p) ->
        val list = p.first is Block.Item && i > 0 && parts[i - 1].first is Block.Item
        (if (i == 0) "" else if (spoken || list) "\n" else "\n\n") + p.second
    }
}

private fun stripInline(s: String, spoken: Boolean): String = INLINE.replace(s) { m ->
    val g = m.groups
    when {
        g[1] != null -> g[1]!!.value
        g[2] != null -> stripInline(g[2]!!.value, spoken)
        g[3] != null -> if (spoken || !g[4]!!.value.startsWith("http")) g[3]!!.value else "${g[3]!!.value} (${g[4]!!.value})"
        g[5] != null -> g[5]!!.value
        g[6] != null -> g[6]!!.value
        g[8] != null -> stripInline(g[8]!!.value, spoken)
        spoken -> "link" + g[7]!!.value.takeLastWhile { it in ".,;:!?" }
        else -> g[7]!!.value
    }
}

@Composable
fun Markdown(text: String, modifier: Modifier = Modifier) {
    val st = inlineStyle()
    val body = MaterialTheme.typography.bodyLarge
    val blocks = remember(text) { parseBlocks(text) }
    fun inline(s: String) = buildAnnotatedString { appendInline(s, st) }
    Column(modifier) {
        for ((i, b) in blocks.withIndex()) {
            // Items of one list sit close together; everything else gets air.
            val prev = blocks.getOrNull(i - 1)
            val gap = when {
                prev == null -> 0.dp
                b is Block.Item && prev is Block.Item -> 6.dp
                b is Block.Heading -> 18.dp
                else -> 12.dp
            }
            if (gap > 0.dp) Spacer(Modifier.height(gap))
            MdBlock(b, body, ::inline)
        }
    }
}

@Composable
private fun MdBlock(b: Block, body: androidx.compose.ui.text.TextStyle, inline: (String) -> AnnotatedString) {
        when (b) {
            is Block.Para -> Text(inline(b.text), style = body)
            is Block.Heading -> Text(
                inline(b.text),
                style = when (b.level) {
                    1 -> MaterialTheme.typography.titleLarge
                    2 -> MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp)
                    else -> MaterialTheme.typography.titleMedium
                },
            )
            is Block.Item -> Row(Modifier.padding(start = (b.depth * 22).dp)) {
                Text(if (b.marker == "•" && b.depth > 0) "◦" else b.marker, Modifier.widthIn(min = if (b.marker == "•") 18.dp else 24.dp),
                    style = body, color = P.muted)
                Text(inline(b.text), style = body)
            }
            is Block.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(P.hairline))
                Text(inline(b.text), Modifier.padding(start = 14.dp), style = body, color = P.muted)
            }
            is Block.Rule -> Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp).background(P.hairline))
            is Block.Table -> MdTable(b, inline)
            is Block.Code -> CodeBlock(b)
            is Block.Show -> ShownFile(b.path, b.caption)
        }
}

@Composable
private fun CodeBlock(b: Block.Code) {
    val copy = rememberCopy()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1600); copied = false } }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, P.hairline, RoundedCornerShape(12.dp)).background(P.codeBg)) {
        Row(Modifier.fillMaxWidth().background(P.codeHeader).padding(start = 14.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(b.lang.ifEmpty { "code" }, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = P.muted)
            Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable { copy(b.text); copied = true }
                .padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(if (copied) R.drawable.ic_check else R.drawable.ic_copy), null, Modifier.size(14.dp), tint = P.muted)
                Text(if (copied) "Copied" else "Copy", Modifier.padding(start = 6.dp), style = MaterialTheme.typography.bodySmall, color = P.muted)
            }
        }
        Text(
            b.text, Modifier.horizontalScroll(rememberScrollState()).padding(14.dp),
            fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp, softWrap = false,
        )
    }
}

/** Columns size to their widest cell; rows are single lines, so every cell in a row has the same height. */
@Composable
private fun MdTable(t: Block.Table, inline: (String) -> AnnotatedString) {
    val line = P.hairline
    val headerBg = P.codeHeader
    val shape = RoundedCornerShape(12.dp)
    // As wide as its columns (up to the screen, then it scrolls), so lines and shading reach the border.
    Box(Modifier.clip(shape).border(1.dp, line, shape)) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            // Each column is as wide as its widest cell, and every cell fills that width so lines and shading meet.
            for (c in t.header.indices) Column(Modifier.width(IntrinsicSize.Max)) {
                Cell(inline(t.header[c]), true, headerBg, line, bottomLine = true)
                t.rows.forEachIndexed { r, row ->
                    Cell(inline(row.getOrElse(c) { "" }), false, null, line, bottomLine = r < t.rows.lastIndex)
                }
            }
        }
    }
}

@Composable
private fun Cell(text: AnnotatedString, header: Boolean, bg: androidx.compose.ui.graphics.Color?, line: androidx.compose.ui.graphics.Color, bottomLine: Boolean) {
    Box(
        Modifier.fillMaxWidth().height(42.dp).let { if (bg != null) it.background(bg) else it }
            .drawBehind { if (bottomLine) drawLine(line, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f) }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = if (header) FontWeight.SemiBold else null, softWrap = false, maxLines = 1)
    }
}
