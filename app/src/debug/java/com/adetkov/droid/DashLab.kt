package com.adetkov.droid

import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import kotlinx.coroutines.delay
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.scale
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * Debug builds: Dash at every size and feeling, and the chat's morphs on buttons, to look at them closely.
 * `adb shell am start -n com.adetkov.droid/.DashLab [--es page feels|morph|work|after]`: work cycles through what an
 * agent does (each pose and its reaction), after shows what a finished turn leaves (your turn, failed) and the
 * notification pictures. export writes his motion in every state as PNG frames (and a still of each) to the app's
 * external files (export/, a `.done` file when finished), for design pages; `--ef scale 2` renders them twice as big,
 * `--es only yourturn,morph` just those clips.
 */
class DashLab : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val page = intent.getStringExtra("page") ?: "morph"
        setContent { DroidTheme { Column(Modifier.fillMaxSize().background(P.bg).statusBarsPadding()) { when (page) { "feels" -> Feels(); "work" -> WorkPage(); "after" -> After(); "export" -> Export(intent.getFloatExtra("scale", 1f), intent.getStringExtra("only")?.split(",")?.toSet()); "smooth" -> Smooth(intent.getStringExtra("only")?.split(",")?.toSet()); else -> Morph() } } } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Feels() {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (s in listOf(24, 28, 40, 56, 84, 112)) DashMini(Feel.IDLE, s.dp)
        }
        FlowRow(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (f in Feel.entries) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                DashMini(f, 70.dp)
                Text(f.name.lowercase(), style = MaterialTheme.typography.labelSmall, color = P.muted)
            }
        }
    }
}

private fun labImage(ctx: android.content.Context) = File(ctx.filesDir, "fake-screen/3-com.google.android.gm.jpg").takeIf { it.exists() }
    ?.let { BitmapFactory.decodeFile(it.path) }?.let { it.scale(300, 300 * it.height / it.width).asImageBitmap() }

/** Each kind of step in turn, as the chat's creature, a big Dash and the chat's frame. */
@Composable
private fun WorkPage() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val img = remember { labImage(ctx) }
    val order = listOf(Work.THINK to false, Work.OPEN to false, Work.LOOK to false, Work.TAP to false, Work.TYPE to false,
        Work.LOOK to true, Work.SCROLL to false, Work.WAIT to false, Work.WEB to false, Work.READ to false, Work.WRITE to false)
    var i by remember { mutableIntStateOf(0) }
    val (work, said) = order[i % order.size]
    val doing = Doing(work, i, said)
    LaunchedEffect(Unit) { while (true) { delay(3200); i++ } }
    val rigs = listOf(rememberDash("a"), rememberDash("b"), rememberDash("c") { it.frame.snap(1f) })
    for (r in rigs) LaunchedEffect(r, i) { if (i > 0) r.react(doing); r.feel(work.feel()) }
    LaunchedEffect(i) { if (work == Work.TAP) repeat(3) { rigs[2].tap(listOf(.78f, .3f, .55f)[it], listOf(.16f, .42f, .7f)[it]); delay(800) } }
    Column(Modifier.padding(20.dp)) {
        Text((if (said) "says something, then " else "") + work.name.lowercase() + " → " + work.feel().name.lowercase(),
            style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("work_label"))
        Row(Modifier.padding(top = 24.dp), verticalAlignment = Alignment.Top) {
            DashSlot(rigs[0], null, DpSize(32.dp, 44.dp), 44.dp, 2.2f, gap = 14.dp)
            Dash(rigs[1], Modifier.size(112.dp, 124.dp))
            Box(Modifier.padding(start = 16.dp)) { DashSlot(rigs[2], img, DpSize(32.dp, 44.dp), 108.dp, img?.let { it.height.toFloat() / it.width } ?: 2.2f) }
        }
    }
}

/** What a finished turn leaves: your turn (creature, frame), couldn't finish; and the notification pictures. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun After() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val img = remember { labImage(ctx) }
    val asks = rememberDash("asks") { it.feel(Feel.NEEDS_YOU) }
    val framed = rememberDash("framed") { it.frame.snap(1f); it.feel(Feel.NEEDS_YOU); it.drain.snap(.3f) }
    val failed = rememberDash("failed") { it.feel(Feel.CONFUSED) }
    Column(Modifier.padding(20.dp)) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column { DashSlot(asks, null, DpSize(32.dp, 44.dp), 44.dp, 2.2f); Text("asks", style = MaterialTheme.typography.labelSmall) }
            Column { DashSlot(framed, img, DpSize(32.dp, 44.dp), 44.dp, img?.let { it.height.toFloat() / it.width } ?: 2.2f); Text("held, asks", style = MaterialTheme.typography.labelSmall) }
            Column { DashSlot(failed, null, DpSize(32.dp, 44.dp), 44.dp, 2.2f); Text("failed", style = MaterialTheme.typography.labelSmall) }
        }
        val d = androidx.compose.ui.platform.LocalDensity.current.density
        val light = P.bg.luminance() > .5f
        FlowRow(Modifier.padding(top = 28.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (f in listOf(Feel.THINKING, Feel.LOOKING, Feel.TYPING, Feel.WORKING, Feel.NEEDS_YOU, Feel.HAPPY, Feel.CONFUSED, Feel.IDLE)) {
                val b = remember(f, light) { dashPortrait(f, (64 * d).toInt(), d, light).asImageBitmap() }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    androidx.compose.foundation.Image(b, null, Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(P.surface))
                    Text(f.name.lowercase(), style = MaterialTheme.typography.labelSmall, color = P.muted)
                }
            }
        }
    }
}

@Composable
private fun Morph() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val img = remember { File(ctx.filesDir, "fake-screen/3-com.google.android.gm.jpg").takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
        // the chat's feed is 300 px wide
        ?.let { it.scale(300, 300 * it.height / it.width).asImageBitmap() } }
    var big by remember { mutableStateOf(false) }
    var released by remember { mutableStateOf(false) }
    val rig = rememberDash(Unit, Feel.THINKING)
    val width by animateDpAsState(if (big) 108.dp else 44.dp, spring(dampingRatio = .82f, stiffness = 320f), label = "w")
    fun go(frame: Float, present: Float, wide: Boolean, feel: Feel) { released = false; big = wide; rig.frame.t = frame; rig.present.t = present; rig.feel(feel) }
    Column(Modifier.padding(20.dp)) {
        Row(Modifier.padding(bottom = 28.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((label, act) in listOf<Pair<String, () -> Unit>>(
                "creature" to { go(0f, 1f, false, Feel.WORKING) },
                "frame" to { go(1f, 1f, true, Feel.WORKING) },
                "held" to { go(1f, 1f, false, Feel.HAPPY) },
                "letgo" to { go(1f, 0f, false, Feel.IDLE) },
                "leave" to { go(0f, 0f, false, Feel.HAPPY) },
                "drain" to { rig.drain.t = if (rig.drain.t > .5f) 0f else .62f },
                "release" to { released = true },
            )) Text(label, Modifier.clip(RoundedCornerShape(50)).background(P.surface).clickable(onClick = act).padding(horizontal = 10.dp, vertical = 8.dp).testTag(label),
                style = MaterialTheme.typography.labelMedium)
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            if (released && img != null) ScreenWindow(img, 44.dp, Modifier.padding(end = 14.dp))
            else DashSlot(rig, img, DpSize(32.dp, 44.dp), width, img?.let { it.height.toFloat() / it.width } ?: 2.2f, gap = 14.dp)
            Column(Modifier.weight(1f)) {
                Text("Working  12s", style = MaterialTheme.typography.bodyMedium, color = P.muted, modifier = Modifier.padding(vertical = 4.dp))
                Text("Searching Gmail for Lisbon", style = MaterialTheme.typography.bodyMedium, color = P.faint)
            }
        }
    }
}

/** A recording: [feel] for [secs] after a warm-up; [events] (seconds from the start) poke the rig as the app would. */
private class Clip(val name: String, val feel: Feel, val secs: Float = 5f, val frame: Boolean = false, val onLight: Boolean = false,
                   val setup: (DashRig) -> Unit = {}, val events: List<Pair<Float, (DashRig) -> Unit>> = emptyList())

private fun every(start: Float, step: Float, until: Float, act: (DashRig) -> Unit) =
    generateSequence(start) { it + step }.takeWhile { it < until }.map { it to act }.toList()

private val CLIPS = listOf(
    Clip("idle", Feel.IDLE),
    Clip("poke", Feel.IDLE, events = listOf(1.2f to { r: DashRig -> r.poke() })),
    Clip("thinking", Feel.THINKING),
    Clip("looking", Feel.LOOKING),
    Clip("opening", Feel.LOOKING, events = listOf(.6f to { r: DashRig -> r.beat(Feel.SURPRISED, .55f) }, 3.1f to { r: DashRig -> r.beat(Feel.SURPRISED, .55f) })),
    Clip("typing", Feel.TYPING),
    Clip("scrolling", Feel.SCROLLING, secs = 4.6f),
    Clip("waiting", Feel.WAITING),
    Clip("reading", Feel.READING),
    Clip("searching", Feel.SEARCHING, secs = 7.4f),
    Clip("writing", Feel.FOCUSED),
    Clip("working", Feel.WORKING, events = every(.4f, 1.25f, 5f) { it.nod() }),
    Clip("saying", Feel.WORKING, events = listOf(.7f to { r: DashRig -> r.say() }, 3.2f to { r: DashRig -> r.say() })),
    Clip("yourturn", Feel.NEEDS_YOU),
    Clip("yourturn-light", Feel.NEEDS_YOU, onLight = true),
    Clip("failed", Feel.CONFUSED),
    Clip("oops", Feel.CONFUSED, events = listOf(.5f to { r: DashRig -> r.beat(Feel.OOPS, 1.6f) })),
    Clip("done", Feel.HAPPY, secs = 4.5f),
    Clip("listening", Feel.LISTENING, setup = { r -> r.level = { val t = r.time; (abs(sin(t * 7.3f)) * (.55f + .45f * sin(t * 2.1f)) * (if (sin(t * .9f) > -.4f) 1f else .1f)).coerceIn(0f, 1f) } }),
    Clip("sleepy", Feel.SLEEPY, secs = 7.2f),
    Clip("morph", Feel.WORKING, secs = 10f, frame = true, setup = { it.selfTaps = true }, events = listOf(
        .9f to { r: DashRig -> r.frame.t = 1f },
        5.4f to { r: DashRig -> r.feel(Feel.NEEDS_YOU); r.drain.t = .25f },
        8.3f to { r: DashRig -> r.frame.t = 0f; r.feel(Feel.HAPPY); r.drain.t = 0f },
        9.4f to { r: DashRig -> r.feel(Feel.WORKING) })),
    Clip("frame-working", Feel.WORKING, secs = 6f, frame = true, setup = { it.frame.snap(1f); it.selfTaps = true }),
    Clip("frame-yourturn", Feel.NEEDS_YOU, frame = true, setup = { it.frame.snap(1f); it.drain.snap(.3f) }),
    Clip("frame-yourturn-light", Feel.NEEDS_YOU, frame = true, onLight = true, setup = { it.frame.snap(1f); it.drain.snap(.3f) }),
    Clip("frame-driving", Feel.DRIVING, secs = 4f, frame = true, setup = { it.frame.snap(1f); it.pale.snap(1f) }),
    // his head (the notch) in each working pose
    Clip("frame-thinking", Feel.THINKING, frame = true, setup = { it.frame.snap(1f) }),
    Clip("frame-looking", Feel.LOOKING, frame = true, setup = { it.frame.snap(1f) }),
    Clip("frame-typing", Feel.TYPING, frame = true, setup = { it.frame.snap(1f) }),
    Clip("frame-scrolling", Feel.SCROLLING, secs = 4.6f, frame = true, setup = { it.frame.snap(1f) }),
    Clip("frame-waiting", Feel.WAITING, frame = true, setup = { it.frame.snap(1f) }),
    Clip("frame-searching", Feel.SEARCHING, secs = 7.4f, frame = true, setup = { it.frame.snap(1f) }),
    Clip("frame-sleepy", Feel.SLEEPY, frame = true, setup = { it.frame.snap(1f); it.drain.snap(.9f) }),
    Clip("frame-done", Feel.HAPPY, secs = 4.5f, frame = true, setup = { it.frame.snap(1f) }),
)

@Composable
private fun Export(k: Float, only: Set<String>?) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var status by remember { mutableStateOf("exporting") }
    LaunchedEffect(Unit) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { exportAll(ctx, k, only) { status = it } } }
    Text(status, Modifier.padding(20.dp).testTag("export_status"))
}

private fun exportAll(ctx: android.content.Context, k: Float, only: Set<String>?, say: (String) -> Unit) {
    val dir = File(ctx.getExternalFilesDir(null), "export").apply { deleteRecursively(); mkdirs() }
    val shot = File(ctx.filesDir, "fake-screen/3-com.google.android.gm.jpg").takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
    val sw = (330 * k).toInt()
    val img = shot?.scale(sw, sw * shot.height / shot.width)?.asImageBitmap()
    val fps = 30; val dt = 1f / fps
    // a big Dash: his body ~200 px, drawn as a 66 dp body (the app's big Dash), so hops and strokes keep their proportions
    val S = (480 * k).toInt(); val w0 = 192f * k; val d = w0 / 66f
    val u = w0 / .74f; val bottom = S * .76f + .08f * u
    val home = Rect(S / 2f - u / 2, bottom - 1.38f * u, S / 2f + u / 2, bottom)
    // a frame: the screen 330 px wide with room for his notch and hook above; the creature the chat's 32 x 44 dp
    val FW = (420 * k).toInt(); val top = 30f * k + 40f * d; val sh = img?.height ?: 700
    val FH = (top + sh + 30f * k).toInt()
    val screen = Rect((FW - sw) / 2f, top, (FW + sw) / 2f, top + sh)
    val cw = 32f * d; val ch = 44f * d
    val fhome = Rect(FW / 2f - cw / 2, top - 18f * d, FW / 2f + cw / 2, top - 18f * d + ch)
    fun render(rig: DashRig, c: Clip, out: File) {
        val (w, h) = if (c.frame) FW to FH else S to S
        val bmp = ImageBitmap(w, h)
        CanvasDrawScope().draw(Density(d), LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(bmp), Size(w.toFloat(), h.toFloat())) {
            if (c.frame) drawDash(rig, img, screen, 3f * d, true, home = fhome, onLight = c.onLight)
            else drawDash(rig, null, null, 3f * d, true, home = home, onLight = c.onLight)
        }
        out.outputStream().use { bmp.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
    val clips = CLIPS.filter { only == null || it.name in only }
    for ((i, c) in clips.withIndex()) {
        say("${c.name} (${i + 1}/${clips.size})")
        // the still: as reduced motion shows him
        render(DashRig(calm = true, c.feel).also { c.setup(it); it.settle() }, c, File(dir, "still-${c.name}.png"))
        val rig = DashRig(calm = false, c.feel).also { c.setup(it); it.settle() }
        repeat(36) { rig.step(dt) }           // a warm-up: breathing and blinking under way
        val sub = File(dir, c.name).apply { mkdirs() }
        val pending = c.events.sortedBy { it.first }.toMutableList()
        val n = (c.secs * fps).toInt()
        for (k in 0 until n) {
            val t = k * dt
            while (pending.isNotEmpty() && pending[0].first <= t) pending.removeAt(0).second(rig)
            rig.step(dt)
            render(rig, c, File(sub, "%04d.png".format(k)))
        }
    }
    // the chat's size, still: how each state reads at 32 x 44 dp (at 3x)
    val small = listOf(Feel.IDLE, Feel.THINKING, Feel.LOOKING, Feel.TYPING, Feel.SCROLLING, Feel.WAITING, Feel.READING, Feel.SEARCHING,
        Feel.FOCUSED, Feel.WORKING, Feel.NEEDS_YOU, Feel.CONFUSED, Feel.HAPPY, Feel.LISTENING, Feel.SLEEPY)
    for (f in small) {
        val bmp = ImageBitmap(150, 170)
        val rig = DashRig(calm = true, f).also { it.settle() }
        CanvasDrawScope().draw(Density(3f), LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(bmp), Size(150f, 170f)) {
            drawDash(rig, null, null, 9f, true, home = Rect(75f - 48f, 20f, 75f + 48f, 20f + 132f))
        }
        File(dir, "small-${f.name.lowercase()}.png").outputStream().use { bmp.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
    File(dir, ".done").writeText("ok")
    say("done")
}

// ------------------------------------------------------------------------------------------------------------------
// Smoothness: `--es page smooth [--es only feels,morph]`. Every scene is drawn at 60 fps with animations on and with
// reduced motion, at a big size and a row's size; each frame is compared with the one before. A jump is a frame that
// changes far more than the frames on either side of it. Writes smooth/report.csv (mode, size, scene, frame, change)
// and, for the worst jumps, the three frames around them; `.done` when finished.
// ------------------------------------------------------------------------------------------------------------------

private fun chain(feels: List<Feel>, hold: Float, from: Float = .4f) = feels.mapIndexed { i, f -> from + i * hold to { r: DashRig -> r.feel(f) } }

private val SCENES = listOf(
    Clip("feels", Feel.IDLE, secs = Feel.entries.size * 1.1f + 1f, events = chain(Feel.entries, 1.1f)),
    Clip("feels-back", Feel.CELEBRATE, secs = Feel.entries.size * 1.1f + 1f, events = chain(Feel.entries.reversed(), 1.1f)),
    // quick changes: each new feeling lands while the last is still on its way
    Clip("feels-quick", Feel.IDLE, secs = Feel.entries.size * .35f + 1.5f, events = chain(Feel.entries.shuffled(kotlin.random.Random(7)), .35f)),
    // a call cut short at different points
    Clip("call-cut", Feel.IDLE, secs = 13f, events = listOf(.3f to Feel.NEEDS_YOU, 1.2f to Feel.READING, 2.4f to Feel.NEEDS_YOU, 3.6f to Feel.LISTENING,
        4.8f to Feel.NEEDS_YOU, 5.9f to Feel.THINKING, 7.2f to Feel.NEEDS_YOU, 8.5f to Feel.IDLE, 9.6f to Feel.NEEDS_YOU, 10.4f to Feel.SLEEPY, 11.6f to Feel.IDLE)
        .map { (t, f) -> t to { r: DashRig -> r.feel(f) } }),
    Clip("yourturn", Feel.NEEDS_YOU, secs = 12f),
    Clip("happy", Feel.HAPPY, secs = 3f),
    Clip("excited", Feel.EXCITED, secs = 3f),
    Clip("celebrate", Feel.CELEBRATE, secs = 3f),
    Clip("sleepy", Feel.SLEEPY, secs = 5f, events = listOf(2.5f to { r: DashRig -> r.feel(Feel.ASLEEP) }, 4f to { r: DashRig -> r.feel(Feel.IDLE) })),
    Clip("reactions", Feel.WORKING, secs = 7f, events = listOf(.4f to { r: DashRig -> r.poke() }, 1.3f to { r: DashRig -> r.say() }, 2.4f to { r: DashRig -> r.nod() },
        3.0f to { r: DashRig -> r.beat(Feel.SURPRISED, .55f) }, 4.0f to { r: DashRig -> r.beat(Feel.OOPS, 1.1f) }, 5.6f to { r: DashRig -> r.poke() },
        5.8f to { r: DashRig -> r.poke() })),
    Clip("work", Feel.THINKING, secs = 12f, events = chain(listOf(Feel.LOOKING, Feel.TYPING, Feel.SCROLLING, Feel.WAITING, Feel.SEARCHING, Feel.FOCUSED, Feel.WORKING, Feel.READING), 1.4f)),
    Clip("leave", Feel.HAPPY, secs = 4f, events = listOf(.6f to { r: DashRig -> r.present.t = 0f }, 2.2f to { r: DashRig -> r.present.t = 1f })),
    Clip("morph", Feel.WORKING, secs = 11f, frame = true, events = listOf(
        .5f to { r: DashRig -> r.frame.t = 1f }, 2.5f to { r: DashRig -> r.feel(Feel.NEEDS_YOU) }, 4.3f to { r: DashRig -> r.frame.t = 0f },
        5.6f to { r: DashRig -> r.feel(Feel.HAPPY) }, 6.4f to { r: DashRig -> r.frame.t = 1f }, 7.0f to { r: DashRig -> r.frame.t = 0f },
        8.4f to { r: DashRig -> r.feel(Feel.CONFUSED); r.frame.t = 1f }, 9.6f to { r: DashRig -> r.present.t = 0f })),
    Clip("frame-feels", Feel.IDLE, secs = Feel.entries.size * 1.1f + 1f, frame = true, setup = { it.frame.snap(1f) }, events = chain(Feel.entries, 1.1f)),
    Clip("frame-yourturn", Feel.NEEDS_YOU, secs = 8f, frame = true, setup = { it.frame.snap(1f); it.drain.snap(.3f) },
        events = listOf(6.5f to { r: DashRig -> r.feel(Feel.READING) })),
    Clip("frame-taps", Feel.WORKING, secs = 5f, frame = true, setup = { it.frame.snap(1f); it.selfTaps = true }),
    Clip("frame-drive", Feel.WORKING, secs = 5f, frame = true, setup = { it.frame.snap(1f) }, events = listOf(
        .5f to { r: DashRig -> r.pale.t = 1f; r.feel(Feel.DRIVING) }, 2.5f to { r: DashRig -> r.pale.t = 0f; r.feel(Feel.WORKING) },
        3.8f to { r: DashRig -> r.present.t = 0f })),
)

@Composable
private fun Smooth(only: Set<String>?) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var status by remember { mutableStateOf("checking") }
    LaunchedEffect(Unit) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { smoothAll(ctx, only) { status = it } } }
    Text(status, Modifier.padding(20.dp).testTag("smooth_status"))
}

private fun smoothAll(ctx: android.content.Context, only: Set<String>?, say: (String) -> Unit) {
    val dir = File(ctx.getExternalFilesDir(null), "smooth").apply { deleteRecursively(); mkdirs() }
    val fps = 60; val dt = 1f / fps
    val csv = File(dir, "report.csv").bufferedWriter()
    csv.write("mode,size,scene,frame,change\n")
    // sizes: the empty chat's big Dash (a 66 dp body at 2.75x), a drawer row's (28 dp box at 2.75x), and the chat's frame
    data class Size2(val name: String, val w: Int, val h: Int, val d: Float, val home: Rect?, val screen: Rect?)
    val d = 2.75f
    val u = 66f * d / .74f
    val big = Size2("big", 360, 360, d, Rect(180f - u / 2, 330f - 1.38f * u, 180f + u / 2, 330f), null)
    val row = Size2("row", 160, 160, d, Rect(80f - 14f * d, 110f - 28f * d, 80f + 14f * d, 110f), null)
    val sw = 108f * d; val sh = sw * 2.1f
    val frame = Size2("frame", (sw + 80f * d).toInt(), (sh + 70f * d).toInt(), d, Rect(40f * d, 4f * d, 40f * d + 32f * d, 4f * d + 44f * d),
        Rect(40f * d, 40f * d, 40f * d + sw, 40f * d + sh))
    val scenes = SCENES.filter { only == null || it.name in only }
    for ((i, c) in scenes.withIndex()) for (calm in listOf(false, true)) for (sz in if (c.frame) listOf(frame) else listOf(big, row)) {
        val mode = if (calm) "calm" else "anim"
        say("${c.name} $mode ${sz.name} (${i + 1}/${scenes.size})")
        val rig = DashRig(calm, c.feel).also { c.setup(it); it.settle() }
        repeat(30) { rig.step(dt) }
        val bmp = ImageBitmap(sz.w, sz.h); val ab = bmp.asAndroidBitmap()
        val n = sz.w * sz.h
        val px = Array(3) { IntArray(n) }       // the last three frames, oldest first
        val ch = FloatArray((c.secs * fps).toInt())
        val hardAt = IntArray(ch.size)
        var saved = 0
        val pending = c.events.sortedBy { it.first }.toMutableList()
        for (k in ch.indices) {
            val t = k * dt
            while (pending.isNotEmpty() && pending[0].first <= t) pending.removeAt(0).second(rig)
            rig.step(dt)
            ab.eraseColor(0)
            CanvasDrawScope().draw(Density(sz.d), LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(bmp), Size(sz.w.toFloat(), sz.h.toFloat())) {
                drawRect(androidx.compose.ui.graphics.Color(0xFF131319))
                if (sz.screen != null) drawDash(rig, null, sz.screen, 3f * sz.d, true, home = sz.home)
                else drawDash(rig, null, null, 3f * sz.d, true, home = sz.home)
            }
            val a = px[0]; px[0] = px[1]; px[1] = px[2]; px[2] = a
            ab.getPixels(px[2], 0, sz.w, 0, 0, sz.w, sz.h)
            if (k == 0) continue
            var s = 0L
            var hard = 0                // pixels that changed by more than half: what a jump does (an edge moving a whole
                                        // pixel or more); the rasterizer's quarter-pixel steps and a fade's 1/255 steps don't
            val p = px[1]; val q = px[2]
            for (j in 0 until n) {
                val x = p[j]; val y = q[j]
                if (x == y) continue
                val dr = abs((x shr 16 and 255) - (y shr 16 and 255)); val dg = abs((x shr 8 and 255) - (y shr 8 and 255)); val db = abs((x and 255) - (y and 255))
                s += dr + dg + db
                if (max(dr, max(dg, db)) > 128) hard++
            }
            ch[k] = s / 765f           // in pixels fully changed
            hardAt[k] = hard
            csv.write("$mode,${sz.name},${c.name},$k,${"%.1f".format(ch[k])}\n")
            // a jump at k-1: far more change than both neighbours
            if (k >= 2 && saved < 4) {
                val m = ch[k - 1]; val nb = max(ch[k - 2], ch[k])
                if (m > 40f * (sz.d / 2.75f) && m > 3f * nb && hardAt[k - 1] > 12) {
                    saved++
                    for ((o, arr) in listOf(px[0], px[1], px[2]).withIndex()) {
                        val b = android.graphics.Bitmap.createBitmap(arr, sz.w, sz.h, android.graphics.Bitmap.Config.ARGB_8888)
                        File(dir, "${c.name}-$mode-${sz.name}-%04d-$o.png".format(k - 1)).outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    }
                }
            }
        }
    }
    csv.close()
    File(dir, ".done").writeText("ok")
    say("done")
}
