package com.adetkov.droid

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.net.toUri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * What the agent set for later: a wake-up (`droid later WHEN MESSAGE`: at that time its message goes to the chat as from
 * the agent itself, starting a turn), or a notification (`droid notify --at`). Kept in files/later.json so they outlive
 * the app's process and a restart of the phone (the app puts them back when it starts); one whose time passed while
 * the phone was off goes off at once.
 */
object Later {
    private fun file(ctx: Context) = File(ctx.filesDir, "later.json")

    /** What is set; a file that can't be read is kept aside (see [keepAside]) before the next save replaces it. */
    private fun load(ctx: Context): MutableList<JSONObject> {
        val f = file(ctx)
        if (!f.exists()) return mutableListOf()
        return try { JSONArray(f.readText()).let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.toMutableList() } }
        catch (_: Exception) { keepAside(f); mutableListOf() }
    }

    private fun save(ctx: Context, list: List<JSONObject>) {
        // The alarms are set (or cancelled) all the same: only a restart of the phone goes by the file.
        try { writeWhole(file(ctx), JSONArray(list).toString()) } catch (e: IOException) { Log.w("Droid", "later: ${e.message}") }
    }

    /** Sets [w] for later. Off the main thread (see [allowExact]). */
    fun schedule(ctx: Context, w: Wake) {
        allowExact(ctx)
        synchronized(this) {
            w.note?.let { dropNote(ctx, w.conv, JSONObject(it).optString("id")) }  // a notification set again replaces the one set before
            val o = JSONObject().put("id", UUID.randomUUID().toString()).put("conv", w.conv).put("at", w.at).put("text", w.text)
            if (w.note != null) o.put("note", w.note)
            save(ctx, load(ctx) + o)
            arm(ctx, o)
        }
    }

    /** Takes away a notification set for later in chat [conv] under [noteId] (taken away, or set again). */
    @Synchronized
    fun dropNote(ctx: Context, conv: String, noteId: String) {
        val list = load(ctx)
        val gone = list.filter { it.optString("conv") == conv && it.has("note") && JSONObject(it.optString("note")).optString("id") == noteId }
        if (gone.isEmpty()) return
        gone.forEach { ctx.getSystemService(AlarmManager::class.java).cancel(intent(ctx, it.optString("id"))) }
        save(ctx, list - gone.toSet())
    }

    /** Takes away everything set for later in chat [conv] (it was deleted). */
    @Synchronized
    fun dropChat(ctx: Context, conv: String) {
        val list = load(ctx)
        val gone = list.filter { it.optString("conv") == conv }
        if (gone.isEmpty()) return
        gone.forEach { ctx.getSystemService(AlarmManager::class.java).cancel(intent(ctx, it.optString("id"))) }
        save(ctx, list - gone.toSet())
    }

    private fun intent(ctx: Context, id: String) = PendingIntent.getBroadcast(ctx, 0,
        Intent(ctx, LaterReceiver::class.java).setData("droid://later/$id".toUri()).putExtra("id", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** Sets every kept wake-up again (after the app or the phone started). Off the main thread (see [allowExact]). */
    fun restore(ctx: Context) {
        if (synchronized(this) { load(ctx).isEmpty() }) return
        allowExact(ctx)
        synchronized(this) { load(ctx).forEach { arm(ctx, it) } }
    }

    /** [id] went off: takes it out and returns it, or null if it is gone. */
    @Synchronized
    fun take(ctx: Context, id: String): Wake? {
        val list = load(ctx)
        val o = list.firstOrNull { it.optString("id") == id } ?: return null
        save(ctx, list - o)
        return Wake(o.optString("conv"), o.optLong("at"), o.optString("text"), o.optString("note").ifEmpty { null })
    }

    private fun arm(ctx: Context, o: JSONObject) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = intent(ctx, o.optString("id"))
        val at = o.optLong("at").coerceAtLeast(System.currentTimeMillis() + 1000)
        // Exact when Droid may set exact alarms (see allowExact); otherwise Android may delay it a little.
        if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    @Volatile private var hasExact = false

    /**
     * Grants Droid exact alarms through `appops` (as root or the shell user) when a wake-up needs them, until it has
     * them: a try that failed (Droid's helper not up yet, at boot) is made again with the next wake-up. Outside the lock
     * and off the main thread: without root it may start Droid's helper, which takes a while, and a wake-up going off
     * meanwhile (on the main thread) must not wait for that.
     */
    private fun allowExact(ctx: Context) {
        if (hasExact) return
        val am = ctx.getSystemService(AlarmManager::class.java)
        if (!am.canScheduleExactAlarms()) {
            try { (ctx.applicationContext as DroidApp).host.exec("appops set ${ctx.packageName} SCHEDULE_EXACT_ALARM allow").run { outputStream.close(); waitFor() } }
            catch (e: Exception) { Log.w("Droid", "exact alarms: ${e.message}") }
        }
        hasExact = am.canScheduleExactAlarms()
    }
}

/** Something the agent set went off: its notification is posted, or its message goes to its chat (not a deleted one). */
class LaterReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val w = Later.take(ctx, intent.getStringExtra("id") ?: return) ?: return
        val app = ctx.applicationContext as DroidApp
        if (app.store.get(w.conv) == null) return
        if (w.note != null) Notify.post(ctx, w.conv, org.json.JSONObject(w.note))
        else {
            startTask(ctx, w.conv, w.text, wake = true)
            app.store.saveNow()  // it's out of later.json already: the chat must have it on disk at once
        }
    }
}
