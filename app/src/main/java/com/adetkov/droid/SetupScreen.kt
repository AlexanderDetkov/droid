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

/** First-run setup and later maintenance: one row per step, each with its state and action. */
@Composable
fun SetupScreen(setup: Setup, firstRun: Boolean, onClose: () -> Unit) {
    val s by setup.state.collectAsState()
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
            if (firstRun) {
                // Dash thinks while a step runs and cheers when Droid is ready
                DashMini(when { s.ready -> Feel.HAPPY; s.busy != null -> Feel.THINKING; else -> Feel.IDLE }, 84.dp,
                    Modifier.padding(top = 4.dp), label = "Dash, Droid's character")
                Text("Set up Droid", Modifier.padding(start = 4.dp, top = 8.dp), style = MaterialTheme.typography.headlineSmall)
                Text("An AI agent running on this phone, using your apps on a hidden screen. Claude Code comes first; Codex and OpenCode can be added.", Modifier.padding(start = 4.dp, top = 6.dp, bottom = 20.dp),
                    style = MaterialTheme.typography.bodyMedium, color = P.muted)
            }

            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(P.surface)) {
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
                        if (!s.paired && s.pairing == null) Action("Pair", idle, primary = true) { withPermissions(setup::pair) }
                        else if (s.paired && !s.helper) Action("Start", idle, primary = true) { withPermissions(setup::startHelper) }
                    }
                    AnimatedVisibility(s.pairing != null) { Pairing(s.pairing.orEmpty(), setup) }
                    if (s.paired && s.pairing == null && !s.helper) TextButton({ withPermissions(setup::pair) }, Modifier.padding(start = 54.dp), enabled = idle) {
                        Text("Pair again", color = P.muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
                // Root or not, Wireless debugging works too (the agent then has the shell user's rights, not root's). Root
                // is offered wherever there is an su, even one that refuses Droid for now.
                if (s.pairing == null && (s.access == Access.ROOT || s.su)) TextButton({ setup.setAccess(if (s.access == Access.ROOT) Access.ADB else Access.ROOT) },
                    Modifier.padding(start = 54.dp).testTag("setup_access"), enabled = idle) {
                    Text(if (s.access == Access.ROOT) "Use Wireless debugging instead of root" else "Use root instead",
                        color = P.muted, style = MaterialTheme.typography.bodySmall)
                }
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

            if (s.installed) {
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
                    Step(2, "OpenCode", oc?.detail ?: "Open-source agent for Anthropic, OpenAI, Google, OpenRouter or a local model, about 80 MB.", oc != null, optional = true) {
                        if (oc == null) Action("Install", idle) { setup.installEngine(Engine.OPENCODE) }
                    }
                    if (oc != null) KeyEntry("Add a provider API key", idle, "Provider (anthropic, openai, google, openrouter)") { provider, key ->
                        setup.openCodeKey(provider.orEmpty(), key)
                    }
                }
                Text("Pick the agent for new chats in Settings. A chat keeps the agent it started with.", Modifier.padding(start = 4.dp, top = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = P.muted)
            }

            s.busy?.let {
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

            if (s.ready && firstRun) Box(
                Modifier.padding(top = 20.dp).fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp)).background(P.ink)
                    .clickable { s.readyEngine?.let(setup::useEngine); onClose() }.testTag("setup_done"),
                contentAlignment = Alignment.Center,
            ) { Text("Start", style = MaterialTheme.typography.labelLarge, color = P.onInk) }
            // Whatever is there, even half installed, but only while Droid can reach it
            if (s.linux && s.connected && !firstRun) TextButton({ confirmRemove = true }, Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp), enabled = idle) {
                Text("Remove Droid's Linux", color = P.danger)
            }
            CopyDiagnostics(setup, Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Line() = HorizontalDivider(Modifier.padding(start = 54.dp), color = P.hairline.copy(alpha = .7f))

@Composable
private fun Step(n: Int, title: String, detail: String, done: Boolean, optional: Boolean = false, actions: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (done) Icon(painterResource(R.drawable.ic_done_circle), "Done", Modifier.size(24.dp), tint = P.success)
            else Box(Modifier.size(22.dp).clip(CircleShape).border(1.5.dp, P.faint, CircleShape), contentAlignment = Alignment.Center) {
                Text("$n", style = MaterialTheme.typography.labelSmall, color = P.muted)
            }
        }
        Column(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (optional && !done) Text("  Optional", style = MaterialTheme.typography.labelSmall, color = P.faint)
            }
            Text(detail, style = MaterialTheme.typography.bodySmall, color = P.muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
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

/**
 * Opens Developer options, scrolled to the setting [key] where the phone supports that. While they're off, About phone
 * instead, at Build number (tapped seven times to turn them on): Developer options' own page would only say they're off.
 */
private fun openDeveloperOptions(ctx: android.content.Context, key: String? = null) {
    fun open(action: String, key: String?) = ctx.startActivity(android.content.Intent(action)
        .apply { key?.let { putExtra(":settings:fragment_args_key", it) } }.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    val on = android.provider.Settings.Global.getInt(ctx.contentResolver, android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
    try {
        if (on) open(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, key)
        else open(android.provider.Settings.ACTION_DEVICE_INFO_SETTINGS, "build_number")
    } catch (_: Exception) {
        open(android.provider.Settings.ACTION_SETTINGS, null)
    }
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
 * While Droid pairs with Wireless debugging ([status] is what it's doing): where to find the pairing code, which goes
 * into Droid's notification, since Settings closes the code's dialog when you leave it.
 */
@Composable
private fun Pairing(status: String, setup: Setup) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    fun openSettings() = openDeveloperOptions(ctx, "toggle_adb_wireless")
    Column(Modifier.fillMaxWidth().padding(start = 54.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(
            "Turn on Developer options: Settings → About phone → tap Build number seven times.",
            "In Developer options, turn on Wireless debugging. Allow it on this network, with Always allow ticked.",
            "Tap Pair device with pairing code, then type the code into Droid's notification.",
        ).forEachIndexed { i, line ->
            Row { Text("${i + 1}.", Modifier.padding(end = 8.dp), style = MaterialTheme.typography.bodyMedium, color = P.muted)
                Text(line, style = MaterialTheme.typography.bodyMedium) }
        }
        Text(status, Modifier.testTag("setup_pairing"), style = MaterialTheme.typography.bodySmall, color = P.muted)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextButton(setup::cancelPairing) { Text("Cancel", color = P.muted) }
            Action("Open settings", true, primary = true) { openSettings() }
        }
    }
}

/**
 * While `claude auth login` waits: sign in on its page, then paste the code it shows. The page opens in the browser
 * when there is one; the link can always be copied, for phones without a browser or to sign in elsewhere.
 */
@Composable
private fun SignIn(url: String, setup: Setup) {
    val uri = LocalUriHandler.current
    val copy = rememberCopy()
    var code by rememberSaveable { mutableStateOf("") }
    var opened by rememberSaveable(url) { mutableStateOf(false) }
    fun open() = try { uri.openUri(url) } catch (_: Exception) {}  // no app handles web links
    LaunchedEffect(url) { if (!opened) { opened = true; open() } }
    Column(Modifier.fillMaxWidth().padding(start = 54.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Sign in on the Claude page, then paste the code it shows.", style = MaterialTheme.typography.bodyMedium)
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
