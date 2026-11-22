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
fun AppsScreen(convs: List<Conv>, running: Set<String>, hidden: String?, onOpen: (String) -> Unit, onLongPress: (Conv) -> Unit, onBack: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val q = query.trim()
    val byId = remember(convs) { convs.associateBy { it.id } }
    val tiles = remember(convs, running, q) {
        appTiles(convs, running, hidden, max = Int.MAX_VALUE, within = Long.MAX_VALUE).filter { t ->
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
        }.toList()
    }
    Column(Modifier.fillMaxSize().background(P.bg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(60.dp).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(R.drawable.ic_back, "Back", onBack, Modifier.testTag("back"), size = 48.dp, iconSize = 24.dp)
        }
        Text("Your apps", Modifier.padding(start = 20.dp), style = MaterialTheme.typography.headlineMedium.copy(fontSize = 30.sp, fontWeight = FontWeight.Bold))
        Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 4.dp).fillMaxWidth().height(50.dp).clip(RoundedCornerShape(25.dp))
            .background(P.surface).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_search), null, Modifier.size(20.dp), tint = P.muted)
            Box(Modifier.padding(start = 10.dp).weight(1f)) {
                if (query.isEmpty()) Text("Find one", style = MaterialTheme.typography.bodyLarge, color = P.muted)
                BasicTextField(query, { query = it }, Modifier.fillMaxWidth().testTag("search"), singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = P.text), cursorBrush = SolidColor(P.text))
            }
        }
        LazyColumn(Modifier.weight(1f).testTag("apps"), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
            groups.forEach { (label, list) ->
                item(key = "h$label") {
                    Text(label, Modifier.padding(start = 20.dp, top = 20.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge,
                        color = P.muted, fontWeight = FontWeight.Bold)
                }
                items(list, key = { it.conv }) { t ->
                    val c = byId[t.conv]
                    AppRow(t, Modifier.combinedClickable(onClick = { onOpen(t.conv) }, onLongClick = { c?.let(onLongPress) }))
                }
            }
        }
        Box(Modifier.navigationBarsPadding())
    }
}

/** One app in the list: Dash as he stands with it, its name, his words, and when. */
@Composable
private fun AppRow(t: AppTile, modifier: Modifier) {
    val rig = rememberDash("app:${t.conv}", feel = t.feel)
    LaunchedEffect(t.feel) { rig.feel(t.feel) }
    Row(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp).testTag("app_row"), verticalAlignment = Alignment.CenterVertically) {
        Dash(rig, Modifier.size(width = 40.dp, height = 44.dp), label = null, onPoke = null)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(t.name, style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (t.words.isNotEmpty()) Text(t.words, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                color = if (t.calls) P.call else P.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (!t.working) Text(shortWhen(t.at), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall, color = P.faint)
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
