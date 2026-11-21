package com.adetkov.droid

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
import kotlinx.coroutines.withContext

/*
 * A chat on the owner's screen is its latest turn, as the app the agent made for it: what was asked at the top, Dash
 * with his words (the headline), and the answer filling the rest; its footer of buttons, the next step, pinned at the
 * bottom. While the agent works, the screen is Dash holding his live screen, with what he's doing in plain words. A tap
 * on the page starts the next turn on this same screen. The whole chat, turn by turn, is a tap away (Transcript).
 */

/** The top of the home screen: the chats on the left, Settings on the right. */
@Composable
fun HomeBar(onMenu: () -> Unit, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBtn(R.drawable.ic_menu, "Chats", onMenu, Modifier.testTag("menu"), size = 48.dp)
        Spacer(Modifier.weight(1f))
        IconBtn(R.drawable.ic_settings, "Settings", onSettings, Modifier.testTag("settings"), size = 48.dp)
    }
}

/**
 * The top of a chat: back home, what was asked ([title]), and the chat's menu. [onTalk]: a microphone here, while the
 * page's own next step takes the talk bar's place at the bottom.
 */
@Composable
fun TurnBar(title: String, onBack: () -> Unit, onMore: () -> Unit, onTalk: (() -> Unit)?, typeOnly: Boolean = false) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().height(60.dp).padding(start = 4.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBtn(R.drawable.ic_back, "Back", onBack, Modifier.testTag("back"), size = 48.dp, iconSize = 24.dp)
        Column(Modifier.weight(1f).padding(start = 4.dp, end = 8.dp)) {
            Text("You asked", style = MaterialTheme.typography.labelMedium, color = P.muted)
            Text(title, style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("asked"))
        }
        // (a keyboard where the phone has no voice set up)
        if (onTalk != null) Box(Modifier.size(48.dp).clip(CircleShape).background(P.call.copy(alpha = .14f)).clickable(onClick = onTalk)
            .testTag("talk_top").semantics { contentDescription = if (typeOnly) "Type to Dash" else "Talk to Dash" }, contentAlignment = Alignment.Center) {
            Icon(painterResource(if (typeOnly) R.drawable.ic_keyboard else R.drawable.ic_mic), null, Modifier.size(22.dp), tint = P.text)
        }
        IconBtn(R.drawable.ic_more, "Chat options", onMore, Modifier.testTag("overflow"), size = 48.dp)
    }
}

/**
 * The chat's latest turn as the owner's screen (see the top of this file). [feed]: the run's screen while it is up;
 * [held]: answered, its screen still open for a reply, until [closesAt]. [onHistory]: the whole chat, steps and all;
 * [onSpeak]: Dash says his words (a tap on him); [onFooter]: whether the answer's page has a footer, its next step,
 * which takes the talk bar's place.
 */
@Composable
fun TurnScreen(
    conv: Conv, running: Boolean, feed: ScreenFeed?, modifier: Modifier = Modifier,
    held: Boolean = false, closesAt: Long? = null, taps: Flow<Pair<Int, Int>>? = null, doing: Doing? = null,
    typing: Boolean = false, listening: Boolean = false, level: () -> Float = { 0f },
    onWatch: () -> Unit = {}, onTakeOver: () -> Unit = {}, onRetry: () -> Unit = {}, onSetup: () -> Unit = {},
    onHistory: () -> Unit = {}, onSpeak: (String) -> Unit = {}, onFooter: (Boolean) -> Unit = {},
) {
    val messages = conv.messages
    val start = remember(messages) { messages.indexOfLast { it.kind.opensTurn }.coerceAtLeast(0) }
    val items = remember(messages, running) { buildItems(messages, running) }
    val steps = remember(items, start) { items.lastOrNull { it is Item.Steps && it.turn == start } as? Item.Steps }
    val live = steps?.live ?: running
    val waiting = remember(messages, running) { waitingPage(messages, running) }
    val state = when {
        waiting != null -> "page"
        live -> "work"
        else -> "answer"
    }
    // The body changes as the turn does: working, a page waiting on you mid-task, the answer.
    AnimatedContent(state to start, modifier, label = "turn",
        transitionSpec = { fadeIn(tween(240, 60)) togetherWith fadeOut(tween(140)) }) { (s, _) ->
        when (s) {
            "work" -> Working(conv, start, steps, feed, taps, doing, onWatch)
            "page" -> {
                val m = waiting ?: return@AnimatedContent
                val info = remember(m.data) { PageInfo.of(m) }
                Column(Modifier.fillMaxSize()) {
                    Headline(conv, running, steps, feed, held, closesAt, typing, listening, level, onWatch, onHistory, onSpeak,
                        page = info.title)
                    WaitingPage(conv.id, m, info, onTakeOver, onFooter, Modifier.weight(1f))
                }
            }
            else -> Column(Modifier.fillMaxSize()) {
                Headline(conv, running, steps, feed, held, closesAt, typing, listening, level, onWatch, onHistory, onSpeak)
                AnswerBody(conv, start, onTakeOver, onRetry, onSetup, onFooter, onSpeak, Modifier.weight(1f))
            }
        }
    }
    // a body without a page has no footer
    LaunchedEffect(state, start) { if (state == "work") onFooter(false) }
}

/**
 * Dash and his words, the turn's headline: what the agent had him say (`droid dash`), or what the turn came to; "Your
 * turn" over them when it's the owner's move. A tap on him says them aloud. Under them, quietly: how long he worked
 * (opens the whole chat), and an app his screen holds open for a reply (opens it).
 */
@Composable
private fun Headline(
    conv: Conv, running: Boolean, steps: Item.Steps?, feed: ScreenFeed?, held: Boolean, closesAt: Long?,
    typing: Boolean, listening: Boolean, level: () -> Float, onWatch: () -> Unit, onHistory: () -> Unit, onSpeak: (String) -> Unit,
    /** A page waiting on the owner mid-task: its title, when Dash has no words of his own for it. */
    page: String? = null,
) {
    val messages = conv.messages
    val mood = remember(messages) { lastMood(messages) }
    val outcome = remember(messages, running) { lastOutcome(messages, running) }
    val calls = page != null || outcome == Outcome.ASKS || mood?.calls == true
    val words = mood?.words?.takeIf { it.isNotBlank() } ?: when {
        page != null -> page.ifBlank { "Your turn" }
        outcome == Outcome.FAILED -> "That didn't work"
        outcome == Outcome.STOPPED -> "Stopped"
        outcome == Outcome.ASKS -> "Your turn"
        else -> "Here you go"
    }
    val rig = rememberDash(conv.id)
    rig.level = level
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
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(held, closesAt) { while (held && closesAt != null) { now = System.currentTimeMillis(); delay(1000) } }
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 20.dp, top = 2.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Dash(rig, Modifier.size(width = 66.dp, height = 74.dp).testTag("dash"), label = "Dash. Tap to hear him",
            onPoke = { rig.poke(); onSpeak(words) })
        Column(Modifier.weight(1f).padding(start = 6.dp)) {
            if (calls) Text("Your turn", Modifier.padding(start = 14.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge,
                color = P.call, fontWeight = FontWeight.Bold)
            SpeechBubble(words)
            val worked = steps?.takeIf { it.start > 0 && it.end > it.start }?.let { "Worked for ${duration(it.end - it.start)}" }
            val app = rememberAppInfo(feed?.app?.takeIf { held })?.label
            val left = closesAt?.let { ((it - now + 59_999) / 60_000).coerceIn(0, HOLD_FOR / 60_000) }
            Row(Modifier.padding(start = 14.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (worked != null) Text("$worked ›", Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onHistory).testTag("worked"),
                    style = MaterialTheme.typography.bodySmall, color = P.faint)
                if (held && app != null) Text((if (worked != null) "  ·  " else "") + "$app open" + (if (left != null && left > 0) " · $left min" else ""),
                    Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onWatch).testTag("held"),
                    style = MaterialTheme.typography.bodySmall, color = P.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Dash's words in a speech bubble whose tail points left, at him. */
@Composable
internal fun SpeechBubble(text: String) {
    // solid, so the tail and the bubble are one shape where they overlap
    val bg = P.call.copy(alpha = if (P.dark) .22f else .12f).compositeOver(P.bg)
    Box(Modifier.padding(start = 6.dp)) {
        Box(Modifier.align(Alignment.CenterStart).offset(x = (-6).dp).size(16.dp).rotate(45f).clip(RoundedCornerShape(3.dp)).background(bg))
        AnimatedContent(text, label = "words", transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) }) { t ->
            Text(t, Modifier.clip(RoundedCornerShape(22.dp)).background(bg).padding(horizontal = 16.dp, vertical = 11.dp).testTag("dash_says"),
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
                color = P.text, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The turn's answer, filling the screen under Dash: a page the agent made, as an app that scrolls itself with its footer
 * pinned; or its words, with any pages it showed in the chat on the way, and how a turn that didn't finish ended.
 */
@Composable
private fun AnswerBody(conv: Conv, start: Int, onTakeOver: () -> Unit, onRetry: () -> Unit, onSetup: () -> Unit,
                       onFooter: (Boolean) -> Unit, onSpeak: (String) -> Unit, modifier: Modifier) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as DroidApp
    val messages = conv.messages
    val answerAt = remember(messages, start) { (start until messages.size).lastOrNull { messages[it].kind == Kind.AGENT } }
    val answer = answerAt?.let { messages[it] }
    val html = remember(answer?.text) { answer != null && hasHtml(answer.text) }
    val dark = P.dark
    if (answer != null && answerAt != null && html) {
        val actions = remember(conv.id, answerAt) {
            ChatPageActions(ctx, conv.id, null, isLive = { pageLiveNow(app, conv.id, null, answerAt) }, onScreen = onTakeOver)
        }
        // the same origin as in the chat's history, so it keeps what it stored there
        key(answerAt, answer.text, dark) {
            FillPage("screen:${conv.id}:$answerAt:${answer.text.hashCode()}:$dark", "answer:${conv.id}:$answerAt",
                { cx -> answerDocument(cx, answer.text, dark, PageMode.SCREEN) }, namedPaths(answer.text), actions,
                modifier.fillMaxWidth().testTag("answer_page"), live = pageLive(app, conv, answerAt), onFooter = onFooter, screenOf = conv.id)
        }
        return
    }
    LaunchedEffect(Unit) { onFooter(false) }
    // Words, and what else the turn showed: as the chat shows them, one column that scrolls.
    val scroll = rememberScrollState()
    val drag = rememberListDrag(scroll)
    val turn = remember(messages, start) { (start until messages.size).map { it to messages[it] } }
    val status = turn.lastOrNull { it.second.kind == Kind.STATUS }
    val pages = remember(LocalPageCache.current) { PageCache() }
    androidx.compose.runtime.DisposableEffect(pages) { onDispose { pages.clear() } }
    CompositionLocalProvider(LocalListDrag provides drag, LocalPageCache provides pages) {
        Column(modifier.fillMaxWidth().verticalScroll(scroll).padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            turn.forEach { (i, m) ->
                when {
                    m.kind == Kind.PAGE && PageInfo.of(m).where == "chat" -> {
                        val info = PageInfo.of(m)
                        val pa = remember(conv.id, info.id) { ChatPageActions(ctx, conv.id, info.id, onScreen = onTakeOver) }
                        InlinePage("p:${conv.id}:${info.id}:${m.text.hashCode()}", "page:${conv.id}:${info.id}", dark,
                            { cx -> pageDocument(cx, m.text, dark, PageMode.INLINE) }, namedPaths(m.text), pa,
                            Modifier.padding(bottom = 14.dp).testTag("page"), live = pageLive(app, conv, i))
                    }
                    m.kind == Kind.AGENT -> WordsAnswer(m.text, onSpeak)
                }
            }
            if (status != null && (answerAt == null || status.first > answerAt))
                StatusRow(status.second.text, conv.engine, retry = onRetry, onSetup = onSetup)
        }
    }
}

/** An answer in words: big, plain, and copy, read aloud and share under it. */
@Composable
private fun WordsAnswer(text: String, onSpeak: (String) -> Unit) {
    val ctx = LocalContext.current
    val copy = rememberCopy()
    val haptic = rememberHaptic()
    val shown = remember(text) { answerText(text) }
    // a few sentences read big, as Dash would say them; a long answer at its usual size
    val type = MaterialTheme.typography
    val big = shown.length < 320
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        MaterialTheme(typography = if (big) type.copy(bodyLarge = type.bodyLarge.copy(fontSize = 20.sp, lineHeight = 29.sp)) else type) {
            Markdown(shown, Modifier.fillMaxWidth().testTag("answer"))
        }
        Row(Modifier.offset(x = (-10).dp).padding(top = 4.dp)) {
            IconBtn(R.drawable.ic_copy, "Copy", { copy(stripMarkdown(text)); haptic() }, Modifier.testTag("copy"), tint = P.muted, size = 40.dp, iconSize = 18.dp)
            IconBtn(R.drawable.ic_speaker, "Read aloud", { onSpeak(text) }, Modifier.testTag("speak"),
                tint = P.muted, size = 40.dp, iconSize = 18.dp)
            IconBtn(R.drawable.ic_share, "Share", { shareText(ctx, stripMarkdown(text)) }, Modifier.testTag("share"), tint = P.muted, size = 40.dp, iconSize = 18.dp)
        }
    }
}

/** A page the agent waits on mid-task (`droid show --wait`), filling the screen: the owner's turn, then it goes on. */
@Composable
private fun WaitingPage(conv: String, m: Msg, info: PageInfo, onTakeOver: () -> Unit, onFooter: (Boolean) -> Unit, modifier: Modifier) {
    val ctx = LocalContext.current
    val dark = P.dark
    val actions = remember(conv, info.id) { ChatPageActions(ctx, conv, info.id, onScreen = onTakeOver) }
    key(info.id, m.text, dark) {
        FillPage("screen:$conv:${info.id}:${m.text.hashCode()}:$dark", "page:$conv:${info.id}",
            { cx -> pageDocument(cx, m.text, dark, PageMode.SCREEN) }, namedPaths(m.text), actions,
            modifier.fillMaxWidth().testTag("waiting_page"), onFooter = onFooter, screenOf = conv)
    }
}

/**
 * Dash at work: holding his live screen, big, while an app is on it (a tap watches, or takes over); the creature, thinking,
 * while there's none. Under him, what he's doing now in plain words. He poses for what the agent does, and his eyes go
 * where it taps. The screen's frames are also kept as the turn's still (at most every 2 s), for its history.
 */
@Composable
private fun Working(conv: Conv, start: Int, steps: Item.Steps?, feed: ScreenFeed?, taps: Flow<Pair<Int, Int>>?, doing: Doing?, onWatch: () -> Unit) {
    val ctx = LocalContext.current
    val used = feed?.app != null
    val stillFile = remember(conv.id, steps?.stillKey) { steps?.let { Stills.file(ctx, conv.id, it.stillKey) } }
    var saved by remember { mutableIntStateOf(0) }
    LaunchedEffect(feed, stillFile) {
        if (feed != null && stillFile != null) snapshotFlow { feed.frame.takeIf { feed.app != null } }.filterNotNull().conflate().collect {
            withContext(Dispatchers.IO) { Stills.save(stillFile, it) }
            saved++
            delay(2000)
        }
    }
    val liveFrame = feed?.frame?.takeIf { used }
    val liveImage = remember(liveFrame) { liveFrame?.asImageBitmap() }
    val shown = remember { arrayOfNulls<ImageBitmap>(1) }
    val frame = (liveImage ?: shown[0].takeIf { used }).also { if (it != null) shown[0] = it }
    val rig = rememberDash("work:${conv.id}:$start") { r -> if (used) r.frame.snap(1f) }
    LaunchedEffect(used) { rig.present.t = 1f; rig.frame.t = if (used) 1f else 0f }
    LaunchedEffect(doing) {
        val d = doing ?: Doing(Work.THINK)
        if (d.n > 0) rig.react(d)
        rig.feel(d.work.feel())
        delay(if (d.said) 6000 else 9000)
        if (d.work != Work.WAIT) rig.feel(Feel.THINKING)
    }
    val display = rememberDisplaySize()
    LaunchedEffect(taps, used) { if (used && taps != null) taps.collect { (x, y) -> rig.tap(x / display.width, y / display.height) } }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val messages = conv.messages
    val caption = remember(messages) { latestStatus(messages).takeIf { it != "Working…" } } ?: "Getting started…"
    val ratio = frame?.let { it.height.toFloat() / it.width } ?: screenRatio()
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(.35f))
        DashSlot(rig, frame, DpSize(132.dp, 146.dp), 196.dp, ratio, Modifier.testTag(if (used) "window" else "dash"),
            label = if (used) "Droid's screen, live. Tap to watch" else "Dash, working", origin = used, onClick = if (used) onWatch else null)
        AnimatedContent(caption, Modifier.padding(top = 22.dp), label = "doing",
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) }) { t ->
            Text(t, Modifier.fillMaxWidth().testTag("doing"), style = MaterialTheme.typography.titleLarge.copy(fontSize = 21.sp, lineHeight = 28.sp,
                fontWeight = FontWeight.Bold), textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        val since = steps?.start?.takeIf { it > 0 } ?: messages.getOrNull(start)?.ts ?: now
        Text(if (used) "Tap my screen to watch me work" else "Working · ${duration(now - since)}", Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp), color = P.muted, textAlign = TextAlign.Center)
        Spacer(Modifier.weight(.65f))
    }
}

/**
 * Listening, full screen: Dash listens, and what he hears comes up in big words. It goes when the owner stops talking
 * ([onDone] sends it now); [onType] is for typing instead.
 */
@Composable
fun ListeningScreen(words: String, level: () -> Float, onCancel: () -> Unit, onDone: () -> Unit, onType: () -> Unit) {
    val rig = rememberDash("listening")
    rig.level = level
    LaunchedEffect(Unit) { rig.feel(Feel.LISTENING) }
    val onCall = if (P.dark) Color(0xFF0D0D0D) else Color.White
    Column(Modifier.fillMaxSize().background(P.bg).statusBarsPadding().navigationBarsPadding().testTag("listening"),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(R.drawable.ic_close, "Cancel", onCancel, Modifier.testTag("cancel_listen"), size = 48.dp, iconSize = 24.dp)
            Spacer(Modifier.weight(1f))
            IconBtn(R.drawable.ic_keyboard, "Type instead", onType, Modifier.testTag("type_instead"), size = 48.dp, iconSize = 24.dp, tint = P.muted)
        }
        Spacer(Modifier.weight(.3f))
        Dash(rig, Modifier.size(width = 170.dp, height = 188.dp).testTag("dash"), label = null)
        Text("I'm listening…", Modifier.padding(top = 18.dp), style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
            color = P.call, fontWeight = FontWeight.SemiBold)
        Text(words, Modifier.padding(start = 28.dp, end = 28.dp, top = 14.dp).testTag("heard"),
            style = MaterialTheme.typography.headlineSmall.copy(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center, maxLines = 5, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.weight(.7f))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f).height(60.dp).clip(RoundedCornerShape(30.dp)).background(P.surface).clickable(onClick = onCancel),
                contentAlignment = Alignment.Center) { Text("Cancel", style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp)) }
            Row(Modifier.weight(1f).height(60.dp).clip(RoundedCornerShape(30.dp)).background(P.call).clickable(onClick = onDone).testTag("done_listen"),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_check), null, Modifier.size(22.dp), tint = onCall)
                Text("Done", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold), color = onCall)
            }
        }
        Text("Or just stop talking. I'll start by myself.", Modifier.padding(top = 12.dp, bottom = 12.dp),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp), color = P.muted)
    }
}

