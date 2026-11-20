package com.adetkov.droid

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Whether a newer Droid is out: the latest release on GitHub, asked at most once a day. Droid installs nothing itself;
 * Settings shows the new version, linked to its release page.
 */
object Updates {
    const val REPO = "AlexanderDetkov/droid"
    const val SOURCE = "https://github.com/$REPO"
    private const val LATEST = "https://api.github.com/repos/$REPO/releases/latest"
    private const val EVERY_MS = 24 * 3600_000L

    data class Release(val version: String, val url: String)

    /** A release newer than this app, or null. */
    val newer = MutableStateFlow<Release?>(null)

    fun version(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty()

    /** Shows what the last check found, then asks GitHub again if that was a day ago. Blocking: call it off the main thread. */
    fun check(ctx: Context) {
        val sp = ctx.getSharedPreferences("updates", 0)
        val mine = version(ctx)
        fun show(tag: String?, url: String?) {
            newer.value = if (tag != null && url != null && isNewer(tag, mine)) Release(tag.trimStart('v', 'V'), url) else null
        }
        show(sp.getString("tag", null), sp.getString("url", null))
        if (System.currentTimeMillis() - sp.getLong("checked", 0) in 0 until EVERY_MS) return  // (a clock set back asks again)
        try {
            val c = URL(LATEST).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 10_000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            try {
                // 404 until the first release is public: nothing to show, ask again tomorrow. Anything else (GitHub's rate
                // limit, an outage) keeps what the last check found and asks again next start.
                val code = c.responseCode
                if (code == 200 || code == 404) {
                    val (tag, url) = if (code == 200) JSONObject(c.inputStream.bufferedReader().readText())
                        .let { it.optString("tag_name").ifEmpty { null } to it.optString("html_url").ifEmpty { null } } else null to null
                    sp.edit { putLong("checked", System.currentTimeMillis()); putString("tag", tag); putString("url", url) }
                    show(tag, url)
                }
            } finally { c.disconnect() }
        } catch (e: Exception) {
            Log.i("Droid", "update check: $e")  // offline: try again next start
        }
    }

    /** "v1.2.10" > "1.2.9"; a suffix after the numbers ("1.0-beta") is ignored. */
    fun isNewer(tag: String, than: String): Boolean {
        fun parts(v: String) = v.trimStart('v', 'V').takeWhile { it.isDigit() || it == '.' }.split('.').mapNotNull { it.toIntOrNull() }
        val a = parts(tag); val b = parts(than)
        if (a.isEmpty()) return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
