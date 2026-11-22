package com.adetkov.droid

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** One of Droid's own screens: back and its [title] at the top, then its content (Dash speaks where he has something to say, not here). */
@Composable
fun SubScreen(
    title: String, onBack: () -> Unit, backIcon: Int = R.drawable.ic_back, backTag: String = "back",
    actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().background(P.bg)) {
        Where(backIcon, "Back", onBack, title, leftTag = backTag, right = actions)
        content()
    }
}

/**
 * Settings, for everyone: who's signed in, what Droid should know about you, voice and looks. What only technical
 * people need (the agent and its model, memory, voice match, Setup's details) is one tap further, under Advanced.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit, onInstructions: () -> Unit, onSetup: () -> Unit, onAbout: () -> Unit, onAdvanced: () -> Unit) {
    val app = LocalContext.current.applicationContext as DroidApp
    val prefs = app.prefs
    val setup by app.setup.state.collectAsState()
    val theme by prefs.theme.collectAsState()
    val engine by prefs.engine.collectAsState()
    val speak by prefs.speakReplies.collectAsState()
    val home by prefs.homePage.collectAsState()
    val haptics by prefs.haptics.collectAsState()
    val update by Updates.newer.collectAsState()

    SubScreen("Settings", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
            // Who Droid works as; a tap goes to Setup, where signing in happens
            Group("Account") {
                val other = setup.others[engine]
                KitRow(if (engine == Engine.CLAUDE) setup.email ?: "Not signed in" else engine.label, Modifier.testTag("settings_account"),
                    sub = if (engine == Engine.CLAUDE) planLabel(setup.plan) ?: if (setup.signedIn) "Claude account" else "Tap to sign in"
                        else other?.detail ?: "Tap to set it up",
                    lead = { Avatar(setup.email, 42.dp) }, onClick = onSetup, trailing = { Chevron() })
            }

            update?.let { u ->
                Group(null) {
                    NavItem(R.drawable.ic_download, "Droid ${u.version} is out", "Download it from GitHub",
                        { openLink(app, u.url) }, "settings_update")
                }
            }

            Group("You and me") {
                NavItem(R.drawable.ic_notes, "Instructions", "What Droid should know about you, and how you like things done", onInstructions, "settings_instructions")
            }

            Group("Talking") {
                ToggleItem(R.drawable.ic_speaker, "Talk out loud", "I read my answers to you when you talk to me", speak, prefs::setSpeakReplies, "settings_speak")
                Divider()
                ToggleItem(R.drawable.ic_sparkle, "Your day from me", "Each morning, on your home screen",
                    home, prefs::setHomePage, "settings_home_page")
            }

            Group("Looks") {
                KitRow("Theme", lead = { IconTile(R.drawable.ic_theme) }) {
                    Segmented(ThemeMode.entries.map { it.label }, theme.ordinal) { prefs.setTheme(ThemeMode.entries[it]) }
                }
                Divider()
                ToggleItem(R.drawable.ic_haptics, "Haptic feedback", null, haptics, prefs::setHaptics, "settings_haptics")
            }

            Group("Droid") {
                NavItem(R.drawable.ic_info, "About", "Droid ${remember { Updates.version(app) }}, source and licenses", onAbout, "settings_about")
                Divider()
                NavItem(R.drawable.ic_settings, "Advanced", "Agent and model, memory, voice match, setup", onAdvanced, "settings_advanced")
            }
        }
    }
}

/** What technical people may want to change: the agent and its model, Claude's memory, voice match, Setup's details. */
@Composable
fun AdvancedScreen(onBack: () -> Unit, onMemory: () -> Unit, onSetup: () -> Unit, onVoiceMatch: () -> Unit, onDeleteAll: () -> Unit) {
    val app = LocalContext.current.applicationContext as DroidApp
    val prefs = app.prefs
    val setup by app.setup.state.collectAsState()
    val engine by prefs.engine.collectAsState()
    val models by prefs.models.collectAsState()
    val voiceMatch by app.voiceMatch.enabled.collectAsState()
    val voiceprint by app.voiceMatch.print.collectAsState()
    val convs by app.store.convs.collectAsState()
    var confirmDeleteAll by remember { mutableStateOf(false) }

    if (confirmDeleteAll) ConfirmDialog(
        "Delete all chats?", "All ${convs.size} chats, their attachments and what Droid remembers of them will be deleted.",
        "Delete all", onDeleteAll, { confirmDeleteAll = false }, danger = true,
    )

    SubScreen("Advanced", onBack, backTag = "advanced_close") {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
            Group("Agent") {
                EngineItem(engine, setup.others, prefs::setEngine)
                Divider()
                ModelItem(engine, models[engine].orEmpty()) { prefs.setModel(engine, it) }
                Divider()
                NavItem(R.drawable.ic_memory, "Memory", "What Claude has learned about you", onMemory, "settings_memory")
            }

            Group("Voice") {
                NavItem(R.drawable.ic_shield, "Voice match", when {
                    voiceprint == null -> "Skip the fingerprint on the lock screen"
                    voiceMatch -> "On: your voice skips the fingerprint"
                    else -> "Off"
                }, onVoiceMatch, "settings_voice_match")
                if (setup.assistant) {
                    Divider()
                    ToggleItem(R.drawable.ic_screen, "Send your screen", "The assistant sees the app you open it over",
                        setup.screenshots, app.setup::setScreenshots, "settings_screenshots")
                }
            }

            Group("Phone") {
                NavItem(R.drawable.ic_setup, "Setup and updates",
                    if (setup.voice && !setup.voiceCurrent) "Better speech-to-text is out"
                    else (if (setup.access == Access.ROOT) "Root" else "Wireless debugging") + ", Linux, agents, sign-in", onSetup, "settings_setup")
            }

            Group(null) {
                val tint = if (convs.isNotEmpty()) P.danger else P.faint  // nothing to delete
                KitRow("Delete all chats", Modifier.testTag("delete_all"), lead = { IconTile(R.drawable.ic_delete_all, tint) }, titleColor = tint,
                    onClick = if (convs.isNotEmpty()) ({ confirmDeleteAll = true }) else null)
            }
        }
    }
}

/** Droid's version, its source and the licenses of what it's built with and downloads ([about.md](assets/about.md)). */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val text = remember { runCatching { ctx.assets.open("about.md").bufferedReader().use { it.readText() } }.getOrDefault("") }
    SubScreen("About", onBack, backTag = "about_close") {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp)) {
            Text("Droid ${remember { Updates.version(ctx) }}", Modifier.padding(top = 8.dp, bottom = 12.dp), style = MaterialTheme.typography.headlineSmall)
            Markdown(text)
            Spacer(Modifier.height(32.dp))
        }
    }
}

/** A section of rows, under its [label] (a little room instead, for none). */
@Composable
internal fun Group(label: String?, content: @Composable ColumnScope.() -> Unit) {
    Column {
        if (label != null) KitLabel(label) else Spacer(Modifier.height(20.dp))
        Column(Modifier.fillMaxWidth(), content = content)
    }
}

/** Between two rows of a [Group]. */
@Composable
internal fun Divider() = HorizontalDivider(Modifier.padding(start = 60.dp), color = P.hairline)

@Composable
internal fun NavItem(icon: Int, title: String, detail: String?, onClick: () -> Unit, tag: String? = null) =
    KitRow(title, if (tag != null) Modifier.testTag(tag) else Modifier, sub = detail, lead = { IconTile(icon) }, onClick = onClick, trailing = { Chevron() })

@Composable
internal fun ToggleItem(icon: Int, title: String, detail: String?, on: Boolean, set: (Boolean) -> Unit, tag: String) =
    KitRow(title, Modifier.testTag(tag), sub = detail, lead = { IconTile(icon) }, onClick = { set(!on) }) { KitSwitch(on, set) }

@Composable
private fun EngineItem(engine: Engine, status: Map<Engine, EngineStatus>, set: (Engine) -> Unit) {
    Box {
        var open by remember { mutableStateOf(false) }
        KitRow("Agent", Modifier.testTag("settings_engine"), sub = engine.label + " · for new chats", lead = { IconTile(R.drawable.ic_bolt) },
            onClick = { open = true }) { Icon(painterResource(R.drawable.ic_expand), null, Modifier.size(20.dp), tint = P.faint) }
        DropdownMenu(open, { open = false }, shape = RoundedCornerShape(18.dp), containerColor = P.sheet) {
            Engine.entries.forEach { e ->
                // New chats would fail on an agent that isn't installed and signed in (Setup does that).
                val usable = e == Engine.CLAUDE || status[e]?.signedIn == true
                Row(Modifier.width(280.dp).clickable(enabled = usable) { set(e); open = false }.padding(horizontal = 16.dp, vertical = 10.dp)
                    .testTag("engine_" + e.id), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(e.label, style = MaterialTheme.typography.titleSmall, color = if (usable) P.text else P.faint)
                        Text(if (e == Engine.CLAUDE) "The default" else status[e]?.detail ?: "Not installed: see Setup",
                            style = MaterialTheme.typography.bodySmall, color = P.muted)
                    }
                    Icon(painterResource(R.drawable.ic_check), null, Modifier.size(20.dp), tint = if (e == engine) P.text else Color.Transparent)
                }
            }
        }
    }
}

@Composable
private fun ModelItem(engine: Engine, model: String, set: (String) -> Unit) {
    Box {
        var open by remember { mutableStateOf(false) }
        var typing by remember { mutableStateOf(false) }
        if (typing) TextDialog("Model name", model, "Use", { set(it.trim()) }, { typing = false })
        KitRow("Model", Modifier.testTag("settings_model"), sub = engine.model(model).let { if (it.alias.isEmpty()) it.detail else "${it.label} · ${it.detail}" },
            lead = { IconTile(R.drawable.ic_sparkle) }, onClick = { open = true }) {
            Icon(painterResource(R.drawable.ic_expand), null, Modifier.size(20.dp), tint = P.faint)
        }
        DropdownMenu(open, { open = false }, shape = RoundedCornerShape(18.dp), containerColor = P.sheet) {
            engine.models.forEach { m ->
                Row(Modifier.width(280.dp).clickable { set(m.alias); open = false }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(m.label, style = MaterialTheme.typography.titleSmall)
                        Text(m.detail, style = MaterialTheme.typography.bodySmall, color = P.muted)
                    }
                    Icon(painterResource(R.drawable.ic_check), null, Modifier.size(20.dp), tint = if (m.alias == model) P.text else Color.Transparent)
                }
            }
            if (engine.custom) Row(Modifier.width(280.dp).clickable { open = false; typing = true }.padding(horizontal = 16.dp, vertical = 10.dp)
                .testTag("model_custom"), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.padding(end = 12.dp)) {
                    Text("Other…", style = MaterialTheme.typography.titleSmall)
                    Text(if (engine == Engine.OPENCODE) "provider/model, e.g. anthropic/claude-sonnet-4-5" else "A model name Codex accepts",
                        style = MaterialTheme.typography.bodySmall, color = P.muted)
                }
            }
        }
    }
}

/** A small pill switch between a few options. */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(P.raised).padding(3.dp)) {
        options.forEachIndexed { i, o ->
            val on = i == selected
            Text(
                o, Modifier.clip(RoundedCornerShape(50)).background(if (on) P.bg else Color.Transparent).clickable { onSelect(i) }
                    .padding(horizontal = 12.dp, vertical = 6.dp).testTag("seg_" + o.lowercase()),
                style = MaterialTheme.typography.labelMedium, color = if (on) P.text else P.muted,
            )
        }
    }
}
