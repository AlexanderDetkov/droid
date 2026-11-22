package com.adetkov.droid

import androidx.compose.foundation.Image
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withContext

/*
 * A chat on the owner's screen is its latest turn, in Droid's four rows (see Kit): where you are (the app's name, or
 * what you asked while Dash works on it), Dash with his words (the headline) and his steps under them, the page, and the
 * bar. The page is the app the agent made for this turn; while he works with nothing new drawn, his screen in his ring
 * if he's on it, else the app he works on, stepped back (and the button you tapped on it, going); an answer in words
 * when there's no app. Your own words are the history's (Transcript), a tap away.
 */

/**
 * The chat as the owner's screen: see above. The chat's app ([currentApp]) is live across turns, so a tap on it goes to
 * the agent; a page the agent waits on mid-task takes its place. [feed]: the run's screen while it is up; [held]:
 * answered, its screen still open for a reply, until [closesAt]. [onSpeak]: Dash says his words (a tap on him).
 */
@Composable
fun TurnScreen(
    conv: Conv, running: Boolean, feed: ScreenFeed?, modifier: Modifier = Modifier,
    held: Boolean = false, closesAt: Long? = null, taps: Flow<Pair<Int, Int>>? = null, doing: Doing? = null,
    typing: Boolean = false, listening: Boolean = false, level: () -> Float = { 0f },
    onWatch: () -> Unit = {}, onTakeOver: () -> Unit = {}, onRetry: () -> Unit = {}, onSetup: () -> Unit = {},
    onSpeak: (String) -> Unit = {},
) {
    val messages = conv.messages
    val start = remember(messages) { messages.indexOfLast { it.kind.opensTurn }.coerceAtLeast(0) }
    val items = remember(messages, running) { buildItems(messages, running) }
    val steps = remember(items, start) { items.lastOrNull { it is Item.Steps && it.turn == start } as? Item.Steps }
    val live = steps?.live ?: running
    val waiting = remember(messages, running) { waitingPage(messages, running) }
    val appAt = remember(messages) { messages.currentApp() }
    val made = appAt.takeIf { it > start }            // the app this turn made
    // the app from before, which this turn works on: if it was still on screen when this turn began (no answer in words
    // or failure came after it)
    val before = appAt.takeIf { at ->
        at in 0..start && (at + 1 until start).none { i -> messages[i].let { (it.kind == Kind.AGENT && it.text.isNotBlank() && !hasHtml(it.text)) || (it.kind == Kind.STATUS && it.text != "Stopped") } }
    }
    val answerAt = remember(messages, start) { (start until messages.size).lastOrNull { messages[it].kind == Kind.AGENT } }
    val words = answerAt?.let { messages[it].text }?.takeIf { !hasHtml(it) && it.isNotBlank() }
    val status = remember(messages, start) {
        (start until messages.size).lastOrNull { messages[it].kind == Kind.STATUS }?.takeIf { answerAt == null || it > answerAt }
    }
    // a turn that failed: what it means, in Dash's words, and what fixes it (a stop is the owner's own doing)
    val failed = remember(messages, start, status) { status?.let { messages[it].text }?.takeIf { !live && it != "Stopped" }?.let { plainStatus(it, conv.engine) ?: Plain("That didn't work. " + it.removePrefix("Error: ")) } }
    val (failWords, failRest) = remember(failed) {
        failed?.text?.let { t -> t.indexOf(". ").takeIf { it > 0 }?.let { t.substring(0, it + 1) to t.substring(it + 2) } ?: (t to null) } ?: (null to null)
    }
    // the button tapped on the app, which this turn answers: it shows going while the app steps back
    val tapped = remember(messages, start) { messages.getOrNull(start)?.takeIf { it.kind == Kind.USER && it.data != null }?.let(::requestText) }
    val mood = remember(messages) { lastMood(messages) }
    // a short answer is Dash's to say (unless the agent gave him words of his own): nothing more under him
    val short = words?.let(::shortAnswer)
    val saysAnswer = short != null && mood?.words.isNullOrBlank()
    var stepsOpen by remember { mutableStateOf(false) }
    if (live) SaveStills(conv.id, steps, feed)
    val used = live && feed?.app != null
    // the screen's latest picture, kept through a frame that hasn't come yet
    val liveImage = remember(feed?.frame, used) { feed?.frame?.takeIf { used }?.asImageBitmap() }
    val shown = remember { arrayOfNulls<ImageBitmap>(1) }
    val frame = (liveImage ?: shown[0].takeIf { used }).also { if (it != null) shown[0] = it }
    // the agent at work on his screen, with nothing new drawn: his screen is the page
    val big = waiting == null && made == null && live && frame != null

    Column(modifier.fillMaxSize()) {
        Headline(conv, running, live && waiting == null, steps, taps.takeIf { big }, doing, typing, listening, level, onSpeak,
            onSteps = { stepsOpen = true }, mood = mood, page = waiting?.let { w -> remember(w.data) { PageInfo.of(w) } }?.title,
            said = short.takeIf { saysAnswer || made != null }, failWords = failWords)
        Box(Modifier.weight(1f).fillMaxWidth().padding(top = 14.dp)) {
            when {
                waiting != null -> {
                    val info = remember(waiting.data) { PageInfo.of(waiting) }
                    WaitingPage(conv.id, waiting, info, onTakeOver, Modifier.fillMaxSize())
                }
                // the same app keeps its view (what's typed and picked in it) from turn to turn
                made != null -> key(conv.id, made) { AppPage(conv, made, onTakeOver, Modifier.fillMaxSize()) }
                // (with the button tapped on the app going under it)
                big -> Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) { ScreenBody(frame, onWatch) }
                    if (tapped != null) NextStep(tapped, {}, busy = true, tag = "tapped")
                }
                failed != null -> failRest?.let { FailedBody(it) }
                live && before == null -> {}
                words != null && !live -> if (!saysAnswer) WordsBody(words, Modifier.fillMaxSize())
                before != null -> key(conv.id, before) {
                    Box(Modifier.fillMaxSize()) {
                        AppPage(conv, before, onTakeOver, Modifier.fillMaxSize())
                        SteppedBack(live, if (live) tapped else null)
                    }
                }
            }
        }
        if (failed != null) {
            if (failed.setup) NextStep("Open Setup", onSetup, tag = "status_setup")
            else NextStep("Try again", onRetry, tag = "retry")
        }
    }
    if (stepsOpen && steps != null) StepsSheet(steps, live, if (feed?.app != null) onWatch else null) { stepsOpen = false }
}

/**
 * Over the app while Dash works on what was asked of it ([on]): it steps back (fades, and takes no taps), and the
 * button that was [tapped] on it shows going, where its footer was.
 */
@Composable
private fun BoxScope.SteppedBack(on: Boolean, tapped: String?) {
    val fade by animateFloatAsState(if (on) 1f else 0f, tween(if (on) 220 else 320), label = "back")
    if (fade == 0f) return
    val bg = P.bg
    Column(Modifier.matchParentSize().graphicsLayer { alpha = fade }.pointerInput(Unit) {
        awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
    }) {
        Box(Modifier.weight(1f).fillMaxWidth().background(bg.copy(alpha = .72f)))
        if (tapped != null) Box(Modifier.fillMaxWidth().background(bg).padding(bottom = 6.dp)) {
            NextStep(tapped, {}, busy = true, tag = "tapped")
        }
    }
}

/** What the owner asked, as the history shows it: their words, or what they sent without any. */
internal fun requestText(m: Msg): String = m.text.trim().ifEmpty {
    when {
        m.files.any { it.source == Source.CAMERA } -> "A photo"
        m.files.any { it.source == Source.SCREEN } -> "My screen"
        m.files.isNotEmpty() -> m.files.first().name
        else -> "…"
    }
}

/** What the top row calls a chat: what the owner asked while Dash works on it (unless it was a tap on its app), else its app's name. */
fun chatTitle(c: Conv, running: Boolean): String {
    val asked = c.messages.lastOrNull { it.kind.opensTurn }?.takeIf { it.kind == Kind.USER && it.data == null }
    return if (running && asked != null) requestText(asked) else appName(c)
}

/** A turn's steps, as the grey line under Dash says them when it's over: "9 steps · 41s". */
internal fun stepsLine(s: Item.Steps): String? {
    val n = s.lines.count { !it.note }
    if (n == 0) return null
    return (if (n == 1) "1 step" else "$n steps") + " · " + duration(s.end - s.start)
}

/**
 * Dash and his words, the headline. While the agent works his words hold still ("On it!", or what it had him say), and
 * the grey line under them is what it's doing and for how long. After: what the agent had him say (`droid dash`), else
 * its answer when it's short ([said]), else what the turn came to; his steps under them. A tap on him says his words.
 */
@Composable
private fun Headline(
    conv: Conv, running: Boolean, working: Boolean, steps: Item.Steps?, taps: Flow<Pair<Int, Int>>?, doing: Doing?,
    typing: Boolean, listening: Boolean, level: () -> Float, onSpeak: (String) -> Unit, onSteps: () -> Unit, mood: Mood?,
    /** A page waiting on the owner mid-task: its title, when Dash has no words of his own for it. */
    page: String? = null,
    /** The turn's answer, short enough for him to say. */
    said: String? = null,
    /** How the turn failed, in a sentence. */
    failWords: String? = null,
) {
    val messages = conv.messages
    val outcome = remember(messages, running) { lastOutcome(messages, running) }
    val calls = page != null || outcome == Outcome.ASKS || mood?.calls == true
    val own = mood?.words?.takeIf { it.isNotBlank() }
    val words = when {
        listening -> "I'm listening…"
        working -> own ?: "On it!"
        else -> failWords ?: own ?: said ?: when {
            page != null -> page.ifBlank { "Your turn" }
            outcome == Outcome.FAILED -> "That didn't work"
            outcome == Outcome.STOPPED -> "Stopped"
            outcome == Outcome.ASKS -> "Your turn"
            else -> "Here you go"
        }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    if (working) LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val step = remember(messages) { latestStep(messages) }
    val sub = if (working) step ?: "Getting started" else steps?.let(::stepsLine)
    val time = if (working) duration(now - (steps?.start?.takeIf { it > 0 } ?: now)) else null
    val rig = rememberDash("line:${conv.id}")
    rig.level = level
    LaunchedEffect(Unit) { rig.present.t = 1f; rig.frame.t = 0f }
    if (working && !listening) DashAtWork(rig, doing, taps)
    else {
        val feel = when {
            listening -> Feel.LISTENING
            typing -> Feel.READING
            mood != null -> mood.feel
            calls -> Feel.NEEDS_YOU
            outcome == Outcome.FAILED -> Feel.CONFUSED
            outcome == Outcome.DONE -> Feel.HAPPY
            else -> Feel.IDLE
        }
        LaunchedEffect(feel) { rig.feel(feel) }
    }
    DashLine(words, sub = sub, subTail = time, onSub = if (steps != null && (working || steps.lines.isNotEmpty())) onSteps else null, rig = rig,
        onPoke = { onSpeak(words) })
}

/** Dash's pose while the agent works: for what it does, a reaction to each thing, his eyes to where it taps. */
@Composable
private fun DashAtWork(rig: DashRig, doing: Doing?, taps: Flow<Pair<Int, Int>>?) {
    LaunchedEffect(doing) {
        val d = doing ?: Doing(Work.THINK)
        if (d.n > 0) rig.react(d)
        rig.feel(d.work.feel())
        delay(if (d.said) 6000 else 9000)
        if (d.work != Work.WAIT) rig.feel(Feel.THINKING)
    }
    val display = rememberDisplaySize()
    LaunchedEffect(taps) { taps?.collect { (x, y) -> rig.tap(x / display.width, y / display.height) } }
}

/** Keeps the run's screen as the turn's still (at most every 2 s) while an app is on it, for the chat's history. */
@Composable
private fun SaveStills(conv: String, steps: Item.Steps?, feed: ScreenFeed?) {
    val ctx = LocalContext.current
    val stillFile = remember(conv, steps?.stillKey) { steps?.let { Stills.file(ctx, conv, it.stillKey) } }
    LaunchedEffect(feed, stillFile) {
        if (feed != null && stillFile != null) snapshotFlow { feed.frame.takeIf { feed.app != null } }.filterNotNull().conflate().collect {
            withContext(Dispatchers.IO) { Stills.save(stillFile, it) }
            delay(2000)
        }
    }
}

/** The agent's screen as the page while it works and has drawn nothing yet: as big as it fits, in Dash's ring. */
@Composable
private fun ScreenBody(frame: ImageBitmap?, onWatch: () -> Unit) {
    if (frame == null) return
    BoxWithConstraints(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, bottom = 8.dp), contentAlignment = Alignment.Center) {
        val ratio = frame.height.toFloat() / frame.width
        val w = minOf(maxWidth, maxHeight / ratio)
        RingedScreen(frame, w, Modifier.testTag("window"), onClick = onWatch)
    }
}

/** Droid's screen, [width] wide, in Dash's ring: what he sees. */
@Composable
fun RingedScreen(frame: ImageBitmap, width: Dp, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val density = LocalDensity.current
    val ratio = frame.height.toFloat() / frame.width
    val shape = RoundedCornerShape(with(density) { screenCorner(width.toPx(), density.density).toDp() })
    Box(modifier.size(width, width * ratio).clip(shape).background(P.surface)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .semantics { contentDescription = "Droid's screen, live" }) {
        Image(frame, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        Box(Modifier.matchParentSize().border(4.dp, Ring, shape))
    }
}

/** His steps, in a sheet: what he did and said on the way, and his screen while it's up. */
@Composable
private fun StepsSheet(steps: Item.Steps, live: Boolean, onWatch: (() -> Unit)?, onDismiss: () -> Unit) {
    ActionSheet(onDismiss, title = if (live) "What I'm doing" else stepsLine(steps) ?: "What I did") { close ->
        Box(Modifier.padding(horizontal = 24.dp).heightIn(max = 420.dp).verticalScroll(rememberScrollState())) { StepsList(steps.lines, live) }
        if (onWatch != null) Box(Modifier.padding(horizontal = 20.dp)) {
            KitRow("See my screen", lead = { IconTile(R.drawable.ic_screen) }, line = true, onClick = { close(); onWatch() }, trailing = { Chevron() })
        }
    }
}

/** His steps, one a line: done ones checked, his thoughts quieter, and (while he works) the one he's on, lit. */
@Composable
private fun StepsList(lines: List<Line>, live: Boolean) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        lines.forEachIndexed { i, l ->
            val now = live && i == lines.lastIndex
            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 2.dp).size(22.dp), contentAlignment = Alignment.Center) {
                    when {
                        now -> Box(Modifier.size(22.dp).clip(CircleShape).background(Lavender), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(P.call))
                        }
                        l.note -> Text("···", style = MaterialTheme.typography.bodyMedium, color = P.muted)
                        else -> Icon(painterResource(R.drawable.ic_check), null, Modifier.size(20.dp), tint = P.text)
                    }
                }
                Text(l.text, Modifier.padding(start = 14.dp),
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = if (l.note) 16.sp else 17.sp, lineHeight = 23.sp,
                        fontWeight = if (now) FontWeight.Bold else FontWeight.Normal),
                    color = if (l.note && !now) P.muted else P.text, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** The chat's app filling the screen under Dash: a page the agent showed, or an answer with HTML; it scrolls itself, its footer pinned. */
@Composable
private fun AppPage(conv: Conv, at: Int, onTakeOver: () -> Unit, modifier: Modifier) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as DroidApp
    val m = conv.messages[at]
    val dark = P.dark
    // the same origins as in the chat's history, so what it stored there it has here
    if (m.kind == Kind.PAGE) {
        val info = remember(m.data) { PageInfo.of(m) }
        val actions = remember(conv.id, info.id) { ChatPageActions(ctx, conv.id, info.id, onScreen = onTakeOver) }
        key(m.text, dark) {
            FillPage("screen:${conv.id}:${info.id}:${m.text.hashCode()}:$dark", "page:${conv.id}:${info.id}",
                { cx -> pageDocument(cx, m.text, dark, PageMode.SCREEN) }, namedPaths(m.text), actions,
                modifier.testTag("app_page"), live = pageLive(app, conv, at), screenOf = conv.id)
        }
    } else {
        val actions = remember(conv.id, at) { ChatPageActions(ctx, conv.id, null, isLive = { pageLiveNow(app, conv.id, null, at) }, onScreen = onTakeOver) }
        key(m.text, dark) {
            FillPage("screen:${conv.id}:$at:${m.text.hashCode()}:$dark", "answer:${conv.id}:$at",
                { cx -> answerDocument(cx, m.text, dark, PageMode.SCREEN) }, namedPaths(m.text), actions,
                modifier.testTag("answer_page"), live = pageLive(app, conv, at), screenOf = conv.id)
        }
    }
}

/** What a failure means beyond Dash's first sentence, quietly, under his words. */
@Composable
private fun FailedBody(text: String) {
    Text(text, Modifier.padding(start = 98.dp, end = 24.dp).testTag("said"),
        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 25.sp), color = P.muted)
}

/**
 * A chat without an app: its answer in words, under Dash; a few sentences big, as he'd say them. (Copy and read aloud
 * are in the chat's menu.)
 */
@Composable
private fun WordsBody(text: String, modifier: Modifier) {
    val shown = remember(text) { answerText(text) }
    val type = MaterialTheme.typography
    val big = shown.length < 320
    Column(modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
        MaterialTheme(typography = if (big) type.copy(bodyLarge = type.bodyLarge.copy(fontSize = 20.sp, lineHeight = 29.sp)) else type) {
            SelectionContainer { Markdown(shown, Modifier.fillMaxWidth().testTag("answer")) }
        }
    }
}

/** A page the agent waits on mid-task (`droid show --wait`), filling the screen: the owner's turn, then it goes on. */
@Composable
private fun WaitingPage(conv: String, m: Msg, info: PageInfo, onTakeOver: () -> Unit, modifier: Modifier) {
    val ctx = LocalContext.current
    val dark = P.dark
    val actions = remember(conv, info.id) { ChatPageActions(ctx, conv, info.id, onScreen = onTakeOver) }
    key(info.id, m.text, dark) {
        FillPage("screen:$conv:${info.id}:${m.text.hashCode()}:$dark", "page:$conv:${info.id}",
            { cx -> pageDocument(cx, m.text, dark, PageMode.SCREEN) }, namedPaths(m.text), actions,
            modifier.fillMaxWidth().testTag("waiting_page"), screenOf = conv)
    }
}
