package com.adetkov.droid

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A run's hidden screen, live: VdHelper's frame server on 127.0.0.1:[port]. Once each side has shown it knows the run's
 * [token] ([openScreen]), the feed gets frames at most [width] pixels wide (0: full size), at most [fps] a second, as
 * [4-byte big-endian length][JPEG], and the app in front as [negative length][package name] ([NO_APP]: none). [send]
 * passes touches and keys back (Watch's take over). Shared by Watch (full size) and the chat's window (small and slow).
 */
@Stable
class ScreenFeed(val port: Int, private val token: String, private val width: Int, private val fps: Int) {
    /** The latest frame, once one came. */
    var frame by mutableStateOf<Bitmap?>(null)
        private set
    /** The package in front on the hidden screen, once the helper says; null while nothing is (the screen is unused). */
    var app by mutableStateOf<String?>(null)
        private set
    var connected by mutableStateOf(false)
        private set

    private val input = Channel<String>(Channel.UNLIMITED)  // lines for the hidden screen, written off the main thread
    @Volatile private var socket: Socket? = null

    /** A line for the hidden screen ("d X Y", "k 4", ...); lines from before a (re)connect are dropped. */
    fun send(line: String) { input.trySend(line) }

    /**
     * Streams until the server closes the connection, retrying for a few seconds while a new screen comes up and
     * [isUp] says it is there. Cancelling closes the socket, which ends a blocking read at once.
     */
    suspend fun run(isUp: () -> Boolean) = coroutineScope {
        val loop = launch(Dispatchers.IO) { stream(isUp) }
        try { loop.join() } finally { close() }
    }

    fun close() { try { socket?.close() } catch (_: IOException) {} }

    private suspend fun stream(isUp: () -> Boolean) = coroutineScope {
        var failures = 0
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        while (isActive) {
            var got = false
            val s = Socket()
            socket = s
            try {
                openScreen(s, port, token, width, fps)
                s.soTimeout = 0  // frames come only when the screen changes
                val out = s.getOutputStream()
                connected = true
                while (input.tryReceive().isSuccess) {}  // lines from before this connection are stale
                val writer = launch {
                    try { for (line in input) { out.write("$line\n".toByteArray()); out.flush() } } catch (_: IOException) {}
                }
                try {
                    val inp = DataInputStream(s.getInputStream().buffered())
                    while (isActive) {
                        val n = inp.readInt()
                        when {
                            n == NO_APP -> app = null
                            n in -256..-1 -> app = String(ByteArray(-n).also(inp::readFully), Charsets.UTF_8)
                            n in 1..8_000_000 -> BitmapFactory.decodeByteArray(ByteArray(n).also(inp::readFully), 0, n, opts)?.let { frame = it; got = true }
                            else -> break
                        }
                    }
                } finally {
                    writer.cancel()
                }
            } catch (_: IOException) {
            } finally {
                connected = false
                try { s.close() } catch (_: IOException) {}
            }
            // The server may need a moment to come up right after a task starts; a screen that is still up gets
            // reconnected to; one that closed ends the stream.
            if (got) failures = 0
            if (failures++ < 10 && isUp()) delay(500) else break
        }
    }
}

/** What VdHelper sends in place of an app's name when nothing is in front on its display. */
const val NO_APP = Int.MIN_VALUE

/**
 * Whether an app is in front on the hidden screen at [port]: the helper says so as soon as a client connects. True
 * when it can't tell (no answer within a second and a half, or not the run's helper): then the screen is treated as
 * in use, as it always was.
 */
fun screenInUse(port: Int, token: String): Boolean = try {
    Socket().use { s ->
        openScreen(s, port, token, 1, 1)
        val inp = DataInputStream(s.getInputStream().buffered())
        var answer: Boolean? = null
        while (answer == null) {
            val n = inp.readInt()
            when {
                n == NO_APP -> answer = false
                n in -256..-1 -> answer = true
                n in 1..8_000_000 -> inp.readFully(ByteArray(n))  // a frame
                else -> answer = true
            }
        }
        answer
    }
} catch (_: IOException) { true }

/**
 * Connects [s] to the frame server at [port] and makes sure it is the run's helper: any app may listen on a local port,
 * and a stranger there would get the owner's touches and could show anything. Each side proves it knows [token]
 * without sending it (see VdHelper.handshake); then the feed asks for frames at most [width] wide, at most [fps] a
 * second. Leaves a 1.5 s read timeout.
 */
internal fun openScreen(s: Socket, port: Int, token: String, width: Int, fps: Int) {
    s.connect(InetSocketAddress("127.0.0.1", port), 1000)
    s.soTimeout = 1500
    val inp = DataInputStream(s.getInputStream())
    val out = s.getOutputStream()
    val a = ByteArray(32).also(SecureRandom()::nextBytes)
    out.write(a)
    out.flush()
    val proof = ByteArray(32).also(inp::readFully)
    if (!MessageDigest.isEqual(proof, screenHmac(token, "droid-screen-server", a))) throw IOException("Not the run's screen on port $port")
    val b = ByteArray(32).also(inp::readFully)
    out.write(screenHmac(token, "droid-screen-client", b) + "v $width $fps\n".toByteArray())
    out.flush()
}

internal fun screenHmac(token: String, label: String, nonce: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
    init(SecretKeySpec(token.toByteArray(Charsets.US_ASCII), "HmacSHA256"))
    update(label.toByteArray(Charsets.US_ASCII))
    doFinal(nonce)
}

/**
 * A feed for the run at [port] with [token] while this is composed and the app is in the foreground; null without a
 * screen. [onEnd]: the stream ended for good (the screen closed, or never came up).
 */
@Composable
fun rememberScreenFeed(
    port: Int?, token: String?, width: Int = 0, fps: Int = 10, isUp: () -> Boolean = { true }, onEnd: () -> Unit = {},
): ScreenFeed? {
    if (port == null || token == null) return null
    val feed = remember(port, token, width, fps) { ScreenFeed(port, token, width, fps) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val up by rememberUpdatedState(isUp)
    val end by rememberUpdatedState(onEnd)
    LaunchedEffect(feed) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            feed.run { up() }
            end()
        }
    }
    DisposableEffect(feed) { onDispose { feed.close() } }
    return feed
}
