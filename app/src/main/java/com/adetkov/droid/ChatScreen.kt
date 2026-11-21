package com.adetkov.droid

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
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
import androidx.compose.ui.text.TextRange
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
    /** A failure that Setup fixes (a sign-in run out, free models used up): open Setup. */
    onSetup: () -> Unit = {},
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
    // An answer taller than the screen opens at its top, like an app: Dash's words and the start of what the agent made,
    // rather than its end. Once, as the turn ends.
    val latest by rememberUpdatedState(items)
    var wasRunning by remember(conv.id) { mutableStateOf(running) }
    LaunchedEffect(running) {
        val ended = wasRunning && !running
        wasRunning = running
        if (!ended) return@LaunchedEffect
        delay(700)  // its page lays out
        val all = latest
        val turn = all.indexOfLast { it is Item.Message && it.msg.kind.opensTurn }
        val answer = all.indexOfLast { it is Item.Message && (it.msg.kind == Kind.AGENT || it.msg.kind == Kind.PAGE) }
        if (answer <= turn) return@LaunchedEffect
        val info = state.layoutInfo
        val size = info.visibleItemsInfo.firstOrNull { it.index == answer }?.size ?: return@LaunchedEffect
        if (size > info.viewportSize.height * .7f) {
            follow = false
            val steps = all.subList(turn, answer).indexOfLast { it is Item.Steps }.takeIf { it >= 0 }?.plus(turn)
            state.animateScrollToItem(steps ?: answer)
        }
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
    val droid = LocalContext.current.applicationContext as DroidApp
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
                                    conv = conv.id, index = item.index, live = pageLive(droid, conv, item.index)) { selecting = stripMarkdown(m.text) }
                                Kind.PAGE -> PageMessage(m, conv.id, pageLive(droid, conv, item.index), onTakeOver)
                                Kind.WAKE -> WakeLine(m.text)
                                Kind.STATUS -> StatusRow(m.text, conv.engine, retry = if (item === items[lastIndex] && !running) onRetry else null, onSetup = onSetup)
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
                    Text(if (app != null) "$app stays open for your reply" else "Droid's screen stays open for your reply", Modifier.weight(1f).padding(top = 4.dp),
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
    /** The chat and the answer's place in it, and whether a page in it may act ([pageLive]: in the latest turn). */
    conv: String = "", index: Int = 0, live: Boolean = true,
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
            val app = ctx.applicationContext as DroidApp
            val actions = remember(conv, index) { ChatPageActions(ctx, conv, null, isLive = { pageLiveNow(app, conv, null, index) }, onScreen = onTakeOver) }
            val dark = P.dark
            InlinePage("a:$conv:$index:${text.hashCode()}", "answer:$conv:$index", dark, { cx -> answerDocument(cx, text, dark) },
                namedPaths(text), actions, Modifier.testTag("answer"), live = live)
        } else Markdown(shown, Modifier.fillMaxWidth().combinedClickable(remember { MutableInteractionSource() }, null,
            onLongClick = onLongPress, onClick = {}).testTag("answer"))
        // A page is an app to use, not text to copy: the copy, read aloud and share row is for answers in words.
        if (!html) Row(Modifier.offset(x = (-10).dp).padding(top = 4.dp)) {
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
 * A page the agent showed (`droid show`): in the chat, the page itself, and the owner's answer under it once given
 * (one that went as a message is the chat's); one shown elsewhere (full screen, floating, a notification) is a line
 * here that opens it full screen. [live]: whether it may still act ([pageLive]).
 */
@Composable
private fun PageMessage(m: Msg, conv: String, live: Boolean, onTakeOver: () -> Unit) {
    val ctx = LocalContext.current
    val info = remember(m.data) { PageInfo.of(m) }
    Column(Modifier.fillMaxWidth()) {
        if (info.where == "chat") {
            val actions = remember(conv, info.id) { ChatPageActions(ctx, conv, info.id, onScreen = onTakeOver) }
            val dark = P.dark
            InlinePage("p:$conv:${info.id}:${m.text.hashCode()}", "page:$conv:${info.id}", dark, { cx -> pageDocument(cx, m.text, dark, PageMode.INLINE) },
                namedPaths(m.text), actions, Modifier.testTag("page"), live = live)
        } else Row(
            Modifier.clip(RoundedCornerShape(14.dp)).border(1.dp, P.hairline, RoundedCornerShape(14.dp))
                .clickable { ctx.startActivity(pageIntent(ctx, conv, info.id)) }.padding(start = 12.dp, end = 16.dp, top = 10.dp, bottom = 10.dp).testTag("page_link"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_screen), null, Modifier.size(18.dp), tint = P.muted)
            Text(info.title.ifEmpty { "Page" }, Modifier.padding(start = 10.dp), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        info.reply?.takeIf { it.isNotEmpty() }?.let { r ->
            Text(r, Modifier.align(Alignment.End).padding(top = 10.dp, start = 48.dp).clip(RoundedCornerShape(22.dp)).background(P.surface)
                .padding(horizontal = 16.dp, vertical = 10.dp).testTag("page_reply"), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** The newest page the agent put on the home screen (`droid show --home`): its chat and its PAGE message. */
internal fun homePageOf(convs: List<Conv>): Pair<String, Msg>? = convs.flatMap { c ->
    c.messages.filter { it.kind == Kind.PAGE && PageInfo.of(it).where == "home" }.map { c.id to it }
}.maxByOrNull { it.second.ts }

/**
 * The agent's page on the home screen: a place to start from, not a question asked once, so it stays live; what it
 * sends starts a new chat (the agent is told where it came from), which [onStarted] opens.
 */
@Composable
fun HomePage(conv: String, m: Msg, onStarted: (String) -> Unit) {
    val ctx = LocalContext.current
    val info = remember(m.data) { PageInfo.of(m) }
    val scope = rememberCoroutineScope()
    val actions = remember(conv, info.id) {
        object : PageActions by ChatPageActions(ctx, conv, info.id, isLive = { true }) {
            override fun send(text: String, data: String?) {
                scope.launch { startTask(ctx, null, text, data = data, via = "I tapped this on the page you put on my home screen")?.let(onStarted) }
            }
        }
    }
    val dark = P.dark
    InlinePage("home:$conv:${info.id}:${m.text.hashCode()}", "page:$conv:${info.id}", dark,
        { cx -> pageDocument(cx, m.text, dark, PageMode.INLINE) }, namedPaths(m.text), actions, live = true)
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
internal fun StatusRow(text: String, engine: Engine, retry: (() -> Unit)?, onSetup: () -> Unit) {
    val stopped = text == "Stopped"
    // The agent's words when they already say it; otherwise what they mean, with theirs a tap away (and Setup when
    // that's where it's fixed). A long one opens in full on a tap too.
    val plain = remember(text, engine) { plainStatus(text, engine) }
    var open by remember(text) { mutableStateOf(false) }
    val color = if (stopped) P.muted else P.danger
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The icon stays with the first line when the text runs to more
            Row(Modifier.weight(1f, fill = false)) {
                Icon(painterResource(if (stopped) R.drawable.ic_stop_circle else R.drawable.ic_error), null,
                    Modifier.padding(top = 2.dp).size(16.dp), tint = color)
                Text(plain?.text ?: text, Modifier.padding(start = 8.dp).clickable(enabled = !stopped) { open = !open }.testTag("status"),
                    style = MaterialTheme.typography.bodyMedium, color = color, maxLines = if (open) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis)
            }
            if (retry != null && plain?.setup != true) Pill(R.drawable.ic_retry, "Retry", retry, Modifier.padding(start = 12.dp).testTag("retry"))
        }
        if (open && plain != null && plain.text != text.removePrefix("Error: ").removePrefix("Failed: ")) Text(text, Modifier.padding(start = 24.dp, top = 6.dp).testTag("status_said"),
            style = MaterialTheme.typography.bodySmall, color = P.muted)
        if (plain?.setup == true) Row(Modifier.padding(start = 24.dp, top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(R.drawable.ic_setup, "Setup", onSetup, Modifier.testTag("status_setup"))
            if (retry != null) Pill(R.drawable.ic_retry, "Retry", retry, Modifier.testTag("retry"))
        }
    }
}

@Composable
private fun Pill(icon: Int, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) = Row(
    modifier.clip(RoundedCornerShape(50)).border(1.dp, P.hairline, RoundedCornerShape(50))
        .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Icon(painterResource(icon), null, Modifier.size(16.dp), tint = P.text)
    Text(label, Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelMedium)
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
    val rig = rememberDash(s.key, active = !released) { r ->
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
                        else if (app != null) "$app stays open for your reply" else "Stays open for your reply") +
                        (if (left != null && left > 0) " · $left min" else "")
                    // first what the turn left you (your turn, the agent's words), then the screen's time
                    Column {
                        if (!failed) DashSays(mood, asks)
                        Text(rest, Modifier.padding(top = 6.dp), style = small, color = P.faint, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                // a page waits on you mid-task
                AnimatedVisibility(s.live && asking != null, enter = fadeIn(tween(240, 160)) + expandVertically(clip = false), exit = fadeOut(tween(160)) + shrinkVertically(clip = false)) {
                    DashSays(Mood(Feel.NEEDS_YOU, asking.orEmpty()), true)
                }
                // after the turn: your turn, or what the agent had him say (a failure says so below the answer)
                val label = if (failed) null else turnLabel(outcome, mood)
                // (also beside the still a closed screen left: still their turn)
                AnimatedVisibility((waits || (!s.live && !held && hadScreen)) && label != null, enter = fadeIn(tween(240, 160)) + expandVertically(clip = false), exit = fadeOut(tween(160)) + shrinkVertically(clip = false)) {
                    DashSays(mood, asks)
                }
            }
        }
        Box(Modifier.fillMaxWidth().animateContentSize(spring(stiffness = 500f))) { if (open) Timeline(s.lines) }
    }
}

/**
 * What Dash says after a turn, in a speech bubble beside him: the agent's words (`droid dash`), the headline the owner
 * reads first; "Your turn" over them when it's the owner's move ([asks], or he calls).
 */
@Composable
private fun DashSays(mood: Mood?, asks: Boolean) {
    val calls = asks || mood?.calls == true
    val words = mood?.words.orEmpty()
    if (!calls && words.isEmpty()) return
    Column(Modifier.padding(top = 4.dp)) {
        if (calls && words.isNotEmpty()) Text("Your turn", style = MaterialTheme.typography.labelLarge, color = P.call, fontWeight = FontWeight.SemiBold)
        Text(words.ifEmpty { "Your turn" }, Modifier.padding(top = 4.dp)
            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 20.dp))
            .background(P.call.copy(alpha = if (P.dark) .22f else .12f)).padding(horizontal = 14.dp, vertical = 10.dp).testTag("dash_says"),
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
            color = P.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
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

/**
 * Starting points on a new chat: a tap sends the request (shown shorter on it). The quick ones, so a first try answers
 * within a minute (Claude on the demo phone, 2026-11-20: calendar 46 s, timer 27 s, quiet 31 s).
 */
private val SUGGESTIONS = listOf(
    Triple(R.drawable.ic_s_calendar, "What's on my calendar?", "What's on my calendar today and tomorrow?"),
    Triple(R.drawable.ic_s_alarm, "Set a 10-minute timer", "Set a timer for 10 minutes"),
    Triple(R.drawable.ic_s_dnd, "Quiet my phone till morning", "Quiet my phone until tomorrow morning"),
)

@Composable
fun EmptyState(onSuggestion: (String) -> Unit, modifier: Modifier, listening: Boolean = false, level: () -> Float = { 0f }, typing: Boolean = false,
               home: (@Composable () -> Unit)? = null,
               /** The home page's greeting (Dash's words when he made it), and when he made it ("this morning"). */
               greeting: String? = null, made: String? = null,
               /** He's making today's home page. */
               preparing: Boolean = false,
               /** Dash's apps (the chats of the last week) and opening one. */
               apps: List<AppTile> = emptyList(), onOpen: (String) -> Unit = {}) {
    // Dash says hi, then rests: he listens while you talk and reads along while you type.
    val rig = rememberDash()  // at rest, so the greeting below is a change: he hops
    var greeted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(1400); greeted = true }
    val feel = when { listening -> Feel.LISTENING; typing -> Feel.READING; !greeted -> Feel.HAPPY; else -> Feel.IDLE }
    LaunchedEffect(feel) { rig.feel(feel) }
    rig.level = level
    // The agent's own page for the home screen (droid show --home), the owner's day: Dash greets them, and his page is
    // under him in place of the starting points.
    // Dash's board: what he's doing for you and did, a tile each (your turn first), around his page for your day.
    if (home != null || apps.isNotEmpty()) {
        val scroll = rememberScrollState()
        val drag = rememberListDrag(scroll)
        val (now, before) = apps.partition { it.calls || it.working }
        Column(modifier.verticalScroll(scroll).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Dash(rig, Modifier.size(width = 66.dp, height = 74.dp).testTag("dash"), label = null)
                Box(Modifier.weight(1f).padding(start = 6.dp)) { SpeechBubble(if (preparing) "Getting your day ready…" else greeting ?: "Hi! What should I do?") }
            }
            if (now.isNotEmpty()) AppTiles(now, onOpen, Modifier.padding(bottom = 16.dp))
            if (home != null) CompositionLocalProvider(LocalListDrag provides drag) {
                Box(Modifier.fillMaxWidth().testTag("home_page")) { home() }
            }
            if (made != null) Text("Dash made this page for you $made", Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = P.faint, textAlign = TextAlign.Center)
            // earlier ones only without his page, which already says what the day holds (all of it is in the chats)
            if (home == null && before.isNotEmpty()) AppTiles(before, onOpen, Modifier.padding(top = 12.dp, bottom = 16.dp))
        }
        return
    }
    // Dash asks, and three big starters wait under him; they step aside when there's no room for them (the keyboard up
    // with a long draft, say), rather than run into him: Dash stays, reading along.
    BoxWithConstraints(modifier) {
        var words by remember { mutableIntStateOf(0) }
        var chips by remember { mutableIntStateOf(0) }
        val fits = with(LocalDensity.current) { maxHeight.toPx() >= words + chips + 24.dp.toPx() }
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(Modifier.padding(horizontal = 28.dp).onSizeChanged { words = it.height }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Bubble(if (preparing) "Getting your day ready…" else "Hi! What should I do?")
                    Dash(rig, Modifier.padding(top = 4.dp).size(width = 150.dp, height = 166.dp).testTag("dash"), label = null)
                    Text("I use your apps for you, on my own little screen. You can keep using your phone.", Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.bodyLarge, color = P.muted, textAlign = TextAlign.Center)
                }
            }
            AnimatedVisibility(fits, enter = fadeIn(tween(160)) + expandVertically(), exit = fadeOut(tween(160)) + shrinkVertically()) {
                Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp).onSizeChanged { chips = it.height }.testTag("suggestions"),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SUGGESTIONS.forEach { (icon, label, request) ->
                        Row(Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(20.dp)).background(P.surface)
                            .clickable { onSuggestion(request) }.padding(horizontal = 18.dp).testTag("suggestion"),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(painterResource(icon), null, Modifier.size(24.dp), tint = P.call)
                            Text(label, Modifier.padding(start = 14.dp), style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/** Tiles two by two, one per app (see [AppTile]). */
@Composable
private fun AppTiles(tiles: List<AppTile>, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().testTag("apps"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { t -> AppTileView(t, Modifier.weight(1f).fillMaxHeight()) { onOpen(t.conv) } }
                if (row.size == 1 && tiles.size > 1) Spacer(Modifier.weight(1f))  // a tile alone takes the row
            }
        }
    }
}

/** One app: Dash as he stands with it, its name, and his words about it; ringed in his color when it's your turn. */
@Composable
private fun AppTileView(t: AppTile, modifier: Modifier, onClick: () -> Unit) {
    val rig = rememberDash("tile:${t.conv}", feel = t.feel)
    LaunchedEffect(t.feel) { rig.feel(t.feel) }
    val shape = RoundedCornerShape(24.dp)
    Column(modifier.heightIn(min = 128.dp).clip(shape).background(P.surface)
        .let { if (t.calls) it.border(2.dp, P.call, shape) else it }.clickable(onClick = onClick).padding(14.dp).testTag("app_tile")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dash(rig, Modifier.size(width = 40.dp, height = 44.dp), label = null, onPoke = null)
            if (t.calls) Text("Your turn", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelLarge, color = P.call, fontWeight = FontWeight.Bold)
            else if (t.working) Text("Working", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelLarge, color = P.muted)
        }
        Text(t.name, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold),
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (t.words.isNotEmpty()) Text(t.words, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodyMedium, color = P.muted,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Dash's words over him, in a speech bubble that points down at him. */
@Composable
private fun Bubble(text: String) {
    // solid, so the tail and the bubble are one shape where they overlap
    val bg = P.call.copy(alpha = if (P.dark) .22f else .12f).compositeOver(P.bg)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, Modifier.clip(RoundedCornerShape(24.dp)).background(bg).padding(horizontal = 22.dp, vertical = 14.dp).testTag("dash_says"),
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), textAlign = TextAlign.Center)
        Box(Modifier.offset(y = (-9).dp).size(18.dp).rotate(45f).clip(RoundedCornerShape(3.dp)).background(bg))
    }
}

enum class Listen { DICTATE, VOICE }

/**
 * How the owner talks to Droid. At rest it is one big button, Tap to talk (voice mode: it sends when they stop
 * talking), with typing a tap away beside it, and Stop while the chat runs. Typing, a draft or an attachment, and
 * listening, open the message box: attach on the left, the text, dictation, and one round button on the right that is
 * voice mode when empty, send with text, stop while the chat runs, and done while listening.
 */
@Composable
fun Composer(
    text: String, onText: (String) -> Unit, files: List<Attachment>, onRemove: (Attachment) -> Unit,
    listening: Listen?, level: () -> Float, running: Boolean,
    onAttach: () -> Unit, onSend: () -> Unit, onStop: () -> Unit, onDictate: () -> Unit, onVoice: () -> Unit,
    onFinishListening: () -> Unit, onCancelListening: () -> Unit, modifier: Modifier = Modifier,
    /** Typing: the message box is open (with the keyboard), rather than the talk bar. */
    typing: Boolean = false, onTyping: (Boolean) -> Unit = {},
    /** The page's own next step is at the bottom (its footer): no talk bar under it (the top bar has a microphone). */
    hidden: Boolean = false,
) {
    val canSend = text.isNotBlank() || files.isNotEmpty()
    var field by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    if (!typing && text.isEmpty() && files.isEmpty() && listening == null) {
        // (out of the way, it still keeps the page's footer clear of the system's gesture bar)
        if (!hidden) TalkBar(running, onVoice, onStop, onType = { onTyping(true) }, modifier) else Spacer(modifier.fillMaxWidth().height(4.dp))
        return
    }
    // Opened to type: the keyboard comes up at once; left empty, it closes back into the talk bar.
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(typing) { if (typing) runCatching { focus.requestFocus() } }
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
                        value, { field = it; onText(it.text) }, Modifier.fillMaxWidth().testTag("input").focusRequester(focus)
                            .onFocusChanged { f -> if (focused && !f.isFocused && text.isEmpty() && files.isEmpty()) onTyping(false); focused = f.isFocused },
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

/**
 * The talk bar: Tap to talk, the way in for anyone, with typing beside it. While Dash works: Stop, big, and talking to
 * him (to add something) beside it.
 */
@Composable
private fun TalkBar(running: Boolean, onVoice: () -> Unit, onStop: () -> Unit, onType: () -> Unit, modifier: Modifier) {
    val onCall = if (P.dark) Color(0xFF0D0D0D) else Color.White
    Row(modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (running) {
            Row(Modifier.weight(1f).height(60.dp).clip(RoundedCornerShape(30.dp)).background(P.surface).clickable(onClick = onStop).testTag("stop"),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_stop), null, Modifier.size(18.dp), tint = P.text)
                Text("Stop", Modifier.padding(start = 10.dp), style = MaterialTheme.typography.titleMedium.copy(fontSize = 19.sp, fontWeight = FontWeight.Bold))
            }
            Box(Modifier.size(60.dp).clip(CircleShape).background(P.call.copy(alpha = .14f)).clickable(onClick = onVoice).testTag("voice")
                .semantics { contentDescription = "Talk to Dash" }, contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_mic), null, Modifier.size(24.dp), tint = P.text)
            }
            return@Row
        }
        Row(Modifier.weight(1f).height(60.dp).clip(RoundedCornerShape(30.dp)).background(P.call).clickable(onClick = onVoice).testTag("voice"),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_mic), null, Modifier.size(24.dp), tint = onCall)
            Text("Tap to talk", Modifier.padding(start = 10.dp), style = MaterialTheme.typography.titleMedium.copy(fontSize = 19.sp, fontWeight = FontWeight.Bold), color = onCall)
        }
        Box(Modifier.size(60.dp).clip(CircleShape).background(P.call.copy(alpha = .14f)).clickable(onClick = onType).testTag("type")
            .semantics { contentDescription = "Type instead" }, contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_keyboard), null, Modifier.size(24.dp), tint = P.text)
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
