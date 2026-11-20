package com.adetkov.droid

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val BOTTOM = 100_000  // scroll offset that lands on the end of the last item

@Composable
fun Transcript(
    conv: Conv, running: Boolean,
    /** The run's screen while it is up (working, or held after answering), shown live in the last turn. */
    feed: ScreenFeed?,
    speakingKey: String?, onSpeak: (String, String) -> Unit, onRetry: () -> Unit, onWatch: () -> Unit, modifier: Modifier,
    /** A page handed over the agent's screen (droid.screen): open it with the owner's touches going to it. */
    onTakeOver: () -> Unit = {},
    /** The run answered and still holds its hidden screen, for a reply on it or for the user to take it over. */
    held: Boolean = false,
    /** When a held screen closes. */
    closesAt: Long? = null,
    /** Where the agent taps its screen, in the screen's pixels. */
    taps: Flow<Pair<Int, Int>>? = null,
    /** What the run is doing now (see [Runner.doing]). */
    doing: Doing? = null,
    /** What the owner is doing in the message box, which a Dash waiting on them follows: typing, or talking ([level]). */
    typing: Boolean = false, listening: Boolean = false, level: () -> Float = { 0f },
) {
    val messages = conv.messages
    val items = remember(messages, running) { buildItems(messages, running) }
    val outcome = remember(messages, running) { lastOutcome(messages, running) }
    // as the agent set Dash in the last turn, and a page it waits on (Your turn, mid-task)
    val mood = remember(messages) { lastMood(messages) }
    val asking = remember(messages, running) { waitingPage(messages, running)?.let { PageInfo.of(it).title } }
    val attending = when { listening -> Feel.LISTENING; typing -> Feel.READING; else -> null }
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var menuFor by remember { mutableStateOf<Msg?>(null) }  // a long-pressed request
    var selecting by remember { mutableStateOf<String?>(null) }
    // Follow new content only while the user is at the bottom. Only their own drag up leaves it: the list's own scrolls
    // and corrections (a send's scroll to the end while the live screen above it grows, the keyboard) never do.
    var follow by remember(conv.id) { mutableStateOf(true) }
    val dragged by state.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(state) {
        snapshotFlow { Triple(state.canScrollForward, dragged, state.lastScrolledBackward) }
            .collect { (more, drag, back) -> if (!more) follow = true else if (drag && back) follow = false }
    }
    // Also when the run stops: the answer's buttons come then.
    LaunchedEffect(conv.id, items.size, messages.lastOrNull()?.text?.length, running) {
        if (follow && items.isNotEmpty()) state.scrollToItem(items.lastIndex, BOTTOM)
    }
    // A request just sent always brings the end into view, even when scrolled up.
    LaunchedEffect(messages.count { it.kind.opensTurn }) {
        if (items.isNotEmpty()) { follow = true; state.animateScrollToItem(items.lastIndex, BOTTOM) }
    }
    val lastIndex = items.lastIndex
    // The run's screen belongs to the last turn: its work row shows it.
    val screenTurn = remember(items) {
        items.subList(items.indexOfLast { it is Item.Message && it.msg.kind.opensTurn }.coerceAtLeast(0), items.size)
            .firstOrNull { it is Item.Steps }?.key
    }
    // Pages in this chat stay loaded while it shows; a drag that starts on one scrolls the chat.
    val pages = remember(conv.id) { PageCache() }
    DisposableEffect(pages) { onDispose { pages.clear() } }
    val drag = rememberListDrag(state, onDrag = { dy -> if (dy > 0) follow = false },
        grew = { if (follow) scope.launch { state.scrollToItem(state.layoutInfo.totalItemsCount - 1, BOTTOM) } })
    val focus = LocalFocusManager.current
    val view = LocalView.current
    val latestIndex = remember(messages) { messages.indices.lastOrNull { messages[it].kind.opensTurn } ?: 0 }
    Box(modifier) { CompositionLocalProvider(LocalPageCache provides pages, LocalListDrag provides drag) {
        LazyColumn(
            Modifier.fillMaxSize().testTag("messages").dismissesKeyboard(drag, focus, view), state,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
        ) {
            items(items, key = { it.key }) { item ->
                val first = item === items.first()
                val gap = when {
                    first -> 0.dp
                    item is Item.Message && item.msg.kind.opensTurn -> 28.dp
                    else -> 14.dp
                }
                Box(Modifier.padding(top = gap).animateItem(fadeInSpec = tween(260), fadeOutSpec = null, placementSpec = spring(stiffness = 600f))) {
                    when (item) {
                        is Item.Steps -> {
                            val last = item.key == screenTurn
                            WorkLog(item, conv.id, if (last) feed else null, onWatch, closesAt = if (last) closesAt else null,
                                taps = if (last) taps else null, doing = if (last) doing else null, outcome = if (last) outcome else null,
                                attending = if (last) attending else null, level = level, mood = if (last) mood else null, asking = if (last) asking else null)
                        }
                        is Item.Message -> {
                            val m = item.msg
                            val key = "${conv.id}:${item.index}"
                            when (m.kind) {
                                Kind.USER -> UserMessage(m) { menuFor = m }
                                // Copy, read aloud and share are under every answer; a long press selects its text.
                                Kind.AGENT -> Answer(m.text, speakingKey == key, { onSpeak(key, m.text) }, onTakeOver = onTakeOver,
                                    conv = conv.id, index = item.index, latest = item.index > latestIndex) { selecting = stripMarkdown(m.text) }
                                Kind.PAGE -> PageMessage(m, conv.id, item.index > latestIndex, onTakeOver)
                                Kind.WAKE -> WakeLine(m.text)
                                Kind.STATUS -> StatusRow(m.text, retry = if (item === items[lastIndex] && !running) onRetry else null)
                                else -> Text(m.text, style = MaterialTheme.typography.bodySmall, color = P.muted)
                            }
                        }
                    }
                }
            }
            // A turn without steps has no work row to carry the screen it holds open: the same frame and line, on their own.
            if (held && feed?.app != null && screenTurn == null) item(key = "held") {
                Row(Modifier.fillMaxWidth().padding(top = 14.dp).animateItem(fadeInSpec = tween(260), fadeOutSpec = null)) {
                    val f = feed.frame
                    val img = remember(f) { f?.asImageBitmap() }
                    val rig = rememberDash("held") { it.frame.snap(1f) }
                    LaunchedEffect(closesAt) {
                        while (true) {
                            rig.drain.t = closesAt?.let { (1f - (it - System.currentTimeMillis()) / HOLD_FOR.toFloat()).coerceIn(0f, 1f) } ?: 0f
                            if (closesAt == null) break
                            delay(1000)
                        }
                    }
                    rig.level = level
                    LaunchedEffect(attending, outcome, mood) { rig.feel(attending ?: mood?.feel ?: if (outcome == Outcome.ASKS) Feel.NEEDS_YOU else Feel.IDLE) }
                    DashSlot(rig, img, CREATURE, STILL, img?.let { it.height.toFloat() / it.width } ?: screenRatio(), Modifier.testTag("still"),
                        gap = 14.dp, label = "Droid's screen, open", origin = true, onClick = onWatch)
                    val app = rememberAppInfo(feed.app)?.label
                    Text(if (app != null) "Open in $app for your reply" else "Droid's screen is open for your reply", Modifier.weight(1f).padding(top = 4.dp),
                        style = MaterialTheme.typography.bodyMedium, color = P.faint)
                }
            }
        }
        }
        // Text scrolling under the top bar fades out instead of being cut off.
        val bg = P.bg
        Box(Modifier.fillMaxWidth().height(20.dp).align(Alignment.TopCenter)
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(bg, bg.copy(alpha = 0f)))))
        AnimatedVisibility(!follow, Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
            enter = fadeIn() + scaleIn(initialScale = .8f), exit = fadeOut() + scaleOut(targetScale = .8f)) {
            Box(
                Modifier.size(38.dp).shadow(6.dp, CircleShape).clip(CircleShape).background(P.sheet).border(1.dp, P.hairline, CircleShape)
                    .clickable { follow = true; scope.launch { state.animateScrollToItem(items.lastIndex, BOTTOM) } }.testTag("to_bottom"),
                contentAlignment = Alignment.Center,
            ) { Icon(painterResource(R.drawable.ic_down), "Scroll to bottom", Modifier.size(20.dp), tint = P.text) }
        }
    }
    menuFor?.let { m -> MessageSheet(m, { selecting = m.text }) { menuFor = null } }
    selecting?.let { SelectTextSheet(it) { selecting = null } }
}

/** The user's request: a bubble on the right, attachments above it. */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun UserMessage(m: Msg, onLongPress: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 48.dp), horizontalAlignment = Alignment.End) {
        if (m.files.isNotEmpty()) FlowRow(
            Modifier.padding(bottom = if (m.text.isBlank()) 0.dp else 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(6.dp),
            itemVerticalAlignment = Alignment.Bottom,
        ) { m.files.forEach { AttachmentView(it, big = true) } }
        if (m.text.isNotBlank()) Text(
            m.text,
            Modifier.clip(RoundedCornerShape(22.dp)).background(P.surface)
                .combinedClickable(remember { MutableInteractionSource() }, null, onLongClick = onLongPress, onClick = {})
                .padding(horizontal = 16.dp, vertical = 10.dp).testTag("user_msg"),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

/** An image as a rounded thumbnail; any other file as a chip with its name. */
@Composable
fun AttachmentView(a: Attachment, big: Boolean, modifier: Modifier = Modifier) {
    if (a.source == Source.SCREEN) {
        // Phone-shaped, so it reads as the screen it is rather than a crop of it.
        val shape = RoundedCornerShape(if (big) 14.dp else 10.dp)
        Thumb(a.path, if (big) 200 else 80, modifier.size(if (big) 64.dp else 26.dp, if (big) 136.dp else 56.dp).clip(shape)
            .border(1.dp, P.hairline, shape))
    } else if (a.isImage) {
        val s = if (big) 132.dp else 56.dp
        Thumb(a.path, if (big) 400 else 168, modifier.size(s).clip(RoundedCornerShape(if (big) 18.dp else 14.dp)))
    } else Row(
        modifier.height(56.dp).widthIn(max = 220.dp).clip(RoundedCornerShape(14.dp)).background(P.surface)
            .border(1.dp, P.hairline, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(P.raised), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_doc), null, Modifier.size(18.dp), tint = P.text)
        }
        Column(Modifier.padding(start = 10.dp)) {
            Text(a.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(a.name.substringAfterLast('.', "").uppercase().ifEmpty { "FILE" }, style = MaterialTheme.typography.labelSmall, color = P.muted)
        }
    }
}

/** A final answer with a quiet action row underneath. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Answer(
    text: String, speaking: Boolean, onSpeak: () -> Unit, onTakeOver: () -> Unit,
    /** The chat and the answer's place in it, and whether it is in the latest turn (a page there may set Dash). */
    conv: String = "", index: Int = 0, latest: Boolean = true,
    onLongPress: () -> Unit,
) {
    val copy = rememberCopy()
    val ctx = LocalContext.current
    val haptic = rememberHaptic()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1600); copied = false } }
    // An answer with HTML is a page the agent made: it renders as one, seamless with the chat.
    val html = remember(text) { hasHtml(text) }
    val shown = remember(text) { if (html) stripMarkdown(text) else answerText(text) }
    Column(Modifier.fillMaxWidth()) {
        if (html) {
            val isLatest by rememberUpdatedState(latest)
            val actions = remember(conv, index) { ChatPageActions(ctx, conv, null, isLive = { isLatest }, onScreen = onTakeOver) }
            val dark = P.dark
            InlinePage("a:$conv:$index:${text.hashCode()}", "answer:$conv:$index", dark, { cx -> answerDocument(cx, text, dark) },
                namedPaths(text), actions, Modifier.testTag("answer"))
        } else Markdown(shown, Modifier.fillMaxWidth().combinedClickable(remember { MutableInteractionSource() }, null,
            onLongClick = onLongPress, onClick = {}).testTag("answer"))
        Row(Modifier.offset(x = (-10).dp).padding(top = 4.dp)) {
            IconBtn(if (copied) R.drawable.ic_check else R.drawable.ic_copy, "Copy", { copy(shown); copied = true; haptic() },
                Modifier.testTag("copy"), tint = P.muted, size = 36.dp, iconSize = 18.dp)
            IconBtn(if (speaking) R.drawable.ic_stop_circle else R.drawable.ic_speaker, if (speaking) "Stop reading" else "Read aloud", onSpeak,
                Modifier.testTag("speak"), tint = if (speaking) P.text else P.muted, size = 36.dp, iconSize = 18.dp)
            IconBtn(R.drawable.ic_share, "Share", { shareText(ctx, stripMarkdown(text)) },
                Modifier.testTag("share"), tint = P.muted, size = 36.dp, iconSize = 18.dp)
        }
    }
}

/**
 * A page the agent showed (`droid show`): in the chat, the page itself, and the owner's answer under it once given;
 * one shown elsewhere (full screen, floating, a notification) is a line here that opens it full screen.
 */
@Composable
private fun PageMessage(m: Msg, conv: String, latest: Boolean, onTakeOver: () -> Unit) {
    val ctx = LocalContext.current
    val info = remember(m.data) { PageInfo.of(m) }
    val isLatest by rememberUpdatedState(latest)
    val answered by rememberUpdatedState(info.reply != null)
    Column(Modifier.fillMaxWidth()) {
        if (info.where == "chat") {
            val actions = remember(conv, info.id) { ChatPageActions(ctx, conv, info.id, isLive = { isLatest && !answered }, onScreen = onTakeOver) }
            val dark = P.dark
            InlinePage("p:$conv:${info.id}:${m.text.hashCode()}", "page:$conv:${info.id}", dark, { cx -> pageDocument(cx, m.text, dark, PageMode.INLINE) },
                namedPaths(m.text), actions, Modifier.testTag("page"))
        } else Row(
            Modifier.clip(RoundedCornerShape(14.dp)).border(1.dp, P.hairline, RoundedCornerShape(14.dp))
                .clickable { ctx.startActivity(pageIntent(ctx, conv, info.id)) }.padding(start = 12.dp, end = 16.dp, top = 10.dp, bottom = 10.dp).testTag("page_link"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_screen), null, Modifier.size(18.dp), tint = P.muted)
            Text(info.title.ifEmpty { "Page" }, Modifier.padding(start = 10.dp), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        info.reply?.let { r ->
            Text(r, Modifier.align(Alignment.End).padding(top = 10.dp, start = 48.dp).clip(RoundedCornerShape(22.dp)).background(P.surface)
                .padding(horizontal = 16.dp, vertical = 10.dp).testTag("page_reply"), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** The agent waking itself up (`droid later`): a quiet line where a request would start the turn. */
@Composable
private fun WakeLine(text: String) {
    Row(Modifier.fillMaxWidth().testTag("wake"), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.ic_s_alarm), null, Modifier.size(16.dp), tint = P.muted)
        Text(text.ifBlank { "Droid checked back" }, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall, color = P.muted,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MessageSheet(m: Msg, onSelect: () -> Unit, onDismiss: () -> Unit) {
    val copy = rememberCopy()
    ActionSheet(onDismiss) { close ->
        SheetRow(R.drawable.ic_copy, "Copy", { copy(m.text); close() }, tag = "sheet_copy")
        SheetRow(R.drawable.ic_select, "Select text", { onSelect(); close() }, tag = "sheet_select")
    }
}

@Composable
private fun SelectTextSheet(text: String, onDismiss: () -> Unit) {
    ActionSheet(onDismiss, title = "Select text") {
        SelectionContainer(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text(text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** How a turn ended without an answer: stopped, failed, interrupted. The last one offers Retry. */
@Composable
private fun StatusRow(text: String, retry: (() -> Unit)?) {
    val stopped = text == "Stopped"
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(if (stopped) R.drawable.ic_stop_circle else R.drawable.ic_error), null, Modifier.size(16.dp),
            tint = if (stopped) P.muted else P.danger)
        Text(text, Modifier.padding(start = 8.dp).weight(1f, fill = false), style = MaterialTheme.typography.bodyMedium,
            color = if (stopped) P.muted else P.danger, maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (retry != null) Row(
            Modifier.padding(start = 12.dp).clip(RoundedCornerShape(50)).border(1.dp, P.hairline, RoundedCornerShape(50))
                .clickable(onClick = retry).padding(horizontal = 12.dp, vertical = 6.dp).testTag("retry"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_retry), null, Modifier.size(16.dp), tint = P.text)
            Text("Retry", Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelMedium)
        }
    }
}

/**
 * A turn's work, with Dash. While the run works without an app, he is a small creature beside "Working"; when an app
 * opens on Droid's screen he grows into that screen's frame (tap: Watch), the latest step beside him in large type. His
 * pose is what the agent is doing ([doing]): looking at the screen, typing, scrolling, waiting on an app, thinking.
 * After the answer, if the screen is held for a reply, he shrinks with it to a small still, his edge draining as the hold
 * runs out; when the screen closes he lets go and the turn keeps a plain still. Otherwise he stays only while the turn
 * needs something from you ([outcome]): an answer that asks you (he looks up at you, tip lit), or a failure (puzzled);
 * a turn that is simply done gets a happy hop, and he goes. While he waits on you he follows what you do ([attending]:
 * reading as you type, listening as you talk). The steps open into a timeline.
 * [screen]: the run's screen while it is up; only the chat's last turn gets it. [closesAt]: when a held screen closes.
 */
@Composable
private fun WorkLog(s: Item.Steps, convId: String, screen: ScreenFeed?, onWatch: () -> Unit, closesAt: Long? = null,
                    taps: Flow<Pair<Int, Int>>? = null, doing: Doing? = null, outcome: Outcome? = null, attending: Feel? = null,
                    level: () -> Float = { 0f }, mood: Mood? = null, asking: String? = null) {
    var open by rememberSaveable(s.key) { mutableStateOf(false) }
    val n = s.lines.size
    val small = MaterialTheme.typography.bodyMedium
    // The screen shows only while an app is on it: a task that never uses it (or whose app the owner took away) reads
    // as plain text. Its latest frame then is also kept as the turn's still (at most every 2 s), for after it's gone.
    val used = screen?.app != null
    val ctx = LocalContext.current
    val stillFile = remember(convId, s.stillKey) { Stills.file(ctx, convId, s.stillKey) }
    var saved by remember { mutableIntStateOf(0) }
    LaunchedEffect(screen, stillFile) {
        if (screen != null) snapshotFlow { screen.frame.takeIf { screen.app != null } }.filterNotNull().conflate().collect {
            withContext(Dispatchers.IO) { Stills.save(stillFile, it) }
            saved++
            delay(2000)
        }
    }
    val liveFrame = screen?.frame?.takeIf { used }
    val liveImage = remember(liveFrame) { liveFrame?.asImageBitmap() }
    val still = rememberStill(stillFile, if (screen == null) saved else -1).takeIf { screen == null }
    // the last picture shown stays up while the still loads (the turn's screen just moved on to the next turn), so his
    // frame never flashes empty
    val shown = remember { arrayOfNulls<ImageBitmap>(1) }
    val frame = (liveImage ?: still ?: shown[0]).also { shown[0] = it }
    val big = s.live && used             // working on Droid's screen: the large layout
    val held = !s.live && used           // answered, the screen still open for a reply
    // The clock: the time worked while live, the hold's minutes left while held.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(s.live, held, closesAt) { now = System.currentTimeMillis(); while (s.live || held) { now = System.currentTimeMillis(); delay(1000) } }
    // Dash is here while the turn is live or its screen is held, and on a turn without a screen while it waits on you;
    // a turn from before, or one whose screen has closed, is plain text and a still.
    val asks = outcome == Outcome.ASKS
    val failed = outcome == Outcome.FAILED
    val hadScreen = remember(stillFile, saved, screen == null) { stillFile.exists() }
    // the agent set how he looks after this turn: he stays, as it set him
    val waits = !s.live && !held && screen == null && !hadScreen && (asks || failed || mood != null)
    var released by remember(s.key) { mutableStateOf(!s.live && screen == null && !waits) }
    var late by remember(s.key) { mutableStateOf(false) }  // a held screen in its last minute and a half
    // after the turn: what he waits on you for, or what you're doing about it
    val after = attending ?: when { failed -> Feel.CONFUSED; mood != null -> mood.feel; asks -> Feel.NEEDS_YOU; late -> Feel.SLEEPY; else -> Feel.IDLE }
    val rig = rememberDash(s.key) { r ->
        if (big || (!s.live && screen != null)) r.frame.snap(1f)
        if (!s.live) r.feel(after)
        // a request just sent: he grows in beside it (as the last turn's Dash shrinks away above), rather than appearing
        else if (System.currentTimeMillis() - s.start < 3000) r.present.snap(0f)
    }
    rig.level = level
    LaunchedEffect(s.live, big, held, waits) {
        when {
            s.live -> { released = false; rig.present.t = 1f; rig.frame.t = if (big) 1f else 0f }
            held -> { released = false; rig.present.t = 1f; rig.frame.t = 1f; if (outcome == Outcome.DONE && mood == null) rig.beat(Feel.HAPPY, 1.3f) }
            waits -> { released = false; rig.present.t = 1f; rig.frame.t = 0f; if (failed) rig.beat(Feel.OOPS, 1.1f) }
            else -> {
                // a screen that is up but hasn't said which app is in front yet (reconnecting) gets a moment first
                if (screen != null) delay(1500)
                // done: a happy beat, then the creature shrinks away or the frame lets go of the screen
                if (outcome == Outcome.DONE && mood == null && rig.frame.x < .5f) { rig.beat(Feel.HAPPY, .9f); delay(900) }
                rig.present.t = 0f; delay(1100); released = true
            }
        }
    }
    // while it works: a pose for what the agent does, a reaction to each thing it does; nothing new for a while, and he
    // is thinking it over (a wait stays a wait)
    LaunchedEffect(s.live, doing, asking) {
        if (!s.live) return@LaunchedEffect
        if (asking != null) { rig.feel(Feel.NEEDS_YOU); return@LaunchedEffect }  // a page waits on you
        val d = doing ?: Doing(Work.THINK)
        if (d.n > 0) rig.react(d)
        rig.feel(d.work.feel())
        delay(if (d.said) 6000 else 9000)
        if (d.work != Work.WAIT) rig.feel(Feel.THINKING)
    }
    LaunchedEffect(s.live, after) { if (!s.live) rig.feel(after) }
    // the agent set his feeling mid-task: a moment of it, then back to what it's doing
    LaunchedEffect(mood) { if (s.live && mood != null && asking == null) rig.beat(mood.feel, 2.5f) }
    // where the agent taps its screen: a ripple there, and his eyes go to it
    val display = rememberDisplaySize()
    LaunchedEffect(taps, big) {
        if (big && taps != null) taps.collect { (x, y) -> rig.tap(x / display.width, y / display.height) }
    }
    // a held screen's time, running out along his edge; sleepy near the end
    LaunchedEffect(held, closesAt) {
        if (!held || closesAt == null) { rig.drain.t = 0f; return@LaunchedEffect }
        while (true) {
            val left = (closesAt - System.currentTimeMillis()).coerceAtLeast(0)
            rig.drain.t = (1f - left / HOLD_FOR.toFloat()).coerceIn(0f, 1f)
            late = left < 90_000
            delay(1000)
        }
    }
    val ratio = frame?.let { it.height.toFloat() / it.width } ?: screenRatio()
    val width by animateDpAsState(if (big) 108.dp else STILL, spring(dampingRatio = .82f, stiffness = 320f), label = "window")
    val toggle = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = n > 0) { open = !open }.testTag("worklog")
    val chevron = @Composable {
        val angle by animateFloatAsState(if (open) 90f else 0f, label = "chevron")
        Icon(painterResource(R.drawable.ic_chevron), null, Modifier.padding(start = 2.dp).size(18.dp).rotate(angle), tint = P.muted)
    }
    val working = @Composable {
        Text("Working", style = small, color = P.muted)
        if (s.start > 0) Text("  ${duration(now - s.start)}", style = small, color = P.faint)
    }
    // Only the timeline animates its size (animateContentSize clips). Dash draws inside his slot, head and all.
    // Everything hangs from the top, and the text beside him is one column that changes in place: as he becomes the
    // frame of the live screen, the latest step grows into large type and the app's name comes in under it.
    val bigness by animateFloatAsState(if (big) 1f else 0f, spring(dampingRatio = 1f, stiffness = 300f), label = "big")
    val large = MaterialTheme.typography.titleMedium.copy(fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium)
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            when {
                !released -> DashSlot(rig, frame, CREATURE, width.coerceAtLeast(8.dp), ratio, Modifier.testTag(if (big) "window" else "dash"),
                    gap = 14.dp, label = when { used -> "Droid's screen, live"; s.live -> "Dash, working"; asks -> "Dash, waiting for your reply"; failed -> "Dash, puzzled"; else -> "Dash" }, origin = used,
                    onClick = if (used) onWatch else if (n > 0) ({ open = !open }) else null)
                frame != null -> ScreenWindow(frame, STILL, Modifier.padding(end = 14.dp).testTag("still"),
                    onClick = if (n > 0) ({ open = !open }) else null)
            }
            Column(Modifier.weight(1f).padding(start = 4.dp * bigness)) {
                Row(toggle.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Crossfade(s.live, label = "header") { live ->
                        if (live) Row { working() }
                        else Text(if (s.start > 0 && s.end > s.start) "Worked for ${duration(s.end - s.start)}" else "$n steps",
                            style = small, color = P.muted)
                    }
                    if (n > 0) chevron()
                }
                // what he's doing: fades out when the turn ends
                AnimatedVisibility(s.live && (big || (!open && n > 0)), enter = fadeIn(tween(200)) + expandVertically(clip = false),
                    exit = fadeOut(tween(160)) + shrinkVertically(clip = false)) {
                    AnimatedContent(s.lines.lastOrNull()?.text ?: "Getting started", label = "latest",
                        transitionSpec = { (fadeIn(tween(220)) + slideInVertically { it / 3 }) togetherWith fadeOut(tween(120)) using SizeTransform(clip = false) }) { t ->
                        Text(t, Modifier.padding(top = 6.dp * bigness), style = androidx.compose.ui.text.lerp(small, large, bigness),
                            color = androidx.compose.ui.graphics.lerp(P.faint, P.text, bigness),
                            maxLines = if (big) 4 else 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                AnimatedVisibility(big, enter = fadeIn(tween(220, 120)) + expandVertically(clip = false), exit = fadeOut(tween(120)) + shrinkVertically(clip = false)) {
                    AppLabel(screen?.app, Modifier.padding(top = 12.dp))
                }
                AnimatedVisibility(held, enter = fadeIn(tween(240, 160)) + expandVertically(clip = false), exit = fadeOut(tween(160)) + shrinkVertically(clip = false)) {
                    val app = rememberAppInfo(screen?.app)?.label
                    // minutes left, rounded up: 10 min just after the answer, 1 min in its last minute
                    val left = closesAt?.let { ((it - now + 59_999) / 60_000).coerceIn(0, HOLD_FOR / 60_000) }
                    val rest = (if (asks) (if (app != null) "$app stays open" else "Screen stays open")
                        else if (app != null) "Open in $app for your reply" else "Open for your reply") +
                        (if (left != null && left > 0) " · $left min" else "")
                    val call = P.call
                    val text = P.text
                    // first what the turn left you (your turn, the agent's words), then the screen's time
                    val said = if (failed) null else turnLabel(outcome, mood)
                    Text(if (said != null) buildAnnotatedString {
                            withStyle(SpanStyle(color = if (asks) call else text, fontWeight = FontWeight.Medium)) { append(said) }; append(" · $rest")
                        } else AnnotatedString(rest),
                        style = small, color = if (said != null) P.muted else P.faint, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                // a page waits on you mid-task
                AnimatedVisibility(s.live && asking != null, enter = fadeIn(tween(240, 160)) + expandVertically(clip = false), exit = fadeOut(tween(160)) + shrinkVertically(clip = false)) {
                    Text(listOf("Your turn", asking.orEmpty()).filter { it.isNotEmpty() }.joinToString(" · "), Modifier.padding(top = 4.dp),
                        style = small, color = P.call, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                // after the turn: your turn, or what the agent had him say (a failure says so below the answer)
                val label = if (failed) null else turnLabel(outcome, mood)
                AnimatedVisibility(waits && label != null, enter = fadeIn(tween(240, 160)) + expandVertically(clip = false), exit = fadeOut(tween(160)) + shrinkVertically(clip = false)) {
                    Text(label.orEmpty(), style = small, color = if (asks) P.call else P.muted, fontWeight = if (asks) FontWeight.Medium else null,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Box(Modifier.fillMaxWidth().animateContentSize(spring(stiffness = 500f))) { if (open) Timeline(s.lines) }
    }
}

/** Dash as a creature beside a turn's "Working": he stands in this box. */
private val CREATURE = DpSize(32.dp, 44.dp)
/** A turn's still, and a held screen's frame. */
private val STILL = 44.dp

@Composable
private fun Timeline(lines: List<Line>) {
    val rail = P.hairline
    val dot = P.faint
    Column(Modifier.padding(top = 6.dp, bottom = 2.dp)) {
        lines.forEachIndexed { i, l ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Box(Modifier.width(18.dp).fillMaxHeight().drawBehind {
                    val x = 4.dp.toPx()
                    val cy = 11.dp.toPx()
                    if (i > 0) drawLine(rail, Offset(x, 0f), Offset(x, cy), 1.dp.toPx())
                    if (i < lines.lastIndex) drawLine(rail, Offset(x, cy), Offset(x, size.height), 1.dp.toPx())
                    if (l.note) drawRoundRect(dot, Offset(x - 3.dp.toPx(), cy - 3.dp.toPx()), Size(6.dp.toPx(), 6.dp.toPx()), CornerRadius(3.dp.toPx()))
                    else drawCircle(dot, 2.5.dp.toPx(), Offset(x, cy))
                })
                // Narration reads as the agent talking; steps are quieter.
                Text(l.text, Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.bodyMedium, color = if (l.note) P.muted else P.faint)
            }
        }
    }
}

/** Starting points shown on a new chat; tapping one puts it in the message box. */
private val SUGGESTIONS = listOf(
    Triple(R.drawable.ic_s_messages, "Catch me up", "on unread messages"),
    Triple(R.drawable.ic_s_calendar, "Check my calendar", "today and tomorrow"),
    Triple(R.drawable.ic_s_alarm, "Set a timer", "for 10 minutes"),
    Triple(R.drawable.ic_s_mail, "Triage my inbox", "and flag anything urgent"),
    Triple(R.drawable.ic_s_music, "Play something", "I'd like but haven't heard"),
    Triple(R.drawable.ic_s_dnd, "Quiet my phone", "until tomorrow morning"),
)

@Composable
fun EmptyState(onSuggestion: (String) -> Unit, modifier: Modifier, listening: Boolean = false, level: () -> Float = { 0f }, typing: Boolean = false) {
    // Dash says hi, then rests: he listens while you talk and reads along while you type.
    val rig = rememberDash(setup = { it.feel(Feel.HAPPY) })
    var greeted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(1400); greeted = true }
    val feel = when { listening -> Feel.LISTENING; typing -> Feel.READING; !greeted -> Feel.HAPPY; else -> Feel.IDLE }
    LaunchedEffect(feel) { rig.feel(feel) }
    rig.level = level
    Box(modifier) {
        Column(Modifier.align(Alignment.Center).padding(horizontal = 32.dp).padding(bottom = 72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Dash(rig, Modifier.size(width = 112.dp, height = 124.dp).testTag("dash"), label = "Dash, Droid's character")
            Text("What can I do for you?", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text("I use your apps on a hidden screen while you keep using the phone.", Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodyMedium, color = P.muted, textAlign = TextAlign.Center)
        }
        LazyRow(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 8.dp).testTag("suggestions"),
            contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(SUGGESTIONS) { (icon, title, rest) ->
                Column(
                    Modifier.width(168.dp).clip(RoundedCornerShape(18.dp)).border(1.dp, P.hairline, RoundedCornerShape(18.dp))
                        .clickable { onSuggestion("$title $rest") }.padding(horizontal = 14.dp, vertical = 12.dp).testTag("suggestion"),
                ) {
                    Icon(painterResource(icon), null, Modifier.size(18.dp), tint = P.muted)
                    Text(title, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(rest, style = MaterialTheme.typography.bodySmall, color = P.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

enum class Listen { DICTATE, VOICE }

/**
 * The message box: attach on the left, the text, dictation, and one round button on the right that is voice
 * mode when empty, send with text, stop while the chat runs, and done while listening.
 */
@Composable
fun Composer(
    text: String, onText: (String) -> Unit, files: List<Attachment>, onRemove: (Attachment) -> Unit,
    listening: Listen?, level: () -> Float, running: Boolean,
    onAttach: () -> Unit, onSend: () -> Unit, onStop: () -> Unit, onDictate: () -> Unit, onVoice: () -> Unit,
    onFinishListening: () -> Unit, onCancelListening: () -> Unit, modifier: Modifier = Modifier,
) {
    val canSend = text.isNotBlank() || files.isNotEmpty()
    var field by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    Column(modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp)) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(P.surface)
                .border(1.dp, P.hairline.copy(alpha = if (P.dark) .6f else 1f), RoundedCornerShape(28.dp))
                .animateContentSize(spring(stiffness = 700f)),
        ) {
            if (files.isNotEmpty()) LazyRow(
                Modifier.fillMaxWidth().padding(top = 10.dp), contentPadding = PaddingValues(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(files, key = { it.path }) { a ->
                    Box {
                        AttachmentView(a, big = false)
                        Box(
                            Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp).size(20.dp).clip(CircleShape)
                                .background(P.ink).clickable { onRemove(a) },
                            contentAlignment = Alignment.Center,
                        ) { Icon(painterResource(R.drawable.ic_close), "Remove", Modifier.size(12.dp), tint = P.onInk) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.Bottom) {
                if (listening != null) IconBtn(R.drawable.ic_close, "Cancel", onCancelListening, Modifier.testTag("cancel_listen"), tint = P.muted)
                else IconBtn(R.drawable.ic_add, "Attach", onAttach, Modifier.testTag("attach"), tint = P.text, iconSize = 24.dp)
                Box(Modifier.weight(1f).heightIn(min = 40.dp).padding(horizontal = 6.dp, vertical = 9.dp), contentAlignment = Alignment.CenterStart) {
                    if (text.isEmpty()) Text(
                        when (listening) { null -> "Message Droid"; Listen.DICTATE -> "Listening…"; Listen.VOICE -> "Listening… I'll send when you pause" },
                        style = MaterialTheme.typography.bodyLarge, color = P.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    // Text put in from outside (a suggestion, dictation) gets the cursor at its end.
                    val value = if (field.text == text) field else TextFieldValue(text, TextRange(text.length))
                    BasicTextField(
                        value, { field = it; onText(it.text) }, Modifier.fillMaxWidth().testTag("input"),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = P.text), cursorBrush = SolidColor(P.text), maxLines = 6,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    )
                }
                if (listening != null) Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { LevelBars(level) }
                else if (!canSend) IconBtn(R.drawable.ic_mic, "Dictate", onDictate, Modifier.testTag("mic"), tint = P.text)
                val action = when {
                    listening != null -> R.drawable.ic_check
                    canSend -> R.drawable.ic_send
                    running -> R.drawable.ic_stop
                    else -> R.drawable.ic_voice
                }
                Box(
                    Modifier.padding(start = 2.dp).size(40.dp).clip(CircleShape).background(P.ink)
                        .clickable {
                            when (action) {
                                R.drawable.ic_check -> onFinishListening()
                                R.drawable.ic_send -> onSend()
                                R.drawable.ic_stop -> onStop()
                                else -> onVoice()
                            }
                        }
                        .testTag(when (action) { R.drawable.ic_check -> "done_listen"; R.drawable.ic_send -> "send"; R.drawable.ic_stop -> "stop"; else -> "voice" }),
                    contentAlignment = Alignment.Center,
                ) {
                    AnimatedContent(action, label = "action", transitionSpec = {
                        (fadeIn(tween(160)) + scaleIn(tween(160), .6f)) togetherWith (fadeOut(tween(100)) + scaleOut(tween(100), .6f))
                    }) { a ->
                        Icon(painterResource(a), when (a) {
                            R.drawable.ic_check -> "Done"; R.drawable.ic_send -> "Send"; R.drawable.ic_stop -> "Stop"; else -> "Voice mode"
                        }, Modifier.size(if (a == R.drawable.ic_stop) 18.dp else 22.dp), tint = P.onInk)
                    }
                }
            }
        }
    }
}

/** Four bars that follow the voice's loudness. */
@Composable
private fun LevelBars(level: () -> Float) {
    val t by rememberInfiniteTransition(label = "bars").animateFloat(0f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "t")
    val color = P.text
    Box(Modifier.size(22.dp).drawBehind {
        val l = level()
        val w = 3.dp.toPx()
        val gap = (size.width - 4 * w) / 3
        val shape = floatArrayOf(.55f, 1f, .8f, .45f)
        for (i in 0 until 4) {
            val wobble = if (i % 2 == 0) t else 1 - t
            val h = (size.height * (.18f + (.82f * (l * .85f + .15f * wobble)) * shape[i])).coerceAtMost(size.height)
            drawRoundRect(color, Offset(i * (w + gap), (size.height - h) / 2), Size(w, h), CornerRadius(w / 2))
        }
    })
}

/** The attach sheet: Droid's camera, photos, files. */
@Composable
fun AttachSheet(onCamera: () -> Unit, pickers: Pickers, onDismiss: () -> Unit) {
    ActionSheet(onDismiss) { close ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(
                Triple(R.drawable.ic_camera, "Camera", onCamera),
                Triple(R.drawable.ic_photos, "Photos", pickers.photos),
                Triple(R.drawable.ic_file, "Files", pickers.files),
            ).forEach { (icon, label, go) ->
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(P.surface).clickable { close(); go() }
                        .padding(vertical = 18.dp).testTag("attach_" + label.lowercase()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(painterResource(icon), null, Modifier.size(24.dp), tint = P.text)
                    Text(label, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        Text("Droid gets a copy it can open, look at or send on.", Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall, color = P.muted)
    }
}
