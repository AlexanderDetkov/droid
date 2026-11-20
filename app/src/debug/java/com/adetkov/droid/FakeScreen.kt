package com.adetkov.droid

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Debug builds on an emulator: a stand-in for VdHelper's frame server, so the chat's window and Watch can be worked on
 * without a phone. Same protocol: the handshake, JPEG frames and the app in front. It plays the frames
 * in [dir] (Droid's files/fake-screen) named `N-PACKAGE.jpg` (N orders them; `N-.jpg`: no app in front), a few seconds each, and stays on the last
 * one; without any, it draws placeholders. Touches are ignored. A `script.txt` there makes FakeProc play a whole turn
 * instead of its canned one (see [script]). To put them there:
 *
 *     adb push frames/. /data/local/tmp/fake-screen/
 *     adb shell run-as com.adetkov.droid cp -r /data/local/tmp/fake-screen files/
 */
class FakeScreen private constructor(private val server: ServerSocket, private val dir: File) {
    val token: String = UUID.randomUUID().toString()
    val port: Int get() = server.localPort
    /** The frame to show, from 1 as numbered in [dir]; 0: one after another by time. */
    @Volatile var frame = 0
    private val started = SystemClock.uptimeMillis()
    private val frames: List<Pair<String, Bitmap>> by lazy(::load)

    fun close() = try { server.close() } catch (_: IOException) {}

    private fun current() = frames[
        (if (frame > 0) frame - 1 else ((SystemClock.uptimeMillis() - started) / STEP_MS).toInt()).coerceIn(0, frames.lastIndex)]

    private fun serve(c: Socket) = c.use {
        val view = VdHelper.View()
        if (!VdHelper.handshake(c, token, view)) return@use
        val inp = c.getInputStream().bufferedReader()
        val width = AtomicInteger(view.width)
        val interval = AtomicLong(view.interval.toLong())
        val apps = AtomicBoolean(true)
        thread(isDaemon = true) {
            try {
                for (line in generateSequence { inp.readLine() }) {
                    val a = line.trim().split(' ')
                    if (a[0] != "v") android.util.Log.i("FakeScreen", "input: $line")  // what Watch sends (take over)
                    if (a[0] == "v" && a.size == 3) {
                        width.set(a[1].toIntOrNull()?.coerceAtLeast(0) ?: 0)
                        interval.set(1000L / (a[2].toIntOrNull() ?: 10).coerceIn(1, 10))
                        apps.set(true)
                    }
                }
            } catch (_: IOException) {}
        }
        val out = DataOutputStream(BufferedOutputStream(c.getOutputStream()))
        var sent: Bitmap? = null
        var sentWidth = -1
        var sentApp: String? = null
        try {
            while (!server.isClosed) {
                val (pkg, frame) = current()
                if (apps.get() && pkg != sentApp) {
                    sentApp = pkg
                    val name = pkg.toByteArray()
                    if (name.isEmpty()) out.writeInt(NO_APP)  // "N-.jpg": nothing in front, the screen unused
                    else { out.writeInt(-name.size); out.write(name) }
                }
                val w = width.get()
                if (frame !== sent || w != sentWidth) {
                    sent = frame
                    sentWidth = w
                    val scaled = if (w in 1 until frame.width) frame.scale(w, frame.height * w / frame.width) else frame
                    val jpeg = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, 80, jpeg)
                    out.writeInt(jpeg.size())
                    jpeg.writeTo(out)
                }
                out.flush()
                Thread.sleep(interval.get())
            }
        } catch (_: IOException) {
        } catch (_: InterruptedException) {}
    }

    private fun load(): List<Pair<String, Bitmap>> {
        val files = dir.listFiles { f -> f.name.endsWith(".jpg") }.orEmpty()
            .mapNotNull { f -> Regex("""(\d+)-(.*)\.jpg""").matchEntire(f.name)?.let { m -> Triple(m.groupValues[1].toInt(), m.groupValues[2], f) } }
            .sortedBy { it.first }
            .mapNotNull { (_, pkg, f) -> BitmapFactory.decodeFile(f.path)?.let { pkg to it } }
        return files.ifEmpty { (1..3).map { "com.android.settings" to placeholder(it) } }
    }

    private fun placeholder(n: Int): Bitmap {
        val b = createBitmap(1080, 2400)
        val c = Canvas(b)
        c.drawColor(Color.WHITE)
        val gray = Paint().apply { color = Color.rgb(226, 226, 226) }
        c.drawRect(0f, 0f, 1080f, 180f, Paint().apply { color = Color.rgb(242, 242, 242) })
        for (i in 0 until 9) c.drawRoundRect(60f, 300f + i * 150f, if (i % 3 == 2) 640f else 1020f, 340f + i * 150f, 20f, 20f, gray)
        c.drawText("Fake screen $n", 60f, 120f, Paint().apply { color = Color.rgb(13, 13, 13); textSize = 64f; isAntiAlias = true })
        return b
    }

    /** One line of a scripted turn: after [delayMs], show [frame] (if given) and emit [text] as [kind]. */
    class Line(val delayMs: Long, val frame: Int?, val kind: Char, val text: String)

    companion object {
        private const val STEP_MS = 5000L

        /**
         * The scripted [turn] (1 for a run's first), if [dir] has one: script.txt, then script-2.txt and so on. Lines of
         * `SECONDS [#FRAME] TEXT`, where TEXT is a step ("description || command"), or narration after "> ", or the answer
         * after "= " (with \n for line breaks), or a failure after "! ".
         */
        fun script(dir: File, turn: Int): List<Line>? = File(dir, if (turn <= 1) "script.txt" else "script-$turn.txt").takeIf { it.canRead() }?.readLines()?.mapNotNull { raw ->
            val m = Regex("""([\d.]+)\s+(?:#(\d+)\s+)?(.*)""").matchEntire(raw.trim()) ?: return@mapNotNull null
            val rest = m.groupValues[3]
            // > narration, = the answer (= file:PATH reads it from a file), ! an error, @ a raw line (a droid event),
            // ? PAGE_ID waits for the owner's answer to that page; anything else is a step
            val kind = rest.firstOrNull()?.takeIf { it in "=>!@?" && rest.getOrNull(1) == ' ' } ?: '-'
            val text = if (kind == '-') rest else rest.substring(2)
            Line((m.groupValues[1].toDouble() * 1000).toLong(), m.groupValues[2].toIntOrNull(), kind,
                if (kind == '=' && text.startsWith("file:")) File(text.removePrefix("file:")).readText() else text.replace("\\n", "\n"))
        }

        /** A fake screen on a free local port showing what is in [dir], until [close]. */
        fun start(dir: File): FakeScreen {
            val screen = FakeScreen(ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")), dir)
            thread(isDaemon = true, name = "fake-screen") {
                try {
                    while (true) { val c = screen.server.accept(); thread(isDaemon = true) { screen.serve(c) } }
                } catch (_: IOException) {}
            }
            return screen
        }
    }
}
