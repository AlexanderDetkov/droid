package com.adetkov.droid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class ScreenTest {
    private val token = "0123456789abcdef0123456789abcdef"

    /** Runs [server] on one connection to a local port, and [client] against it; the server's result. */
    private fun <T> exchange(server: (Socket) -> T, client: (Int) -> Unit): T =
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { ss ->
            val result = CompletableFuture<T>()
            thread { try { ss.accept().use { result.complete(server(it)) } } catch (e: Exception) { result.completeExceptionally(e) } }
            client(ss.localPort)
            result.get(5, TimeUnit.SECONDS)
        }

    @Test fun theAppAndTheHelperEachProveTheyKnowTheToken() {
        val view = VdHelper.View()
        val ok = exchange({ VdHelper.handshake(it, token, view) }) { port ->
            Socket().use { s -> openScreen(s, port, token, 300, 2); s.getInputStream().read() }  // until the server is done
        }
        assertTrue(ok)
        assertEquals(300, view.width)
        assertEquals(500, view.interval)
    }

    @Test fun theAppWontTakeAStrangerForTheHelper() {
        var refused = false
        exchange({ s -> s.getOutputStream().write(ByteArray(64)); DataInputStream(s.getInputStream()).readFully(ByteArray(32)) }) { port ->
            try { Socket().use { openScreen(it, port, token, 0, 10) } } catch (_: IOException) { refused = true }
        }
        assertTrue(refused)
    }

    @Test fun theHelperWontTakeAStrangerForTheApp() {
        val ok = exchange({ VdHelper.handshake(it, token, VdHelper.View()) }) { port ->
            Socket().use { s ->
                // A client that heard the server's proof but can't make its own (a wrong token).
                s.connect(java.net.InetSocketAddress("127.0.0.1", port))
                s.getOutputStream().write(ByteArray(32))
                val b = ByteArray(64).also(DataInputStream(s.getInputStream())::readFully).copyOfRange(32, 64)
                s.getOutputStream().write(screenHmac("not the token", "droid-screen-client", b) + "v 0 10\n".toByteArray())
                s.getInputStream().read()
            }
        }
        assertFalse(ok)
    }

    @Test fun aViewIsSetOnlyByAViewLine() {
        val v = VdHelper.View()
        assertTrue(v.set("v 240 1"))
        assertEquals(240, v.width)
        assertEquals(1000, v.interval)
        assertTrue(v.set("v 0 50"))  // full size, at most 10 a second
        assertEquals(100, v.interval)
        assertFalse(v.set("d 10 10"))
        assertFalse(v.set("v x 2"))
    }

    @Test fun theHelperOutlivesNoAgent() {
        assumeTrue(File("/proc/self/stat").exists())
        val agent = ProcessBuilder("sh", "-c", "echo $$; exec sleep 30").start()
        val lifeline = VdHelper.Lifeline(agent.inputStream.bufferedReader().readLine())
        assertFalse(lifeline.gone())
        agent.destroy()
        agent.waitFor()
        assertTrue(lifeline.gone())
        assertFalse(VdHelper.Lifeline("").gone())  // an older vd-start.sh: none
    }

    /** When a shot asked for at [asked] is due, on a display drawn as [frames] (uptime to whether it changed much). */
    private fun dueAt(asked: Long, frames: Map<Long, Boolean> = emptyMap(), settle: VdHelper.Settle = VdHelper.Settle(),
                      screens: Map<Long, String> = emptyMap()): Long {
        var t = 0L
        while (true) {
            screens[t]?.let { settle.screen(it, t) }
            frames[t]?.let { settle.frame(t, it) }
            if (t >= asked) {
                settle.ask(t)
                if (settle.due(t)) return t.also { settle.done() }
            }
            t += 10
        }
    }

    @Test fun aStillScreenIsShotOnceATapHasHadTimeToShow() {
        val s = VdHelper.Settle()
        s.screen("clock/.Main", 0)
        assertEquals(10_500, dueAt(10_000, settle = s, screens = mapOf(0L to "clock/.Main")))
        // the same screen again: a tap's change, a frame at a time and then still
        assertEquals(21_100, dueAt(20_000, mapOf(20_100L to true, 20_400L to true, 20_600L to true), s))
    }

    @Test fun aBlinkingCursorDoesntHoldAShotBack() {
        val s = VdHelper.Settle().apply { screen("calc/.Main", 0); ask(0); done() }  // looked at it before
        val blinks = (1..20).associate { 5_000L + it * 500 to false }  // small changes, one frame at a time
        assertEquals(5_500, dueAt(5_000, blinks, s))
    }

    @Test fun anAnimationIsWaitedOutButNotForever() {
        val s = VdHelper.Settle().apply { screen("a/.Main", 0); ask(0); done() }
        val anim = (0..40).associate { 1_000L + it * 20 to false }  // a burst of small frames, 1000-1800
        assertEquals(2_300, dueAt(1_000, anim, s))
        val spinner = (0..400).associate { 3_000L + it * 20 to false }  // never stops
        val t = dueAt(3_000, spinner, s)
        assertEquals(3_000 + VdHelper.Settle.MOST, t)
        assertFalse(s.settled(t))  // said to be still changing
    }

    @Test fun aScreenJustOpenedGetsLongerToDraw() {
        val s = VdHelper.Settle().apply { screen("home/.Main", 0); ask(0); done() }
        // a cold start: the splash at 1050, nothing while the app loads, then its page at 1900
        val start = mapOf(1_050L to true, 1_900L to true, 1_920L to false)
        val t = dueAt(1_010, start, s, mapOf(1_000L to "mail/.Inbox"))
        assertEquals(1_920 + VdHelper.Settle.QUIET_NEW, t)
        // that screen again later: back to the short wait
        assertEquals(10_500, dueAt(10_000, settle = s))
    }

    @Test fun aScreenThatOpenedWhileTheAgentWasBusyIsntWaitedForAgain() {
        val s = VdHelper.Settle().apply { screen("home/.Main", 0); ask(0); done() }
        val t = dueAt(8_000, mapOf(1_050L to true, 1_900L to true), s, mapOf(1_000L to "mail/.Inbox"))
        assertEquals(8_500, t)
    }

    @Test fun aChangeIsMoreThanASliverOfTheScreen() {
        val before = IntArray(90 * 200)
        assertTrue(VdHelper.differs(null, before))  // a first frame
        val cursor = before.copyOf().also { for (y in 0 until 6) it[y * 90 + 10] = -1 }
        assertFalse(VdHelper.differs(before, cursor))
        val page = before.copyOf().also { for (i in 0 until 2_000) it[i] = -1 }
        assertTrue(VdHelper.differs(before, page))
    }
}
