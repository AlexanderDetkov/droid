package com.adetkov.droid

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/*
 * Home: Dash, and every chat in one list. What needs you comes first (swipe one for Not now), then the apps you pinned,
 * then everything else, newest first, with what he's working on at its top. Search is the top left; a search that
 * finds nothing offers to ask him instead. On a first visit, three things to try.
 */

/** Starting points on a first visit: a tap sends the request. The quick ones, so a first try answers within a minute. */
private val STARTERS = listOf(
    Triple(R.drawable.ic_s_calendar, "What's on my calendar?", "What's on my calendar today and tomorrow?"),
    Triple(R.drawable.ic_s_alarm, "Set a 10-minute timer", "Set a timer for 10 minutes"),
    Triple(R.drawable.ic_s_dnd, "Quiet my phone till morning", "Quiet my phone until tomorrow morning"),
)

private val COUNTS = listOf("No", "One", "Two", "Three", "Four", "Five", "Six")

@Composable
fun HomeScreen(
    convs: List<Conv>, running: Set<String>, notNow: Map<String, Long>, modifier: Modifier,
    listening: Boolean, level: () -> Float, typing: Boolean,
    /** Dash is making the owner's day page. */
    preparing: Boolean,
    /** What the owner searches for; null: not searching. */
    query: String?,
    onAsk: (String) -> Unit, onOpen: (String) -> Unit, onNotNow: (String) -> Unit, onMenu: (Conv) -> Unit,
) {
    val rig = rememberDash()  // at rest, so the greeting is a change: he hops
    var greeted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(1400); greeted = true }
    LaunchedEffect(listening, typing, greeted) {
        rig.feel(when { listening -> Feel.LISTENING; typing -> Feel.READING; !greeted -> Feel.HAPPY; else -> Feel.IDLE })
    }
    rig.level = level
    val byId = remember(convs) { convs.associateBy { it.id } }
    val tileCache = remember { TileCache() }
    val tiles = remember(convs, running, notNow) {
        appTiles(convs, running, hidden = null, max = Int.MAX_VALUE, within = Long.MAX_VALUE, notNow = notNow, cache = tileCache)
    }
    val q = query?.trim().orEmpty()
    val ink = P.text
    val found = remember(tiles, q, ink) { if (q.isEmpty()) emptyList() else tiles.mapNotNull { t -> byId[t.conv]?.let { match(it, t, q, ink) } } }
    val needs = tiles.filter { it.calls }
    val pinned = tiles.filter { !it.calls && byId[it.conv]?.pinned == true }
    val rest = tiles.filter { !it.calls && byId[it.conv]?.pinned != true }
    val words = when {
        listening -> "I'm listening…"
        needs.size == 1 -> "One thing needs you."
        needs.size > 1 -> "${COUNTS.getOrElse(needs.size) { needs.size.toString() }} things need you."
        preparing -> "Getting your day ready…"
        tiles.isEmpty() -> "Hi! I'm Dash. What should I do?"
        else -> "Hi! What should I do?"
    }
    // (Dash spans the width, as on every screen; the list keeps 20 dp from the edges)
    val side = Modifier.padding(horizontal = 20.dp)
    LazyColumn(modifier.testTag("home"), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (query != null) {
            if (q.isEmpty()) return@LazyColumn
            if (found.isEmpty()) item("none") {
                Column(side.padding(top = 18.dp)) {
                    Text("Nothing about “$q” yet.", Modifier.padding(start = 4.dp, bottom = 14.dp),
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp), color = P.muted)
                    KitChip("Ask me about “$q”", { onAsk(q) }, tag = "ask_instead")
                }
            }
            itemsIndexed(found, key = { _, f -> f.tile.conv }) { i, f ->
                KitRow(f.tile.name, side.testTag("found"), lead = { AppIcon(f.tile.app, f.tile.name) }, line = i > 0,
                    onClick = { onOpen(f.tile.conv) }, onLongClick = { byId[f.tile.conv]?.let(onMenu) }) {}
                if (f.snippet != null) Text(f.snippet, side.padding(start = 60.dp, end = 4.dp, bottom = 10.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp), color = P.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            return@LazyColumn
        }
        item("dash") { DashLine(words, size = DashSize.BIG, rig = rig) }
        if (tiles.isEmpty()) {
            item("try") { KitLabel("Try saying", side) }
            itemsIndexed(STARTERS, key = { _, s -> s.second }) { i, (icon, label, request) ->
                KitRow(label, side.testTag("suggestion"), lead = { IconTile(icon) }, line = i > 0, onClick = { onAsk(request) })
            }
            return@LazyColumn
        }
        if (needs.isNotEmpty()) {
            item("needs") { KitLabel("Needs you", side) }
            itemsIndexed(needs, key = { _, t -> "n:${t.conv}" }) { i, t ->
                NotNow({ onNotNow(t.conv) }, side.animateItem()) { AppRow(t, i > 0, byId, onOpen, onMenu) }
            }
        }
        if (pinned.isNotEmpty()) {
            item("pinned") { KitLabel("Pinned", side) }
            item("tiles") { Box(side) { PinnedTiles(pinned, byId, onOpen, onMenu) } }
        }
        if (rest.isNotEmpty()) {
            item("recent") { KitLabel("Recent", side) }
            itemsIndexed(rest, key = { _, t -> "r:${t.conv}" }) { i, t -> AppRow(t, i > 0, byId, onOpen, onMenu, side.animateItem()) }
        }
    }
}

/** One of Dash's apps in the list: its first letter, its name and his words about it ([Busy] while he's at it). */
@Composable
private fun AppRow(t: AppTile, line: Boolean, byId: Map<String, Conv>, onOpen: (String) -> Unit, onMenu: (Conv) -> Unit, modifier: Modifier = Modifier) =
    KitRow(t.name, modifier.testTag("app_row"), sub = t.words.ifEmpty { null }, lead = { AppIcon(t.app, t.name) }, busy = t.working, line = line,
        onClick = { onOpen(t.conv) }, onLongClick = { byId[t.conv]?.let(onMenu) })

/** The apps the owner pinned, as tiles, three to a row. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PinnedTiles(tiles: List<AppTile>, byId: Map<String, Conv>, onOpen: (String) -> Unit, onMenu: (Conv) -> Unit) {
    Column(Modifier.padding(top = 8.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { t ->
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).background(P.surface)
                        .combinedClickable(onClick = { onOpen(t.conv) }, onLongClick = { byId[t.conv]?.let(onMenu) })
                        .padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 12.dp).testTag("pinned")) {
                        if (t.working) Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(P.bg), contentAlignment = Alignment.Center) { Busy(size = 18.dp) }
                        else AppIcon(t.app, t.name, bg = P.bg)
                        Text(t.name, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold),
                            minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private val HOME_SPACES = Regex("\\s+")

/** A chat a search found, and where: its name, or what was said in it (a few words around the match, the match bold). */
private class Found(val tile: AppTile, val snippet: androidx.compose.ui.text.AnnotatedString?)

private fun match(c: Conv, t: AppTile, q: String, ink: androidx.compose.ui.graphics.Color): Found? {
    if (t.name.contains(q, true)) return Found(t, null)
    for (m in c.messages.asReversed()) {
        if (m.kind != Kind.USER && m.kind != Kind.AGENT) continue
        val text = (if (hasHtml(m.text)) htmlToText(m.text) else stripMarkdown(m.text)).replace(HOME_SPACES, " ")
        val at = text.indexOf(q, ignoreCase = true)
        if (at < 0) continue
        val from = (at - 28).coerceAtLeast(0).let { s -> text.indexOf(' ', s).takeIf { s > 0 && it in s until at }?.plus(1) ?: s }
        val to = (at + q.length + 60).coerceAtMost(text.length)
        return Found(t, buildAnnotatedString {
            if (from > 0) append("…")
            append(text.substring(from, at))
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = ink)) { append(text.substring(at, at + q.length)) }
            append(text.substring(at + q.length, to))
        })
    }
    return null
}

/** The top row while searching: back, and the search itself (with the keyboard up), and a way to clear it. */
@Composable
fun SearchRow(query: String, onQuery: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(Modifier.fillMaxWidth().statusBarsPadding().height(60.dp).padding(start = 6.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBtn(R.drawable.ic_back, "Back", onClose, Modifier.testTag("search_close"), size = 48.dp, iconSize = 24.dp)
        Row(Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(24.dp)).background(P.surface).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("Find a chat", style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp), color = P.muted)
                BasicTextField(query, onQuery, Modifier.fillMaxWidth().focusRequester(focus).testTag("search"), singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = P.text, fontSize = 17.sp), cursorBrush = SolidColor(P.call))
            }
            if (query.isNotEmpty()) IconBtn(R.drawable.ic_close, "Clear", { onQuery("") }, size = 40.dp, iconSize = 18.dp, tint = P.muted)
        }
    }
}
