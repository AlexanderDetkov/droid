package com.adetkov.droid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.adetkov.droid.adb.Adb
import com.adetkov.droid.adb.AdbKey
import com.adetkov.droid.adb.Discovery
import java.io.File
import kotlin.concurrent.thread

/**
 * Debug builds: drive the Wireless-debugging code from adb, results in logcat (tag DroidAdbTest):
 *   am broadcast -n com.adetkov.droid/.AdbTestReceiver -a pair --es code C [--ei port P]   (port found by mDNS if absent)
 *   ... -a shell --ei port P --es cmd '...' [--es fp F]       ... -a ensure [--es cmd 'id']
 *   ... -a send --es text '...' [--es conv ID]    a request, as if typed in that chat (a new chat without one)
 *   ... -a page --es conv ID --es page PAGE --es text '...' [--es data JSON]   a page's droid.send, as if tapped
 *   ... -a delete --es conv ID     deletes a chat, its files and its agent session, as the chat's menu does
 */
class AdbTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val key = AdbKey.load(File(context.filesDir, "adb"))
        val helper = AdbHelper(context.applicationContext)
        thread {
            try {
                when (intent.action) {
                    "delete" -> deleteChat(context, intent.getStringExtra("conv")!!)
                    "send" -> Log.i(TAG, "chat " + startTask(context, intent.getStringExtra("conv"), intent.getStringExtra("text").orEmpty()))
                    "page" -> pageSend(context, intent.getStringExtra("conv")!!, intent.getStringExtra("page"),
                        intent.getStringExtra("text").orEmpty(), intent.getStringExtra("data"))
                    "pair" -> {
                        val port = intent.getIntExtra("port", 0).takeIf { it > 0 } ?: Discovery.find(context, Discovery.PAIRING, 20_000)
                        Log.i(TAG, "pairing port=$port")
                        helper.pair(port!!, intent.getStringExtra("code").orEmpty())
                        Log.i(TAG, "paired")
                    }
                    "shell" -> Log.i(TAG, "output: " + Adb.shell(key, intent.getIntExtra("port", 0), intent.getStringExtra("cmd").orEmpty(), intent.getStringExtra("fp")).output)
                    "ensure" -> {
                        val c = helper.ensure()
                        val p = c.exec(intent.getStringExtra("cmd") ?: "id")
                        p.outputStream.close()
                        Log.i(TAG, "bridge ${c.port}: " + p.inputStream.bufferedReader().readText() + " exit=" + p.waitFor())
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object { const val TAG = "DroidAdbTest" }
}
