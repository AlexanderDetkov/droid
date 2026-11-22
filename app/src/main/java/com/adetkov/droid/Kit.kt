package com.adetkov.droid

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Droid's kit: the few parts every screen is made of, so the app and the agent's pages read as one thing.
 *
 * Every screen is four rows: where you are ([Where]), Dash and his words ([DashLine]), the page, and the bar ([TalkBar]).
 * Dash's line is the only headline; the grey line under it is always his steps. There is one next step ([NextStep]),
 * just above the bar. Periwinkle is Dash: solid for the next step and the ways to answer him, lavender for his words and
 * what's picked, his ring around what he sees. Switches and checks are ink; everything else is grey on the background.
 */

/** Lavender: Dash's words (his bubble), the talk bar, and what's picked. */
val Lavender: Color @Composable @ReadOnlyComposable get() = P.call.copy(alpha = if (P.dark) .22f else .12f).compositeOver(P.bg)

/** Text in Dash's color on lavender. */
val CallText: Color @Composable @ReadOnlyComposable get() = if (P.dark) Color(0xFFC7CBFF) else Color(0xFF3F44B8)

/** Text on solid periwinkle. */
val OnCall: Color @Composable @ReadOnlyComposable get() = if (P.dark) Color(0xFF0D0D0D) else Color.White

/** Dash's ring: around what he sees (his screen, the camera). */
val Ring: Color get() = DashColors.Accent

/** How big Dash's line is: big on home and first run, small beside a screen or in a list of turns. */
enum class DashSize(val box: DpSize, val font: Int, val line: Int) {
    BIG(DpSize(78.dp, 86.dp), 22, 28), NORMAL(DpSize(66.dp, 74.dp), 20, 26), SMALL(DpSize(44.dp, 48.dp), 17, 22)
}

/**
 * The top row: where you are. [left] goes back (or opens everything, on home), [title] names the screen quietly, and
 * [right] holds one action: Stop while Dash works, else the screen's menu.
 */
@Composable
fun Where(left: Int?, leftLabel: String, onLeft: () -> Unit, title: String? = null, leftTag: String = "back",
          right: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().height(60.dp).padding(start = 6.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (left != null) IconBtn(left, leftLabel, onLeft, Modifier.testTag(leftTag), size = 48.dp, iconSize = 24.dp)
        else Spacer(Modifier.width(14.dp))
        Text(title.orEmpty(), Modifier.weight(1f).padding(start = 4.dp, end = 8.dp).testTag("title"),
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        right()
    }
}

/** Stop, in the top row while Dash works. */
@Composable
fun StopButton(onStop: () -> Unit) {
    Row(Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(P.surface).clickable(onClick = onStop)
        .padding(start = 14.dp, end = 16.dp).testTag("stop"), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(11.dp).clip(RoundedCornerShape(3.dp)).background(P.text))
        Text("Stop", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold))
    }
}

/**
 * Dash and his words: the headline of every screen. [sub] is the grey line under them, always his steps ([onSub]
 * opens them). [dash] draws him in his box (a creature, or holding his screen); by default, [rig] standing there.
 */
@Composable
fun DashLine(
    words: String?, modifier: Modifier = Modifier, size: DashSize = DashSize.NORMAL, rig: DashRig? = null,
    sub: String? = null, onSub: (() -> Unit)? = null, onPoke: (() -> Unit)? = null,
    /** After [sub], never cut short: how long he's been at it. */
    subTail: String? = null,
    dash: (@Composable (DpSize) -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth().padding(start = if (size == DashSize.SMALL) 16.dp else 12.dp, end = 20.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        when {
            dash != null -> dash(size.box)
            rig != null -> Dash(rig, Modifier.size(size.box).testTag("dash"), label = if (onPoke != null) "Dash. Tap to hear him" else null,
                onPoke = onPoke?.let { p -> { rig.poke(); p() } })
        }
        Column(Modifier.weight(1f).padding(start = 6.dp)) {
            if (words != null) SpeechBubble(words, size)
            if (sub != null) Row(Modifier.padding(start = if (words != null) 14.dp else 4.dp, top = if (words != null) 6.dp else 0.dp).clip(RoundedCornerShape(10.dp))
                .let { if (onSub != null) it.clickable(onClick = onSub) else it }.padding(vertical = 2.dp, horizontal = 2.dp).testTag("dash_sub"),
                verticalAlignment = Alignment.CenterVertically) {
                AnimatedContent(sub, Modifier.weight(1f, fill = false), label = "sub",
                    transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) using SizeTransform(clip = false) }) { t ->
                    Text(t, style = MaterialTheme.typography.bodyMedium, color = P.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (subTail != null) Text(" · $subTail", style = MaterialTheme.typography.bodyMedium, color = P.muted, maxLines = 1, softWrap = false)
                if (onSub != null) Icon(painterResource(R.drawable.ic_chevron), null, Modifier.padding(start = 2.dp).size(16.dp), tint = P.faint)
            }
        }
    }
}

/**
 * Dash's words in a lavender bubble whose tail points left, at him. New words fade in as the bubble eases to their size,
 * so it never jumps.
 */
@Composable
fun SpeechBubble(text: String, size: DashSize = DashSize.NORMAL) {
    // solid, so the tail and the bubble are one shape where they overlap
    val bg = Lavender
    Box(Modifier.padding(start = 6.dp)) {
        Box(Modifier.align(Alignment.CenterStart).offset(x = (-6).dp).size(16.dp).rotate(45f).clip(RoundedCornerShape(3.dp)).background(bg))
        Box(Modifier.clip(RoundedCornerShape(20.dp)).background(bg)
            .padding(horizontal = if (size == DashSize.SMALL) 13.dp else 16.dp, vertical = if (size == DashSize.SMALL) 9.dp else 12.dp)) {
            AnimatedContent(text, label = "words", transitionSpec = {
                fadeIn(tween(200, 100)) togetherWith fadeOut(tween(100)) using SizeTransform(clip = false) { _, _ -> spring(stiffness = 420f) }
            }) { t ->
                Text(t, Modifier.testTag("dash_says"),
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = size.font.sp, lineHeight = size.line.sp, fontWeight = FontWeight.Bold),
                    color = P.text, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** What the owner said, typed or ([tap]) tapped: a quiet grey bubble on the right; [big] while it's being said. A long press: [onLongClick]. */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun YouBubble(text: String, modifier: Modifier = Modifier, tap: Boolean = false, big: Boolean = false, onLongClick: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 72.dp, end = 20.dp, top = 6.dp), horizontalArrangement = Arrangement.End) {
        Row(Modifier.clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 6.dp, bottomStart = 20.dp)).background(P.surface)
            .let { if (onLongClick != null) it.combinedClickable(onLongClick = onLongClick, onClick = {}) else it }
            .padding(horizontal = if (big) 16.dp else 14.dp, vertical = if (big) 12.dp else 9.dp).testTag("you_said"),
            verticalAlignment = Alignment.CenterVertically) {
            if (tap) Icon(painterResource(R.drawable.ic_tap), "You tapped", Modifier.padding(end = 7.dp).size(16.dp), tint = P.muted)
            Text(text, style = MaterialTheme.typography.bodyLarge.copy(fontSize = if (big) 20.sp else 16.sp, lineHeight = if (big) 27.sp else 21.sp,
                fontWeight = if (big) FontWeight.SemiBold else FontWeight.Medium), maxLines = if (big) 5 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The next step: one big button, just above the bar. [quiet]: outlined, for a way back rather than forward; [busy]: a
 * spinner while it goes.
 */
@Composable
fun NextStep(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, quiet: Boolean = false, busy: Boolean = false,
             enabled: Boolean = true, icon: Int? = null, tag: String = "next") {
    val shape = RoundedCornerShape(30.dp)
    val solid = !quiet && enabled
    Row(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp).height(60.dp).clip(shape)
        .then(if (quiet) Modifier.border(1.5.dp, P.hairline, shape) else Modifier.background(if (enabled) P.call else P.raised))
        .clickable(enabled = enabled && !busy, onClick = onClick).testTag(tag),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        val color = if (solid) OnCall else if (enabled) P.text else P.faint
        if (busy) Spinner(Modifier.padding(end = 12.dp), size = 20.dp, color = color, stroke = 2.5.dp)
        else if (icon != null) Icon(painterResource(icon), null, Modifier.padding(end = 8.dp).size(20.dp), tint = color)
        Text(label, style = MaterialTheme.typography.titleMedium.copy(fontSize = 19.sp, fontWeight = if (quiet) FontWeight.SemiBold else FontWeight.Bold), color = color,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The bar at rest, one lavender pill in three parts: Tap to talk in the middle, typing on the left, and on the right
 * [onAdd]: a photo, the camera or a file (none: nothing there). The left always switches between talking and typing,
 * the right adds or cancels, and the middle says what a tap does. [typeOnly]: no voice on this phone (yet).
 */
@Composable
fun TalkBar(onTalk: () -> Unit, onType: () -> Unit, modifier: Modifier = Modifier, onAdd: (() -> Unit)? = null, typeOnly: Boolean = false) {
    Row(modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp).height(64.dp).clip(RoundedCornerShape(32.dp)).background(Lavender),
        verticalAlignment = Alignment.CenterVertically) {
        if (typeOnly) Spacer(Modifier.width(64.dp)) else BarSide(R.drawable.ic_keyboard, "Type instead", "type", CallText, onType)
        Row(Modifier.weight(1f).height(64.dp).clickable(onClick = if (typeOnly) onType else onTalk).testTag("voice"),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(P.call), contentAlignment = Alignment.Center) {
                Icon(painterResource(if (typeOnly) R.drawable.ic_keyboard else R.drawable.ic_mic), null, Modifier.size(21.dp), tint = OnCall)
            }
            Text(if (typeOnly) "Tap to type" else "Tap to talk", Modifier.padding(start = 12.dp),
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold), color = CallText)
        }
        if (onAdd != null) BarSide(R.drawable.ic_add, "Add a photo, the camera or a file", "attach", CallText, onAdd)
        else Spacer(Modifier.width(64.dp))
    }
}

/** The bar while Dash listens: the same pill, solid. Typing on the left keeps what he heard; Tap when done; cancel on the right. */
@Composable
fun ListenBar(level: () -> Float, onDone: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier, onType: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp).height(64.dp).clip(RoundedCornerShape(32.dp)).background(P.call)
        .testTag("listening"), verticalAlignment = Alignment.CenterVertically) {
        if (onType != null) BarSide(R.drawable.ic_keyboard, "Type instead", "type_instead", OnCall, onType) else Spacer(Modifier.width(64.dp))
        Row(Modifier.weight(1f).height(64.dp).clickable(onClick = onDone).testTag("done_listen"),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            LevelBars(level, OnCall)
            Text("Tap when done", Modifier.padding(start = 12.dp), style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold),
                color = OnCall)
        }
        BarSide(R.drawable.ic_close, "Cancel", "cancel_listen", OnCall, onCancel, iconSize = 22.dp)
    }
}

/** One of the bar's two ends: a 64 dp square with its icon. */
@Composable
fun BarSide(icon: Int, label: String, tag: String, tint: Color, onClick: () -> Unit, iconSize: Dp = 24.dp) {
    Box(Modifier.size(64.dp).clip(CircleShape).clickable(onClick = onClick).testTag(tag).semantics { contentDescription = label },
        contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), null, Modifier.size(iconSize), tint = tint)
    }
}

/** A section's name in a list ("Needs you", "Today"). */
@Composable
fun KitLabel(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier.padding(start = 4.dp, top = 22.dp, bottom = 2.dp), style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
        color = P.muted)

/** A row's lead: an icon on a grey tile. */
@Composable
fun IconTile(icon: Int, tint: Color = P.text) {
    Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(P.surface), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), null, Modifier.size(21.dp), tint = tint)
    }
}

/** A row's lead for one of Dash's apps: its first letter on a grey tile (a person gets a round [Avatar]). */
@Composable
fun LetterTile(name: String) {
    Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(P.surface), contentAlignment = Alignment.Center) {
        Text((name.firstOrNull { it.isLetterOrDigit() } ?: '•').uppercaseChar().toString(),
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold))
    }
}

/**
 * A row: a [lead] (a tile or avatar), its [title] and a quiet [sub] under it ([busy]: a spinner before it, for what
 * Dash is working on), and what's at its end ([trailing]: a value, a switch, a chevron). [line]: a hairline above it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun KitRow(
    title: String, modifier: Modifier = Modifier, sub: String? = null, lead: (@Composable () -> Unit)? = null, busy: Boolean = false,
    titleColor: Color = P.text, line: Boolean = false, onClick: (() -> Unit)? = null, onLongClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Column(modifier.fillMaxWidth()) {
        if (line) HorizontalDivider(Modifier.padding(start = if (lead != null) 60.dp else 4.dp), color = P.hairline)
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .let { if (onClick != null || onLongClick != null) it.combinedClickable(onClick = onClick ?: {}, onLongClick = onLongClick) else it }
            .padding(horizontal = 4.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            if (lead != null) { lead(); Spacer(Modifier.width(14.dp)) }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
                    color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (sub != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    if (busy) Spinner(Modifier.padding(end = 6.dp), size = 12.dp, color = P.muted, stroke = 1.8.dp)
                    Text(sub, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 20.sp), color = P.muted,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            trailing()
        }
    }
}

/**
 * A row the owner can set aside: swiped left, "Not now" shows under it, and letting go past it calls [onNotNow] (also a
 * screen reader's action). [enabled] false: just the row.
 */
@Composable
fun NotNow(onNotNow: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable () -> Unit) {
    if (!enabled) { Box(modifier) { content() }; return }
    // (only a deliberate swipe: past 40% of the row)
    val state = rememberSwipeToDismissBoxState(positionalThreshold = { it * .4f })
    val done by rememberUpdatedState(onNotNow)
    LaunchedEffect(state.currentValue) { if (state.currentValue == SwipeToDismissBoxValue.EndToStart) done() }
    SwipeToDismissBox(state, enableDismissFromStartToEnd = false,
        modifier = modifier.semantics { customActions = listOf(CustomAccessibilityAction("Not now") { done(); true }) },
        backgroundContent = {
            Box(Modifier.fillMaxSize().padding(vertical = 8.dp).clip(RoundedCornerShape(16.dp)).background(P.surface).padding(end = 20.dp),
                contentAlignment = Alignment.CenterEnd) {
                Text("Not now", style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold))
            }
        }) { Box(Modifier.background(P.bg)) { content() } }
}

/** A row's chevron: it opens something. */
@Composable
fun Chevron() = Icon(painterResource(R.drawable.ic_chevron), null, Modifier.padding(start = 8.dp).size(20.dp), tint = P.faint)

/** On or off, in ink. */
@Composable
fun KitSwitch(on: Boolean, set: (Boolean) -> Unit) = Switch(on, set, Modifier.padding(start = 8.dp), colors = SwitchDefaults.colors(
    checkedThumbColor = P.onInk, checkedTrackColor = P.ink, uncheckedThumbColor = P.bg,
    uncheckedTrackColor = P.raised, uncheckedBorderColor = Color.Transparent, checkedBorderColor = Color.Transparent,
))

/** A choice to tap, in a row of them: lavender, ringed, while it's picked. */
@Composable
fun Pick(on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, label: String? = null, content: @Composable RowScope.() -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Row(modifier.height(44.dp).clip(shape).background(if (on) Lavender else Color.Transparent)
        .border(1.5.dp, if (on) Ring else P.hairline, shape).clickable(onClickLabel = label, onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, content = content)
}

/** A pick's label. */
@Composable
fun PickText(text: String, on: Boolean) = Text(text, style = MaterialTheme.typography.labelLarge.copy(fontSize = 16.sp,
    fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold), color = if (on) CallText else P.text)

/** Four bars that follow the voice's loudness. */
@Composable
fun LevelBars(level: () -> Float, color: Color = P.text, size: Dp = 22.dp) = LevelBarsImpl(level, color, size)
