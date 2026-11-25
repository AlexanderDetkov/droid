package com.adetkov.droid

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A page the agent showed full screen (`droid show --full`), or one the chat's history opens (a page, or an answer
 * the agent made as an app): Back to the chat, the page's title, the page, and a message box under it to tell the
 * agent something about it (its answer, while the agent waits on it). A page that answers (droid.send, or elsewhere:
 * the chat's message box) or closes itself (droid.close) is done here. A page shown full screen while one is (the
 * same, updated, or another) takes its place.
 */
class PageActivity : ComponentActivity() {
    /** The chat, and the page it shows: by its id, else the answer at a message. */
    private var shown by mutableStateOf<Triple<String, String?, Int>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (!show(intent)) { finish(); return }
        setContent { DroidTheme { shown?.let { (conv, page, at) -> key(conv, page, at) { PageScreen(conv, page, at, ::finish) } } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        show(intent)
    }

    private fun show(i: Intent): Boolean {
        val conv = i.getStringExtra(EXTRA_CONV) ?: return false
        val page = i.getStringExtra(EXTRA_PAGE)
        val at = i.getIntExtra(EXTRA_AT, -1)
        if (page == null && at < 0) return false
        if (page != null) Notify.cancel(this, conv, pageNote(page))  // the notification that told of it
        shown = Triple(conv, page, at)
        return true
    }
}

@Composable
private fun PageScreen(conv: String, pageId: String?, at: Int, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as DroidApp
    val convs by app.store.convs.collectAsState()
    val running by app.runner.running.collectAsState()  // whether the agent still waits on it changes with its run
    val c = convs.firstOrNull { it.id == conv }
    // the page by its id (its latest), or an answer with HTML
    val i = if (c == null) -1 else if (pageId != null) c.pageIndex(pageId)
        else at.takeIf { c.messages.getOrNull(it)?.let { m -> m.kind == Kind.AGENT && hasHtml(m.text) } == true } ?: -1
    if (c == null || i < 0) { LaunchedEffect(Unit) { onClose() }; return }
    val m = c.messages[i]
    val info = if (pageId != null) PageInfo.of(m) else PageInfo("", title = pageTitle(m.text))
    // as the chat and its run are now (both collected above, so this follows them)
    val waits = remember(c, running) { pageId != null && awaited(app, c, i) }
    val live = remember(c, running) { pageLive(app, c, i) }
    CloseWhenAnswered(info.reply != null, onClose)
    val dark = P.dark
    val actions = remember(conv, pageId, i) {
        ChatPageActions(ctx, conv, pageId, isLive = { pageLiveNow(app, conv, pageId, i) },
            onScreen = { openChat(ctx, conv, handoff = true); onClose() }, onClose = onClose,
            onSent = { openChat(ctx, conv, handoff = false); onClose() })
    }
    Column(Modifier.fillMaxSize().background(P.bg).systemBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(R.drawable.ic_back, "Back to the chat", { openChat(ctx, conv, handoff = false); onClose() }, size = 48.dp)
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(info.title.ifEmpty { c.title }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (waits) Text("Your turn", style = MaterialTheme.typography.bodySmall, color = P.call)
            }
        }
        key(m.text, dark) {
            if (pageId != null) FillPage("full:$pageId:${m.text.hashCode()}:$dark", "page:$conv:$pageId", { cx -> pageDocument(cx, m.text, dark, PageMode.FULL) },
                namedPaths(m.text), actions, Modifier.weight(1f).fillMaxWidth(), live = live, base = info.base.ifEmpty { null })
            else FillPage("full:a$i:${m.text.hashCode()}:$dark", chatOrigin(conv), { cx -> answerDocument(cx, m.text, dark, PageMode.FULL) },
                namedPaths(m.text), actions, Modifier.weight(1f).fillMaxWidth(), live = live)
        }
        PageComposer("Message Dash") { text -> pageSend(ctx, conv, pageId, text, null); openChat(ctx, conv, handoff = false); onClose() }
    }
}

/** Calls [onClose] once the page shown here is answered, wherever that was; not for one that was answered when shown. */
@Composable
internal fun CloseWhenAnswered(answered: Boolean, onClose: () -> Unit) {
    val before = remember { booleanArrayOf(answered) }
    LaunchedEffect(answered) {
        if (answered && !before[0]) onClose()
        before[0] = answered
    }
}

/** A message box with a send button, the same lavender pill as the chat's bar. */
@Composable
fun PageComposer(hint: String, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val send = { text.trim().takeIf { it.isNotEmpty() }?.let { onSend(it); text = "" } }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp).clip(RoundedCornerShape(32.dp))
        .background(Lavender).height(64.dp).padding(start = 22.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            if (text.isEmpty()) Text(hint, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp), color = P.muted)
            BasicTextField(text, { text = it }, Modifier.fillMaxWidth(), singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = P.text, fontSize = 18.sp), cursorBrush = SolidColor(P.call),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }))
        }
        IconBtn(R.drawable.ic_send, "Send", { send() }, Modifier.clip(RoundedCornerShape(24.dp)).background(P.call.copy(alpha = if (text.isBlank()) .4f else 1f)),
            tint = OnCall, size = 48.dp, iconSize = 22.dp, enabled = text.isNotBlank())
    }
}

/**
 * Opens chat [conv] in Droid ([handoff]: with the agent's screen handed to the owner): the Droid already open, with
 * what was over it (a page full screen) closed, so there is never a second one.
 */
fun openChat(ctx: Context, conv: String, handoff: Boolean) {
    ctx.startActivity(Intent(ctx, MainActivity::class.java).putExtra(EXTRA_CONV, conv).putExtra(EXTRA_WATCH, handoff).putExtra(EXTRA_HANDOFF, handoff)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
}
