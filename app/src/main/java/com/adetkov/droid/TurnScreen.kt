package com.adetkov.droid

import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
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
import androidx.compose.runtime.derivedStateOf
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import android.graphics.Bitmap
import androidx.compose.runtime.key
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
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
 * A chat on the owner's screen opens at now, its latest turn, in Droid's four rows (see Kit): where you are (the app's
 * name, or what you asked while Dash works on it), Dash with his words (the headline) and his steps under them, the
 * page, and the bar. The page is the app the agent made for this turn; while he works with nothing new drawn, his screen
 * in his ring if he's on it, else the app he works on, still yours to use (and the button you tapped on it, going); an answer
 * in words when there's no app. Above now, a scroll up, are the turns before it and your own words (see Chat).
 */

/**
 * Now, the chat's latest turn: see above. [fill] makes it exactly the chat's space, which it fills; an answer in words
 * may run longer, read on down. The chat's app ([currentApp]) is live across turns, so a tap on it goes to the agent; a
 * page the agent waits on mid-task takes its place. [feed]: the run's screen while it is up; [held]: answered, its
 * screen still open for a reply, until [closesAt]. [onSpeak]: Dash says his words (a tap on him).
 */
@Composable
fun TurnScreen(
    conv: Conv, running: Boolean, feed: ScreenFeed?, fill: Modifier, modifier: Modifier = Modifier,
    held: Boolean = false, closesAt: Long? = null, taps: Flow<Pair<Int, Int>>? = null, doing: Doing? = null,
    typing: Boolean = false, listening: Boolean = false, level: () -> Float = { 0f },
    /** The owner puts their own hands on Dash's screen. */
    onDoItMyself: () -> Unit = {}, onTakeOver: () -> Unit = {}, onRetry: () -> Unit = {}, onSetup: () -> Unit = {},
    onSpeak: (String) -> Unit = {},
    /** Scrolling up past the top goes to the chat's History (null: there's nothing before this turn). */
    onHistory: (() -> Unit)? = null,
    /** What it shows now, as it changes: the note on the owner's next message tells the agent (see [OnScreen]). */
    onShown: (OnScreen) -> Unit = {},
    /** Covered by Dash's screen in the owner's hands: Dash holds still. */
    hidden: Boolean = false,
) {
    val messages = conv.messages
    val start = remember(messages) { messages.indexOfLast { it.kind.opensTurn }.coerceAtLeast(0) }
    val items = remember(messages, running) { buildItems(messages, running, start) }
    val steps = remember(items, start) { items.lastOrNull { it is Item.Steps && it.turn == start } as? Item.Steps }
    val live = steps?.live ?: running
    val waiting = remember(messages, running) { waitingPage(messages, running) }
    val appAt = remember(messages) { messages.currentApp() }
    val made = appAt.takeIf { it > start }            // the app this turn made
    // the app from before, which this turn works on (see appBefore)
    val before = remember(messages, start) { messages.appBefore(start) }
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
    // the button tapped on the app, which this turn answers: it shows going while he works on it
    val tapped = remember(messages, start) { messages.getOrNull(start)?.takeIf { it.kind == Kind.USER && it.data != null }?.let(::requestText) }
    val mood = remember(messages) { lastMood(messages) }
    // a short answer is Dash's to say (unless the agent gave him words of his own): nothing more under him
    val short = words?.let(::shortAnswer)
    val saysAnswer = short != null && mood?.words.isNullOrBlank()
    var stepsOpen by remember { mutableStateOf(false) }
    val used = live && feed?.app != null
    // whether a picture has come: the frames themselves are read only where they are drawn (see ScreenBody), so a frame
    // doesn't recompose all of this
    val hasFrame by remember(feed) { derivedStateOf { feed?.frame != null } }
    // the agent at work on his screen, with nothing new drawn: his screen is the page
    val big = waiting == null && made == null && live && used && hasFrame
    // answered, with nothing drawn and his screen still open on an app (it waits on the owner's hands, a sign-in say):
    // his screen is the page, and Do it myself the next step
    val holds = held && feed?.app != null && hasFrame
    val calls = remember(messages, running) { lastOutcome(messages, running) == Outcome.ASKS || lastMood(messages)?.calls == true }
    val hands = !live && holds && waiting == null && made == null && failed == null && calls
    // stopped by the owner: it can be tried again
    val stopped = !live && status?.let { messages[it].text == "Stopped" } == true
    // Do it myself, while he works: offered for a few seconds when the owner taps his screen (so it never nags)
    var offered by remember { mutableStateOf(false) }
    LaunchedEffect(offered) { if (offered) { delay(6000); offered = false } }
    val density = LocalDensity.current
    val pull = remember(onHistory != null) { onHistory?.let { go -> Pull(down = true, threshold = with(density) { 80.dp.toPx() }) { go() } } }
    val pulled by animateFloatAsState(pull?.pulled ?: 0f, spring(stiffness = Spring.StiffnessHigh), label = "pull")
    // drags on anything but the app reach the pull (the app's are its own)
    val pulls = if (pull != null) Modifier.scrollable(rememberScrollableState { 0f }, Orientation.Vertical) else Modifier
    // the app on screen: the one this turn made, else the one before, unless a page waiting, his screen, a failure or
    // an answer in words takes its place
    val app = if (waiting != null) null else made ?: before?.takeIf { !big && failed == null && (live || words == null) }
    // an answer in words, with no app: as long as it is, read on down the chat (all else fills the screen, and scrolls itself)
    val inWords = waiting == null && made == null && !big && !hands && failed == null && !live && words != null && !saysAnswer
    // what all that comes to on the owner's screen, which the agent is told with their next message (the page below draws it)
    val onScreen = when {
        waiting != null -> OnScreen.WAITING
        app != null -> OnScreen.APP
        big || hands -> OnScreen.SCREEN
        failed != null -> OnScreen.FAILED
        !live && words != null -> OnScreen.WORDS
        else -> OnScreen.NOTHING
    }
    SideEffect { onShown(onScreen) }
    if (live) SaveStills(conv.id, steps, feed)
    // The chat's app keeps one view from version to version (a new one reloads it in place), so it changes rather than
    // starting over; and opened again later, it refreshes itself if its folder can (see AppRefresh).
    val appViews = remember(conv.id) { PageCache() }
    DisposableEffect(appViews) { onDispose { appViews.clear() } }
    var refreshed by remember(conv.id) { mutableIntStateOf(0) }
    val appMsg = (made ?: before)?.let { messages.getOrNull(it) }
    val ctx = LocalContext.current
    LaunchedEffect(conv.id, appMsg?.data, running) {
        val m = appMsg?.takeIf { it.kind == Kind.PAGE && !running } ?: return@LaunchedEffect
        if (withContext(Dispatchers.IO) { AppRefresh.run(ctx.applicationContext as DroidApp, conv.id, m) }) refreshed++
    }

    Box(modifier.fillMaxWidth().then(if (inWords) Modifier.fillMaxSize() else fill)) {
    if (pull != null && pulled > 1f) HistoryHint(pull.ready, pull.progress, Modifier.align(Alignment.TopCenter))
    Column(Modifier.fillMaxWidth().then(if (pull != null) Modifier.nestedScroll(pull.connection) else Modifier)
        .graphicsLayer { translationY = pulled }
        .then(if (inWords) Modifier.fillMaxSize().verticalScroll(rememberScrollState()) else Modifier.fillMaxSize())) {
        Box(pulls) {
            Headline(conv, running, live && waiting == null, !hidden, steps, taps.takeIf { big }, doing, typing, listening, level, onSpeak,
                onSteps = { stepsOpen = true }, mood = mood, page = waiting?.let { w -> remember(w.data) { PageInfo.of(w) } }?.title,
                said = short.takeIf { saysAnswer || made != null }, failWords = failWords)
        }
        if (inWords && words != null) WordsBody(words, Modifier.padding(top = 14.dp))
        else Box(Modifier.weight(1f).fillMaxWidth().padding(top = 14.dp).then(if (app == null && waiting == null) pulls else Modifier)) {
            when {
                waiting != null -> {
                    val info = remember(waiting.data) { PageInfo.of(waiting) }
                    WaitingPage(conv.id, waiting, info, onTakeOver, Modifier.fillMaxSize(), at = remember(messages, waiting) { messages.indexOf(waiting) })
                }
                // The same app keeps its view (what's typed and picked in it) from turn to turn: the one this turn
                // made, or, while Dash works on what was asked of it, the one before (with the button
                // tapped on it going).
                // (one view for the chat's app, whatever its version: a new version reloads it in place)
                app != null -> key(conv.id) {
                    Box(Modifier.fillMaxSize()) {
                        // its picture for the history is kept while it's the turn's own (not the one before, being worked on)
                        AppPage(conv, app, onTakeOver, Modifier.fillMaxSize(), appViews, refreshed, keepsStill = app == made || !live, working = live)
                        Going(app != made && live, if (live) tapped else null)
                    }
                }
                big -> Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        // a tap around his screen puts the offer away; one on it shows or hides it
                        if (offered) Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures { offered = false } })
                        ScreenBody({ feed?.frame }) { offered = !offered }
                        // over his screen's foot, so nothing moves when it comes and goes
                        androidx.compose.animation.AnimatedVisibility(offered && tapped == null, Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
                            enter = fadeIn(tween(160)), exit = fadeOut(tween(200))) {
                            KitChip("Do it myself", { offered = false; onDoItMyself() }, Modifier.shadow(8.dp, RoundedCornerShape(20.dp)), tag = "do_it_myself")
                        }
                    }
                    if (tapped != null) NextStep(tapped, {}, busy = true, tag = "tapped")
                }
                hands -> Column(Modifier.fillMaxSize()) {
                    if (words != null && !saysAnswer) Text(answerText(words), Modifier.padding(start = 98.dp, end = 24.dp, bottom = 10.dp).testTag("said"),
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 24.sp), color = P.muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Box(Modifier.weight(1f)) { ScreenBody({ feed?.frame }) }
                    NextStep("Do it myself", onDoItMyself, tag = "do_it_myself")
                }
                failed != null -> failRest?.let { FailedBody(it) }
                // working with no app on his screen: the page is his steps as he goes
                live && steps != null && steps.lines.isNotEmpty() -> Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(start = 28.dp, end = 24.dp, top = 6.dp).testTag("live_steps")) { StepsList(steps.lines, live = true) }
                else -> {}  // nothing drawn yet, or the answer is Dash's to say
            }
        }
        if (failed != null) {
            if (failed.setup) NextStep("Open Setup", onSetup, tag = "status_setup")
            else NextStep("Try again", onRetry, tag = "retry")
        } else if (stopped) NextStep("Try again", onRetry, tag = "retry")
    }
    }
    if (stepsOpen && steps != null) StepsSheet(steps, live) { stepsOpen = false }
}

/**
 * What the owner's screen shows of a chat's now, under Dash's words, as [TurnScreen] draws it. Their next message from
 * it tells the agent ([note]), so what the agent thinks is on their screen is what is: its app (an answer in words
 * takes its place until it shows the app again), its words, its own screen, or that the turn didn't work.
 */
enum class OnScreen(val note: String?) {
    APP("your app was on my screen"),
    /** A page the agent waits on mid-task: the owner's message answers it (see [ownerSend]). */
    WAITING(null),
    SCREEN("your own screen was on mine"),
    WORDS("your answer in words was on my screen"),
    FAILED("my screen said your last turn didn't work"),
    /** Nothing of the agent's yet (it's starting, or at work with nothing to show). */
    NOTHING(null),
}

/** Over the top of the chat while it's pulled down: History is there; past far enough, letting go opens it. */
@Composable
private fun HistoryHint(ready: Boolean, progress: Float, modifier: Modifier) {
    Row(modifier.padding(top = 6.dp).graphicsLayer { alpha = progress }.clip(RoundedCornerShape(20.dp)).background(if (ready) Lavender else P.surface)
        .padding(horizontal = 16.dp, vertical = 9.dp).testTag("history_hint"), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.ic_history), null, Modifier.padding(end = 8.dp).size(18.dp), tint = if (ready) CallText else P.muted)
        Text(if (ready) "Let go for History" else "History", style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold),
            color = if (ready) CallText else P.muted)
    }
}

/**
 * Over the app while Dash works on what was asked of it ([on]): the button that was [tapped] on it shows going, where
 * its footer was. The app itself stays as it is, to read, scroll and use meanwhile (his line says he's at work; what
 * it sends him comes after, as a message typed meanwhile would).
 */
@Composable
private fun BoxScope.Going(on: Boolean, tapped: String?) {
    val fade by animateFloatAsState(if (on && tapped != null) 1f else 0f, tween(if (on) 220 else 320), label = "going")
    val shown = remember { mutableStateOf(tapped) }.apply { if (tapped != null) value = tapped }.value  // kept while it fades
    if (fade == 0f || shown == null) return
    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().graphicsLayer { alpha = fade }.background(P.bg).padding(bottom = 6.dp)) {
        NextStep(shown, {}, busy = true, tag = "tapped")
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
    conv: Conv, running: Boolean, working: Boolean, active: Boolean, steps: Item.Steps?, taps: Flow<Pair<Int, Int>>?, doing: Doing?,
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
    // his first words this turn, until the agent gives him its own: not the same every time
    val first = remember(messages) {
        val starts = messages.indices.filter { messages[it].kind.opensTurn }
        firstWords(starts.lastOrNull()?.let { messages[it].ts } ?: 0L, starts.dropLast(1).lastOrNull()?.let { messages[it].ts })
    }
    val words = when {
        listening -> "I'm listening…"
        working -> own ?: first
        else -> failWords ?: own ?: said ?: when {
            page != null -> page.ifBlank { "Your turn" }
            outcome == Outcome.FAILED -> "That didn't work"
            outcome == Outcome.STOPPED -> "Stopped"
            outcome == Outcome.ASKS -> "Your turn"
            else -> "Here you go"
        }
    }
    val step = remember(messages) { latestStep(messages) }
    val sub = if (working) step ?: "Getting started" else steps?.let(::stepsLine)
    val since = steps?.start?.takeIf { it > 0 }
    val rig = rememberDash("line:${conv.id}", active = active)
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
    DashLine(words, sub = sub, subTail = if (working) ({ Elapsed(since) }) else null, onSub = if (steps != null && (working || steps.lines.isNotEmpty())) onSteps else null, rig = rig,
        busy = working, onPoke = { onSpeak(words) })
}

/** How long he's been at it, after the grey line: it ticks here, so only this recomposes each second. */
@Composable
private fun Elapsed(since: Long?) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    Text(" · " + duration(since?.let { now - it } ?: 0L), style = MaterialTheme.typography.bodyMedium, color = P.muted, maxLines = 1, softWrap = false)
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

/**
 * Keeps the run's screen as the turn's still, for the chat's history, while an app is on it: only the last picture
 * counts, so it is written when the screen leaves or the turn ends, and every 10 s meanwhile.
 */
@Composable
internal fun SaveStills(conv: String, steps: Item.Steps?, feed: ScreenFeed?) {
    val ctx = LocalContext.current
    val stillFile = remember(conv, steps?.stillKey) { steps?.let { Stills.file(ctx, conv, it.stillKey) } }
    LaunchedEffect(feed, stillFile) {
        if (feed == null || stillFile == null) return@LaunchedEffect
        var last: Bitmap? = null
        var saved: Bitmap? = null
        suspend fun save() {
            val b = last?.takeIf { it !== saved } ?: return
            saved = b
            withContext(Dispatchers.IO + NonCancellable) { Stills.save(stillFile, b) }
        }
        try {
            coroutineScope {
                launch { while (true) { delay(10_000); save() } }
                snapshotFlow { feed.frame.takeIf { feed.app != null } }.collect { if (it != null) last = it else save() }
            }
        } finally { save() }
    }
}

/** The agent's screen as the page while it works and has drawn nothing yet: as big as it fits, in Dash's ring. */
@Composable
private fun ScreenBody(latest: () -> Bitmap?, onTap: (() -> Unit)? = null) {
    val bitmap = latest()  // read here, so a new frame redraws only this
    val frame = remember(bitmap) { bitmap?.asImageBitmap() } ?: return
    BoxWithConstraints(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, bottom = 8.dp), contentAlignment = Alignment.Center) {
        val ratio = frame.height.toFloat() / frame.width
        val w = minOf(maxWidth, maxHeight / ratio)
        RingedScreen(frame, w, Modifier.testTag("window"), onClick = onTap)
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
        .semantics { contentDescription = "Dash's screen, live" }) {
        Image(frame, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        Box(Modifier.matchParentSize().border(4.dp, Ring, shape))
    }
}

/** His steps, in a sheet: what he did and said on the way. */
@Composable
private fun StepsSheet(steps: Item.Steps, live: Boolean, onDismiss: () -> Unit) {
    ActionSheet(onDismiss, title = if (live) "Steps so far" else stepsLine(steps) ?: "Steps") {
        Box(Modifier.padding(horizontal = 24.dp).heightIn(max = 420.dp).verticalScroll(rememberScrollState())) { StepsList(steps.lines, live) }
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

/**
 * The chat's app filling the screen under Dash: a page the agent showed, or an answer with HTML; it scrolls itself, its
 * footer pinned. One view in [views] for the chat (and theme): a new version, or a refresh ([refreshed]), reloads it in
 * place, keeping the owner's place in it.
 */
@Composable
private fun AppPage(conv: Conv, at: Int, onTakeOver: () -> Unit, modifier: Modifier, views: PageCache, refreshed: Int, keepsStill: Boolean = true,
                    working: Boolean = false) {
    val ctx = LocalContext.current
    val still = remember(conv.id, at) { Stills.file(ctx, conv.id, "p$at") }.takeIf { keepsStill }
    val app = ctx.applicationContext as DroidApp
    val m = conv.messages[at]
    val dark = P.dark
    val version = "${m.kind}:$at:${m.text.hashCode()}:$refreshed:$dark"  // (a theme switch reloads it, in place)
    // the chat's one origin, as in its history, so what the app stored in any version it has here
    if (m.kind == Kind.PAGE) {
        val info = remember(m.data) { PageInfo.of(m) }
        val actions = remember(conv.id, info.id) { ChatPageActions(ctx, conv.id, info.id, onScreen = onTakeOver) }
        FillPage("app:${conv.id}:$dark", chatOrigin(conv.id, info),
            { cx -> pageDocument(cx, m.text, dark, PageMode.SCREEN) }, namedPaths(m.text), actions,
            modifier.testTag("app_page"), live = pageLive(app, conv, at), screenOf = conv.id,
            base = info.base.ifEmpty { null }, version = version, shared = views, still = still, working = working)
    } else {
        val actions = remember(conv.id, at) { ChatPageActions(ctx, conv.id, null, isLive = { pageLiveNow(app, conv.id, null, at) }, onScreen = onTakeOver) }
        FillPage("app:${conv.id}:$dark", chatOrigin(conv.id),
            { cx -> answerDocument(cx, m.text, dark, PageMode.SCREEN) }, namedPaths(m.text), actions,
            modifier.testTag("answer_page"), live = pageLive(app, conv, at), screenOf = conv.id, version = version, shared = views, still = still, working = working)
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
 * are in the chat's menu.) A long one runs on down the chat.
 */
@Composable
private fun WordsBody(text: String, modifier: Modifier) {
    val shown = remember(text) { answerText(text) }
    val type = MaterialTheme.typography
    val big = shown.length < 320
    Column(modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
        MaterialTheme(typography = if (big) type.copy(bodyLarge = type.bodyLarge.copy(fontSize = 20.sp, lineHeight = 29.sp)) else type) {
            SelectionContainer { Markdown(shown, Modifier.fillMaxWidth().testTag("answer")) }
        }
    }
}

/** A page the agent waits on mid-task (`droid show --wait`), filling the screen: the owner's turn, then it goes on. */
@Composable
private fun WaitingPage(conv: String, m: Msg, info: PageInfo, onTakeOver: () -> Unit, modifier: Modifier, at: Int) {
    val ctx = LocalContext.current
    val dark = P.dark
    val actions = remember(conv, info.id) { ChatPageActions(ctx, conv, info.id, onScreen = onTakeOver) }
    key(info.id, m.text, dark) {
        FillPage("screen:$conv:${info.id}:${m.text.hashCode()}:$dark", "page:$conv:${info.id}",
            { cx -> pageDocument(cx, m.text, dark, PageMode.SCREEN) }, namedPaths(m.text), actions,
            modifier.fillMaxWidth().testTag("waiting_page"), screenOf = conv, still = remember(conv, at) { Stills.file(ctx, conv, "p$at") })
    }
}
