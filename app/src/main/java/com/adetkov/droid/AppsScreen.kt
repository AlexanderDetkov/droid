package com.adetkov.droid

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Your apps: every chat as the app Dash made for it, behind the home screen's menu. What needs you first, then what
 * he's working on, then pinned ones and the rest by day; each with Dash as he stands with it, and his words. A search
 * finds one by its name or what was said in it; a long press opens its menu (rename, pin, share, delete).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppsScreen(convs: List<Conv>, running: Set<String>, hidden: String?, notNow: Map<String, Long>, onOpen: (String) -> Unit, onLongPress: (Conv) -> Unit,
               onNotNow: (String) -> Unit, onBack: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val q = query.trim()
    val byId = remember(convs) { convs.associateBy { it.id } }
    val tiles = remember(convs, running, q, notNow) {
        appTiles(convs, running, hidden, max = Int.MAX_VALUE, within = Long.MAX_VALUE, notNow = notNow).filter { t ->
            q.isEmpty() || t.name.contains(q, true) || byId[t.conv]?.messages?.any {
                (it.kind == Kind.USER || it.kind == Kind.AGENT) && (if (hasHtml(it.text)) htmlToText(it.text) else it.text).contains(q, true)
            } == true
        }
    }
    val now = System.currentTimeMillis()
    val groups = remember(tiles) {
        tiles.groupBy { t ->
            when {
                t.calls -> "Needs you"
                t.working -> "Working on it"
                byId[t.conv]?.pinned == true -> "Pinned"
                else -> dayGroup(t.at, now)
            }
        }.toList().sortedBy { (label, _) -> listOf("Needs you", "Working on it", "Pinned").indexOf(label).let { if (it < 0) 3 else it } }
    }
    Column(Modifier.fillMaxSize().background(P.bg)) {
        Where(R.drawable.ic_back, "Back", onBack, "Everything")
        Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 4.dp).fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp))
            .background(P.surface).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_search), null, Modifier.size(20.dp), tint = P.muted)
            Box(Modifier.padding(start = 10.dp).weight(1f)) {
                if (query.isEmpty()) Text("Find a chat", style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp), color = P.muted)
                BasicTextField(query, { query = it }, Modifier.fillMaxWidth().testTag("search"), singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = P.text, fontSize = 17.sp), cursorBrush = SolidColor(P.call))
            }
        }
        LazyColumn(Modifier.weight(1f).testTag("apps"), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            groups.forEach { (label, list) ->
                item(key = "h$label") { KitLabel(label) }
                // (keyed by group too: a row set aside moves out of Needs you as a new row, not the swiped one)
                itemsIndexed(list, key = { _, t -> "$label:${t.conv}" }) { i, t ->
                    val c = byId[t.conv]
                    // what needs the owner can be set aside here too; the rest stays as it is
                    NotNow({ onNotNow(t.conv) }, Modifier.animateItem(), enabled = t.calls) {
                        KitRow(t.name, Modifier.testTag("app_row"), sub = t.words.ifEmpty { null }, lead = { LetterTile(t.name) }, busy = t.working,
                            line = i > 0, onClick = { onOpen(t.conv) }, onLongClick = { c?.let(onLongPress) }) {
                            if (!t.working) Text(shortWhen(t.at), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall, color = P.faint)
                        }
                    }
                }
            }
        }
        Box(Modifier.navigationBarsPadding())
    }
}

/** "9:12" today, "Fri" this week, "Nov 3" before. */
private fun shortWhen(ts: Long, now: Long = System.currentTimeMillis()): String {
    val day = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    val f = when {
        ts >= day -> "h:mm"
        ts >= day - 6 * 86_400_000L -> "EEE"
        else -> "MMM d"
    }
    return SimpleDateFormat(f, Locale.getDefault()).format(ts)
}
