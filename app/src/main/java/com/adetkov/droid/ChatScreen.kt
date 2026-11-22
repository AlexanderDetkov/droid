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
import java.text.SimpleDateFormat
import java.util.Locale
import androidx.compose.runtime.key
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
import androidx.compose.ui.unit.Dp
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

/**
 * The whole chat, turn by turn ("Earlier in this chat"), in the kit: what the owner asked or tapped, in their bubbles;
 * Dash, small, with what he said and his steps under it (a tap opens them, with the screen he ended on); and what the
 * agent made, as it was (the latest turn's still live). Back to now, under it, returns to the chat's screen.
 */
@Composable
fun Transcript(
    conv: Conv, running: Boolean, speakingKey: String?, onSpeak: (String, String) -> Unit, onRetry: () -> Unit, modifier: Modifier,
    /** A failure that Setup fixes (a sign-in run out, free models used up): open Setup. */
    onSetup: () -> Unit = {},
    /** A page handed over the agent's screen (droid.screen): open it with the owner's touches going to it. */
    onTakeOver: () -> Unit = {},
) {
    val messages = conv.messages
    val items = remember(messages, running) { buildItems(messages, running) }
    val talks = remember(messages) { turnTalks(messages) }
    // each message's turn (where it starts), and the turns that have a steps row to carry Dash
    val turnOf = remember(messages) { var t = 0; IntArray(messages.size) { i -> if (messages[i].kind.opensTurn) t = i; t } }
    val stepped = remember(items) { items.mapNotNullTo(HashSet()) { (it as? Item.Steps)?.turn } }
    // when: over the first turn, and over one that came an hour or more after the one before
    val times = remember(messages) {
        val starts = messages.indices.filter { messages[it].kind.opensTurn }
        starts.filterIndexed { n, i -> n == 0 || messages[i].ts - messages[starts[n - 1]].ts >= 3600_000L }.toSet()
    }
    val waiting = remember(messages, running) { waitingPage(messages, running)?.let { PageInfo.of(it).title } }
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var menuFor by remember { mutableStateOf<Msg?>(null) }  // a long-pressed request or answer
    var selecting by remember { mutableStateOf<String?>(null) }
    // Follow new content only while the user is at the bottom. Only their own drag up leaves it: the list's own scrolls
    // and corrections (a page above growing, the keyboard) never do.
    var follow by remember(conv.id) { mutableStateOf(true) }
    val dragged by state.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(state) {
        snapshotFlow { Triple(state.canScrollForward, dragged, state.lastScrolledBackward) }
            .collect { (more, drag, back) -> if (!more) follow = true else if (drag && back) follow = false }
    }
    LaunchedEffect(conv.id, items.size, messages.lastOrNull()?.text?.length, running) {
        if (follow && items.isNotEmpty()) state.scrollToItem(items.lastIndex, BOTTOM)
    }
    // It opens at now, scrolled back from: again as its pages lay out (they come in at no height).
    LaunchedEffect(conv.id) {
        repeat(3) { delay(350); if (follow && items.isNotEmpty()) state.scrollToItem(items.lastIndex, BOTTOM) }
    }
    // Pages in this chat stay loaded while it shows; a drag that starts on one scrolls the chat.
    val pages = remember(conv.id) { PageCache() }
    DisposableEffect(pages) { onDispose { pages.clear() } }
    val drag = rememberListDrag(state, onDrag = { dy -> if (dy > 0) follow = false },
        grew = { if (follow) scope.launch { state.scrollToItem(state.layoutInfo.totalItemsCount - 1, BOTTOM) } })
    val focus = LocalFocusManager.current
    val view = LocalView.current
    val droid = LocalContext.current.applicationContext as DroidApp
    val lastIndex = items.lastIndex
    Box(modifier) { CompositionLocalProvider(LocalPageCache provides pages, LocalListDrag provides drag) {
        LazyColumn(
            Modifier.fillMaxSize().testTag("messages").dismissesKeyboard(drag, focus, view), state,
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
        ) {
            items(items, key = { it.key }) { item ->
                val gap = when {
                    item === items.first() -> 0.dp
                    item is Item.Message && item.msg.kind.opensTurn -> 28.dp
                    else -> 12.dp
                }
                Column(Modifier.fillMaxWidth().padding(top = gap).animateItem(fadeInSpec = tween(260), fadeOutSpec = null, placementSpec = spring(stiffness = 600f))) {
                    when (item) {
                        is Item.Steps -> {
                            val last = item.turn == turnOf.lastOrNull()
                            TurnLine(item, conv.id, talks[item.turn], asking = if (last) waiting else null)
                        }
                        is Item.Message -> {
                            val m = item.msg
                            val key = "${conv.id}:${item.index}"
                            if (item.index in times) TimeLabel(m.ts)
                            val talk = talks[turnOf[item.index]]
                            when (m.kind) {
                                Kind.USER -> UserMessage(m) { menuFor = m }
                                // a short answer is what Dash says: on his steps row, or on its own when the turn had none
                                Kind.AGENT -> if (talk?.saysAnswer == true) { if (turnOf[item.index] !in stepped) TurnLine(null, conv.id, talk) }
                                    else Answer(m.text, speakingKey == key, { onSpeak(key, m.text) }, onTakeOver = onTakeOver,
                                        conv = conv.id, index = item.index, live = pageLive(droid, conv, item.index)) { menuFor = m }
                                Kind.PAGE -> PageMessage(m, conv.id, pageLive(droid, conv, item.index), onTakeOver)
                                Kind.WAKE -> WakeLine(m.text)
                                Kind.STATUS -> StatusRow(m.text, conv.engine, retry = if (item === items[lastIndex] && !running) onRetry else null, onSetup = onSetup)
                                else -> Text(m.text, Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall, color = P.muted)
                            }
                        }
                    }
                }
            }
        }
        }
        // Text scrolling under the top row fades out instead of being cut off.
        val bg = P.bg
        Box(Modifier.fillMaxWidth().height(20.dp).align(Alignment.TopCenter)
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(bg, bg.copy(alpha = 0f)))))
    }
    menuFor?.let { m -> MessageSheet(m, speakingKey, onSpeak, { selecting = stripMarkdown(m.text) }) { menuFor = null } }
    selecting?.let { SelectTextSheet(it) { selecting = null } }
}

/** What Dash says for a turn in the history ([TurnLine]): the agent's words for him, else its answer when it's short, which he then says instead. */
private class TurnTalk(val words: String?, val feel: Feel, val saysAnswer: Boolean)

/** Each turn's [TurnTalk], by where the turn starts. */
private fun turnTalks(messages: List<Msg>): Map<Int, TurnTalk> {
    val out = HashMap<Int, TurnTalk>()
    var from = 0
    while (from < messages.size) {
        var to = from + 1
        while (to < messages.size && !messages[to].kind.opensTurn) to++
        val turn = messages.subList(from, to)
        // a turn the owner stopped keeps no words it was given on the way
        val mood = if (turn.any { it.kind == Kind.STATUS && it.text == "Stopped" }) null else turn.lastOrNull { it.kind == Kind.DASH }?.let { moodOf(it.text) }
        val own = mood?.words?.takeIf { it.isNotBlank() }
        val short = turn.lastOrNull { it.kind == Kind.AGENT }?.text?.let(::shortAnswer)
        out[from] = TurnTalk(own ?: short, mood?.feel ?: Feel.IDLE, own == null && short != null)
        from = to
    }
    return out
}

/** When a turn was asked, over it: "Today, 2:14 PM". */
@Composable
private fun TimeLabel(ts: Long) {
    val text = remember(ts) {
        val day = when (val g = dayGroup(ts)) { "Today", "Yesterday" -> g; else -> SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(ts) }
        day + ", " + java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(ts)
    }
    Text(text, Modifier.fillMaxWidth().padding(bottom = 10.dp), style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
        color = P.muted, textAlign = TextAlign.Center)
}

/**
 * A turn's Dash in the history: small, with what he said ([talk]) and his steps under it ("3 steps · 41s"; while he
 * works, what he's doing and for how long). A tap on the steps opens them, with the screen he ended on.
 */
@Composable
private fun TurnLine(s: Item.Steps?, conv: String, talk: TurnTalk?, asking: String? = null) {
    val live = s?.live == true
    var open by rememberSaveable(s?.key) { mutableStateOf(false) }
    val feel = when { asking != null -> Feel.NEEDS_YOU; live -> Feel.THINKING; else -> talk?.feel ?: Feel.IDLE }
    val rig = rememberDash("turn:${s?.key}", feel, active = live)
    LaunchedEffect(feel) { rig.feel(feel) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    if (live) LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val n = s?.lines?.size ?: 0
    val working = live && asking == null
    val sub = when {
        s == null -> null
        working -> s.lines.lastOrNull()?.text ?: "Getting started"
        else -> stepsLine(s) ?: if (n > 0) "What I did" else null
    }
    val words = talk?.words.takeIf { !live || asking != null } ?: when { asking != null -> asking.ifBlank { "Your turn" }; live -> "On it!"; else -> null }
    val ctx = LocalContext.current
    val stillFile = remember(conv, s?.stillKey) { s?.let { Stills.file(ctx, conv, it.stillKey) } }
    Column(Modifier.fillMaxWidth()) {
        DashLine(words, size = DashSize.SMALL, rig = rig, sub = sub, subTail = if (working && s != null) duration(now - s.start) else null,
            onSub = if (n > 0) ({ open = !open }) else null)
        AnimatedVisibility(open && s != null, enter = fadeIn(tween(200)) + expandVertically(), exit = fadeOut(tween(120)) + shrinkVertically()) {
            Column(Modifier.padding(start = 72.dp, end = 20.dp, top = 10.dp)) {
                val still = stillFile?.let { rememberStill(it, live) }
                if (still != null) ScreenWindow(still, 72.dp, Modifier.padding(bottom = 12.dp).testTag("still"))
                if (s != null) Timeline(s.lines)
            }
        }
    }
}

/** The owner's request in the history: their bubble on the right (a tap's, with the hand), what they sent above it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UserMessage(m: Msg, onLongPress: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        if (m.files.isNotEmpty()) FlowRow(
            Modifier.padding(start = 72.dp, end = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(6.dp),
            itemVerticalAlignment = Alignment.Bottom,
        ) { m.files.forEach { AttachmentView(it, big = true) } }
        if (m.text.isNotBlank()) YouBubble(m.text, Modifier.testTag("user_msg"), tap = m.data != null, onLongClick = onLongPress)
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

/** An answer in the history: a page the agent made, as it was; words, plainly (a long press: copy, read aloud, share). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Answer(
    text: String, speaking: Boolean, onSpeak: () -> Unit, onTakeOver: () -> Unit,
    /** The chat and the answer's place in it, and whether a page in it may act ([pageLive]: in the latest turn). */
    conv: String = "", index: Int = 0, live: Boolean = true,
    onLongPress: () -> Unit,
) {
    val ctx = LocalContext.current
    // An answer with HTML is a page the agent made: it renders as one, seamless with the chat.
    val html = remember(text) { hasHtml(text) }
    Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        if (html) {
            val app = ctx.applicationContext as DroidApp
            val actions = remember(conv, index) { ChatPageActions(ctx, conv, null, isLive = { pageLiveNow(app, conv, null, index) }, onScreen = onTakeOver) }
            val dark = P.dark
            InlinePage("a:$conv:$index:${text.hashCode()}", "answer:$conv:$index", dark, { cx -> answerDocument(cx, text, dark) },
                namedPaths(text), actions, Modifier.testTag("answer"), live = live)
        } else {
            val shown = remember(text) { answerText(text) }
            Markdown(shown, Modifier.fillMaxWidth().combinedClickable(remember { MutableInteractionSource() }, null,
                onLongClick = onLongPress, onClick = {}).testTag("answer"))
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
        if (info.where == "chat") Box(Modifier.padding(horizontal = 20.dp)) {
            val actions = remember(conv, info.id) { ChatPageActions(ctx, conv, info.id, onScreen = onTakeOver) }
            val dark = P.dark
            InlinePage("p:$conv:${info.id}:${m.text.hashCode()}", "page:$conv:${info.id}", dark, { cx -> pageDocument(cx, m.text, dark, PageMode.INLINE) },
                namedPaths(m.text), actions, Modifier.testTag("page"), live = live)
        } else Row(
            Modifier.padding(horizontal = 20.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, P.hairline, RoundedCornerShape(14.dp))
                .clickable { ctx.startActivity(pageIntent(ctx, conv, info.id)) }.padding(start = 12.dp, end = 16.dp, top = 10.dp, bottom = 10.dp).testTag("page_link"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_screen), null, Modifier.size(18.dp), tint = P.muted)
            Text(info.title.ifEmpty { "Page" }, Modifier.padding(start = 10.dp), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        info.reply?.takeIf { it.isNotEmpty() }?.let { r -> YouBubble(r, Modifier.padding(top = 4.dp).testTag("page_reply"), tap = true) }
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
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("wake"), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.ic_s_alarm), null, Modifier.size(16.dp), tint = P.muted)
        Text(text.ifBlank { "Droid checked back" }, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall, color = P.muted,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** A long-pressed request or answer: copy it, select some of it, and an answer's read aloud and share. */
@Composable
private fun MessageSheet(m: Msg, speakingKey: String?, onSpeak: (String, String) -> Unit, onSelect: () -> Unit, onDismiss: () -> Unit) {
    val copy = rememberCopy()
    val ctx = LocalContext.current
    val text = stripMarkdown(m.text)
    ActionSheet(onDismiss) { close ->
        SheetRow(R.drawable.ic_copy, "Copy", { copy(text); close() }, tag = "sheet_copy")
        SheetRow(R.drawable.ic_select, "Select text", { onSelect(); close() }, tag = "sheet_select", line = true)
        if (m.kind == Kind.AGENT) {
            val key = "${m.ts}:${m.text.hashCode()}"
            SheetRow(R.drawable.ic_speaker, if (speakingKey == key) "Stop reading" else "Read it aloud", { onSpeak(key, m.text); close() }, tag = "sheet_speak", line = true)
            SheetRow(R.drawable.ic_share, "Share", { shareText(ctx, text); close() }, tag = "sheet_share", line = true)
        }
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
    Column(Modifier.padding(horizontal = 20.dp)) {
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

/** A way on from a failure, as a pick. */
@Composable
private fun Pill(icon: Int, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) = Pick(false, onClick, modifier) {
    Icon(painterResource(icon), null, Modifier.padding(end = 6.dp).size(18.dp), tint = P.text)
    PickText(label, false)
}

/** A turn's steps, one a line on a quiet rail: his narration darker than his steps. */
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
               /** Dash's apps (the chats of the last week), opening one, and setting one aside (Not now). */
               apps: List<AppTile> = emptyList(), onOpen: (String) -> Unit = {}, onNotNow: (String) -> Unit = {},
               /** A long press on one: its menu. */
               onMenu: (String) -> Unit = {}) {
    // Dash says hi, then rests: he listens while you talk and reads along while you type.
    val rig = rememberDash()  // at rest, so the greeting below is a change: he hops
    var greeted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(1400); greeted = true }
    val feel = when { listening -> Feel.LISTENING; typing -> Feel.READING; !greeted -> Feel.HAPPY; else -> Feel.IDLE }
    LaunchedEffect(feel) { rig.feel(feel) }
    rig.level = level
    // Home is a page like any other: Dash's words, then what needs you, what he's at, and his page for your day (or the
    // latest apps); on a first visit, three things to say.
    val scroll = rememberScrollState()
    val drag = rememberListDrag(scroll)
    val needs = apps.filter { it.calls }
    val working = apps.filter { it.working && !it.calls }
    val rest = apps.filter { !it.calls && !it.working }
    val words = when {
        listening -> "I'm listening…"
        greeting != null -> greeting
        needs.size == 1 -> "One thing needs you."
        needs.size > 1 -> "${COUNTS.getOrElse(needs.size) { needs.size.toString() }} things need you."
        preparing -> "Getting your day ready…"
        apps.isEmpty() -> "Hi! I'm Dash. What should I do?"
        else -> "Hi! What should I do?"
    }
    Column(modifier.verticalScroll(scroll)) {
        DashLine(words, size = DashSize.BIG, rig = rig)
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 20.dp)) {
            if (needs.isNotEmpty()) { KitLabel("Needs you"); AppRows(needs, onOpen, onMenu, onNotNow) }
            if (working.isNotEmpty()) { KitLabel("Working on it"); AppRows(working, onOpen, onMenu) }
            if (home != null) {
                Box(Modifier.padding(top = 22.dp).fillMaxWidth().testTag("home_page")) {
                    CompositionLocalProvider(LocalListDrag provides drag) { home() }
                }
                if (made != null) Text("I made this page for you $made", Modifier.fillMaxWidth().padding(top = 12.dp),
                    style = MaterialTheme.typography.bodySmall, color = P.faint, textAlign = TextAlign.Center)
            } else if (rest.isNotEmpty()) { KitLabel("Recent"); AppRows(rest, onOpen, onMenu, onNotNow) }
            if (apps.isEmpty() && home == null) {
                KitLabel("Try saying")
                Column(Modifier.testTag("suggestions")) {
                    SUGGESTIONS.forEachIndexed { i, (icon, label, request) ->
                        KitRow(label, Modifier.testTag("suggestion"), lead = { IconTile(icon) }, line = i > 0, onClick = { onSuggestion(request) })
                    }
                }
            }
        }
    }
}

private val COUNTS = listOf("No", "One", "Two", "Three", "Four", "Five", "Six")

/**
 * Dash's apps, a row each: its first letter, its name, and his words about it (a spinner while he's at it). A long
 * press opens its menu; with [onNotNow], a row swiped left is set aside.
 */
@Composable
private fun AppRows(tiles: List<AppTile>, onOpen: (String) -> Unit, onMenu: (String) -> Unit, onNotNow: ((String) -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().testTag("apps")) {
        tiles.forEachIndexed { i, t ->
            key(t.conv) {
                NotNow({ onNotNow?.invoke(t.conv) }, enabled = onNotNow != null) {
                    KitRow(t.name, Modifier.testTag("app_tile"), sub = t.words.ifEmpty { null }, lead = { LetterTile(t.name) }, busy = t.working,
                        line = i > 0, onClick = { onOpen(t.conv) }, onLongClick = { onMenu(t.conv) })
                }
            }
        }
    }
}

/**
 * How the owner talks to Droid: the bar at the bottom of every screen. At rest, Tap to talk in the middle, typing on
 * the left and adding (a photo, the camera, a file) on the right; while Dash listens, the same pill, solid. Typing, a
 * draft or an attachment open the message box in its place, the same three parts: talk instead on the left (what's
 * typed goes first), the text, and send on the right (adding, while there's nothing to send).
 */
@Composable
fun Composer(
    text: String, onText: (String) -> Unit, files: List<Attachment>, onRemove: (Attachment) -> Unit,
    listening: Boolean, level: () -> Float,
    onAttach: () -> Unit, onSend: () -> Unit, onVoice: () -> Unit,
    onFinishListening: () -> Unit, onCancelListening: () -> Unit, modifier: Modifier = Modifier,
    /** Typing: the message box is open (with the keyboard), rather than the talk bar. */
    typing: Boolean = false, onTyping: (Boolean) -> Unit = {},
    /** No voice on this phone (yet): the bar types. */
    typeOnly: Boolean = false,
) {
    if (listening) { ListenBar(level, onFinishListening, onCancelListening, modifier, onType = { onTyping(true) }); return }
    val canSend = text.isNotBlank() || files.isNotEmpty()
    var field by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    if (!typing && text.isEmpty() && files.isEmpty()) {
        TalkBar(onVoice, onType = { onTyping(true) }, modifier, onAdd = onAttach, typeOnly = typeOnly)
        return
    }
    // Opened to type: the keyboard comes up at once; left empty, it closes back into the talk bar.
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(typing) { if (typing) runCatching { focus.requestFocus() } }
    Column(modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp)) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(P.bg).border(2.dp, P.call, RoundedCornerShape(32.dp))
                .animateContentSize(spring(stiffness = 700f)),
        ) {
            if (files.isNotEmpty()) LazyRow(
                Modifier.fillMaxWidth().padding(top = 10.dp), contentPadding = PaddingValues(horizontal = 14.dp),
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
            Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (typeOnly) Box(Modifier.width(20.dp))
                else IconBtn(R.drawable.ic_mic, "Talk instead", onVoice, Modifier.testTag("mic"), tint = CallText, size = 56.dp, iconSize = 22.dp)
                Box(Modifier.weight(1f).heightIn(min = 40.dp).padding(end = 4.dp, top = 9.dp, bottom = 9.dp), contentAlignment = Alignment.CenterStart) {
                    if (text.isEmpty()) Text("Message Droid", style = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp), color = P.muted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // Text put in from outside (what Dash heard) gets the cursor at its end.
                    val value = if (field.text == text) field else TextFieldValue(text, TextRange(text.length))
                    BasicTextField(
                        value, { field = it; onText(it.text) }, Modifier.fillMaxWidth().testTag("input").focusRequester(focus)
                            .onFocusChanged { f -> if (focused && !f.isFocused && text.isEmpty() && files.isEmpty()) onTyping(false); focused = f.isFocused },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = P.text, fontSize = 18.sp, lineHeight = 25.sp), cursorBrush = SolidColor(P.call), maxLines = 6,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    )
                }
                if (canSend) RoundAction(R.drawable.ic_send, "Send", "send", onSend)
                else IconBtn(R.drawable.ic_add, "Add a photo, the camera or a file", onAttach, Modifier.testTag("attach"), tint = CallText, size = 52.dp, iconSize = 24.dp)
            }
        }
    }
}

/** The message box's one solid button, in Dash's color: send, or done dictating. */
@Composable
private fun RoundAction(icon: Int, label: String, tag: String, onClick: () -> Unit) {
    Box(Modifier.padding(start = 2.dp, end = 2.dp).size(48.dp).clip(CircleShape).background(P.call).clickable(onClick = onClick).testTag(tag)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), null, Modifier.size(22.dp), tint = OnCall)
    }
}

/** Four bars that follow the voice's loudness. */
@Composable
internal fun LevelBarsImpl(level: () -> Float, color: Color, box: Dp) {
    val t by rememberInfiniteTransition(label = "bars").animateFloat(0f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "t")
    Box(Modifier.size(box).drawBehind {
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
        listOf(
            Triple(R.drawable.ic_camera, "Camera", onCamera),
            Triple(R.drawable.ic_photos, "Photos", pickers.photos),
            Triple(R.drawable.ic_file, "Files", pickers.files),
        ).forEachIndexed { i, (icon, label, go) ->
            SheetRow(icon, label, { close(); go() }, tag = "attach_" + label.lowercase(), line = i > 0)
        }
    }
}
