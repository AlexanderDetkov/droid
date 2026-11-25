package com.adetkov.droid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

/** Dash never jumps: whatever changes at once moves a target, and the numbers he's drawn from get there smoothly. */
class DashTest {
    private val dt = 1 / 60f
    private fun numbers(r: DashRig) = listOf(r.open, r.smile, r.eyes, r.eyeL, r.eyeR, r.antenna, r.curl, r.length, r.lookX, r.lookY,
        r.tilt, r.lean, r.squash, r.stretch, r.glow, r.lit, r.call, r.depth, r.blush)

    @Test fun reducedMotionEasesEveryChangeInsteadOfJumping() {
        val r = DashRig(calm = true)
        repeat(30) { r.step(dt) }
        for (f in Feel.entries + Feel.IDLE) {
            r.feel(f)
            val from = numbers(r).map { it.x }
            r.step(dt)
            // a jump would cover the whole way in the first frame; an ease starts slowly
            for ((i, sp) in numbers(r).withIndex()) {
                val way = abs(sp.t - from[i])
                if (way > .02f) assertTrue("$f: number $i moved ${abs(sp.x - from[i])} of $way in one frame", abs(sp.x - from[i]) < .5f * way)
            }
            repeat(90) { r.step(dt) }
            // and it gets there, and rests (so a still Dash isn't redrawn)
            for ((i, sp) in numbers(r).withIndex()) assertTrue("$f: number $i not there", abs(sp.x - sp.t) < .01f)
        }
        repeat(120) { r.step(dt) }
        assertTrue("a calm Dash at rest still moves", !r.step(dt))
    }

    @Test fun aCalmDashAtRestSleepsUntilSomethingChangesHim() {
        val r = DashRig(calm = true)
        fun woken() = r.stirred.tryReceive().isSuccess.also { while (r.stirred.tryReceive().isSuccess) Unit }
        repeat(120) { r.step(dt) }
        woken()
        assertTrue(r.resting()); assertFalse(r.step(dt))
        r.feel(Feel.HAPPY); assertTrue("a new feeling wakes him", woken())
        repeat(120) { r.step(dt) }; woken()
        r.present.t = .5f; assertTrue("a target set from outside wakes him", woken())
        r.present.t = .5f; assertFalse("the same target again doesn't", woken())
        repeat(120) { r.step(dt) }
        r.poke(); assertTrue("a poke wakes him", woken())
        repeat(30) { r.step(dt) }
        assertFalse("not at rest until the poke's moment of happy is over", r.resting())
        repeat(120) { r.step(dt) }
        assertTrue(r.resting())
        r.feel(Feel.LISTENING); r.step(dt)
        assertFalse("listening follows the voice", r.resting())
    }

    @Test fun reducedMotionDoesNotHopOrBounce() {
        val r = DashRig(calm = true, Feel.CELEBRATE)
        var high = 0f
        repeat(300) { r.step(dt); high = maxOf(high, r.hop.x) }
        r.say(); r.nod(); r.poke(); r.beat(Feel.SURPRISED, .5f); r.feel(Feel.EXCITED); r.feel(Feel.NEEDS_YOU)
        repeat(600) { r.step(dt); high = maxOf(high, r.hop.x) }
        assertTrue("hopped $high dp", high < .001f)
    }

    @Test fun everythingMovesSmoothlyThroughInterruptions() {
        // every feeling, interrupted at odd moments by pokes, nods, words, taps, a start: no number he is drawn from jumps
        // (covers much of its way in one frame), none jitters (turns back and forth), and his lift stays gentle
        val r = DashRig(calm = false)
        val script = listOf(Feel.NEEDS_YOU, Feel.READING, Feel.NEEDS_YOU, Feel.HAPPY, Feel.EXCITED, Feel.WAITING, Feel.ASLEEP, Feel.CELEBRATE,
            Feel.NEEDS_YOU, Feel.IDLE, Feel.SLEEPY, Feel.SURPRISED, Feel.NEEDS_YOU, Feel.LISTENING, Feel.TYPING, Feel.LOOKING, Feel.SCROLLING,
            Feel.SEARCHING, Feel.THINKING, Feel.CONFUSED, Feel.DRIVING, Feel.WORKING)
        val all = numbers(r)
        val looks = setOf(8, 9)   // where he looks moves quickest (a glance), the rest are slower
        var prev = all.map { it.x }; val sign = IntArray(all.size); val turns = IntArray(all.size)
        var lift = r.hop.x; var liftV = 0f
        var worstLift = 0f; var frames = 0
        for ((i, f) in script.withIndex()) {
            r.feel(f)
            repeat(40 + i * 7) { k ->
                when ((i * 31 + k) % 97) { 3 -> r.poke(); 17 -> r.nod(); 29 -> r.say(); 41 -> r.tap(.7f, .3f); 59 -> r.beat(Feel.SURPRISED, .55f) }
                r.step(dt); frames++
                val now = all.map { it.x }
                for (j in all.indices) {
                    val v = now[j] - prev[j]
                    val most = if (j in looks) .3f else .12f
                    assertTrue("$f: number $j jumped $v in a frame", abs(v) < most)
                    val sg = if (v > 2e-3f) 1 else if (v < -2e-3f) -1 else 0
                    if (sg != 0 && sign[j] != 0 && sg != sign[j]) turns[j]++
                    if (sg != 0) sign[j] = sg
                }
                prev = now
                val lv = r.hop.x - lift
                worstLift = max(worstLift, abs(lv - liftV)); liftV = lv; lift = r.hop.x
            }
        }
        val secs = frames * dt
        for (j in all.indices) assertTrue("number $j turned back ${turns[j] / secs} times a second", turns[j] / secs < 2.5f)
        // (the old rig kicked his hops by several dp a frame; a gentle hop changes its speed by a fraction of one)
        assertTrue("his lift kicked by $worstLift dp", worstLift < .35f)
    }

    @Test fun springsAreTheSameAtAnyFrameRate() {
        for (z in floatArrayOf(.45f, .8f, 1f, 1.4f)) {
            val a = Spring(0f, 200f, z, false).apply { t = 1f; v = 3f }
            val b = Spring(0f, 200f, z, false).apply { t = 1f; v = 3f }
            repeat(30) { a.step(1 / 30f) }
            repeat(240) { b.step(1 / 240f) }
            assertTrue("z=$z: ${a.x} vs ${b.x}", abs(a.x - b.x) < 1e-3f && abs(a.v - b.v) < 1e-2f)
        }
    }

    @Test fun aDashMadeInAFeelingIsAlreadyInIt() {
        for (f in Feel.entries) {
            val r = DashRig(calm = false, f)
            // in the pose already (not on his way from idle): the eyes, lids and antenna are at their targets
            assertTrue("$f eyes", abs(r.eyes.x - f.size) < 1e-4f)
            assertTrue("$f lids", abs(r.open.x - f.open) < 1e-4f)
            assertTrue("$f smile", abs(r.smile.x - f.smile) < 1e-4f)
            assertTrue("$f stretch", abs(r.stretch.x - r.stretch.t) < 1e-4f)
            assertTrue("$f no hop", r.hop.x == 0f)
        }
    }

    @Test fun followTakesUpExactlyWhereTheOtherIs() {
        val a = DashRig(calm = false, Feel.NEEDS_YOU)
        repeat(97) { a.step(dt) }
        a.poke()
        repeat(9) { a.step(dt) }
        val b = DashRig(calm = false)
        b.follow(a, feelToo = true)
        assertTrue(b.feel == a.feel)
        for ((x, y) in numbers(a).zip(numbers(b))) assertTrue(x.x == y.x && x.v == y.v)
        assertTrue("hop", a.hop.x == b.hop.x)
        // and both go on the same way: the gesture in progress came along
        a.step(dt); b.step(dt)
        assertTrue("gesture carried over", abs(a.hop.x - b.hop.x) < 1e-5f)
        // and handing back keeps the receiver's own feeling
        val c = DashRig(calm = false, Feel.WORKING)
        c.follow(a, feelToo = false)
        assertTrue(c.feel == Feel.WORKING && c.eyes.x == a.eyes.x)
    }

    @Test fun theAgentNamesFeelingsAndMakesFacesThatStayInRange() {
        assertEquals(Feel.NEEDS_YOU, Feel.of("needs-you"))
        assertEquals(Feel.OOPS, Feel.of(" Oops "))
        assertEquals(null, Feel.of("nope"))
        val f = Feel.face(org.json.JSONObject("""{"smile": 5, "lookX": -0.4, "size": 9, "feel": "curious", "enter": "hop"}"""))
        assertEquals(1f, f.smile, 0f)          // clamped
        assertEquals(-.4f, f.lookX, 1e-6f)
        assertEquals(1.2f, f.size, 1e-6f)      // clamped
        assertEquals(Feel.CURIOUS.antenna, f.antenna, 0f)  // from the named feeling
        assertEquals(Enter.HOP, f.enter)
        assertFalse(f.call)
        // a face never jumps him either
        val r = DashRig(calm = true, Feel.IDLE)
        r.feel(f)
        repeat(120) { r.step(1 / 60f) }
    }
}
