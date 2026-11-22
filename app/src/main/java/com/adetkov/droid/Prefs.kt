package com.adetkov.droid

import android.app.UiModeManager
import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow

enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

/** The user's settings. Each one is a flow the UI watches; setters persist at once. */
class Prefs(private val ctx: Context) {
    private val sp = ctx.getSharedPreferences("prefs", 0)

    val theme = MutableStateFlow(runCatching { ThemeMode.valueOf(sp.getString("theme", null)!!) }.getOrDefault(ThemeMode.SYSTEM))
    /** The engine new chats use; a chat keeps the engine it started with. */
    val engine = MutableStateFlow(Engine.of(sp.getString("engine", null)))
    /** The model chosen for each engine ("" = the engine's default). Claude's keeps the old `model` key. */
    val models = MutableStateFlow(Engine.entries.associateWith { e ->
        sp.getString(modelKey(e), "").orEmpty().takeIf(e::validModel).orEmpty()
    })
    /** Read the answer aloud when the request was spoken. */
    val speakReplies = MutableStateFlow(sp.getBoolean("speak", true))
    val haptics = MutableStateFlow(sp.getBoolean("haptics", true))
    /** Dash makes the home page each day: the owner's day at a glance (see [HomeDay]). */
    val homePage = MutableStateFlow(sp.getBoolean("home_page", true))
    /** The chat Dash makes it in, and when he was last asked to. */
    var homeChat: String?
        get() = sp.getString("home_chat", null)
        set(v) = sp.edit { putString("home_chat", v) }
    var homeAsked: Long
        get() = sp.getLong("home_asked", 0)
        set(v) = sp.edit { putLong("home_asked", v) }

    /**
     * Chats the owner set aside from home ("Not now"), each with the time of its last message then: a new message
     * brings it back.
     */
    val notNow = MutableStateFlow(sp.getStringSet("not_now", emptySet()).orEmpty().mapNotNull { e ->
        e.substringBeforeLast(':', "").takeIf { it.isNotEmpty() }?.let { it to (e.substringAfterLast(':').toLongOrNull() ?: 0L) }
    }.toMap())
    fun setNotNow(conv: String, lastAt: Long) {
        val m = (notNow.value + (conv to lastAt)).entries.sortedByDescending { it.value }.take(200).associate { it.key to it.value }
        notNow.value = m
        sp.edit { putStringSet("not_now", m.map { "${it.key}:${it.value}" }.toSet()) }
    }

    fun setTheme(v: ThemeMode) { theme.value = v; sp.edit { putString("theme", v.name) }; applyTheme() }
    /** Tells Android too, so its splash and window behind Droid match from the first frame. */
    fun applyTheme() = ctx.getSystemService(UiModeManager::class.java).setApplicationNightMode(when (theme.value) {
        ThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO; ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO; ThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
    })
    fun setEngine(v: Engine) { engine.value = v; sp.edit { putString("engine", v.id) } }
    fun setModel(e: Engine, v: String) {
        if (!e.validModel(v)) return
        models.value = models.value + (e to v); sp.edit { putString(modelKey(e), v) }
    }
    fun model(e: Engine) = models.value[e].orEmpty()
    private fun modelKey(e: Engine) = if (e == Engine.CLAUDE) "model" else "model_${e.id}"
    fun setSpeakReplies(v: Boolean) { speakReplies.value = v; sp.edit { putBoolean("speak", v) } }
    fun setHaptics(v: Boolean) { haptics.value = v; sp.edit { putBoolean("haptics", v) } }
    fun setHomePage(v: Boolean) { homePage.value = v; sp.edit { putBoolean("home_page", v) } }
}
