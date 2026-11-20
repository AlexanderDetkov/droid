package com.adetkov.droid

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
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

/**
 * A page the agent showed full screen (`droid show --full`): Back to the chat, the page's title, the page, and a
 * message box under it to tell the agent something about it (its answer, while the agent waits on it). A page that
 * answers (droid.send) or closes itself (droid.close) is done here. A page shown full screen while one is (the same,
 * updated, or another) takes its place.
 */
class PageActivity : ComponentActivity() {
    /** The chat and the page it shows. */
    private var shown by mutableStateOf<Pair<String, String>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (!show(intent)) { finish(); return }
        setContent { DroidTheme { shown?.let { (conv, page) -> key(conv, page) { PageScreen(conv, page, ::finish) } } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        show(intent)
    }

    private fun show(i: Intent): Boolean {
        val conv = i.getStringExtra(EXTRA_CONV) ?: return false
        val page = i.getStringExtra(EXTRA_PAGE) ?: return false
        Notify.cancel(this, conv, pageNote(page))  // the notification that told of it
        shown = conv to page
        return true
    }
}

@Composable
private fun PageScreen(conv: String, pageId: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as DroidApp
    val convs by app.store.convs.collectAsState()
    val running by app.runner.running.collectAsState()  // whether the agent still waits on it changes with its run
    val c = convs.firstOrNull { it.id == conv }
    val m = c?.page(pageId)
    if (c == null || m == null) { LaunchedEffect(Unit) { onClose() }; return }
    val info = PageInfo.of(m)
    val waits = remember(info, running) { awaited(app, conv, info) }
    val dark = P.dark
    val actions = remember(conv, pageId) {
        val chat = { openChat(ctx, conv, handoff = false) }
        object : PageActions by ChatPageActions(ctx, conv, pageId, onScreen = { openChat(ctx, conv, handoff = true); onClose() }, onClose = onClose) {
            override fun send(text: String, data: String?) { pageSend(ctx, conv, pageId, text, data); chat(); onClose() }
        }
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
            FillPage("full:$pageId:${m.text.hashCode()}:$dark", "page:$conv:$pageId", { cx -> pageDocument(cx, m.text, dark, PageMode.FULL) },
                namedPaths(m.text), actions, Modifier.weight(1f).fillMaxWidth())
        }
        PageComposer("Message Droid") { text -> pageSend(ctx, conv, pageId, text, null); openChat(ctx, conv, handoff = false); onClose() }
    }
}

/** A plain message box with a send button, as the chat's looks. */
@Composable
fun PageComposer(hint: String, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val send = { text.trim().takeIf { it.isNotEmpty() }?.let { onSend(it); text = "" } }
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 14.dp).clip(RoundedCornerShape(28.dp))
        .background(P.surface).height(56.dp).padding(start = 20.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            if (text.isEmpty()) Text(hint, style = MaterialTheme.typography.bodyLarge, color = P.muted)
            BasicTextField(text, { text = it }, Modifier.fillMaxWidth(), singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = P.text), cursorBrush = SolidColor(P.text),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }))
        }
        IconBtn(R.drawable.ic_send, "Send", { send() }, Modifier.clip(RoundedCornerShape(22.dp)).background(if (text.isBlank()) P.raised else P.ink),
            tint = if (text.isBlank()) P.muted else P.onInk, size = 44.dp, iconSize = 20.dp, enabled = text.isNotBlank())
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
