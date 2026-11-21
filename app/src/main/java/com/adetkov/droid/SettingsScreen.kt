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

/** A screen with a back arrow, a title and optional actions. */
@Composable
fun SubScreen(
    title: String, onBack: () -> Unit, backIcon: Int = R.drawable.ic_back, backTag: String = "back",
    actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().background(P.bg)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(backIcon, "Back", onBack, Modifier.testTag(backTag), size = 44.dp)
            Text(title, Modifier.weight(1f).padding(start = 6.dp), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            actions()
        }
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
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Who Droid works as; a tap goes to Setup, where signing in happens
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable(onClick = onSetup).padding(horizontal = 4.dp, vertical = 12.dp)
                .testTag("settings_account"), verticalAlignment = Alignment.CenterVertically) {
                Avatar(setup.email, 48.dp)
                Column(Modifier.padding(start = 14.dp)) {
                    val other = setup.others[engine]
                    Text(if (engine == Engine.CLAUDE) setup.email ?: "Not signed in" else engine.label,
                        style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (engine == Engine.CLAUDE) planLabel(setup.plan) ?: if (setup.signedIn) "Claude account" else "Tap to sign in"
                        else other?.detail ?: "Tap to set it up",
                        style = MaterialTheme.typography.bodyMedium, color = P.muted)
                }
            }

            update?.let { u ->
                Group(null) {
                    NavItem(R.drawable.ic_download, "Droid ${u.version} is out", "Download it from GitHub",
                        { openLink(app, u.url) }, "settings_update")
                }
            }

            Group(null) {
                NavItem(R.drawable.ic_notes, "Instructions", "What Droid should know about you, and how you like things done", onInstructions, "settings_instructions")
            }

            Group(null) {
                ToggleItem(R.drawable.ic_speaker, "Read answers aloud", "When you asked by voice", speak, prefs::setSpeakReplies, "settings_speak")
                Divider()
                ToggleItem(R.drawable.ic_sparkle, "Your day from Dash", "Each day he puts what's next and what needs you on Droid's home screen",
                    home, prefs::setHomePage, "settings_home_page")
            }

            Group(null) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_theme), null, Modifier.size(22.dp), tint = P.text)
                    Text("Theme", Modifier.weight(1f).padding(start = 16.dp), style = MaterialTheme.typography.bodyLarge)
                    Segmented(ThemeMode.entries.map { it.label }, theme.ordinal) { prefs.setTheme(ThemeMode.entries[it]) }
                }
                Divider()
                ToggleItem(R.drawable.ic_haptics, "Haptic feedback", null, haptics, prefs::setHaptics, "settings_haptics")
            }

            Group(null) {
                NavItem(R.drawable.ic_info, "About", "Droid ${remember { Updates.version(app) }}, source and licenses", onAbout, "settings_about")
                Divider()
                NavItem(R.drawable.ic_settings, "Advanced", "Agent and model, memory, voice match, setup", onAdvanced, "settings_advanced")
            }
            Spacer(Modifier.height(16.dp))
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
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = convs.isNotEmpty()) { confirmDeleteAll = true }.padding(16.dp).testTag("delete_all"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val tint = if (convs.isNotEmpty()) P.danger else P.faint  // nothing to delete
                    Icon(painterResource(R.drawable.ic_delete_all), null, Modifier.size(22.dp), tint = tint)
                    Text("Delete all chats", Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyLarge, color = tint)
                }
            }
            Spacer(Modifier.height(16.dp))
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

@Composable
internal fun Group(label: String?, content: @Composable ColumnScope.() -> Unit) {
    Column {
        if (label != null) SectionLabel(label, Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(P.surface), content = content)
    }
}

@Composable
internal fun Divider() = HorizontalDivider(Modifier.padding(start = 54.dp), color = P.hairline.copy(alpha = .7f))

@Composable
internal fun NavItem(icon: Int, title: String, detail: String?, onClick: () -> Unit, tag: String? = null) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).let { if (tag != null) it.testTag(tag) else it }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), null, Modifier.size(22.dp), tint = P.text)
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = P.muted)
        }
        Icon(painterResource(R.drawable.ic_chevron), null, Modifier.size(20.dp), tint = P.faint)
    }
}

@Composable
internal fun ToggleItem(icon: Int, title: String, detail: String?, on: Boolean, set: (Boolean) -> Unit, tag: String) {
    Row(Modifier.fillMaxWidth().clickable { set(!on) }.padding(horizontal = 16.dp, vertical = 12.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), null, Modifier.size(22.dp), tint = P.text)
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = P.muted)
        }
        Switch(on, set, colors = SwitchDefaults.colors(
            checkedThumbColor = P.onInk, checkedTrackColor = P.ink, uncheckedThumbColor = P.muted,
            uncheckedTrackColor = P.raised, uncheckedBorderColor = Color.Transparent, checkedBorderColor = Color.Transparent,
        ))
    }
}

@Composable
private fun EngineItem(engine: Engine, status: Map<Engine, EngineStatus>, set: (Engine) -> Unit) {
    Box {
        var open by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth().clickable { open = true }.padding(16.dp).testTag("settings_engine"), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_bolt), null, Modifier.size(22.dp), tint = P.text)
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text("Agent", style = MaterialTheme.typography.bodyLarge)
                Text(engine.label + " · for new chats", style = MaterialTheme.typography.bodySmall, color = P.muted)
            }
            Icon(painterResource(R.drawable.ic_expand), null, Modifier.size(20.dp), tint = P.faint)
        }
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
        Row(Modifier.fillMaxWidth().clickable { open = true }.padding(16.dp).testTag("settings_model"), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_sparkle), null, Modifier.size(22.dp), tint = P.text)
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text("Model", style = MaterialTheme.typography.bodyLarge)
                Text(engine.model(model).let { if (it.alias.isEmpty()) it.detail else "${it.label} · ${it.detail}" },
                    style = MaterialTheme.typography.bodySmall, color = P.muted)
            }
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
