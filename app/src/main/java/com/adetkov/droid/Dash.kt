package com.adetkov.droid

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.channels.Channel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/*
 * Dash, Droid's character: a soft periwinkle creature with a hooked antenna, who shows what Droid is doing. The site's
 * dash.js is the same rig and drawing, so he looks and moves the same everywhere.
 *
 * How he stays smooth and clean:
 *  - Every number that moves is on an exact damped spring (solved, not stepped): a change of target never jumps, and no
 *    frame rate makes him wobble or stutter.
 *  - Gestures (a hop, a nod, a start, a call) are short curves in time that start and end at rest, added on top of the
 *    springs. Nothing ever kicks him, and nothing is random from one frame to the next.
 *  - Each shape is valid by construction: his body is one rounded outline whose corners and head are clamped to the room
 *    they have, and each eye is two arcs over the same width, which can't cross. No path boolean ops, no path clips, no
 *    outline strokes: no seams, slivers or jagged joins at any size or in any pose.
 *  - All of him (antenna, glow, rings, a hop) stays inside his box: a creature in a box of unit u (u = min(width,
 *    height / 1.38): his body .74u wide, his base .08u above the box's bottom), a frame in its rect plus [headRoomDp]
 *    above it. Nothing he draws can be cut off by, or spill over, what's around him.
 *
 * He has two bodies that are one body: a creature, and the frame of Droid's screen with his eyes in a rounded head on its
 * top edge. [DashRig.frame] moves between them. The rule the app follows: when Dash is a frame, Droid's screen is open.
 */

/** Dash's colors. The periwinkle is the app's one accent: it means "Droid is here". */
object DashColors {
    val BodyTop = Color(0xFFBAC0FF)
    val Body = Color(0xFFA3ABFF)
    val Pale = Color(0xFFECEEFF)
    val Stalk = Color(0xFF8A91EC)
    val Tip = Color(0xFFD2C4FF)
    val Ink = Color(0xFF16151B)
    val Glow = Color(0xFFFFF0C8)
    val Blush = Color(0xFFFF8DB6)
    val Mark = Color(0xFF5A5FD6)
    /** The frame where it has to read as a line (Watch's ring, the camera's frame). */
    val Accent = Color(0xFFA9B0FF)
}

/** How his eyes move by themselves in a feeling. */
enum class Scan { NONE, WORK, READ, SWEEP, SCROLL, CARET, DRIFT }

/** A gesture a feeling starts with. */
enum class Enter { NONE, HOP, HOP2, START }

/**
 * What he feels, as targets for the rig. [open]: lids (0 closed); [smile]: + his eyes curve up (a crescent, ^ closed),
 * - they droop (u closed); [size], [asym] (+ right eye bigger); [lookX]/[lookY] -1..1; [glances]: looks about on his
 * own; [scan]: how his eyes move by themselves; [lean], [tilt] (radians), [stretch] (1 rest); [sway]: a slow rock;
 * [depth]: how deep he breathes; [antenna] its angle (+ right), [curl] how it hooks, [length]; [glow] his tip's light;
 * [pulse]: lit by your voice; [tick]: his tip pulsing this many times a second; [blush]; [call]: your turn;
 * [enter]: a gesture on arriving.
 */
class Feel(
    /** Its name, as the agent writes it (any case): `droid dash oops`. */
    val name: String,
    val open: Float = 1f, val smile: Float = 0f, val size: Float = 1f, val asym: Float = 0f,
    val lookX: Float = 0f, val lookY: Float = 0f, val glances: Boolean = true,
    val scan: Scan = Scan.NONE, val scanX: Float = 0f, val scanY: Float = 0f,
    val lean: Float = 0f, val tilt: Float = 0f, val stretch: Float = 1f, val sway: Float = 0f, val depth: Float = 1f,
    val antenna: Float = .15f, val curl: Float = .5f, val length: Float = 1f, val glow: Float = 0f,
    val pulse: Boolean = false, val tick: Float = 0f, val blush: Float = 0f, val call: Boolean = false,
    val enter: Enter = Enter.NONE,
) {
    override fun toString() = name

    companion object {
        val IDLE = Feel("IDLE")
        val HAPPY = Feel("HAPPY", open = .95f, smile = .8f, blush = .55f, lookY = -.12f, stretch = 1.03f, antenna = .05f, curl = .35f, glances = false, enter = Enter.HOP)
        val EXCITED = Feel("EXCITED", size = 1.12f, smile = .25f, lookY = -.15f, stretch = 1.05f, antenna = 0f, curl = .12f, length = 1.1f, glow = .35f, glances = false, enter = Enter.HOP)
        val LOVE = Feel("LOVE", open = .9f, smile = .85f, blush = 1f, glow = .5f, stretch = 1.02f, antenna = .05f, curl = .3f, glances = false)
        val CURIOUS = Feel("CURIOUS", size = 1.06f, asym = .09f, lookX = .7f, lookY = -.12f, tilt = .1f, lean = .04f, antenna = .7f, curl = .45f, stretch = 1.03f, glances = false)
        /** Looking down at what you type. */
        val READING = Feel("READING", open = .86f, lookY = .8f, lean = .04f, tilt = .03f, stretch = .98f, antenna = .5f, glances = false)
        /** Eyes up on you, antenna straight up, tip lit by your voice. */
        val LISTENING = Feel("LISTENING", size = 1.06f, lookY = -.28f, antenna = 0f, curl = .12f, glow = .4f, pulse = true, stretch = 1.02f, glances = false)
        val THINKING = Feel("THINKING", open = .92f, lookX = -.5f, lookY = -.7f, tilt = -.07f, lean = -.03f, antenna = .1f, curl = 1.1f, glow = .18f, scan = Scan.DRIFT, glances = false)
        val CONFUSED = Feel("CONFUSED", asym = -.12f, smile = -.1f, lookX = .25f, lookY = -.4f, tilt = -.13f, lean = -.03f, antenna = -.25f, curl = -1f, glances = false)
        val SURPRISED = Feel("SURPRISED", size = 1.18f, stretch = 1.07f, antenna = 0f, curl = 0f, length = 1.1f, glances = false, enter = Enter.START)
        val FOCUSED = Feel("FOCUSED", open = .64f, lookX = .6f, lookY = .5f, tilt = .05f, lean = .04f, antenna = .9f, curl = .4f, stretch = .98f, glances = false)
        /** Droid at work: looking over its screen, tip softly pulsing. */
        val WORKING = Feel("WORKING", open = .9f, antenna = .55f, curl = .5f, tick = 1.1f, scan = Scan.WORK, glances = false)
        /** Taking in a screen: eyes wide, reading it line by line. */
        val LOOKING = Feel("LOOKING", size = 1.06f, lookY = -.05f, scan = Scan.READ, scanX = .7f, scanY = .45f, lean = .04f, stretch = 1.02f, antenna = .7f, curl = .38f, glances = false)
        /** Typing: eyes down, moving along like a caret. */
        val TYPING = Feel("TYPING", open = .82f, lookY = .72f, scan = Scan.CARET, scanX = .45f, antenna = .45f, curl = .6f, tick = 2.4f, stretch = .97f, lean = .03f, glances = false)
        /** Scrolling: his eyes ride the page. */
        val SCROLLING = Feel("SCROLLING", open = .95f, lookX = .1f, scan = Scan.SCROLL, scanY = .5f, antenna = .5f, glances = false)
        /** Waiting for an app: lids low, a slow rock, tip slowly pulsing. */
        val WAITING = Feel("WAITING", open = .6f, lookY = .2f, sway = .05f, antenna = .85f, curl = .75f, tick = .45f, stretch = .96f, depth = 1.6f, glances = false)
        /** Looking something up: eyes sweeping side to side. */
        val SEARCHING = Feel("SEARCHING", size = 1.04f, lookY = -.18f, scan = Scan.SWEEP, scanX = .75f, antenna = .25f, curl = .95f, stretch = 1.02f, glances = false)
        val STRAINING = Feel("STRAINING", open = .2f, smile = -.25f, stretch = .95f, antenna = .15f, curl = .6f, glances = false)
        /** Your turn: tall, eyes on you, tip lit, signal rings; now and then a wave of his antenna (see [DashRig]). */
        val NEEDS_YOU = Feel("NEEDS_YOU", size = 1.12f, lookY = -.25f, stretch = 1.05f, antenna = 0f, curl = .06f, length = 1.04f, glow = .9f, call = true, glances = false)
        /** You took over his screen: eyes closed, hands off. */
        val DRIVING = Feel("DRIVING", open = 0f, smile = -.5f, antenna = .35f, curl = .9f, stretch = .98f, glances = false)
        val SHY = Feel("SHY", open = .8f, size = .94f, lookX = -.7f, lookY = .6f, tilt = -.08f, lean = -.03f, blush = .85f, stretch = .97f, antenna = .9f, curl = .8f, glances = false)
        val OOPS = Feel("OOPS", size = 1.05f, asym = .08f, lookX = .7f, lookY = -.6f, tilt = -.08f, lean = -.03f, stretch = .96f, antenna = .95f, curl = .75f, glances = false)
        val SLEEPY = Feel("SLEEPY", open = .36f, smile = -.25f, lookY = .35f, sway = .04f, depth = 1.8f, stretch = .96f, antenna = .95f, curl = .85f, glances = false)
        val ASLEEP = Feel("ASLEEP", open = 0f, smile = -.6f, depth = 2.2f, stretch = .94f, antenna = 1.05f, curl = .8f, glances = false)
        val CELEBRATE = Feel("CELEBRATE", smile = .85f, blush = .6f, glow = .6f, size = 1.04f, stretch = 1.04f, antenna = 0f, curl = .15f, glances = false, enter = Enter.HOP2)
        /** Pleased, without the hop: a job done, quietly. */
        val CONTENT = Feel("CONTENT", open = .92f, smile = .7f, blush = .3f, lookY = -.1f, antenna = .1f, curl = .4f, stretch = 1.02f, glances = false)

        /** Every named feeling, in order. */
        val entries: List<Feel> = listOf(IDLE, HAPPY, EXCITED, LOVE, CURIOUS, READING, LISTENING, THINKING, CONFUSED, SURPRISED, FOCUSED, WORKING, LOOKING, TYPING, SCROLLING, WAITING, SEARCHING, STRAINING, NEEDS_YOU, DRIVING, SHY, OOPS, SLEEPY, ASLEEP, CELEBRATE, CONTENT)

        /** The named feeling [name] (any case, "needs-you" or "needs_you"), or null. */
        fun of(name: String): Feel? = name.trim().replace('-', '_').uppercase().let { n -> entries.firstOrNull { it.name == n } }

        /**
         * A face of the agent's own: [o]'s numbers over the named feeling in its "feel" (IDLE without one), each kept
         * to the range the rig draws well, so no value can break him. He calls you only if that feeling does.
         */
        fun face(o: org.json.JSONObject): Feel {
            val b = o.optString("feel").takeIf { it.isNotEmpty() }?.let(::of) ?: IDLE
            fun f(k: String, v: Float, lo: Float, hi: Float) =
                if (o.has(k)) o.optDouble(k, v.toDouble()).toFloat().let { if (it.isNaN()) v else it.coerceIn(lo, hi) } else v
            val enter = when (o.optString("enter")) { "hop" -> Enter.HOP; "hop2" -> Enter.HOP2; "start" -> Enter.START; "" -> b.enter; else -> Enter.NONE }
            return Feel("CUSTOM",
                open = f("open", b.open, 0f, 1f), smile = f("smile", b.smile, -1f, 1f), size = f("size", b.size, .85f, 1.2f),
                asym = f("asym", b.asym, -.15f, .15f), lookX = f("lookX", b.lookX, -1f, 1f), lookY = f("lookY", b.lookY, -1f, 1f),
                glances = o.optBoolean("glances", b.glances), scan = b.scan, scanX = b.scanX, scanY = b.scanY,
                lean = f("lean", b.lean, -.08f, .08f), tilt = f("tilt", b.tilt, -.2f, .2f), stretch = f("stretch", b.stretch, .92f, 1.08f),
                sway = f("sway", b.sway, 0f, .08f), depth = f("depth", b.depth, .5f, 2.4f), antenna = f("antenna", b.antenna, -.5f, 1.1f),
                curl = f("curl", b.curl, -1f, 1.2f), length = f("length", b.length, .85f, 1.12f), glow = f("glow", b.glow, 0f, 1f),
                pulse = false, tick = f("tick", b.tick, 0f, 3f), blush = f("blush", b.blush, 0f, 1f), call = b.call, enter = enter)
        }
    }
}

/** Dash's feeling for what a working agent does (see [Work]). */
fun Work.feel(): Feel = when (this) {
    Work.THINK -> Feel.THINKING
    Work.LOOK, Work.OPEN -> Feel.LOOKING
    Work.TYPE -> Feel.TYPING
    Work.SCROLL -> Feel.SCROLLING
    Work.WAIT -> Feel.WAITING
    Work.READ -> Feel.READING
    Work.WEB -> Feel.SEARCHING
    Work.WRITE -> Feel.FOCUSED
    Work.TAP, Work.BACK, Work.RUN -> Feel.WORKING
}

/**
 * A chat's Dash at a glance (the drawer, notifications): what its run is doing (calling you while a page waits on you),
 * or how its last turn left him: as the agent set him ([mood]), else by how it ended.
 */
fun chatFeel(working: Boolean, doing: Doing?, outcome: Outcome?, mood: Mood? = null, waiting: Boolean = false): Feel = when {
    working && waiting -> Feel.NEEDS_YOU   // a page waits on you
    working -> doing?.work?.feel() ?: Feel.THINKING
    outcome == Outcome.FAILED -> Feel.CONFUSED
    outcome == Outcome.STOPPED -> Feel.IDLE
    mood != null -> mood.feel              // as the agent set him
    outcome == Outcome.ASKS -> Feel.NEEDS_YOU
    outcome == Outcome.DONE -> Feel.HAPPY
    else -> Feel.IDLE
}

/**
 * An exact damped spring: solved for each step rather than integrated, so it never jumps, keeps its speed when the
 * target moves, and moves the same at any frame rate. [k] stiffness, [damping] ratio. [calm] (reduced motion: Android's
 * animations off): never bounces (at least critically damped), and rests exactly on its target once there, so a Dash at
 * rest costs nothing to draw.
 */
class Spring(var x: Float, k: Float, damping: Float, private val calm: Boolean, private val stir: () -> Unit = {}) {
    var v = 0f
    /** Where it's going. A new one wakes a calm rig that rests ([DashRig.resting]). */
    var t = x
        set(value) { if (value != field) { field = value; if (calm) stir() } }
    private val w = sqrt(k)
    private val z = if (calm) max(damping, 1f) else damping
    fun step(dt: Float) {
        if (dt <= 0f) return
        val x0 = x - t; val v0 = v
        val nx: Float; val nv: Float
        if (z < .999f) {
            val wd = w * sqrt(1f - z * z); val e = exp(-z * w * dt); val c = cos(wd * dt); val s = sin(wd * dt)
            nx = e * (x0 * c + (v0 + z * w * x0) / wd * s)
            nv = e * (v0 * c - (z * w * v0 + w * w * x0) / wd * s)
        } else if (z > 1.001f) {
            val q = w * sqrt(z * z - 1f); val r1 = -z * w + q; val r2 = -z * w - q
            val c2 = (v0 - r1 * x0) / (r2 - r1); val c1 = x0 - c2; val e1 = exp(r1 * dt); val e2 = exp(r2 * dt)
            nx = c1 * e1 + c2 * e2; nv = r1 * c1 * e1 + r2 * c2 * e2
        } else {
            val e = exp(-w * dt); val b = v0 + w * x0
            nx = (x0 + b * dt) * e; nv = (v0 - w * b * dt) * e
        }
        x = t + nx; v = nv
        if (calm && abs(nx) < 2e-4f && abs(nv) < 2e-3f) { x = t; v = 0f }
    }
    fun snap(to: Float) { x = to; t = to; v = 0f }
}

/** A gesture, [dur] seconds long: a curve that starts and ends at rest. */
internal enum class Mv(val dur: Float) { HOP(.7f), NOD(.5f), START(.55f), PEEK(.7f), WAVE(1.6f), SQUISH(.6f) }
internal class Move(val kind: Mv, var t: Float, val amp: Float)
internal class Tap(val fx: Float, val fy: Float, val quiet: Boolean) { var age = 0f }

private const val PIf = PI.toFloat()
private const val TAU = 2f * PIf
/** A smooth bump over 0..1: zero (with zero slope) at both ends, 1 in the middle. */
private fun bump(p: Float): Float = if (p <= 0f || p >= 1f) 0f else sin(PIf * p).let { it * it }
private fun smooth(a: Float, b: Float, x: Float): Float { val t = ((x - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }
private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t

/**
 * The rig: Dash's numbers, his gestures and the life layer (breathing, blinks, glances). Call [step] once a frame (done
 * by [rememberDash]); the drawing reads the numbers. [calm]: reduced motion, so no life, gestures or bounce; changes of
 * pose still ease, never jump.
 */
class DashRig(val calm: Boolean, feel: Feel = Feel.IDLE) {
    private fun s(x: Float, k: Float, z: Float) = Spring(x, k, z, calm, ::wake)
    // body
    val frame = s(0f, 80f, .95f)        // 0 creature .. 1 the frame of a screen
    val present = s(1f, 90f, 1f)        // 0: gone (a creature shrinks away; a frame lets go of its screen)
    val squash = s(0f, 260f, .45f)      // a squish you give him with reduced motion
    val tilt = s(0f, 70f, .9f); val lean = s(0f, 70f, .9f); val stretch = s(1f, 90f, .8f)
    val depth = s(1f, 30f, 1f)          // how deep he breathes
    // eyes
    val lookX = s(0f, 520f, 1f); val lookY = s(0f, 520f, 1f)
    val open = s(1f, 260f, 1f); val smile = s(0f, 160f, 1f)
    val eyes = s(1f, 200f, .8f); val eyeL = s(1f, 200f, .9f); val eyeR = s(1f, 200f, .9f)
    val blush = s(0f, 30f, 1f)
    // antenna: a little looser, so it follows his body through, and settles
    val antenna = s(.15f, 55f, .5f); val curl = s(.5f, 55f, .55f); val length = s(1f, 120f, .75f)
    val glow = s(0f, 60f, 1f)
    // looks
    val pale = s(0f, 70f, 1f)           // you are driving his screen
    val lit = s(0f, 50f, 1f)            // lit all round: your turn
    val drain = s(0f, 40f, 1f)          // the held screen's time used, 0..1
    val call = s(0f, 40f, 1f)           // calling you: the signal rings from his tip
    internal val all = listOf(frame, present, squash, tilt, lean, stretch, depth, lookX, lookY, open, smile, eyes, eyeL, eyeR, blush,
        antenna, curl, length, glow, pale, lit, drain, call)
    /** His height above the ground (dp at a 100 dp box), from his gestures: read it, don't set it. */
    val hop = Spring(0f, 1f, 1f, true)
    // what his gestures add this frame
    internal var gSq = 0f; internal var gLn = 0f; internal var gSt = 0f; internal var gWave = 0f
    /** How far into a call (your turn) he is, 0..1..0: brighter rings, a flare of his tip. */
    var callE = 0f; private set
    internal val moves = ArrayList<Move>()

    var feel: Feel = feel; private set
    private var feelFor = 0f
    private var poked = 0f
    private var beatFeel = Feel.IDLE
    private var beatFor = 0f
    private var sayFor = 0f
    /** The current voice level, 0..1, while listening. */
    var level: () -> Float = { 0f }
    /** Where he should look, in -1..1 of his own space, instead of glancing (a touch, a tap on the screen). */
    var lookAt: Offset? = null
        set(value) { if (value != field) { field = value; wake() } }

    // life layer
    var time = 0f; private set
    internal var blink = 0f; private set
    private var blinkT = -1f
    private var nextBlink = 1.2f + Random.nextFloat() * 2.4f   // two Dashes made together don't blink together
    internal var breath = Random.nextFloat() * TAU; private set
    private var glance = 1f + Random.nextFloat() * 2f
    private var gx = 0f; private var gy = 0f
    private var callT = 1f
    private var callHops = 0
    private var tapClock = 1f
    internal val taps = ArrayList<Tap>()
    /** While true and he is a frame, he taps around his screen by himself (the debug/demo stand-in for real taps). */
    var selfTaps = false

    /** Invalidates whatever draws him; bumped by the frame loop. */
    val tick: MutableLongState = mutableLongStateOf(0L)

    /** Wakes the frame loop of a calm Dash at rest: something changed him. */
    internal val stirred = Channel<Unit>(Channel.CONFLATED)
    private fun wake() { stirred.trySend(Unit) }
    /** The feeling he shows now (a poke's, a beat's, or his own). */
    private var shows = feel

    /**
     * Nothing about him will move until something changes him (calm, at rest, no poke, beat or words timing out, not
     * listening to a voice): his frame loop can sleep until [stirred].
     */
    internal fun resting() = calm && poked <= 0f && beatFor <= 0f && sayFor <= 0f && !shows.pulse && !selfTaps

    /** Moving fast (a gesture, a tap, a blink, a quick turn): drawn every frame. Slower life is drawn at 30 fps. */
    internal fun quick(): Boolean {
        if (moves.isNotEmpty() || taps.isNotEmpty() || blinkT >= 0f || poked > 0f) return true
        for (i in all.indices) if (abs(all[i].v) > 2f) return true
        return false
    }

    /** Starts a gesture after [delay] seconds (none with reduced motion, and not the same one twice at once). */
    private fun move(kind: Mv, amp: Float = 1f, delay: Float = 0f) {
        if (calm) return
        if (moves.any { it.kind == kind && it.t < kind.dur * .75f && it.t > -delay - .01f }) return
        moves += Move(kind, -delay, amp)
    }
    private fun enter(f: Feel) = when (f.enter) {
        Enter.HOP -> move(Mv.HOP, 6f)
        Enter.HOP2 -> { move(Mv.HOP, 8f); move(Mv.HOP, 5f, .62f) }
        Enter.START -> move(Mv.START)
        Enter.NONE -> {}
    }

    fun feel(f: Feel) {
        if (f == feel) return
        feel = f; feelFor = 0f; wake()
        enter(f)
        if (f.call) { callT = .35f + Random.nextFloat() * .4f; callHops = 0 }   // calls soon (a list of them: not in step)
    }

    /** A poke: a squish, a little hop and a moment of happy. (Calm, just the squish: it answers your touch.) */
    fun poke() {
        if (calm) squash.v += 1.2f else { move(Mv.SQUISH); move(Mv.HOP, 4f, .3f) }
        if (feel != Feel.LISTENING && feel != Feel.ASLEEP && feel != Feel.DRIVING) poked = .9f
        wake()
    }

    /** A step done: a small nod. */
    fun nod() = move(Mv.NOD)

    /** He says something (the agent's narration): a look up at you, his tip brightening, a little rise (as a frame, his head peeks up). */
    fun say() { move(Mv.PEEK); sayFor = 1.3f; wake() }

    /** [f] for [secs], then back to his feeling. */
    fun beat(f: Feel, secs: Float) { beatFeel = f; beatFor = secs; enter(f); wake() }

    /** What a working agent just did: a look up for something it said, a start for an app opening, else a nod. */
    fun react(d: Doing) = when {
        d.said -> say()
        d.work == Work.OPEN -> beat(Feel.SURPRISED, .55f)
        else -> nod()
    }

    /** Droid tapped its screen at [fx], [fy] (0..1 of the screen): a ripple there, and his eyes go to it. */
    fun tap(fx: Float, fy: Float) { taps += Tap(fx, fy, false); glow.v += 3f; wake() }

    /** Jumps to where he'd be by now (no gestures): for a rig that wasn't stepped while it couldn't be seen. */
    fun settle() {
        beatFor = 0f; sayFor = 0f; moves.clear(); gSq = 0f; gLn = 0f; gSt = 0f; gWave = 0f; callE = 0f; hop.x = 0f
        targets(if (poked > 0f) Feel.HAPPY else feel, 0f)
        for (sp in all) sp.snap(sp.t)
        tick.longValue++
        wake()
    }

    /** Advances the rig by [dt] seconds. False when nothing moved (calm and at rest): no need to draw again. */
    fun step(dt: Float): Boolean {
        if (calm) for (i in all.indices) before[i] = all[i].x
        time += dt; feelFor += dt
        if (poked > 0f) poked -= dt
        if (beatFor > 0f) beatFor -= dt
        if (sayFor > 0f) sayFor -= dt
        val f = when { poked > 0f -> Feel.HAPPY; beatFor > 0f -> beatFeel; else -> feel }
        shows = f
        life(dt)
        for (m in moves) m.t += dt
        moves.removeAll { it.t >= it.kind.dur }
        val lift0 = hop.x
        val lift = gestures()
        targets(f, dt)
        if (!calm) {
            // follow-through: his antenna lags behind his body, and dips as he rises
            antenna.v -= (tilt.v * 1.4f + lean.v * 2.2f) * dt * 6f
            curl.v += (lift - lift0) * .05f
        }
        for (sp in all) sp.step(dt)
        hop.v = if (dt > 0f) (lift - lift0) / dt else 0f; hop.x = lift
        for (t in taps) t.age += dt
        taps.removeAll { it.age > 3f }
        if (!calm) return true
        if (taps.isNotEmpty()) return true
        for (i in all.indices) if (all[i].x != before[i]) return true
        return false
    }
    private val before = FloatArray(all.size)

    /**
     * Takes up exactly where [o] is (another view of the same Dash: Watch growing out of the chat's frame, and back), so
     * the hand-over can't be seen. [feelToo]: its feeling and the targets set from outside (frame, presence, paleness,
     * the held time) as well; without, only where he is and how he's moving, then his own targets take him on.
     */
    fun follow(o: DashRig, feelToo: Boolean) {
        for ((a, b) in all.zip(o.all)) { a.x = b.x; a.v = b.v; if (feelToo) a.t = b.t }
        hop.x = o.hop.x; hop.v = o.hop.v
        time = o.time; blink = o.blink; blinkT = o.blinkT; nextBlink = o.nextBlink; breath = o.breath
        glance = o.glance; gx = o.gx; gy = o.gy; callE = o.callE
        gSq = o.gSq; gLn = o.gLn; gSt = o.gSt; gWave = o.gWave
        moves.clear(); o.moves.mapTo(moves) { Move(it.kind, it.t, it.amp) }
        taps.clear(); o.taps.mapTo(taps) { Tap(it.fx, it.fy, it.quiet).also { t -> t.age = it.age } }
        if (feelToo) {
            feel = o.feel; feelFor = o.feelFor; poked = o.poked; beatFeel = o.beatFeel; beatFor = o.beatFor; sayFor = o.sayFor
            callT = o.callT; callHops = o.callHops
        }
        tick.longValue++
        wake()
    }

    init { if (feel != Feel.IDLE) settle() }   // made in a feeling: he is in it already, not on his way from idle

    private fun life(dt: Float) {
        // blinks: quick to close, a little slower to open; now and then two
        if (blinkT >= 0f) {
            blinkT += dt
            val p = blinkT / .2f
            blink = if (p < .35f) smooth(0f, .35f, p) else 1f - smooth(.45f, 1f, p)
            if (p >= 1f) { blinkT = -1f; blink = 0f }
        } else if (!calm) {
            nextBlink -= dt
            if (nextBlink <= 0f) { blinkT = 0f; nextBlink = if (Random.nextFloat() < .18f) .34f else 2.4f + Random.nextFloat() * 3.6f }
        }
        breath += dt * TAU * when (feel) { Feel.ASLEEP -> .16f; Feel.EXCITED -> .38f; else -> .24f }
        glance -= dt
        if (glance <= 0f) {
            glance = 1.6f + Random.nextFloat() * 3f
            if (Random.nextFloat() < .35f) { gx = 0f; gy = 0f } else { gx = (Random.nextFloat() * 2 - 1) * .6f; gy = (Random.nextFloat() * 2 - 1) * .32f }
        }
    }

    /** Adds up what his gestures do this frame; returns his lift. */
    private fun gestures(): Float {
        var lift = 0f; var sq = 0f; var ln = 0f; var st = 0f; var wave = 0f; var ce = 0f
        for (g in moves) {
            val p = g.t / g.kind.dur
            if (p <= 0f) continue
            when (g.kind) {
                Mv.HOP -> {   // a crouch, up and over, a soft landing
                    val q = ((p - .2f) / .62f).coerceIn(0f, 1f)
                    lift += g.amp * bump(q)
                    sq += .07f * bump(p / .3f) - .05f * bump((p - .16f) / .3f) + .06f * bump((p - .72f) / .28f)
                }
                Mv.NOD -> { sq += .045f * bump(p); ln += .025f * bump(p) }
                Mv.START -> { st += .07f * bump(p / .6f); lift += 2.5f * bump(p / .7f) }
                Mv.PEEK -> { lift += 3f * bump(p); st += .02f * bump(p) }
                Mv.WAVE -> { val e = bump(p); wave += e * sin(TAU * 1.3f * g.t); ce = max(ce, e) }
                Mv.SQUISH -> sq += .1f * bump(p / .34f) - .04f * bump((p - .26f) / .36f) + .012f * bump((p - .58f) / .42f)
            }
        }
        gSq = sq; gLn = ln; gSt = st; gWave = wave; callE = ce
        return lift
    }

    private fun targets(f: Feel, dt: Float) {
        val t = feelFor; val T = time
        open.t = f.open; smile.t = f.smile; eyes.t = f.size + .05f * callE
        eyeL.t = 1f - f.asym; eyeR.t = 1f + f.asym
        blush.t = f.blush; depth.t = f.depth
        // where he looks
        var lx = f.lookX; var ly = f.lookY
        if (f.glances && !calm) { lx += gx; ly += gy }
        if (!calm) when (f.scan) {
            Scan.NONE -> {}
            Scan.WORK -> { lx = gx * 1.1f; ly = .35f + gy * .8f }                                  // looking over the screen
            Scan.READ -> {                                                                         // a line, then the next
                val c = (t / 2.2f).toInt(); val q = (t % 2.2f) / 2.2f
                lx += f.scanX * (if (q < .78f) mix(-1f, 1f, smooth(0f, .78f, q)) else mix(1f, -1f, smooth(.78f, 1f, q)))
                ly += f.scanY * ((c % 3) - 1) * .5f
            }
            Scan.SWEEP -> lx += f.scanX * sin(t * 1.5f)
            Scan.SCROLL -> { val q = (t % 1.8f) / 1.8f; ly += f.scanY * (if (q < .72f) mix(-1f, 1f, smooth(0f, .72f, q)) else mix(1f, -1f, smooth(.72f, 1f, q))) }
            Scan.CARET -> { val q = (t % 2.6f) / 2.6f; lx += f.scanX * (if (q < .8f) mix(-1f, 1f, q / .8f) else mix(1f, -1f, smooth(.8f, 1f, q))) }
            Scan.DRIFT -> { lx += .12f * sin(t * .9f); ly += .08f * sin(t * 1.3f + 1f) }
        } else if (f.scan == Scan.WORK) ly = .35f
        if (sayFor > 0f) { lx = 0f; ly = -.4f }
        val tap = taps.lastOrNull()?.takeIf { it.age < 1.6f }
        if (tap != null) { lx = (tap.fx - .5f) * 1.6f; ly = (tap.fy - .35f) * 1.2f }
        lookAt?.let { if (f.glances || f == Feel.IDLE || f == Feel.CURIOUS) { lx = it.x; ly = it.y } }
        lookX.t = lx; lookY.t = ly
        // his body: the pose, a little of his look, a slow rock
        var ln = f.lean; var tl = f.tilt; var st = f.stretch
        if (!calm) {
            ln += lookX.x * .03f; tl += lookX.x * .02f
            if (f.sway > 0f) tl += f.sway * sin(t * 1.3f)
            if (f.scan == Scan.SWEEP) ln += .05f * sin(t * 1.5f)
            if (f.call) st += .015f * sin(T * 2.2f)
        }
        if (f.pulse) st += .05f * level().coerceIn(0f, 1f)
        if (tap != null && !tap.quiet) ln = (tap.fx - .5f) * .3f               // his head goes to where he taps
        if (sayFor > 0f) { ln *= .3f; st = max(st, 1.04f) }
        lean.t = ln; tilt.t = tl; stretch.t = st
        // his antenna: a slow drift of its own, and a call's wave
        var an = f.antenna; var cu = f.curl
        if (!calm) { an += .05f * sin(T * 1.05f) + .03f * sin(T * .61f + 2f) + .6f * gWave; cu -= .3f * gWave }
        antenna.t = an; curl.t = cu; length.t = f.length + .12f * callE
        // his tip's light
        var gl = f.glow
        if (f.pulse) { val lv = level().coerceIn(0f, 1f); gl = .3f + .7f * (if (calm) lv else max(lv, .18f + .12f * sin(T * 2.4f))) }
        else if (f.tick > 0f) gl = if (calm) .3f else .12f + .4f * (.5f + .5f * sin(TAU * f.tick * T)).pow(2)
        else if (f.call && !calm) gl = f.glow * (.85f + .15f * sin(T * 2.2f))
        gl += .4f * callE
        if (sayFor > 0f) gl = max(gl, .85f * smooth(0f, .4f, sayFor))
        glow.t = gl
        lit.t = if (f.call) 1f else 0f; call.t = if (f.call) 1f else 0f
        // calling you: now and then a wave of his antenna (the first two with a little hop), less often after a while
        if (f.call && !calm) {
            callT -= dt
            if (callT <= 0f) { callT = if (t < 30f) 6f else 10f; move(Mv.WAVE); if (callHops < 2) { callHops++; move(Mv.HOP, 4f, .15f) } }
        }
        if (selfTaps && frame.x > .85f && feel == Feel.WORKING) {
            tapClock -= dt
            if (tapClock <= 0f) { val p = TAP_SPOTS[Random.nextInt(TAP_SPOTS.size)]; tap(p.first, p.second); tapClock = .9f + Random.nextFloat() * .8f }
        }
    }

    companion object {
        private val TAP_SPOTS = listOf(.78f to .16f, .3f to .42f, .5f to .6f, .7f to .86f, .25f to .24f, .62f to .36f)
    }
}

/**
 * Dash in [feel], standing in a [px] square bitmap: his picture where Compose doesn't draw (a notification), [onLight]
 * for a light shade. The last few are kept (a face of the agent's own is a feeling of its own).
 */
fun dashPortrait(feel: Feel, px: Int, density: Float, onLight: Boolean): android.graphics.Bitmap = synchronized(portraits) {
    portraits.getOrPut(Triple(feel, px, onLight)) {
        val rig = DashRig(calm = true, feel).also { it.settle() }
        val img = ImageBitmap(px, px)
        // his box, sized so his body is about two thirds of the picture's width, and placed so all of him (antenna to
        // base) sits in its middle
        val w0 = px * .64f; val u = w0 / .74f
        val bottom = px / 2f + .7f * u - .08f * u
        CanvasDrawScope().draw(Density(density), LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(img), Size(px.toFloat(), px.toFloat())) {
            drawDash(rig, null, null, 3f * density, true, home = Rect(px / 2f - u / 2, bottom - 1.38f * u, px / 2f + u / 2, bottom), shadow = false, onLight = onLight)
        }
        img.asAndroidBitmap()
    }
}
private val portraits = object : LinkedHashMap<Triple<Feel, Int, Boolean>, android.graphics.Bitmap>(16, .75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Triple<Feel, Int, Boolean>, android.graphics.Bitmap>?) = size > 24
}

/** Whether notifications show on a light shade now (the phone's own theme, not Droid's). */
fun lightShade(@Suppress("UNUSED_PARAMETER") ctx: android.content.Context) =  // the system's: Droid sets its own theme on its context
    (android.content.res.Resources.getSystem().configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) != android.content.res.Configuration.UI_MODE_NIGHT_YES

/** Is reduced motion on (Android's animations off)? */
@Composable
fun rememberCalm(): Boolean {
    val ctx = LocalContext.current
    return remember { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

/**
 * A rig that steps while it's on screen: every frame while he moves fast, 30 times a second for his slow life
 * (breathing, drifting, looking about), and not at all while a calm Dash rests. [setup] runs once with the new rig, which then starts settled in
 * what it set (a Dash that appears is already in his pose, not morphing into it in front of you); [from]: instead, it
 * takes up exactly where that Dash is ([DashRig.follow]). [active]: false while he can't be seen (a closed drawer), so
 * he costs nothing there.
 */
@Composable
fun rememberDash(key: Any? = Unit, feel: Feel = Feel.IDLE, active: Boolean = true, from: DashRig? = null, setup: (DashRig) -> Unit = {}): DashRig {
    val calm = rememberCalm()
    val rig = remember(key) { DashRig(calm, feel).also { if (from != null) { it.follow(from, true); setup(it) } else { setup(it); it.settle() } } }
    var wasActive by remember(rig) { mutableStateOf(active) }
    LaunchedEffect(rig, active) {
        if (!active) { wasActive = false; return@LaunchedEffect }
        // what changed while he couldn't be seen has happened already: no catching up on screen
        if (!wasActive) { rig.settle(); wasActive = true }
        var last = withFrameNanos { it }
        var quick = true
        while (true) {
            val now = if (quick) withFrameNanos { it } else slowFrame()
            val moved = rig.step(((now - last) / 1e9f).coerceIn(0f, .05f))
            last = now
            if (moved) rig.tick.longValue = now
            else if (rig.resting()) { rig.stirred.receive(); last = withFrameNanos { it } }
            quick = rig.quick()
        }
    }
    return rig
}

/** The frames slow motion is drawn on: 30 a second, the same ones for everything that uses [slowFrame]. */
private const val SLOW_FRAME_NS = 1_000_000_000L / 30

/**
 * Waits for the next of the frames slow motion is drawn on, 30 a second, and gives its time. Everything slow (Dash's
 * life, the busy spinner) changes on the same frames, so the screen is drawn 30 times a second, not once for each.
 */
suspend fun slowFrame(): Long {
    val from = withFrameNanos { it } / SLOW_FRAME_NS
    while (true) withFrameNanos { it }.let { if (it / SLOW_FRAME_NS != from) return it }
}

/** The head's scale (in dp) for a frame [fwDp] wide (its screen plus edges), shown at [scale]. */
fun headScaleDp(fwDp: Float, scale: Float = 1f): Float = (fwDp * scale / 122f).coerceIn(.6f, 1.6f) / scale

/** Room above a frame's top edge (dp) that all of his head fits in at its tallest: antenna, glow and rings included. */
fun headRoomDp(nsDp: Float, hook: Boolean = true): Float = nsDp * (if (hook) 33f else 16f) + 2f

/**
 * Dash in a box: a creature fitted to it, or (as [DashRig.frame] goes to 1) the frame around [screenRect], given in the
 * box's own pixels (with [headRoomDp] above it inside the box).
 */
@Composable
fun Dash(rig: DashRig, modifier: Modifier = Modifier, screen: ImageBitmap? = null, screenRect: ((Size) -> Rect)? = null,
         edge: Dp = 3.dp, hook: Boolean = true, label: String? = "Dash", onPoke: (() -> Unit)? = { rig.poke() }) {
    val tap = remember { MutableInteractionSource() }
    val onLight = P.bg.luminance() > .5f
    // label null: he's decoration beside words that say it, and screen readers skip him
    Canvas(modifier.let { m -> if (label == null) m.clearAndSetSemantics {} else m.semantics { contentDescription = label } }
        .then(if (onPoke != null) Modifier.clickable(tap, null, onClick = onPoke) else Modifier)) {
        rig.tick.longValue
        drawDash(rig, screen, screenRect?.invoke(size), edge.toPx(), hook, onLight = onLight)
    }
}

/**
 * The live screen as Dash's frame, laid out at the screen's own size ([width] by [width] times [ratio]): his edge and
 * head draw just outside it, so leave [headRoomDp] above it (Watch does). [frame] is the screen's latest picture.
 */
@Composable
fun DashScreen(rig: DashRig, frame: ImageBitmap?, width: Dp, ratio: Float, modifier: Modifier = Modifier,
               edge: Dp = 3.dp, hook: Boolean = true, label: String = "Dash's screen, live", onClick: (() -> Unit)? = null,
               /** The scale this will be shown at (Watch growing from the chat), and how much of the hook to show. */
               scale: () -> Float = { 1f }, hookAmt: () -> Float = { if (hook) 1f else 0f }, onLight: Boolean = P.bg.luminance() > .5f) {
    val tap = remember { MutableInteractionSource() }
    Canvas(modifier.size(width, width * ratio).semantics { contentDescription = label }
        .then(if (onClick != null) Modifier.clickable(tap, null, onClick = onClick) else Modifier)) {
        rig.tick.longValue
        val sc = scale().coerceAtLeast(.05f)
        // the edge looks like the chat's 3 dp when small and grows to [edge] at full size
        val e = mix(3.dp.toPx() / sc, edge.toPx(), smooth(.4f, 1f, sc))
        drawDash(rig, frame, Rect(0f, 0f, size.width, size.height), e, hook, hookAmt(), sc, onLight = onLight)
    }
}

/**
 * Where the chat's live screen is (window pixels) and its latest picture, so Watch can grow out of it; [taken] while
 * Watch has it, so the chat's frame hides under Watch's instead of showing twice.
 */
object ScreenOrigin {
    var rect: Rect? = null
    var image: ImageBitmap? = null
    /** The Dash framing it, while he does: Watch's Dash takes up where he is, and hands back where it got to. */
    var rig: DashRig? = null
    val taken = mutableStateOf(false)
}

/**
 * A slot that is Dash the creature (standing in a [creature] box at its top left) while [DashRig.frame] is 0, and grows
 * from there into the live screen's frame ([width] wide, [ratio] tall, with the room his head needs above it) as it goes
 * to 1. The layout follows the springs, so whatever is beside him moves with him: a creature that leaves
 * ([DashRig.present] to 0) takes his room and the [gap] after him along, and a frame that lets go of its screen gives
 * back the room its head took, ending exactly where a plain [ScreenWindow] of that width sits. All of him draws inside
 * the slot, so a list can't cut him off and he never covers his neighbours.
 */
@Composable
fun DashSlot(rig: DashRig, frame: ImageBitmap?, creature: DpSize, width: Dp, ratio: Float, modifier: Modifier = Modifier,
             gap: Dp = 0.dp, label: String = "Droid's screen", origin: Boolean = false, onClick: (() -> Unit)? = null) {
    val tap = remember { MutableInteractionSource() }
    val geo = remember { FloatArray(3) }  // head room, gap, how much of the creature's room is left
    val hairline = P.hairline
    val onLight = P.bg.luminance() > .5f
    val head = headRoomDp(headScaleDp(width.value + 6f)).dp
    if (origin) { ScreenOrigin.image = frame; ScreenOrigin.rig = rig }
    if (origin) DisposableEffect(rig) { onDispose { if (ScreenOrigin.rig === rig) { ScreenOrigin.rig = null; ScreenOrigin.image = null; ScreenOrigin.rect = null } } }
    Canvas(modifier
        .layout { m, c ->
            rig.tick.longValue
            // the spring itself, no easing on top (that would hold him still, then rush)
            val t = rig.frame.x.coerceIn(0f, 1.05f)
            val pr = rig.present.x.coerceIn(0f, 1f)
            val g = mix(pr, 1f, min(t, 1f))
            // rounded as Modifier.size and padding round, so a frame at rest is exactly a ScreenWindow beside the same gap
            geo[0] = head.toPx() * min(t, 1f) * pr; geo[1] = gap.roundToPx() * g; geo[2] = g
            val ww = width.roundToPx().toFloat(); val wh = (width * ratio).roundToPx().toFloat()
            val w = (mix(creature.width.toPx(), ww, t) * g + geo[1]).roundToInt()
            val h = (mix(creature.height.toPx(), wh, t) * g + geo[0]).roundToInt()
            val p = m.measure(c.copy(minWidth = w, maxWidth = w, minHeight = h, maxHeight = h))
            layout(w, h) { p.place(0, 0) }
        }
        .onGloballyPositioned { c ->
            if (origin && rig.frame.x > .9f) {
                val b = c.boundsInWindow(); ScreenOrigin.rect = Rect(b.left, b.top + geo[0], b.right - geo[1], b.bottom)
            }
        }
        .semantics { contentDescription = label }
        .then(if (onClick != null) Modifier.clickable(tap, null, onClick = onClick) else Modifier)) {
        rig.tick.longValue
        if (origin && ScreenOrigin.taken.value) return@Canvas
        val cw = creature.width.toPx(); val cx = cw * geo[2] / 2
        drawDash(rig, frame, Rect(0f, geo[0], size.width - geo[1], size.height), 3.dp.toPx(), true,
            home = Rect(cx - cw / 2, 0f, cx + cw / 2, creature.height.toPx()), hairline = hairline, onLight = onLight)
    }
}

/** His lights on a light background, where white wouldn't show. */
private val LIGHT_GLOW = Color(0xFF7D83F2)
/** His lit edge and rings on a dark background. */
private val DARK_LIT = Color(0xFFC7CBFF)
private val SHADE = Color(0xFF2A1E6E)
private const val K = .5523f   // a quarter circle as a cubic

/** The corner radius of Droid's screen drawn [w] px wide: one rule at every size, so his frame and a plain still match. */
fun screenCorner(w: Float, density: Float): Float = (w * .1f).coerceIn(5f * density, 28f * density)

// ------------------------------------------------------------------------------------------------------------------
// Drawing
// ------------------------------------------------------------------------------------------------------------------

// Reused objects: drawing allocates next to nothing per frame. One set per thread: a notification's picture is drawn
// off the main thread (dashPortrait) while the screen may be drawing him on it.
private class Scratch {
    val bodyP = Path(); val screenP = Path(); val drainP = Path(); val drainSeg = Path(); val eyeP = Path()
    val stalkP = Path(); val miscP = Path(); val shearM = Matrix(); val stalkX = FloatArray(13); val stalkY = FloatArray(13)
    val measure = PathMeasure()
}
private val scratch = ThreadLocal.withInitial { Scratch() }
private val bodyP get() = scratch.get()!!.bodyP
private val screenP get() = scratch.get()!!.screenP
private val drainP get() = scratch.get()!!.drainP
private val drainSeg get() = scratch.get()!!.drainSeg
private val eyeP get() = scratch.get()!!.eyeP
private val stalkP get() = scratch.get()!!.stalkP
private val miscP get() = scratch.get()!!.miscP
private val shearM get() = scratch.get()!!.shearM
private val stalkX get() = scratch.get()!!.stalkX
private val stalkY get() = scratch.get()!!.stalkY
private val shaders = java.util.WeakHashMap<ImageBitmap, android.graphics.Shader>()
private val shaderM = android.graphics.Matrix()
private val measure get() = scratch.get()!!.measure

/** [image] as a brush that covers the rect [l], [t], [r], [b]: scaled to cover it, centered across, from the top. */
private fun screenBrush(image: ImageBitmap, l: Float, t: Float, r: Float, b: Float): Brush {
    val sh = shaders.getOrPut(image) { ImageShader(image, TileMode.Clamp, TileMode.Clamp) }
    val k = max((r - l) / image.width, (b - t) / image.height)
    shaderM.setScale(k, k); shaderM.postTranslate(l + (r - l - image.width * k) / 2, t)
    sh.setLocalMatrix(shaderM)
    return ShaderBrush(sh)
}

private fun roundRect(p: Path, l: Float, t: Float, r: Float, b: Float, rad: Float) {
    val rr = rad.coerceIn(0f, max(0f, min(r - l, b - t) / 2))
    p.reset()
    p.moveTo(l + rr, t); p.lineTo(r - rr, t); p.cubicTo(r - rr + rr * K, t, r, t + rr - rr * K, r, t + rr)
    p.lineTo(r, b - rr); p.cubicTo(r, b - rr + rr * K, r - rr + rr * K, b, r - rr, b)
    p.lineTo(l + rr, b); p.cubicTo(l + rr - rr * K, b, l, b - rr + rr * K, l, b - rr)
    p.lineTo(l, t + rr); p.cubicTo(l, t + rr - rr * K, l + rr - rr * K, t, l + rr, t)
    p.close()
}

/** A rounded rect clockwise from [gap] right of a point [off] from its top edge's middle round to [gap] left of it. */
private fun roundRectFromTop(p: Path, l: Float, t: Float, r: Float, b: Float, rad: Float, gap: Float, off: Float = 0f) {
    val rr = rad.coerceIn(0f, max(0f, min(r - l, b - t) / 2))
    val mid = (l + r) / 2 + off
    val gp = gap.coerceIn(0f, max(0f, (r - l) / 2 - rr))
    p.reset()
    p.moveTo(mid + gp, t); p.lineTo(r - rr, t); p.cubicTo(r - rr + rr * K, t, r, t + rr - rr * K, r, t + rr)
    p.lineTo(r, b - rr); p.cubicTo(r, b - rr + rr * K, r - rr + rr * K, b, r - rr, b)
    p.lineTo(l + rr, b); p.cubicTo(l + rr - rr * K, b, l, b - rr + rr * K, l, b - rr)
    p.lineTo(l, t + rr); p.cubicTo(l, t + rr - rr * K, l + rr - rr * K, t, l + rr, t)
    p.lineTo(mid - gp, t)
}

/**
 * His head on an edge at [y0]: from the edge up a soft fillet, a rounded top [hw] either side of [hx], [hh] tall, and down
 * to the edge again. Its fillet and corners are clamped to its height, so it never folds, however small it is.
 */
private fun headTo(p: Path, hx: Float, y0: Float, hw: Float, hh: Float, fil: Float) {
    val l = hx - hw; val r = hx + hw; val top = y0 - hh; val f = fil
    val rn = max(0f, min(hw, hh - f)) * .96f
    p.lineTo(l - f, y0)
    p.cubicTo(l - f + f * K, y0, l, y0 - f + f * K, l, y0 - f)
    p.lineTo(l, top + rn)
    p.cubicTo(l, top + rn - rn * K, l + rn - rn * K, top, l + rn, top)
    p.lineTo(r - rn, top)
    p.cubicTo(r - rn + rn * K, top, r, top + rn - rn * K, r, top + rn)
    p.lineTo(r, y0 - f)
    p.cubicTo(r, y0 - f + f * K, r + f - f * K, y0, r + f, y0)
}

/** A soft spot: [color] at its middle fading to nothing at its edge, [rx] by [ry]. */
private fun DrawScope.soft(x: Float, y: Float, rx: Float, ry: Float, color: Color, a: Float) {
    if (a < .005f || rx <= 0f || ry <= 0f) return
    val al = a.coerceIn(0f, 1f)
    withTransform({ scale(1f, ry / rx, Offset(x, y)) }) {
        drawCircle(Brush.radialGradient(0f to color.copy(alpha = al), .55f to color.copy(alpha = al * .55f), 1f to color.copy(alpha = 0f),
            center = Offset(x, y), radius = rx), rx, Offset(x, y))
    }
}

/**
 * His antenna from ([x], [y]): a stalk that hooks more toward its tip, a ball, its glow; returns where the tip is.
 * [a0]: its angle at the base (+ right), [curl]: how it hooks.
 */
private fun DrawScope.antenna(x: Float, y: Float, len: Float, stalkW: Float, tipR: Float, a0: Float, curl: Float, pale: Float,
                              glow: Float, halo: Float, onLight: Boolean, shine: Float): Offset {
    val n = 12
    stalkX[0] = x; stalkY[0] = y
    var px = x; var py = y
    for (i in 1..n) {
        val a = a0 + curl * 2f * ((i - .5f) / n).pow(1.4f)
        px += sin(a) * len / n; py -= cos(a) * len / n
        stalkX[i] = px; stalkY[i] = py
    }
    // through the midpoints with quadratic curves: smooth everywhere
    stalkP.reset(); stalkP.moveTo(x, y)
    for (i in 1 until n) stalkP.quadraticTo(stalkX[i], stalkY[i], (stalkX[i] + stalkX[i + 1]) / 2, (stalkY[i] + stalkY[i + 1]) / 2)
    stalkP.lineTo(px, py)
    drawPath(stalkP, lerp(DashColors.Stalk, Color(0xFFC4C7E8), pale * .7f), style = Stroke(stalkW, cap = StrokeCap.Round, join = StrokeJoin.Round))
    val gl = glow.coerceIn(0f, 1.3f)
    if (gl > .02f) soft(px, py, tipR * halo, tipR * halo, if (onLight) LIGHT_GLOW else DashColors.Glow, .55f * min(1f, gl))
    drawCircle(lerp(lerp(DashColors.Tip, Color(0xFFFFF8E2), min(1f, gl * .7f)), Color(0xFFE4E5F4), pale), tipR, Offset(px, py))
    if (shine > .01f && tipR > 2f * density) drawCircle(Color.White.copy(alpha = .8f * shine), tipR * .3f, Offset(px - tipR * .3f, py - tipR * .32f))
    return Offset(px, py)
}

/**
 * Draws [rig] into this scope. As a creature he stands in [home] (default: the whole scope), fitted to it; as a frame
 * ([DashRig.frame] = 1) his body is [screen] (the screen's rect) grown by [edge] on every side, with [image] inside and
 * his head above it. [scale]: the scale this is shown at, so his head and corners match a frame of that size on screen.
 * [hairline]: the line a frame leaves when it lets go of its screen (a plain still's border).
 */
fun DrawScope.drawDash(rig: DashRig, image: ImageBitmap?, screen: Rect?, edge: Float, hook: Boolean, hookAmt: Float = if (hook) 1f else 0f,
                       scale: Float = 1f, home: Rect? = null, hairline: Color = Color(0x332A1E6E),
                       /** Drawn on a light background: his lights (a glowing tip, a lit edge) are periwinkle, not white. */
                       onLight: Boolean = false, shadow: Boolean = true) {
    val dp = density
    val m = rig.frame.x.coerceIn(0f, 1f)
    val present = rig.present.x.coerceIn(0f, 1f)
    // the creature, fitted to his box
    val box = home ?: Rect(0f, 0f, size.width, size.height)
    val u = min(box.width, box.height / 1.38f)
    val w0 = u * .74f; val h0 = w0 * 1.02f
    val groundC = box.bottom - u * .08f
    val small = ((56f * dp - w0) / (36f * dp)).coerceIn(0f, 1f)   // features grow relative to him when he's small, as an icon's do
    // the frame: the screen grown by the edge
    val sr = screen ?: Rect(0f, 0f, size.width, size.height)
    val fw = sr.width + 2 * edge; val fh = sr.height + 2 * edge
    val w = mix(w0, fw, m); val h = mix(h0, fh, m)
    val ground = mix(groundC, sr.bottom + edge, m)
    val cx = mix(box.center.x, sr.center.x, m)
    val ns = headScaleDp(fw / dp, scale) * dp
    // the morph, in order: his eyes rise to the top as his head grows out of it, then the screen opens inside him
    val em = smooth(.06f, .7f, m)
    val nm = smooth(.2f, .78f, m) * present
    val sa = smooth(.45f, .95f, m)
    // his pose, as a creature (about his base); a frame holds still and its head does the moving
    val k = 1f - m
    val kl = 1f - smooth(0f, .45f, m)
    val breath = if (rig.calm) 0f else sin(rig.breath) * rig.depth.x
    val st = (rig.stretch.x + rig.gSt).coerceIn(.82f, 1.2f)
    val sq = (rig.squash.x + rig.gSq).coerceIn(-.35f, .35f)
    val sx = 1f + ((1f + sq * .9f) / sqrt(st) * (1f - .008f * breath) - 1f) * k
    val sy = 1f + ((1f - sq) * st * (1f + .012f * breath) - 1f) * k
    val sizeK = (u / (100f * dp)).coerceIn(.35f, 1f)
    val lift = max(0f, rig.hop.x) * dp * sizeK * k
    val tilt = rig.tilt.x * k
    val shear = (rig.lean.x + rig.gLn) * kl
    val screenR = screenCorner(sr.width * scale, dp) / scale
    val frameR = screenR + edge
    val rtx = mix(w0 / 2, frameR, m); val rty = mix(h0 * .53f, frameR, m)
    val rbx = mix(w0 * .45f, frameR, m); val rby = mix(h0 * .41f, frameR, m)
    // his head, as a frame: taller when he stands tall, up with a hop or a breath, wider with wide eyes; it slides along
    // the edge the way he leans (to where he taps), and shrinks to fit what room the edge has
    val hs = w / 2 - rtx
    var hw = 14f * ns * nm * (1f + .3f * (rig.eyes.x - 1f))
    var hh = ns * nm * (11f * (1f + (st - 1f) * 1.6f)).coerceIn(8f, 13f) + nm * em * (max(0f, rig.hop.x) * .45f * ns + breath * .35f * ns)
    var fil = min(3.6f * ns * nm, hh * .4f)
    val fit = ((hs - dp) / max(1e-3f, hw + fil)).coerceIn(0f, 1f)
    hw *= fit; hh *= fit; fil *= fit
    val room = max(0f, hs - dp - hw - fil)
    val hx = (rig.lean.x * hw * 3f * em).coerceIn(-room, room)
    // a creature that leaves shrinks away; a frame that lets go fades instead
    val gone = mix(present, 1f, smooth(0f, .5f, m))
    if (gone < .01f) return
    val pale = rig.pale.x.coerceIn(0f, 1f)

    // a soft shadow on the ground, smaller as he rises
    if (shadow && k > .01f) {
        val sk = 1f - (lift / (40f * dp * sizeK)).coerceIn(0f, .45f)
        soft(cx, ground + .5f * dp, w0 * .42f * sk * gone, max(1.2f * dp, w0 * .06f) * sk * gone, SHADE, .17f * k * gone * sk)
    }

    shearM.reset(); shearM.values[Matrix.SkewX] = -shear
    withTransform({
        translate(cx, ground - lift)
        rotate(tilt * 180f / PIf, Offset.Zero)
        transform(shearM)
        scale(sx * gone, sy * gone, Offset.Zero)
    }) {
        val y0 = -h
        // the antenna, behind the body: from his crown, or from his head's top as a frame
        val showHook = mix(1f, hookAmt, em) * mix(1f, present, m)
        val len = rig.length.x * mix(w0 * .27f * (1 + .2f * small), 8f * ns, em) * showHook
        if (len > .5f * dp) {
            val bx = hx * em
            val by = mix(y0 + max(1.5f * dp, w0 * .05f), y0 - hh + min(1.5f * dp, hh * .3f), em)
            val stalkW = mix(max(w0 * .037f * (1 + .45f * small), 1.3f * dp), 1.9f * ns, em)
            val tipR = mix(w0 * .058f * (1 + .35f * small), 2.7f * ns, em) * min(1f, showHook * 1.5f) * (1f + .15f * rig.call.x.coerceIn(0f, 1f))
            val tip = antenna(bx, by, len, stalkW, tipR, rig.antenna.x, rig.curl.x, pale, rig.glow.x, mix(2.6f, 2.4f, em), onLight,
                if (small < .8f) 1f - small else 0f)
            val cl = rig.call.x.coerceIn(0f, 1f) * showHook * mix(1f - smooth(.3f, .7f, small), 1f, em)
            if (cl > .01f) drawCall(rig, tip.x, tip.y, tipR, mix(tipR * 4.2f, 6f * ns, em), max(stalkW * .9f, 1.1f * dp), if (onLight) DashColors.Mark else DARK_LIT, cl)
        }

        // the body: one outline, mochi to frame
        val x0 = -w / 2; val x1 = w / 2
        bodyP.reset()
        bodyP.moveTo(x0 + rtx, y0)
        if (hh > .3f * dp && hw > .5f * dp) headTo(bodyP, hx, y0, hw, hh, fil)
        bodyP.lineTo(x1 - rtx, y0); bodyP.cubicTo(x1 - rtx + rtx * K, y0, x1, y0 + rty - rty * K, x1, y0 + rty)
        bodyP.lineTo(x1, -rby); bodyP.cubicTo(x1, -rby + rby * K, x1 - rbx + rbx * K, 0f, x1 - rbx, 0f)
        bodyP.lineTo(x0 + rbx, 0f); bodyP.cubicTo(x0 + rbx - rbx * K, 0f, x0, -rby + rby * K, x0, -rby)
        bodyP.lineTo(x0, y0 + rty); bodyP.cubicTo(x0, y0 + rty - rty * K, x0 + rtx - rtx * K, y0, x0 + rtx, y0)
        bodyP.close()
        val bodyA = mix(1f, 0f, (1f - present) * m)       // a frame letting go fades, leaving the still's hairline
        val lit = rig.lit.x.coerceIn(0f, 1f)
        if (lit > .01f) {
            // your turn: a soft glow round him (under the body, so only its outside shows), pulsing with each call
            val pulse = if (rig.calm) 1f else (.75f + .25f * sin(rig.time * 2.2f)) * (1f + .5f * rig.callE)
            val lc = if (onLight) LIGHT_GLOW else DARK_LIT
            val gw = mix(max(w0 * .07f, 1.6f * dp), 4.5f * dp, m)
            drawPath(bodyP, lc, alpha = min(1f, .1f * lit * pulse), style = Stroke(gw * 2f * lit, join = StrokeJoin.Round))
            drawPath(bodyP, lc, alpha = min(1f, .2f * lit * pulse), style = Stroke(gw * lit, join = StrokeJoin.Round))
        }
        val top = lerp(DashColors.BodyTop, DashColors.Pale, pale)
        val bot = lerp(DashColors.Body, DashColors.Pale, pale)
        drawPath(bodyP, Brush.verticalGradient(0f to top, 1f to bot, startY = y0 - hh, endY = 0f), alpha = bodyA)
        // a soft light from above, on the creature
        val hl = (1f - smooth(.05f, .5f, m)) * (1f - pale * .6f)
        if (hl > .01f) drawPath(bodyP, Brush.radialGradient(0f to Color.White.copy(alpha = .42f * hl), .5f to Color.White.copy(alpha = .12f * hl),
            1f to Color.White.copy(alpha = 0f), center = Offset(-w * .18f, -h * .76f), radius = w * .62f), alpha = bodyA)
        if (lit > .01f && hh > dp) drawPath(bodyP, Brush.radialGradient(0f to Color.White.copy(alpha = .5f * lit * smooth(dp, 4f * dp, hh)),
            1f to Color.White.copy(alpha = 0f), center = Offset(hx, y0 - hh * .4f), radius = max(dp, hw * 2.2f)))

        // the screen inside the frame
        if (sa > .01f) {
            val l = -w / 2 + edge; val t = y0 + edge; val r = w / 2 - edge; val b = -edge
            roundRect(screenP, l, t, r, b, screenR)
            drawPath(screenP, Color(0xFFF4F5FA), alpha = sa)
            // the picture, painted into the screen's shape: cover, anchored to the top (as a plain still is)
            if (image != null) drawPath(screenP, screenBrush(image, l, t, r, b), alpha = sa)
            clipRect(l, t, r, b) {
                for (tp in rig.taps) {
                    val q = tp.age / .9f
                    if (q > 1f) continue
                    val c = Offset(l + tp.fx * (r - l), t + tp.fy * (b - t))
                    val a = (1 - q) * min(1f, tp.age / .08f)        // in over a few frames, out as it spreads
                    val rr = (5f + q * 16f) * ns
                    drawCircle(DashColors.Accent.copy(alpha = a * .3f), rr, c)
                    drawCircle(DashColors.Mark.copy(alpha = a * .8f), rr, c, style = Stroke(1.6f * dp))
                }
            }
            // a held screen's time, running out along his edge: clockwise from his head round to it again
            val d = rig.drain.x.coerceIn(0f, 1f)
            if (d > .005f) {
                roundRectFromTop(drainP, -w / 2 + edge / 2, y0 + edge / 2, w / 2 - edge / 2, -edge / 2, screenR + edge / 2, hw + fil, hx)
                measure.setPath(drainP, false)
                drainSeg.reset()
                measure.getSegment(0f, measure.length * d, drainSeg, true)
                drawPath(drainSeg, Color(0xFFE4E6F6), style = Stroke(edge * .95f, cap = StrokeCap.Butt), alpha = sa * bodyA)
            }
            // letting go leaves the still's own border
            if (present < 1f) {
                val hw2 = .5f * dp
                roundRect(miscP, l + hw2, t + hw2, r - hw2, b - hw2, screenR - hw2)
                drawPath(miscP, hairline, alpha = (1f - present) * sa, style = Stroke(2 * hw2))
            }
        }

        // eyes: low on his face as a creature, in his head as a frame
        val eyeVis = mix(1f, present, m)
        if (eyeVis > .02f) {
            val lx = rig.lookX.x.coerceIn(-1.2f, 1.2f); val ly = rig.lookY.x.coerceIn(-1.2f, 1.2f)
            val rx = mix(w0 * .076f * (1 + .45f * small), 3.5f * ns * fit, em) * rig.eyes.x * mix(1f, .6f + .4f * present, m)
            val ry = rx * 1.32f
            val sep = mix(w0 * (.175f + .02f * small), hw * .46f, em)
            val ey = mix(-h0 * .43f, y0 - hh * .5f, em); val ex = hx * em
            val ox = lx * mix(w0 * .085f, hw * .16f, em); val oy = ly * mix(h0 * .065f, hh * .1f, em)
            val sepT = sep * (1f - .1f * abs(lx))                 // turning: the eyes come a little together
            val tiltE = rig.tilt.x * rx * 1.4f * em                // as a frame, a tilt is in his eyes
            val line = max(1.1f * dp, rx * .42f)
            drawEye(rig, ex - sepT + ox, ey + oy - tiltE, rx * rig.eyeL.x * (1f - .14f * max(0f, -lx)), ry * rig.eyeL.x, lx, ly, line, eyeVis)
            drawEye(rig, ex + sepT + ox, ey + oy + tiltE, rx * rig.eyeR.x * (1f - .14f * max(0f, lx)), ry * rig.eyeR.x, lx, ly, line, eyeVis)
            val bl = rig.blush.x.coerceIn(0f, 1f) * (1f - em) * (1f - smooth(.5f, .9f, small))
            if (bl > .01f) for (s in floatArrayOf(-1f, 1f))
                soft(ex + s * (sepT + rx * 1.3f) + ox * .6f, ey + ry * 1.15f + oy * .4f, rx * 1.2f, rx * .66f, DashColors.Blush, .75f * bl)
        }
    }
}

/**
 * His call (your turn): signal rings going out left and right from his tip at ([x], [y]), as from an antenna. [r]: the
 * tip's radius; [reach]: how far they go; [amt]: how much he's calling. A calm Dash holds two still rings.
 */
private fun DrawScope.drawCall(rig: DashRig, x: Float, y: Float, r: Float, reach: Float, width: Float, color: Color, amt: Float) {
    val r0 = r * 1.9f + width
    val st = Stroke(width, cap = StrokeCap.Round)
    fun ring(k: Float, a: Float) {
        if (a < .01f) return
        val rr = r0 + reach * k
        val tl = Offset(x - rr, y - rr); val sz = Size(2 * rr, 2 * rr)
        drawArc(color, -38f, 76f, false, tl, sz, alpha = a.coerceIn(0f, 1f), style = st)
        drawArc(color, 142f, 76f, false, tl, sz, alpha = a.coerceIn(0f, 1f), style = st)
    }
    if (rig.calm) { ring(.15f, amt * .8f); ring(.55f, amt * .45f); return }
    val boost = 1f + .4f * rig.callE
    for (i in 0..1) {
        val k = (rig.time / 1.8f + i * .5f) % 1f
        ring(k, amt * smooth(0f, .18f, k) * (1f - k) * (1f - k) * boost)
    }
}

/**
 * One eye, as one filled outline (no stroke: strokes of small closed paths can come out aliased on some GPUs): an upper
 * and a lower half-oval over the same width, so they can never cross. Open, an oval; smiling, the lower edge rises into a
 * crescent; closing, both meet in one fine curved line, tapered at its ends (up when happy, down when relaxed), with
 * nothing swapped or cross-faded. [line]: how thick a closed eye's line is.
 */
private fun DrawScope.drawEye(rig: DashRig, cx: Float, cy: Float, rx: Float, ry: Float, lx: Float, ly: Float, line: Float, alpha: Float) {
    val o = (rig.open.x * (1f - rig.blink)).coerceIn(0f, 1f)
    val sm = rig.smile.x.coerceIn(-1f, 1f)
    val h = line / 2
    val ery = max(.3f, ry - h)
    val tOpen = ery * (1f + .1f * max(0f, sm) + .45f * min(0f, sm)); val bOpen = ery * (1f - 2.15f * max(0f, sm))
    val bow = ery * (if (sm >= 0f) mix(-.12f, .5f, sm) else mix(-.12f, -.42f, -sm))
    val T = mix(bow, tOpen, o) + h; val B = mix(-bow, bOpen, o) + h
    val y = cy + ery * .18f * (1f - o) * (1f - max(0f, sm))
    eyeP.reset()
    eyeP.moveTo(cx - rx, y)
    eyeP.cubicTo(cx - rx, y - T * K, cx - rx * K, y - T, cx, y - T)
    eyeP.cubicTo(cx + rx * K, y - T, cx + rx, y - T * K, cx + rx, y)
    eyeP.cubicTo(cx + rx, y + B * K, cx + rx * K, y + B, cx, y + B)
    eyeP.cubicTo(cx - rx * K, y + B, cx - rx, y + B * K, cx - rx, y)
    eyeP.close()
    val a = alpha.coerceIn(0f, 1f)
    drawPath(eyeP, DashColors.Ink, alpha = a)
    // a catchlight, in the open part only: it shrinks away as the eye closes or smiles
    val sp = smooth(.5f, .92f, (T + B - 2 * h) / (2 * ery))
    if (sp > .01f && rx > 1.6f * density) drawCircle(Color.White.copy(alpha = a * .95f), rx * .36f * sp, Offset(cx + rx * .3f - lx * rx * .06f, y - T * .42f - ly * ry * .04f))
}

/** Where a point is, for [DashRig.lookAt]: [target] relative to [from], normalised to -1..1 with distance [reach]. */
fun lookToward(from: Offset, target: Offset, reach: Float): Offset {
    val dx = target.x - from.x; val dy = target.y - from.y
    val d = hypot(dx, dy).coerceAtLeast(1f)
    val k = min(1f, d / reach)
    return Offset(dx / d * k, dy / d * k * .8f)
}

/** A small creature Dash for rows and buttons, feeling [feel]. */
@Composable
fun DashMini(feel: Feel, size: Dp, modifier: Modifier = Modifier, label: String? = "Dash", active: Boolean = true,
             /** A working run's latest [Doing]: he reacts to each step. */
             doing: Doing? = null) {
    val rig = rememberDash(feel = feel, active = active)
    LaunchedEffect(feel) { rig.feel(feel) }
    LaunchedEffect(doing?.n) { if (doing != null && doing.n > 0 && active) rig.react(doing) }
    Box(modifier.size(size)) { Dash(rig, Modifier.size(size), label = label, onPoke = null) }
}

/**
 * Just Dash's head, standing on the bottom edge of its box: the grabber of a sheet (the assistant's panel), his eyes in
 * it and his antenna above, the same head as a frame's at scale [ns] (1 = a frame about 122 dp wide). All of him fits
 * in the box.
 */
@Composable
fun DashNotch(rig: DashRig, modifier: Modifier = Modifier, ns: Float = 1.25f, label: String? = "Dash") {
    val onLight = P.bg.luminance() > .5f
    Canvas(modifier.size(80.dp, 44.dp).let { m -> if (label == null) m.clearAndSetSemantics {} else m.semantics { contentDescription = label } }) {
        rig.tick.longValue
        val n = ns * density
        val base = size.height
        val st = (rig.stretch.x + rig.gSt).coerceIn(.82f, 1.2f)
        val breath = if (rig.calm) 0f else sin(rig.breath) * rig.depth.x
        val hw = 14f * n * (1f + .3f * (rig.eyes.x - 1f))
        val hh = n * (11f * (1f + (st - 1f) * 1.6f)).coerceIn(8f, 13f) + max(0f, rig.hop.x) * .45f * n + breath * .35f * n
        val fil = min(3.6f * n, hh * .4f)
        val room = max(0f, size.width / 2 - hw - fil - 2f * density)
        val hx = size.width / 2 + (rig.lean.x * hw * 3f).coerceIn(-room, room)
        miscP.reset(); miscP.moveTo(hx - hw - fil - 1f, base)
        headTo(miscP, hx, base, hw, hh, fil)
        miscP.lineTo(hx + hw + fil + 1f, base); miscP.close()
        // the antenna, behind the head
        val tipR = 2.7f * n * (1f + .15f * rig.call.x.coerceIn(0f, 1f))
        val tip = antenna(hx, base - hh + min(1.5f * density, hh * .3f), rig.length.x * 8f * n, 1.9f * n, tipR, rig.antenna.x, rig.curl.x,
            rig.pale.x.coerceIn(0f, 1f), rig.glow.x, 2.4f, onLight, 1f)
        val cl = rig.call.x.coerceIn(0f, 1f)
        if (cl > .01f) drawCall(rig, tip.x, tip.y, tipR, 6f * n, 1.5f * density, if (onLight) DashColors.Mark else DARK_LIT, cl)
        drawPath(miscP, Brush.verticalGradient(0f to DashColors.BodyTop, 1f to DashColors.Body, startY = base - hh, endY = base))
        // eyes
        val lx = rig.lookX.x.coerceIn(-1.2f, 1.2f); val ly = rig.lookY.x.coerceIn(-1.2f, 1.2f)
        val rx = 3.5f * n * rig.eyes.x; val ry = rx * 1.32f
        val sep = hw * .46f * (1f - .1f * abs(lx))
        val ey = base - hh * .5f
        val ox = lx * hw * .16f; val oy = ly * hh * .1f
        val tiltE = rig.tilt.x * rx * 1.4f
        val line = max(1.1f * density, rx * .42f)
        drawEye(rig, hx - sep + ox, ey + oy - tiltE, rx * rig.eyeL.x * (1f - .14f * max(0f, -lx)), ry * rig.eyeL.x, lx, ly, line, 1f)
        drawEye(rig, hx + sep + ox, ey + oy + tiltE, rx * rig.eyeR.x * (1f - .14f * max(0f, lx)), ry * rig.eyeR.x, lx, ly, line, 1f)
    }
}
