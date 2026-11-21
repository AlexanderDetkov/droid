package com.adetkov.droid

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Droid's helper (Bridge.java) on this machine's sh, with the app's client and the agent's `android` (droid-bridge). */
class BridgeTest {
    private val token = "0123456789abcdef0123456789abcdef"
    private val servers = mutableListOf<ServerSocket>()
    private val dir = Files.createTempDirectory("bridge").toFile()

    @Before fun start() {
        Bridge.token = token.toByteArray()
        Bridge.sh = "/bin/sh"
        Bridge.port = serve { Bridge.serve(it) }
        assertTrue("a command from an earlier test still runs", waitUntil { Bridge.active.get() == 0 })
    }

    @After fun stop() {
        servers.forEach { it.close() }
        dir.deleteRecursively()
    }

    /** A helper on a port of its own, handing each connection to [serve]. */
    private fun serve(serve: (Socket) -> Unit): Int {
        val ss = ServerSocket(0, 50, InetAddress.getLoopbackAddress()).also { servers += it }
        thread(isDaemon = true) {
            try { while (true) { val s = ss.accept(); thread(isDaemon = true) { serve(s) } } } catch (_: IOException) {}
        }
        return ss.localPort
    }

    /** Starts [argv] as the agent's Linux would, with the helper on [port]. */
    private fun agent(vararg argv: String, port: Int = Bridge.port): Process {
        assumeTrue("python3 not installed", try { ProcessBuilder("python3", "--version").start().waitFor() == 0 } catch (_: IOException) { false })
        return ProcessBuilder(*argv).apply {
            environment()["DROID_BRIDGE"] = port.toString()
            environment()["DROID_TOKEN"] = token
        }.start()
    }

    /** The agent's `android COMMAND`. */
    private fun android(command: String, port: Int = Bridge.port) = agent("python3", "-I", DROID_BRIDGE, command, port = port)

    private fun waitUntil(ms: Long = 10_000, ok: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (!ok()) { if (System.currentTimeMillis() > end) return false; Thread.sleep(50) }
        return true
    }

    @Test fun androidPassesStdinOutputAndTheExitCode() {
        val p = android("read x; echo out \$x; echo err >&2; exit 3")
        p.outputStream.use { it.write("in\n".toByteArray()) }
        assertEquals("out in\n", p.inputStream.bufferedReader().readText())
        assertEquals("err\n", p.errorStream.bufferedReader().readText())
        assertEquals(3, p.waitFor())
    }

    @Test fun androidUsesXWithAHelperFromBeforeA() {
        // As a helper before 'a' was: it closes the connection on a request it doesn't know.
        val old = serve { s ->
            try {
                s.use {
                    val input = DataInputStream(s.getInputStream())
                    val out = DataOutputStream(s.getOutputStream())
                    if (!Bridge.handshake(s, input, out)) return@use
                    val type = input.readUnsignedByte()
                    val cmd = String(ByteArray(input.readInt()).also { input.readFully(it) })
                    if (type == 'x'.code || type == 'X'.code) Bridge.run(cmd, type, input, out)
                }
            } catch (_: Exception) {}
        }
        val p = android("read x; echo out \$x; exit 4", old)
        p.outputStream.use { it.write("in\n".toByteArray()) }  // none of it lost on the way
        assertEquals("out in\n", p.inputStream.bufferedReader().readText())
        assertEquals(4, p.waitFor())
    }

    @Test fun androidEndsWhenItsReaderDoes() {
        val sh = agent("/bin/sh", "-c", "python3 -I $DROID_BRIDGE 'while :; do echo y; done' | head -n 1")
        assertEquals("y\n", sh.inputStream.bufferedReader().readText())
        assertTrue(sh.waitFor(10, TimeUnit.SECONDS))
        assertTrue("the command still runs", waitUntil { Bridge.active.get() == 0 })
    }

    @Test fun androidStoppedStopsItsCommand() {
        val p = android("echo started; exec sleep 30")
        assertEquals("started", p.inputStream.bufferedReader().readLine())
        p.destroy()  // SIGTERM
        assertTrue("the command still runs", waitUntil(5_000) { Bridge.active.get() == 0 })
    }

    @Test fun anAppRequestCarriesOnWhenTheAppIsGone() {
        val done = File(dir, "done")
        // Plenty of output after the app is gone: a closed pipe would have ended it at its first write.
        val s = request('X', "sleep 0.5; i=0; while [ \$i -lt 2000 ]; do echo line \$i; i=\$((i+1)); done; touch '$done'")
        s.close()
        assertTrue("an app's command ended with the app", waitUntil { done.exists() })
    }

    @Test fun theAppsClientRunsCommands() {
        val p = BridgeClient(Bridge.port, token).exec("cat; echo err >&2", mergeStderr = false)
        p.outputStream.use { it.write("hi\n".toByteArray()) }
        assertEquals("hi\n", p.inputStream.bufferedReader().readText())
        assertEquals("err\n", p.errorStream.bufferedReader().readText())
        assertEquals(0, p.waitFor())
        assertFalse(p.isAlive)
    }

    /** A raw request, as the app's client sends one, on a socket the test can drop without a word. */
    private fun request(type: Char, cmd: String): Socket {
        val s = Socket(InetAddress.getLoopbackAddress(), Bridge.port)
        val input = DataInputStream(s.getInputStream())
        val out = DataOutputStream(s.getOutputStream())
        out.write(ByteArray(32).also { SecureRandom().nextBytes(it) })
        out.flush()
        input.readFully(ByteArray(32))  // the helper's proof, which the app checks
        val b = ByteArray(32).also { input.readFully(it) }
        out.write(Bridge.hmac("droid-bridge-client", b))
        val payload = cmd.toByteArray()
        out.writeByte(type.code)
        out.writeInt(payload.size)
        out.write(payload)
        out.flush()
        return s
    }
}

private const val DROID_BRIDGE = "src/main/assets/setup/bin/droid-bridge"
