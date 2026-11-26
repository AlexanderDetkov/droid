package com.adetkov.droid

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** First run ([FirstRun]: one thing at a time, with Dash) and later maintenance (Settings → Setup: one row per step, each with its state and action). */
@Composable
fun SetupScreen(setup: Setup, firstRun: Boolean, onClose: () -> Unit) {
    if (firstRun) return FirstRun(setup, onClose)
    val s by setup.state.collectAsState()
    val ctx = LocalContext.current
    val idle = s.busy == null && s.signInUrl == null && s.pairing == null
    var confirmRemove by remember { mutableStateOf(false) }
    // Also on coming back: from the root manager, say, where Droid was just allowed root.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { setup.refresh() }
    val withPermissions = rememberWithPermissions()
    BackHandler(s.ready) { onClose() }

    if (confirmRemove) ConfirmDialog(
        "Remove Droid's Linux?", "Deletes Alpine, the agents, their sign-ins, the agent's instructions and its chat transcripts from the phone" +
            if (s.access == Access.ROOT) ": root's Linux, and the one Droid uses through Wireless debugging." else ".",
        "Remove", { setup.remove() }, { confirmRemove = false }, danger = true,
    )

    Column(Modifier.fillMaxSize().background(P.bg).imePadding()) {
        Where(R.drawable.ic_back, "Back", onClose, "Setup", leftTag = "setup_close")
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp),
        ) {
            Column(Modifier.fillMaxWidth()) {
                if (s.access == Access.ROOT) {
                    Step(1, "Root access", if (s.root) "Granted" else "Needs Magisk, KernelSU or APatch. Allow Droid when asked.", s.root) {
                        if (!s.root) Action("Check", idle, primary = true) { setup.refresh() }
                    }
                } else {
                    val detail = when {
                        s.helper -> "Connected. Leave Developer options and USB debugging on: Droid needs them."
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
                // with another AI set up for new chats (Free, ChatGPT), a Claude account is one more choice, not a step left
                val engine by (ctx.applicationContext as DroidApp).prefs.engine.collectAsState()
                val otherAi = engine != Engine.CLAUDE && s.others[engine]?.signedIn == true
                Step(3, "Claude account", s.email ?: if (s.signedIn) "Signed in" else "Your Claude subscription, or a Console account billed per use",
                    s.signedIn, optional = otherAi) {
                    if (s.installed && s.signInUrl == null) {
                        Action(if (s.signedIn) "Switch" else "Sign in", idle, primary = !s.signedIn && !otherAi) { setup.signIn(console = false) }
                    }
                }
                if (s.installed && !s.signedIn && s.signInUrl == null) TextButton({ setup.signIn(console = true) },
                    Modifier.padding(start = 54.dp).testTag("setup_console"), enabled = idle) {
                    Text("Use an Anthropic Console account instead", color = P.muted, style = MaterialTheme.typography.bodySmall)
                }
                AnimatedVisibility(s.signInUrl != null && s.deviceCode == null) { s.signInUrl?.let { SignIn(it, setup) } }
                Line()
                Step(4, "Voice", when {
                    !s.voice -> "Talk to Droid and hear answers, all on the phone. 860 MB."
                    !s.voiceCurrent -> "Installed. Better speech-to-text is out: 500 MB"
                    else -> "Installed"
                }, s.voice, optional = true) {
                    if (s.voice && !s.voiceCurrent) Action("Update", idle) { setup.updateSpeechToText() }
                    else Action(if (s.voice) "Redownload" else "Download", idle, primary = false) { setup.downloadVoice() }
                }
                Line()
                Step(5, "Assistant", if (s.assistant) "Long-press power to talk to Droid" else "Open Droid by long-pressing power", s.assistant, optional = true) {
                    if (s.connected && !s.assistant) Action("Set", idle) { setup.makeAssistant() }
                }
            }

            if (s.inputBlocked) InputBlocked()
            s.displayError?.let { DisplayFailed(it, setup) }
            if (s.installed) OtherAgents(setup, s, idle)

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

            // Whatever is there, even half installed, but only while Droid can reach it
            if (s.linux && s.connected) TextButton({ confirmRemove = true }, Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp), enabled = idle) {
                Text("Remove Droid's Linux", color = P.danger)
            }
            CopyDiagnostics(setup, Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Asks for what pairing needs (notifications, for the code; Android 17: the local network, to find Wireless debugging), then goes on either way. */
@Composable
private fun rememberWithPermissions(): (() -> Unit) -> Unit {
    var after by remember { mutableStateOf<(() -> Unit)?>(null) }
    val ctx = LocalContext.current
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val note = android.Manifest.permission.POST_NOTIFICATIONS
        // Turned down for good (twice, and Android won't ask again): the switch is in Droid's notification settings
        if (granted[note] == false && (ctx as? android.app.Activity)?.shouldShowRequestPermissionRationale(note) == false) {
            after = null
            try {
                ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName))
            } catch (_: Exception) {}
            return@rememberLauncherForActivityResult
        }
        after?.invoke(); after = null
    }
    return { then ->
        after = then
        permissions.launch(listOfNotNull(android.Manifest.permission.POST_NOTIFICATIONS,
            AdbHelper.LOCAL_NETWORK.takeIf { android.os.Build.VERSION.SDK_INT >= 37 }).toTypedArray())
    }
}

/** What a first run offers to think with: free models (OpenCode, no account), ChatGPT (Codex) or Claude (Claude Code). */
private enum class Brain(val engine: Engine) { FREE(Engine.OPENCODE), CHATGPT(Engine.CODEX), CLAUDE(Engine.CLAUDE) }

private enum class Page { HELLO, CONNECT, PICK, WAIT, CHATGPT, CLAUDE, FIX, SCREEN, TROUBLE, READY }

/** What a first-run page says: a few words, maybe one more line, a code to type, and whether something is under way. */
private data class Words(val headline: String, val line: String? = null, val code: String? = null, val progress: Boolean = false)

/**
 * First run, one thing at a time: Dash, a few words and a button, always in the same place. Hello; Connect (root, or
 * Wireless debugging, which [PairingService]'s notification walks through in Settings); a brain to think with, picked
 * while the install runs by itself; its sign-in, if it has one; All set. The rest is in Settings → Setup.
 */
@Composable
private fun FirstRun(setup: Setup, onClose: () -> Unit) {
    val s by setup.state.collectAsState()
    val ctx = LocalContext.current
    val uri = LocalUriHandler.current
    val copy = rememberCopy()
    val withPermissions = rememberWithPermissions()
    // Also on coming back: from Settings or the root manager, say.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { setup.refresh() }
    var started by rememberSaveable { mutableStateOf(false) }
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    var paste by rememberSaveable { mutableStateOf(false) }
    var code by rememberSaveable { mutableStateOf("") }
    var skipFix by rememberSaveable { mutableStateOf(false) }
    var skipScreen by rememberSaveable { mutableStateOf(false) }
    var autoInstalled by rememberSaveable { mutableStateOf(false) }
    val brain = Brain.entries.firstOrNull { it.name == picked }
    val idle = s.busy == null && s.signInUrl == null && s.pairing == null
    // The install starts by itself once Droid is connected, once per visit (Try again allows another).
    LaunchedEffect(s.connected, s.installed, idle, s.error, started) {
        // (after Remove too, once the owner has tapped Start: that's them asking to set it up again)
        if (!autoInstalled && s.connected && !s.installed && idle && s.error == null && (setup.autoInstall || started)) { autoInstalled = true; setup.install() }
    }
    // Then the brain picked gets what it needs: OpenCode or Codex installed, and Codex's sign-in, whose code shows here.
    // Claude's sign-in opens the browser, so it waits for its button.
    LaunchedEffect(brain, s.installed, idle, s.error, s.others) {
        val e = brain?.engine ?: return@LaunchedEffect
        if (!s.installed || !idle || s.error != null || s.usable(e)) return@LaunchedEffect
        if (e != Engine.CLAUDE && s.others[e]?.installed != true) setup.installEngine(e)
        else if (e == Engine.CODEX) setup.signInCodex()
    }
    LaunchedEffect(s.deviceCode) { s.deviceCode?.let(copy) }

    fun pick(b: Brain) { picked = b.name; setup.useEngine(b.engine) }
    // the first run's last step: the AI picked, long-press power for Droid, and voice when it's a free download (Wi-Fi)
    fun letsGo() {
        (brain?.engine ?: s.readyEngine)?.let(setup::useEngine)
        val unmetered = !ctx.getSystemService(android.net.ConnectivityManager::class.java).isActiveNetworkMetered
        setup.finish(assistant = !s.assistant, voice = !s.voice && unmetered)
        onClose()
    }
    fun another() { if (s.signInUrl != null) setup.cancelSignIn(); picked = null; paste = false; code = "" }
    fun open(url: String?) { try { url?.let(uri::openUri) } catch (_: Exception) {} }  // no app handles web links

    val done = s.connected && s.installed && if (brain != null) s.usable(brain.engine) else s.ready
    val fix = !skipFix && (s.inputBlocked || s.access == Access.ADB && s.helper && !s.secureSettings)
    val page = when {
        s.error != null -> Page.TROUBLE
        !started && !done -> Page.HELLO
        !s.connected -> Page.CONNECT
        done -> when { s.displayError != null && !skipScreen -> Page.SCREEN; fix -> Page.FIX; else -> Page.READY }
        brain == null -> Page.PICK
        brain == Brain.CHATGPT && s.deviceCode != null -> Page.CHATGPT
        brain == Brain.CLAUDE -> if (s.installed) Page.CLAUDE else Page.WAIT
        else -> Page.WAIT
    }
    val pairing = s.access == Access.ADB && s.pairing != null
    // Back on All set! is Let's go: leaving there would skip its last step (long-press power wouldn't be Droid's)
    BackHandler(page == Page.READY) { letsGo() }
    val words = when (page) {
        Page.HELLO -> Words("Hi, I'm Dash", "I can use your apps for you.")
        Page.CONNECT -> when {
            !s.checked -> Words("One moment", progress = true)
            s.access == Access.ROOT -> Words("Allow me when asked")
            pairing && s.pairStep < PairingService.CODE_STEP -> Words(PairingService.STEPS[s.pairStep].first)
            pairing && s.pairStep == PairingService.CODE_STEP -> Words("Type the code in my notification")
            pairing -> Words("Connecting", progress = true)
            s.paired -> Words("Almost there", s.helperError)
            else -> Words("A few switches, once", "They let me tap and type in your apps. My notification shows each step.")
        }
        Page.PICK -> Words("Which AI should I use?", progress = s.busy != null)
        Page.WAIT -> Words("Getting ready", progress = true)
        Page.CHATGPT -> Words("Enter this code", "It's copied for you.", code = s.deviceCode)
        Page.CLAUDE -> if (paste) Words("Paste the code", progress = s.busy != null) else Words("Sign in to Claude", progress = s.busy != null)
        Page.FIX -> Words("One more switch", makerFix())
        Page.SCREEN -> Words("My own screen won't open", SCREEN_FAILED)
        Page.TROUBLE -> Words("Something went wrong", s.error?.trim()?.lineSequence()?.firstOrNull())
        Page.READY -> Words("All set!", "Hold the power button to talk to me from any app.")
    }
    val feel = when (page) {
        Page.HELLO -> Feel.HAPPY
        Page.PICK -> Feel.CURIOUS
        Page.WAIT -> Feel.WORKING
        Page.TROUBLE, Page.SCREEN -> Feel.CONFUSED
        Page.READY -> Feel.CELEBRATE
        else -> if (words.progress) Feel.WORKING else Feel.NEEDS_YOU
    }
    val dot = when (page) { Page.CONNECT -> 0; Page.PICK, Page.WAIT -> 1; Page.CHATGPT, Page.CLAUDE -> 2; else -> -1 }

    // Droid's four rows, as everywhere: Dash's words are the headline, a line or a code under them, and the next step
    // at the bottom, always in the same place (there's no talking to him yet, so it sits where the bar will be).
    Column(Modifier.fillMaxSize().background(P.bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
            Box(Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.Center) { Dots(dot) }
            val rig = rememberDash(feel = feel)
            LaunchedEffect(feel) { rig.feel(feel) }
            DashLine(words.headline, size = DashSize.BIG, rig = rig, modifier = Modifier.testTag("setup_headline"))
            AnimatedContent(words, Modifier.weight(1f).fillMaxWidth(), transitionSpec = { fadeIn() togetherWith fadeOut() },
                contentAlignment = Alignment.TopStart, label = "words") { w ->
                Column(Modifier.fillMaxWidth().padding(start = 102.dp, end = 24.dp, top = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    w.code?.let {
                        // (in Droid's own type, not monospace, whose O and 0 look alike: codes have both)
                        Text(it, Modifier.testTag("setup_device_code"), style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Medium, letterSpacing = 2.sp))
                    }
                    w.line?.let {
                        Text(it, Modifier.testTag("setup_line"), style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 25.sp), color = P.muted,
                            maxLines = 5, overflow = TextOverflow.Ellipsis)
                    }
                    if (w.progress) LinearProgressIndicator(Modifier.padding(top = 4.dp).width(160.dp).clip(RoundedCornerShape(2.dp)), color = P.call, trackColor = P.raised)
                }
            }
            // The buttons, at the bottom of a space of one size, so Dash and the main button stay put from page to page
            Box(Modifier.fillMaxWidth().heightIn(min = BUTTONS).padding(bottom = 6.dp), contentAlignment = Alignment.BottomCenter) {
                AnimatedContent(page, Modifier.fillMaxWidth(), transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "buttons") { p ->
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        when (p) {
                            Page.HELLO -> Big("Start", "setup_start") { started = true }
                            Page.CONNECT -> when {
                                // (at the code, Settings must stay on it: opening it again would close the code and its port)
                                !s.checked || pairing && s.pairStep >= PairingService.CODE_STEP -> Big("", "setup_wait", enabled = false, hidden = true) {}
                                s.access == Access.ROOT -> Big("Try again", "setup_retry", enabled = idle) { setup.refresh() }
                                pairing -> Big("Open Settings", "setup_open_settings") { openDeveloperOptions(ctx, "toggle_adb_wireless") }
                                s.paired -> Big("Try again", "setup_retry", enabled = idle) { withPermissions(setup::startHelper) }
                                else -> Big("Open Settings", "setup_open_settings", enabled = idle) { withPermissions { pair(setup, ctx) } }
                            }
                            Page.PICK -> {
                                Choice("Free", "For simple jobs. No account.", "setup_free", primary = true) { pick(Brain.FREE) }
                                Choice("ChatGPT", "Smarter. A free account works.", "setup_chatgpt") { pick(Brain.CHATGPT) }
                                Choice("Claude", "Works best with me. Paid plan.", "setup_claude") { pick(Brain.CLAUDE) }
                            }
                            Page.WAIT -> Big("", "setup_wait", enabled = false, hidden = true) {}
                            Page.CHATGPT -> Big("Open ChatGPT", "setup_open_chatgpt") { s.deviceCode?.let(copy); open(s.signInUrl) }
                            Page.CLAUDE -> if (paste && s.signInUrl != null) {
                                Box(Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(56.dp).clip(RoundedCornerShape(28.dp)).border(1.5.dp, P.hairline, RoundedCornerShape(28.dp))
                                    .padding(horizontal = 20.dp), contentAlignment = Alignment.CenterStart) {
                                    if (code.isEmpty()) Text("Code", style = MaterialTheme.typography.bodyLarge, color = P.muted)
                                    BasicTextField(code, { code = it }, Modifier.fillMaxWidth().testTag("setup_code"), singleLine = true,
                                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = P.text), cursorBrush = SolidColor(P.text))
                                }
                                Big("Continue", "setup_submit", enabled = code.isNotBlank() && s.busy == null) { setup.submitCode(code) }
                            } else Big("Open Claude", "setup_sign_in", enabled = s.signInUrl != null || idle) {
                                if (s.signInUrl == null) setup.signIn(console = false) else open(s.signInUrl)
                            }
                            Page.FIX -> Big("Open Settings", "setup_fix") { openDeveloperOptions(ctx) }
                            Page.SCREEN -> Big("Try again", "setup_screen_retry", enabled = idle) { setup.refresh() }
                            Page.TROUBLE -> Big("Try again", "setup_retry") { autoInstalled = false; setup.dismissError() }
                            Page.READY -> Big("Let's go", "setup_done") { letsGo() }
                        }
                        // A quiet way out, in a place of its own under the main button (empty when there's none)
                        Row(Modifier.height(44.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            when (p) {
                                Page.CONNECT -> when {
                                    !s.checked || pairing && s.pairStep > PairingService.CODE_STEP -> {}
                                    s.access == Access.ROOT -> Small("Use without root", "setup_access", enabled = idle) { setup.setAccess(Access.ADB) }
                                    pairing -> Small("Cancel", "setup_cancel") { setup.cancelPairing() }
                                    s.paired -> Small("Pair again", "setup_pair_again", enabled = idle) { withPermissions { pair(setup, ctx) } }
                                    s.su -> Small("Use root instead", "setup_access", enabled = idle) { setup.setAccess(Access.ROOT) }
                                }
                                Page.CHATGPT -> Small("Pick another", "setup_another") { another() }
                                Page.CLAUDE -> when {
                                    paste && s.signInUrl != null -> Small("Back", "setup_back") { paste = false }
                                    s.signInUrl != null -> {
                                        Small("Paste a code", "setup_paste") { paste = true }
                                        Small("Pick another", "setup_another") { another() }
                                    }
                                    else -> Small("Pick another", "setup_another") { another() }
                                }
                                Page.FIX -> Small("Skip", "setup_skip") { skipFix = true }
                                Page.SCREEN -> {
                                    CopyDiagnostics(setup, Modifier)
                                    Small("Skip", "setup_screen_skip") { skipScreen = true }
                                }
                                Page.TROUBLE -> CopyDiagnostics(setup, Modifier)
                                else -> {}
                            }
                        }
                    }
                }
            }
    }
}

/** The first run's button space: room for the three AIs to pick from and a quiet link under them. */
private val BUTTONS = (64 * 3 + 10 * 3 + 44).dp

/** Where the first run is: Connect, Pick, Sign in ([at], or none). */
@Composable
private fun Dots(at: Int) {
    Row(Modifier.height(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (at >= 0) repeat(3) { i ->
            Box(Modifier.size(if (i == at) 18.dp else 6.dp, 6.dp).clip(CircleShape).background(if (i <= at) P.ink else P.raised))
        }
    }
}

/** First run's next step: big, at the bottom, always in the same place ([hidden] keeps its place empty). */
@Composable
private fun Big(label: String, tag: String, enabled: Boolean = true, hidden: Boolean = false, onClick: () -> Unit) {
    if (hidden) return Spacer(Modifier.height(72.dp))
    NextStep(label, onClick, enabled = enabled, tag = tag)
}

/** One of the AIs to pick: its name and a few words on what it's for and who pays. */
@Composable
private fun Choice(label: String, detail: String, tag: String, primary: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(32.dp)
    Column(
        Modifier.padding(horizontal = 20.dp).fillMaxWidth().heightIn(min = 64.dp).clip(shape)
            .then(if (primary) Modifier.background(P.call) else Modifier.border(1.5.dp, P.hairline, shape))
            .clickable(onClick = onClick).padding(vertical = 8.dp).testTag(tag),  // (taller with the phone's largest text)
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = if (primary) OnCall else P.text)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = if (primary) OnCall.copy(alpha = .8f) else P.muted)
    }
}

/** A quiet way out under the main button. */
@Composable
private fun Small(label: String, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick, Modifier.height(44.dp).testTag(tag), enabled = enabled) {
        Text(label, color = P.muted, style = MaterialTheme.typography.bodyMedium)
    }
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

/** Codex and OpenCode in Settings → Setup (a first run offers them as brains to pick). */
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

@Composable
private fun Line() = HorizontalDivider(Modifier.padding(start = 54.dp), color = P.hairline.copy(alpha = .7f))

@Composable
private fun Step(
    n: Int, title: String, detail: String, done: Boolean, optional: Boolean = false, upcoming: Boolean = false, progress: Boolean = false,
    actions: @Composable RowScope.() -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (done) Icon(painterResource(R.drawable.ic_done_circle), "Done", Modifier.size(24.dp), tint = P.text)
            else Box(Modifier.size(22.dp).clip(CircleShape).border(1.5.dp, P.faint, CircleShape), contentAlignment = Alignment.Center) {
                Text("$n", style = MaterialTheme.typography.labelSmall, color = P.muted)
            }
        }
        Column(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp)) {
            // "Optional" goes along in the title's text, so with the phone's largest type it wraps as words do
            val optionalStyle = MaterialTheme.typography.labelSmall.toSpanStyle().copy(color = P.faint)
            Text(androidx.compose.ui.text.buildAnnotatedString {
                append(title)
                if (optional && !done) { pushStyle(optionalStyle); append("  Optional"); pop() }
            }, style = MaterialTheme.typography.titleSmall, color = if (upcoming) P.muted else P.text)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = if (upcoming) P.faint else P.muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (progress) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(2.dp)), color = P.call, trackColor = P.raised)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), content = actions)
    }
}

@Composable
private fun Action(label: String, enabled: Boolean, primary: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(
        label,
        Modifier.clip(shape).then(if (primary) Modifier.background(if (enabled) P.call else P.raised) else Modifier.border(1.5.dp, P.hairline, shape))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("setup_" + label.lowercase().replace(' ', '_')),
        style = MaterialTheme.typography.labelLarge,
        color = when { !enabled -> P.faint; primary -> OnCall; else -> P.text },
    )
}

/** Opens Developer options (About phone while they're off), scrolled to [key] where the phone supports that. */
private fun openDeveloperOptions(ctx: android.content.Context, key: String? = null) = try {
    ctx.startActivity(pairingSettings(ctx, key))
} catch (_: Exception) {
    ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
}

/**
 * The switch some makers keep off in Developer options, which adb needs to tap, type and change settings (Xiaomi's is
 * documented by scrcpy; OPPO's, OnePlus', realme's and vivo's go by another name).
 */
private fun makerFix() = when (android.os.Build.MANUFACTURER.lowercase()) {
    "xiaomi" -> "In Developer options, turn on USB debugging (Security settings), then restart the phone. Xiaomi asks you to sign in to a Xiaomi account first."
    "oppo", "oneplus", "realme", "vivo" -> "In Developer options, turn on Disable permission monitoring."
    else -> "Look in Developer options for a switch that lets USB debugging control the phone."
}

/** Android refused Droid's test tap ([makerFix]). */
@Composable
private fun InputBlocked() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val fix = makerFix()
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

/** What the first run and Setup say when a task's hidden display won't start ([SetupState.displayError]). */
private const val SCREEN_FAILED = "I work on a screen of my own, and this phone won't open it. Copy the diagnostics for a bug report."

/** A task's hidden display won't start on this phone: why, and the diagnostics to report it with. */
@Composable
private fun DisplayFailed(why: String, setup: Setup) {
    Column(Modifier.padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, P.danger.copy(alpha = .5f), RoundedCornerShape(16.dp))
        .padding(16.dp).testTag("setup_display_failed")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_error), null, Modifier.size(18.dp), tint = P.danger)
            Text("The agent's screen won't open", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleSmall, color = P.danger)
        }
        Text("$SCREEN_FAILED\n\n$why", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = P.muted)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) { CopyDiagnostics(setup, Modifier) }
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
