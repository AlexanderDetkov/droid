package com.adetkov.droid

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/** The app's side of [Bridge]: the helper running as the shell user on 127.0.0.1:[port], which knows [token]. */
class BridgeClient(val port: Int, val token: String) {
    /** What a running helper says about itself: the APK it runs (its code's version) and how many commands it runs. */
    data class Ping(val apk: String, val active: Int)

    /** The helper's answer, or null if it isn't answering. */
    fun ping(): Ping? = try {
        connect(2_000).use { (_, input, out) ->
            frame(out, 'p', ByteArray(0))
            if (input.readUnsignedByte() != 'p'.code) return null
            val text = String(ByteArray(input.readInt()).also { input.readFully(it) })
            Ping(Regex("version=(\\S*)").find(text)?.groupValues?.get(1).orEmpty(), Regex("active=(\\d+)").find(text)?.groupValues?.get(1)?.toInt() ?: 0)
        }
    } catch (_: IOException) {
        null
    }

    /** Asks the helper to exit (to start a newer one). */
    fun quit() {
        try { connect(2_000).use { (_, input, out) -> frame(out, 'q', ByteArray(0)); input.read() } } catch (_: IOException) {}
    }

    /**
     * Starts `sh -c [cmd]` as the shell user; with [mergeStderr] its stderr comes on stdout, as redirectErrorStream does.
     * If the app goes away it carries on, as under `su`: its stdin ends and its output is dropped.
     */
    fun exec(cmd: String, mergeStderr: Boolean = true): Process {
        val c = connect(10_000)
        c.socket.soTimeout = 0
        frame(c.out, if (mergeStderr) 'x' else 'X', cmd.toByteArray(Charsets.UTF_8))
        return BridgeProcess(c)
    }

    private class Conn(val socket: Socket, val input: DataInputStream, val out: DataOutputStream) : AutoCloseable {
        operator fun component1() = socket
        operator fun component2() = input
        operator fun component3() = out
        override fun close() = socket.close()
    }

    private fun connect(timeoutMs: Int): Conn {
        val s = Socket()
        try {
            s.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
            s.soTimeout = timeoutMs
            s.tcpNoDelay = true
            val input = DataInputStream(s.getInputStream())
            val out = DataOutputStream(s.getOutputStream())
            val a = ByteArray(32).also { SecureRandom().nextBytes(it) }
            out.write(a)
            out.flush()
            val proof = ByteArray(32).also { input.readFully(it) }
            // Whoever answers on this port must know the token too, or it isn't Droid's helper.
            if (!MessageDigest.isEqual(proof, hmac("droid-bridge-server", a))) throw IOException("Not Droid's helper on port $port")
            val b = ByteArray(32).also { input.readFully(it) }
            out.write(hmac("droid-bridge-client", b))
            out.flush()
            return Conn(s, input, out)
        } catch (e: IOException) {
            s.close()
            throw e
        }
    }

    private fun hmac(label: String, nonce: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(token.toByteArray(Charsets.US_ASCII), "HmacSHA256"))
        update(label.toByteArray(Charsets.US_ASCII))
        doFinal(nonce)
    }

    /** A command running in the helper, as a [Process]: stdin, stdout and stderr travel as frames on one socket. */
    private class BridgeProcess(private val c: Conn) : Process() {
        private val stdout = Chunks()
        private val stderr = Chunks()
        @Volatile private var exit: Int? = null
        private val done = java.util.concurrent.CountDownLatch(1)
        private val stdin = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) = send('i', b.copyOfRange(off, off + len))
            override fun close() = try { send('e', ByteArray(0)) } catch (_: IOException) {}
        }

        init {
            thread(isDaemon = true, name = "bridge-reader") {
                try {
                    while (true) {
                        val type = c.input.readUnsignedByte()
                        val payload = ByteArray(c.input.readInt()).also { c.input.readFully(it) }
                        when (type.toChar()) {
                            'o' -> stdout.add(payload)
                            'r' -> stderr.add(payload)
                            'x' -> {
                                exit = ((payload[0].toInt() and 0xff) shl 24) or ((payload[1].toInt() and 0xff) shl 16) or
                                    ((payload[2].toInt() and 0xff) shl 8) or (payload[3].toInt() and 0xff)
                                break
                            }
                        }
                    }
                } catch (_: IOException) {
                    // the helper or the connection died
                } finally {
                    if (exit == null) exit = -1
                    stdout.end()
                    stderr.end()
                    done.countDown()
                    try { c.close() } catch (_: IOException) {}
                }
            }
        }

        private fun send(type: Char, b: ByteArray) = synchronized(c.out) { frame(c.out, type, b) }

        override fun getOutputStream(): OutputStream = stdin
        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = stderr
        override fun waitFor(): Int { done.await(); return exit!! }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = done.await(timeout, unit)
        override fun exitValue(): Int = if (done.count == 0L) exit!! else throw IllegalThreadStateException("still running")
        override fun isAlive(): Boolean = done.count != 0L
        /** SIGTERM to the command's shell; like killing `su`'s client, its own children may carry on. */
        override fun destroy() { try { send('k', ByteArray(0)) } catch (_: IOException) {} }
    }

    /** A stream of byte chunks handed over between threads; [end] marks EOF. */
    private class Chunks : InputStream() {
        private val q = LinkedBlockingQueue<ByteArray>()
        private var cur: ByteArray? = null
        private var pos = 0
        private var eof = false

        fun add(b: ByteArray) { if (b.isNotEmpty()) q.put(b) }
        fun end() = q.put(EOF)

        private fun fill(): Boolean {
            while (cur == null || pos >= cur!!.size) {
                if (eof) return false
                val next = q.take()
                if (next === EOF) { eof = true; return false }
                cur = next
                pos = 0
            }
            return true
        }

        override fun read(): Int = if (fill()) cur!![pos++].toInt() and 0xff else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!fill()) return -1
            val n = minOf(len, cur!!.size - pos)
            cur!!.copyInto(b, off, pos, pos + n)
            pos += n
            return n
        }
        override fun available(): Int = cur?.let { it.size - pos } ?: 0

        private companion object { val EOF = ByteArray(0) }
    }

    private companion object {
        fun frame(out: DataOutputStream, type: Char, payload: ByteArray) {
            out.writeByte(type.code)
            out.writeInt(payload.size)
            out.write(payload)
            out.flush()
        }
    }
}
