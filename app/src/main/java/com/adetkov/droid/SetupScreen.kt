package com.adetkov.droid

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * First-run setup ([FirstRun]: connect, install, sign in, each started by the one before) and later maintenance (Settings
 * → Setup: one row per step, each with its state and action).
 */
@Composable
fun SetupScreen(setup: Setup, firstRun: Boolean, onClose: () -> Unit) {
    val s by setup.state.collectAsState()
    val ctx = LocalContext.current
    val idle = s.busy == null && s.signInUrl == null && s.pairing == null
    var confirmRemove by remember { mutableStateOf(false) }
    // Also on coming back: from the root manager, say, where Droid was just allowed root.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { setup.refresh() }
    // Pairing asks for its code in a notification and finds Wireless debugging by mDNS (Android 17: a permission).
    var afterPermissions by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        afterPermissions?.invoke(); afterPermissions = null
    }
    fun withPermissions(then: () -> Unit) {
        afterPermissions = then
        permissions.launch(listOfNotNull(android.Manifest.permission.POST_NOTIFICATIONS,
            AdbHelper.LOCAL_NETWORK.takeIf { android.os.Build.VERSION.SDK_INT >= 37 }).toTypedArray())
    }
    BackHandler(s.ready && !firstRun) { onClose() }

    if (confirmRemove) ConfirmDialog(
        "Remove Droid's Linux?", "Deletes Alpine, the agents, their sign-ins, the agent's instructions and its chat transcripts from the phone" +
            if (s.access == Access.ROOT) ": root's Linux, and the one Droid uses through Wireless debugging." else ".",
        "Remove", { setup.remove() }, { confirmRemove = false }, danger = true,
    )

    Column(Modifier.fillMaxSize().background(P.bg).imePadding()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!firstRun) IconBtn(R.drawable.ic_back, "Back", onClose, Modifier.testTag("setup_close"), size = 44.dp)
            Text(if (firstRun) "" else "Setup", Modifier.padding(start = 6.dp), style = MaterialTheme.typography.titleMedium)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp),
        ) {
            if (firstRun) FirstRun(setup, s, idle, ::withPermissions) else Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(P.surface)) {
                if (s.access == Access.ROOT) {
                    Step(1, "Root access", if (s.root) "Granted" else "Needs Magisk, KernelSU or APatch. Allow Droid when asked.", s.root) {
                        if (!s.root) Action("Check", idle, primary = true) { setup.refresh() }
                    }
                } else {
                    val detail = when {
                        s.helper -> "Connected. Leave USB debugging on: it keeps Droid's helper running."
                        s.paired -> s.helperError ?: "Paired. Droid starts its helper on Wi-Fi."
                        else -> "No root needed: pair Droid with Android's Wireless debugging, once."
                    }
                    Step(1, "Wireless debugging", detail, s.helper) {
                        if (!s.paired && s.pairing == null) Action("Pair", idle, primary = true) { withPermissions { pair(setup, ctx) } }
                        else if (s.paired && !s.helper) Action("Start", idle, primary = true) { withPermissions(setup::startHelper) }
                    }
                    SecureSettings(s)
                    AnimatedVisibility(s.pairing != null) { Pairing(s, setup) }
                    if (s.paired && s.pairing == null && !s.helper) TextButton({ withPermissions { pair(setup, ctx) } }, Modifier.padding(start = 54.dp), enabled = idle) {
                        Text("Pair again", color = P.muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
                AccessSwitch(setup, s, idle)
                Line()
                Step(2, "Linux and Claude Code", if (s.installed) "Installed" else "Alpine Linux with Claude Code, about 350 MB", s.installed) {
                    if (s.connected) Action(if (s.installed) "Update" else "Install", idle, primary = !s.installed) { setup.install() }
                }
                Line()
                Step(3, "Claude account", s.email ?: if (s.signedIn) "Signed in" else "Your Claude subscription, or a Console account billed per use", s.signedIn) {
                    if (s.installed && s.signInUrl == null) {
                        Action(if (s.signedIn) "Switch" else "Sign in", idle, primary = !s.signedIn) { setup.signIn(console = false) }
                    }
                }
                if (s.installed && !s.signedIn && s.signInUrl == null) TextButton({ setup.signIn(console = true) },
                    Modifier.padding(start = 54.dp).testTag("setup_console"), enabled = idle) {
                    Text("Use an Anthropic Console account instead", color = P.muted, style = MaterialTheme.typography.bodySmall)
                }
                AnimatedVisibility(s.signInUrl != null && s.deviceCode == null) { s.signInUrl?.let { SignIn(it, setup) } }
                Line()
                Step(4, "Voice", if (s.voice) "Installed" else "Talk to Droid and hear answers, all on the phone. 820 MB.", s.voice, optional = true) {
                    Action(if (s.voice) "Redownload" else "Download", idle, primary = false) { setup.downloadVoice() }
                }
                Line()
                Step(5, "Assistant", if (s.assistant) "Long-press power to talk to Droid" else "Open Droid by long-pressing power", s.assistant, optional = true) {
                    if (s.connected && !s.assistant) Action("Set", idle) { setup.makeAssistant() }
                }
            }

            if (s.inputBlocked) InputBlocked()
            if (!firstRun && s.installed) OtherAgents(setup, s, idle)

            // On first run the install's progress shows in its step
            s.busy?.takeUnless { firstRun && installing(s) }?.let {
                Column(Modifier.padding(top = 16.dp, start = 4.dp, end = 4.dp)) {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = P.muted)
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(2.dp)), color = P.ink, trackColor = P.raised)
                }
            }
            s.error?.let { err ->
                Column(Modifier.padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, P.danger.copy(alpha = .5f), RoundedCornerShape(16.dp)).padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_error), null, Modifier.size(18.dp), tint = P.danger)
                        Text("Something went wrong", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleSmall, color = P.danger)
                    }
                    Text(err, Modifier.padding(top = 8.dp).testTag("setup_error"), style = MaterialTheme.typography.bodySmall, color = P.muted)
                    TextButton(setup::dismissError, Modifier.align(Alignment.End)) { Text("Dismiss", color = P.text) }
                }
            }

            Row(Modifier.padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, P.hairline, RoundedCornerShape(16.dp)).padding(16.dp)) {
                Icon(painterResource(R.drawable.ic_shield), null, Modifier.size(20.dp), tint = P.muted)
                Text(
                    (if (s.access == Access.ROOT) "Droid gives the agent a root shell on this phone, with web access. "
                    else "Droid gives the agent a shell on this phone with adb's rights (your apps and settings, not their private data), with web access. ") +
                        "Anything it reads (web pages, messages, files) could try to steer it, so give it tasks you'd trust it with." +
                        (if (s.access == Access.ROOT) "" else " Without root, Droid keeps USB debugging on, which some banking apps refuse to run with."),
                    Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodySmall, color = P.muted,
                )
            }

            if (s.ready && firstRun) Ready(setup, s, onClose)
            // Whatever is there, even half installed, but only while Droid can reach it
            if (s.linux && s.connected && !firstRun) TextButton({ confirmRemove = true }, Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp), enabled = idle) {
                Text("Remove Droid's Linux", color = P.danger)
            }
            CopyDiagnostics(setup, Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * First run: three steps, each started by the one before. Connect (root, or Wireless debugging, guided by
 * [PairingService]), Install (by itself once connected) and Sign in (in the browser, which hands back by itself). Then
 * [Ready]. Other agents wait behind a link: Claude Code is the one to start with.
 */
@Composable
private fun ColumnScope.FirstRun(setup: Setup, s: SetupState, idle: Boolean, withPermissions: (() -> Unit) -> Unit) {
    val ctx = LocalContext.current
    var others by rememberSaveable { mutableStateOf(false) }
    // The install starts by itself once Droid is connected, once per visit (a failed one waits for its button).
    var autoInstalled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(s.connected, s.installed, idle) {
        if (!autoInstalled && s.connected && !s.installed && idle && s.error == null && setup.autoInstall) { autoInstalled = true; setup.install() }
    }
    // Dash thinks while a step runs and cheers when Droid is ready
    DashMini(when { s.ready -> Feel.HAPPY; !idle || s.busy != null -> Feel.THINKING; else -> Feel.IDLE }, 84.dp,
        Modifier.padding(top = 4.dp), label = "Dash, Droid's character")
    Text("Set up Droid", Modifier.padding(start = 4.dp, top = 8.dp), style = MaterialTheme.typography.headlineSmall)
    Text("An AI agent that uses your apps on a hidden screen. Three steps, a few minutes.", Modifier.padding(start = 4.dp, top = 6.dp, bottom = 20.dp),
        style = MaterialTheme.typography.bodyMedium, color = P.muted)

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(P.surface)) {
        // Until the first check, nothing says whether this phone has root (a root manager may be asking right now)
        if (!s.checked) Step(1, "Connect", "Checking this phone…", false, progress = true) {}
        else if (s.access == Access.ROOT) {
            Step(1, "Connect", if (s.root) "Root access granted" else "Allow Droid in Magisk, KernelSU or APatch when it asks.", s.root) {
                if (!s.root) Action("Check", idle, primary = true) { setup.refresh() }
            }
        } else {
            Step(1, "Connect", when {
                s.helper -> "Connected through Wireless debugging"
                s.paired -> s.helperError ?: "Paired. Droid starts its helper on Wi-Fi."
                else -> "Through Android's Wireless debugging, once. No root needed."
            }, s.helper) {
                if (!s.paired && s.pairing == null) Action("Start", idle, primary = true) { withPermissions { pair(setup, ctx) } }
                else if (s.paired && !s.helper) Action("Start", idle, primary = true) { withPermissions(setup::startHelper) }
            }
            SecureSettings(s)
            AnimatedVisibility(s.pairing != null) { Pairing(s, setup) }
        }
        AccessSwitch(setup, s, idle)
        Line()
        val installing = installing(s)
        Step(2, "Install", when {
            s.installed -> "Claude Code is ready"
            installing -> s.busy.orEmpty()
            else -> "Claude Code and the Linux it runs in, about 350 MB." + if (setup.autoInstall) " Starts by itself." else ""
        }, s.installed, upcoming = !s.connected, progress = installing) {
            if (s.connected && !s.installed && !installing) Action("Install", idle, primary = true) { setup.install() }
        }
        Line()
        Step(3, "Sign in", s.email ?: if (s.signedIn) "Signed in" else "With your Claude subscription", s.signedIn, upcoming = !s.installed) {
            if (s.installed && !s.signedIn && s.signInUrl == null) Action("Sign in", idle, primary = true) { setup.signIn(console = false) }
        }
        AnimatedVisibility(s.signInUrl != null && s.deviceCode == null) { s.signInUrl?.let { SignIn(it, setup) } }
        if (s.installed && !s.signedIn && s.signInUrl == null) Column(Modifier.padding(start = 54.dp, bottom = 6.dp)) {
            TextButton({ setup.signIn(console = true) }, Modifier.testTag("setup_console"), enabled = idle) {
                Text("Use a Console account", color = P.muted, style = MaterialTheme.typography.bodySmall)
            }
            TextButton({ others = !others }, Modifier.testTag("setup_others")) {
                Text(if (others) "Hide other agents" else "Use ChatGPT or free models", color = P.muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (s.installed && (others || s.signInUrl != null && s.deviceCode != null || s.others.values.any { it.signedIn } && !s.signedIn)) OtherAgents(setup, s, idle)
}

/** Without root, once connected: Android wouldn't let Droid switch Wireless debugging on by itself, which it needs after a restart. Checked again on coming back here, so it goes once the setting is on. */
@Composable
private fun SecureSettings(s: SetupState) {
    if (s.access == Access.ADB && s.helper && !s.secureSettings) Text(
        "Android didn't let Droid switch Wireless debugging on by itself, which it needs to start again after a " +
            "restart. In Developer options, turn on \"USB debugging (Security settings)\" on Xiaomi, Redmi or POCO, " +
            "or \"Disable permission monitoring\" on OPPO, OnePlus or realme, then come back here.",
        Modifier.padding(start = 54.dp, end = 16.dp, bottom = 14.dp).testTag("setup_secure_settings"),
        style = MaterialTheme.typography.bodySmall, color = P.danger,
    )
}

/** The install is running: Droid is connected, there is no Linux yet, and a step is under way. */
private fun installing(s: SetupState) = s.busy != null && s.connected && !s.installed

/** Starts pairing and opens Settings where its first step is, once the notification that guides it (and takes the code) can show. */
private fun pair(setup: Setup, ctx: android.content.Context) {
    setup.pair()
    if (ctx.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()) openDeveloperOptions(ctx, "toggle_adb_wireless")
}

/** Root or not, Wireless debugging works too (the agent then has the shell user's rights, not root's). Root is offered wherever there is an su, even one that refuses Droid for now. */
@Composable
private fun AccessSwitch(setup: Setup, s: SetupState, idle: Boolean) {
    if (s.pairing == null && (s.access == Access.ROOT || s.su)) TextButton({ setup.setAccess(if (s.access == Access.ROOT) Access.ADB else Access.ROOT) },
        Modifier.padding(start = 54.dp).testTag("setup_access"), enabled = idle) {
        Text(if (s.access == Access.ROOT) "Use Wireless debugging instead of root" else "Use root instead",
            color = P.muted, style = MaterialTheme.typography.bodySmall)
    }
}

/** Codex and OpenCode: Settings → Setup always; on first run behind a link, Claude Code being the one to start with. */
@Composable
private fun OtherAgents(setup: Setup, s: SetupState, idle: Boolean) {
    Text("Other agents", Modifier.padding(start = 4.dp, top = 24.dp, bottom = 8.dp), style = MaterialTheme.typography.labelLarge, color = P.muted)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(P.surface)) {
        val codex = s.others[Engine.CODEX]
        Step(1, "Codex", codex?.detail ?: "OpenAI's coding agent, about 120 MB. Sign in with ChatGPT or an API key.", codex?.signedIn == true, optional = true) {
            if (codex == null) Action("Install", idle) { setup.installEngine(Engine.CODEX) }
            else if (s.signInUrl == null) {
                Action(if (codex.signedIn) "Switch" else "Sign in", idle, primary = !codex.signedIn) { setup.signInCodex() }
            }
        }
        if (codex != null && s.signInUrl == null) {
            KeyEntry("Use an OpenAI API key instead", idle, null) { _, key -> setup.codexApiKey(key) }
        }
        AnimatedVisibility(s.signInUrl != null && s.deviceCode != null) {
            DeviceSignIn(s.signInUrl.orEmpty(), s.deviceCode.orEmpty(), setup)
        }
        Line()
        val oc = s.others[Engine.OPENCODE]
        Step(2, "OpenCode", oc?.detail ?: "Open-source agent, about 80 MB. Free models with no account, or a key for Anthropic, OpenAI, Google, OpenRouter or a local model.", oc != null, optional = true) {
            if (oc == null) Action("Install", idle) { setup.installEngine(Engine.OPENCODE) }
        }
        if (oc != null) KeyEntry("Add a provider API key", idle, "Provider (anthropic, openai, google, openrouter)") { provider, key ->
            setup.openCodeKey(provider.orEmpty(), key)
        }
    }
    Text("Pick the agent for new chats in Settings. A chat keeps the agent it started with.", Modifier.padding(start = 4.dp, top = 8.dp),
        style = MaterialTheme.typography.bodySmall, color = P.muted)
}

/**
 * First run, once Droid can take a task: what's worth having that needs nothing more from the owner (each on unless
 * they turn it off: voice only on Wi-Fi), done in the background after Start (see [Setup.finish]).
 */
@Composable
private fun Ready(setup: Setup, s: SetupState, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var assistant by rememberSaveable { mutableStateOf(true) }
    var voice by rememberSaveable { mutableStateOf(!ctx.getSystemService(android.net.ConnectivityManager::class.java).isActiveNetworkMetered) }
    if (!s.assistant || !s.voice) Column(Modifier.padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(P.surface).padding(vertical = 4.dp)) {
        if (!s.assistant) ToggleItem(R.drawable.ic_sparkle, "Long-press power for Droid", "Ask it from any app or the lock screen", assistant, { assistant = it }, "setup_assistant")
        if (!s.voice) ToggleItem(R.drawable.ic_mic, "Talk to Droid", "Speak and hear answers, all on this phone. Downloads 820 MB after you start.", voice, { voice = it }, "setup_voice")
    }
    Box(
        Modifier.padding(top = 20.dp).fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp)).background(P.ink)
            .clickable { s.readyEngine?.let(setup::useEngine); setup.finish(assistant && !s.assistant, voice && !s.voice); onClose() }.testTag("setup_done"),
        contentAlignment = Alignment.Center,
    ) { Text("Start", style = MaterialTheme.typography.labelLarge, color = P.onInk) }
}

@Composable
private fun Line() = HorizontalDivider(Modifier.padding(start = 54.dp), color = P.hairline.copy(alpha = .7f))

@Composable
private fun Step(
    n: Int, title: String, detail: String, done: Boolean, optional: Boolean = false, upcoming: Boolean = false, progress: Boolean = false,
    actions: @Composable RowScope.() -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (done) Icon(painterResource(R.drawable.ic_done_circle), "Done", Modifier.size(24.dp), tint = P.success)
            else Box(Modifier.size(22.dp).clip(CircleShape).border(1.5.dp, P.faint, CircleShape), contentAlignment = Alignment.Center) {
                Text("$n", style = MaterialTheme.typography.labelSmall, color = P.muted)
            }
        }
        Column(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = if (upcoming) P.muted else P.text)
                if (optional && !done) Text("  Optional", style = MaterialTheme.typography.labelSmall, color = P.faint)
            }
            Text(detail, style = MaterialTheme.typography.bodySmall, color = if (upcoming) P.faint else P.muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (progress) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(2.dp)), color = P.ink, trackColor = P.raised)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), content = actions)
    }
}

@Composable
private fun Action(label: String, enabled: Boolean, primary: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(
        label,
        Modifier.clip(shape).then(if (primary) Modifier.background(if (enabled) P.ink else P.raised) else Modifier.border(1.dp, P.hairline, shape))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("setup_" + label.lowercase().replace(' ', '_')),
        style = MaterialTheme.typography.labelLarge,
        color = when { !enabled -> P.faint; primary -> P.onInk; else -> P.text },
    )
}

/** Opens Developer options (About phone while they're off), scrolled to [key] where the phone supports that. */
private fun openDeveloperOptions(ctx: android.content.Context, key: String? = null) = try {
    ctx.startActivity(pairingSettings(ctx, key))
} catch (_: Exception) {
    ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
}

/**
 * Android refused Droid's test tap: some makers keep adb from tapping and typing until a switch of theirs in Developer
 * options is on (Xiaomi's is documented by scrcpy; OPPO's, OnePlus', realme's and vivo's go by another name).
 */
@Composable
private fun InputBlocked() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val fix = when (android.os.Build.MANUFACTURER.lowercase()) {
        "xiaomi" -> "In Developer options, turn on USB debugging (Security settings), then restart the phone. Xiaomi asks you to sign in to a Xiaomi account first."
        "oppo", "oneplus", "realme", "vivo" -> "In Developer options, turn on Disable permission monitoring."
        else -> "Look in Developer options for a switch that lets USB debugging control the phone."
    }
    Column(Modifier.padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, P.danger.copy(alpha = .5f), RoundedCornerShape(16.dp))
        .padding(16.dp).testTag("setup_input_blocked")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_error), null, Modifier.size(18.dp), tint = P.danger)
            Text("The agent can't tap or type", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleSmall, color = P.danger)
        }
        Text("This phone refused Droid's test tap. $fix", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = P.muted)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
            Action("Open settings", true, primary = true) { openDeveloperOptions(ctx) }
        }
    }
}

/** Copies [Setup.diagnostics] for a bug report: the phone, Droid and its setup, nothing private. */
@Composable
private fun CopyDiagnostics(setup: Setup, modifier: Modifier) {
    val copy = rememberCopy()
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(0) }  // 0: idle, 1: collecting, 2: copied
    LaunchedEffect(state) { if (state == 2) { delay(3000); state = 0 } }
    TextButton({
        state = 1
        scope.launch { copy(withContext(Dispatchers.IO) { setup.diagnostics() }); state = 2 }
    }, modifier.testTag("setup_diagnostics"), enabled = state == 0) {
        Text(when (state) { 1 -> "Collecting diagnostics…"; 2 -> "Diagnostics copied"; else -> "Copy diagnostics for a bug report" },
            color = P.muted, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * While Droid pairs with Wireless debugging: the steps in Settings, ticked off as [PairingService] sees them done (its
 * notification says the same while Settings is in front), then the code, which goes into Droid's notification since
 * Settings closes the code's dialog when you leave it.
 */
@Composable
private fun Pairing(s: SetupState, setup: Setup) {
    val ctx = LocalContext.current
    val steps = PairingService.STEPS + ("Enter the pairing code" to "Type the six digits Settings shows into Droid's notification.")
    Column(Modifier.fillMaxWidth().padding(start = 54.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        steps.forEachIndexed { i, (title, how) ->
            val done = i < s.pairStep
            val now = i == s.pairStep
            Row(Modifier.testTag("pair_step_$i")) {
                Box(Modifier.padding(top = 1.dp).size(18.dp), contentAlignment = Alignment.Center) {
                    if (done) Icon(painterResource(R.drawable.ic_done_circle), "Done", Modifier.size(18.dp), tint = P.success)
                    else Text("${i + 1}", style = MaterialTheme.typography.labelMedium, color = if (now) P.text else P.faint)
                }
                Column(Modifier.padding(start = 10.dp)) {
                    Text(title, style = MaterialTheme.typography.bodyMedium, color = if (now) P.text else P.muted)
                    if (now) Text(how, style = MaterialTheme.typography.bodySmall, color = P.muted)
                }
            }
        }
        // what comes after the code, or why the last try failed
        s.pairing?.takeIf { status -> steps.none { it.first == status } }?.let {
            Text(it, Modifier.testTag("setup_pairing"), style = MaterialTheme.typography.bodySmall, color = P.muted)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextButton(setup::cancelPairing) { Text("Cancel", color = P.muted) }
            if (s.pairStep < PairingService.CODE_STEP) Action("Open settings", true, primary = true) { openDeveloperOptions(ctx, "toggle_adb_wireless") }
        }
    }
}

/**
 * While `claude auth login` waits. Its page opened in the browser by itself (see [Setup.signIn]), and signing in there
 * finishes on its own and brings Droid back. Signing in elsewhere (another device, a browser that didn't open) goes by
 * the printed [url] instead, whose page shows a code to paste here.
 */
@Composable
private fun SignIn(url: String, setup: Setup) {
    val uri = LocalUriHandler.current
    val copy = rememberCopy()
    var code by rememberSaveable { mutableStateOf("") }
    var paste by rememberSaveable(url) { mutableStateOf(false) }
    fun open() = try { uri.openUri(url) } catch (_: Exception) {}  // no app handles web links
    Column(Modifier.fillMaxWidth().padding(start = 54.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!paste) {
            Text("Sign in on the Claude page in your browser. Droid comes back by itself when you're done.",
                Modifier.testTag("setup_signin_wait"), style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton({ paste = true }, Modifier.testTag("setup_paste")) { Text("Paste a code instead", color = P.muted) }
                TextButton(setup::cancelSignIn) { Text("Cancel", color = P.muted) }
            }
            return@Column
        }
        Text("Open this page, sign in, then paste the code it shows.", style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(url, Modifier.weight(1f).testTag("setup_url"), style = MaterialTheme.typography.bodySmall, color = P.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton({ copy(url) }) { Text("Copy", color = P.text) }
            TextButton(::open) { Text("Open", color = P.text) }
        }
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(P.bg).border(1.dp, P.hairline, RoundedCornerShape(12.dp)).padding(14.dp)) {
            if (code.isEmpty()) Text("Paste the code", style = MaterialTheme.typography.bodyLarge, color = P.muted)
            BasicTextField(code, { code = it }, Modifier.fillMaxWidth().testTag("setup_code"), singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = P.text), cursorBrush = SolidColor(P.text))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextButton(setup::cancelSignIn) { Text("Cancel", color = P.muted) }
            Action("Continue", code.isNotBlank(), primary = true) { setup.submitCode(code) }
        }
    }
}

/** While `codex login --device-auth` waits: enter [code] at [url] (on this phone or any device); it finishes by itself. */
@Composable
private fun DeviceSignIn(url: String, code: String, setup: Setup) {
    val uri = LocalUriHandler.current
    val copy = rememberCopy()
    var opened by rememberSaveable(url) { mutableStateOf(false) }
    fun open() = try { uri.openUri(url) } catch (_: Exception) {}
    LaunchedEffect(url) { if (!opened) { opened = true; open() } }
    Column(Modifier.fillMaxWidth().padding(start = 54.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Open the page, sign in to ChatGPT and enter this code. It finishes by itself.", style = MaterialTheme.typography.bodyMedium)
        Text(code, Modifier.testTag("setup_device_code"), style = MaterialTheme.typography.headlineSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(url, Modifier.weight(1f).testTag("setup_url"), style = MaterialTheme.typography.bodySmall, color = P.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton({ copy(code) }) { Text("Copy code", color = P.text) }
            TextButton(::open) { Text("Open", color = P.text) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(setup::cancelSignIn) { Text("Cancel", color = P.muted) } }
    }
}

/** A link that opens fields for an API key (and, if [providerHint] is given, the provider it is for). */
@Composable
private fun KeyEntry(label: String, enabled: Boolean, providerHint: String?, save: (provider: String?, key: String) -> Unit) {
    var open by rememberSaveable(label) { mutableStateOf(false) }
    var provider by rememberSaveable(label) { mutableStateOf("") }
    var key by remember(label) { mutableStateOf("") }  // not saved with the screen's state
    if (!open) {
        TextButton({ open = true }, Modifier.padding(start = 54.dp).testTag("setup_key_" + label.take(6).lowercase().replace(' ', '_')), enabled = enabled) {
            Text(label, color = P.muted, style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    @Composable fun field(value: String, hint: String, tag: String, secret: Boolean = false, set: (String) -> Unit) =
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(P.bg).border(1.dp, P.hairline, RoundedCornerShape(12.dp)).padding(14.dp)) {
            if (value.isEmpty()) Text(hint, style = MaterialTheme.typography.bodyLarge, color = P.muted)
            BasicTextField(value, set, Modifier.fillMaxWidth().testTag(tag), singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = P.text), cursorBrush = SolidColor(P.text),
                visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                keyboardOptions = if (secret) androidx.compose.foundation.text.KeyboardOptions(autoCorrectEnabled = false,
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Password) else androidx.compose.foundation.text.KeyboardOptions.Default)
        }
    Column(Modifier.fillMaxWidth().padding(start = 54.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (providerHint != null) field(provider, providerHint, "setup_provider") { provider = it }
        field(key, "Paste the API key", "setup_apikey", secret = true) { key = it }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextButton({ open = false; key = "" }) { Text("Cancel", color = P.muted) }
            Action("Save", enabled && key.isNotBlank() && (providerHint == null || provider.isNotBlank()), primary = true) {
                save(provider.takeIf { providerHint != null }, key); open = false; key = ""
            }
        }
    }
}
