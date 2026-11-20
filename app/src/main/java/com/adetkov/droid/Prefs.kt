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
}
