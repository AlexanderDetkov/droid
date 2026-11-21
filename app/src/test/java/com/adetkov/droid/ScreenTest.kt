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

    /** When a shot asked for at [asked] is due, on a display that draws a frame at each of [frames]. */
    private fun dueAt(asked: Long, frames: Set<Long> = emptySet(), settle: VdHelper.Settle = VdHelper.Settle()): Long {
        var t = 0L
        while (true) {
            if (t in frames) settle.frame(t)
            if (t >= asked) {
                settle.ask(t)
                if (settle.due(t)) return t.also { settle.done() }
            }
            t += 10
        }
    }

    @Test fun aStillScreenIsShotOnceATapHasHadTimeToShow() {
        assertEquals(10_400, dueAt(10_000))
        assertEquals(20_700, dueAt(20_000, setOf(20_100L, 20_300L)))  // the tap's change, then still
    }

    @Test fun aBlinkingCursorStillSettles() {
        assertEquals(5_400, dueAt(5_000, (1..20).map { 5_000L + it * 500 }.toSet()))
    }

    @Test fun aScreenThatNeverStopsIsShotAnyway() {
        val s = VdHelper.Settle()
        val t = dueAt(3_000, (0..400).map { 3_000L + it * 20 }.toSet(), s)
        assertEquals(3_000 + VdHelper.Settle.MOST, t)
        assertFalse(s.settled(t))  // said to be still changing
    }
}
