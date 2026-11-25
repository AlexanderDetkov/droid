package com.adetkov.droid

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.runtime.produceState
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
 * Settings, one screen: who's signed in (the AI for new chats is a tap further), what Dash knows about you, and the few
 * switches anyone wants, then Setup. About and Delete all are its footer; a newer Droid, while there is one, its top.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit, onAccount: () -> Unit, onKnow: () -> Unit, onVoiceMatch: () -> Unit, onSetup: () -> Unit,
                   onAbout: () -> Unit, onDeleteAll: () -> Unit) {
    val app = LocalContext.current.applicationContext as DroidApp
    val prefs = app.prefs
    val setup by app.setup.state.collectAsState()
    val theme by prefs.theme.collectAsState()
    val engine by prefs.engine.collectAsState()
    val speak by prefs.speakReplies.collectAsState()
    val home by prefs.homePage.collectAsState()
    val update by Updates.newer.collectAsState()
    val voiceMatch by app.voiceMatch.enabled.collectAsState()
    val voiceprint by app.voiceMatch.print.collectAsState()
    val convs by app.store.convs.collectAsState()
    var confirmDeleteAll by remember { mutableStateOf(false) }
    if (confirmDeleteAll) ConfirmDialog(
        "Delete all ${convs.size} chats?", "Their apps and what I remember of them go too. Your instructions and my notes stay.",
        "Delete all", onDeleteAll, { confirmDeleteAll = false }, danger = true,
    )

    SubScreen("Settings", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
            update?.let { u ->
                Row(Modifier.padding(top = 6.dp, bottom = 6.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Lavender)
                    .clickable { openLink(app, u.url) }.padding(horizontal = 14.dp, vertical = 12.dp).testTag("settings_update"),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(P.call), contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.ic_download), null, Modifier.size(21.dp), tint = OnCall)
                    }
                    Column(Modifier.padding(start = 14.dp)) {
                        Text("Droid ${u.version} is out", style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold), color = CallText)
                        Text("See what's new and get it", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp), color = CallText)
                    }
                }
            }
            // who you are, and the AI new chats use, in a line: "Claude Max · Smartest", or what's left to set up
            val usable = if (engine == Engine.CLAUDE) setup.signedIn else setup.others[engine]?.signedIn == true
            KitRow("Account", Modifier.testTag("settings_account"),
                sub = if (!usable) "${aiName(engine)} · not set up yet" else listOfNotNull(
                    if (engine == Engine.CLAUDE) planLabel(setup.plan) ?: "Claude" else aiName(engine),
                    engine.model(prefs.model(engine)).label.takeIf { engine.models.size > 1 && it != "Default" },
                ).joinToString(" · "),
                lead = { Avatar(setup.email.takeIf { engine == Engine.CLAUDE }, 42.dp) }, onClick = onAccount, trailing = { Chevron() })
            NavItem(R.drawable.ic_memory, "What I know about you", "Your instructions, and what I've learned", onKnow, "settings_know")
            ToggleItem(R.drawable.ic_speaker, "Talk out loud", "I read my answers when you talk to me", speak, prefs::setSpeakReplies, "settings_speak")
            ToggleItem(R.drawable.ic_sparkle, "Your day from me", "A chat each morning, at the top of your list", home, prefs::setHomePage, "settings_home_page")
            if (setup.assistant) ToggleItem(R.drawable.ic_screen, "Send your screen", "When you ask over another app",
                setup.screenshots, app.setup::setScreenshots, "settings_screenshots")
            NavItem(R.drawable.ic_shield, "Voice match", when {
                voiceprint == null -> "Skip the fingerprint when locked"
                voiceMatch -> "On: your voice skips the fingerprint"
                else -> "Off"
            }, onVoiceMatch, "settings_voice_match")
            KitRow("Theme", lead = { IconTile(R.drawable.ic_theme) }) {
                Segmented(listOf("Phone", "Light", "Dark"), theme.ordinal) { prefs.setTheme(ThemeMode.entries[it]) }
            }
            NavItem(R.drawable.ic_setup, "Setup and updates",
                if (setup.voice && !setup.voiceCurrent) "Better speech is out" else "Access, speech, updates", onSetup, "settings_setup")
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Droid ${remember { Updates.version(app) }} · About", Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onAbout)
                    .padding(horizontal = 4.dp, vertical = 10.dp).testTag("settings_about"),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), color = P.muted)
                Spacer(Modifier.weight(1f))
                if (convs.isNotEmpty()) Text("Delete all chats", Modifier.clip(RoundedCornerShape(10.dp)).clickable { confirmDeleteAll = true }
                    .padding(horizontal = 4.dp, vertical = 10.dp).testTag("delete_all"),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), color = P.danger)
            }
        }
    }
}

/** What each agent is called in Droid: the AI behind it, not the tool. */
fun aiName(e: Engine) = when (e) { Engine.CLAUDE -> "Claude"; Engine.CODEX -> "ChatGPT"; Engine.OPENCODE -> "Free" }

/**
 * Your account: who's signed in, the AI for new chats (one that isn't set up yet goes to Setup), and how hard it
 * thinks. A chat keeps what it started with. Other ways to sign in (a Console account, API keys) are in Setup.
 */
@Composable
fun AccountScreen(onBack: () -> Unit, onSetup: () -> Unit) {
    val app = LocalContext.current.applicationContext as DroidApp
    val prefs = app.prefs
    val setup by app.setup.state.collectAsState()
    val engine by prefs.engine.collectAsState()
    val models by prefs.models.collectAsState()
    SubScreen("Account", onBack, backTag = "account_close") {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
            // who's signed in to Claude; with no Claude account, the AI that new chats use
            val (who, whoSub) = when {
                setup.email != null -> setup.email!! to (planLabel(setup.plan)?.let { "Signed in to $it" } ?: if (setup.signedIn) "Signed in" else null)
                setup.signedIn -> "Signed in to Claude" to planLabel(setup.plan)
                engine == Engine.OPENCODE -> "Free AI" to "No account needed"
                engine == Engine.CODEX && setup.others[engine]?.signedIn == true -> "ChatGPT" to setup.others[engine]?.detail
                else -> "Not signed in" to null
            }
            KitRow(who, sub = whoSub, lead = { Avatar(setup.email, 42.dp) })
            KitLabel("AI for new chats")
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Engine.entries.forEach { e ->
                    val usable = if (e == Engine.CLAUDE) setup.signedIn else setup.others[e]?.signedIn == true
                    val sub = when {
                        e == Engine.CLAUDE && setup.signedIn -> planLabel(setup.plan)?.let { "Your $it plan" } ?: "Your Claude account"
                        e == Engine.CLAUDE -> "Not set up yet"
                        usable -> setup.others[e]?.detail ?: "Ready"
                        e == Engine.CODEX -> "Not set up yet: your ChatGPT account, free or paid"
                        else -> "Not set up yet: no account, simple jobs only"
                    }
                    AiOption(aiName(e), sub, picked = e == engine, usable = usable, tag = "engine_" + e.id) {
                        if (usable) prefs.setEngine(e) else onSetup()
                    }
                }
            }
            val choices = engine.models
            if (choices.size > 1) {
                KitLabel("How hard it thinks")
                val current = choices.indexOfFirst { it.alias == models[engine].orEmpty() }.coerceAtLeast(0)
                Box(Modifier.padding(top = 8.dp)) { Segmented(choices.map { it.label }, current) { prefs.setModel(engine, choices[it].alias) } }
                Text(choices[current].detail + ". A chat keeps what it started with.", Modifier.padding(start = 4.dp, top = 10.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp), color = P.muted)
            } else if (engine.custom) {
                Box(Modifier.padding(top = 14.dp)) { ModelItem(engine, models[engine].orEmpty()) { prefs.setModel(engine, it) } }
            }
            Spacer(Modifier.height(18.dp))
            KitRow("Other ways to sign in", Modifier.testTag("other_sign_in"), sub = "API keys, a Console account", line = true,
                onClick = onSetup) { Chevron() }
        }
    }
}

/** One AI to pick: its name, what it takes, and a check while it's the one. */
@Composable
private fun AiOption(name: String, sub: String, picked: Boolean, usable: Boolean, tag: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Row(Modifier.fillMaxWidth().clip(shape).background(if (picked) Lavender else P.bg).border(1.5.dp, if (picked) Ring else P.hairline, shape)
        .clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 14.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold))
            Text(sub, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp), color = P.muted)
        }
        when {
            picked -> Box(Modifier.size(26.dp).clip(CircleShape).background(P.call), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_check), null, Modifier.size(16.dp), tint = OnCall)
            }
            !usable -> Chevron()
            else -> Box(Modifier.size(26.dp).clip(CircleShape).border(2.dp, P.hairline, CircleShape))
        }
    }
}

/** Droid's version, its source and the licenses of what it's built with and downloads ([about.md](assets/about.md)). */
@Composable
fun AboutScreen(onBack: () -> Unit, onLicenses: () -> Unit) {
    val ctx = LocalContext.current
    val text = remember { runCatching { ctx.assets.open("about.md").bufferedReader().use { it.readText() } }.getOrDefault("") }
    SubScreen("About", onBack, backTag = "about_close") {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp)) {
            Text("Droid ${remember { Updates.version(ctx) }}", Modifier.padding(top = 8.dp, bottom = 12.dp), style = MaterialTheme.typography.headlineSmall)
            Markdown(text)
            KitRow("Licenses", Modifier.testTag("about_licenses"), sub = "Their full texts", line = true, onClick = onLicenses) { Chevron() }
            Spacer(Modifier.height(32.dp))
        }
    }
}

/**
 * The full license texts of what's built into Droid (assets/licenses, one file each: its title line, then the text), as
 * their licenses ask. Their hard-wrapped lines are joined into paragraphs that fit the screen; indented lines stay.
 */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val parts by produceState(emptyList<Pair<Boolean, String>>()) {
        value = withContext(Dispatchers.IO) {
            val files = ctx.assets.list("licenses").orEmpty().sorted()
            files.flatMap { f ->
                val text = runCatching { ctx.assets.open("licenses/$f").bufferedReader().use { it.readText() } }.getOrDefault("")
                val title = text.substringBefore('\n')
                listOf(true to title) + paragraphs(text.substringAfter('\n', "")).map { false to it }
            }
        }
    }
    SubScreen("Licenses", onBack, backTag = "licenses_close") {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
            items(parts.size) { i ->
                val (title, text) = parts[i]
                if (title) Text(text, Modifier.padding(top = if (i == 0) 8.dp else 28.dp, bottom = 8.dp), style = MaterialTheme.typography.titleMedium)
                else Text(text, Modifier.padding(bottom = 10.dp), style = MaterialTheme.typography.bodySmall, color = P.muted)
            }
        }
    }
}

/**
 * [text]'s paragraphs (split at blank lines), less their common indent, each line joined to the one before unless it's
 * indented further or a list item.
 */
internal fun paragraphs(text: String): List<String> =
    text.split(Regex("\n\\s*\n")).map { p ->
        p.trimIndent().lines().fold("") { acc, line ->
            when {
                acc.isEmpty() -> line.trimEnd()
                line.startsWith(" ") || line.startsWith("\t") || Regex("^([-*•]|\\d+[.)]|\\([a-z0-9]+\\))\\s").containsMatchIn(line) -> acc + "\n" + line.trimEnd()
                else -> acc + " " + line.trim()
            }
        }
    }.filter { it.isNotBlank() }

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
private fun ModelItem(engine: Engine, model: String, set: (String) -> Unit) {
    Box {
        var open by remember { mutableStateOf(false) }
        var typing by remember { mutableStateOf(false) }
        if (typing) TextDialog("Model name", model, "Use", { set(it.trim()) }, { typing = false })
        // a model's name as people say it: its last part ("llama-3.3-70b-instruct", not the provider path)
        KitRow("Model", Modifier.testTag("settings_model"), sub = if (model.isEmpty()) "Chosen for you" else model.substringAfterLast('/'),
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
            val on = i == selected  // picked: lavender, like every pick
            Text(
                o, Modifier.clip(RoundedCornerShape(50)).background(if (on) Lavender else Color.Transparent).clickable { onSelect(i) }
                    .padding(horizontal = 12.dp, vertical = 6.dp).testTag("seg_" + o.lowercase()),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (on) FontWeight.Bold else FontWeight.Medium), color = if (on) CallText else P.text,
            )
        }
    }
}
