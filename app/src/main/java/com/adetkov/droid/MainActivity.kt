package com.adetkov.droid

import kotlinx.coroutines.delay
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.WaveReader
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.filter

/** An intent-derived request; a fresh instance each time so it re-triggers effects. */
class Request(val conv: String?, val watch: Boolean, val handoff: Boolean = false, val at: Long = System.nanoTime())

class MainActivity : ComponentActivity() {
    private var request by mutableStateOf<Request?>(null)

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) request = parse(intent)
        testEnroll(intent)
        setContent { DroidTheme { Root(request, Modifier.semantics { testTagsAsResourceId = true }) } }
    }

    override fun onResume() {
        super.onResume()
        (application as DroidApp).resumed = true
        (application as DroidApp).voiceMatch.resetIfUnlocked()
    }
    override fun onPause() { (application as DroidApp).resumed = false; super.onPause() }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        request = parse(intent)
        testEnroll(intent)
    }

    /** Debug builds: enroll voice match from WAVs instead of the microphone. */
    private fun testEnroll(i: Intent) {
        val wavs = testEnrollWavs(this, i) ?: return
        val match = (application as DroidApp).voiceMatch
        thread { Log.i("Droid", "test enrollment: %.3f".format(match.enroll(wavs.map { WaveReader.readWave(it).samples }))) }
    }

    private fun parse(i: Intent) = Request(
        i.getStringExtra(EXTRA_CONV), i.getBooleanExtra(EXTRA_WATCH, false), i.getBooleanExtra(EXTRA_HANDOFF, false),
    )
}

private enum class Screen { CHAT, HISTORY, SETTINGS, ACCOUNT, KNOW, INSTRUCTIONS, VOICE_MATCH, SETUP, ABOUT }

/** A word over the bottom of the screen that goes by itself, with [undo] when what was done can be taken back. */
private class Snack(val text: String, val undo: (() -> Unit)? = null, val at: Long = System.nanoTime())

@Composable
private fun Root(request: Request?, modifier: Modifier) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as DroidApp
    val scope = rememberCoroutineScope()
    val convs by app.store.convs.collectAsState()
    val homeOn by app.prefs.homePage.collectAsState()
    val runningMap by app.runner.running.collectAsState()
    val screens by app.runner.screens.collectAsState()
    val doingMap by app.runner.doing.collectAsState()
    val setup by app.setup.state.collectAsState()
    val notNow by app.prefs.notNow.collectAsState()

    var convId by rememberSaveable { mutableStateOf<String?>(null) }
    var screen by rememberSaveable { mutableStateOf(Screen.CHAT) }
    var returnTo by rememberSaveable { mutableStateOf(Screen.CHAT) }  // where Back leads from Instructions and Setup
    fun go(to: Screen) { returnTo = screen; screen = to }
    var watching by rememberSaveable { mutableStateOf(false) }  // the owner's hands on Dash's screen (Do it myself)
    var typing by rememberSaveable { mutableStateOf(false) }  // the message box is open to type in
    var handoff by rememberSaveable { mutableStateOf(false) }  // the answer handed its screen over: Done gives it back
    var draft by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf<String?>(null) }  // searching home (null: not)
    var files by remember { mutableStateOf(listOf<Attachment>()) }
    var attaching by remember { mutableStateOf(false) }
    var chatMenu by remember { mutableStateOf<Conv?>(null) }
    var toDelete by remember { mutableStateOf<Conv?>(null) }
    var toRename by remember { mutableStateOf<Conv?>(null) }
    var needVoice by remember { mutableStateOf(false) }
    var snack by remember { mutableStateOf<Snack?>(null) }
    LaunchedEffect(snack) { if (snack != null) { delay(8000); snack = null } }
    var stopping by remember { mutableStateOf(setOf<String>()) }  // Stop tapped, the run not gone yet
    val (voice, speaker) = rememberVoice()
    val withMic = rememberWithMic()
    val haptic = rememberHaptic()
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val rootView = androidx.compose.ui.platform.LocalView.current
    val pickers = rememberPickers(denied = { snack = Snack(it) }) { files = files + it }

    val conv = convs.firstOrNull { it.id == convId }
    val running = conv != null && conv.id in runningMap  // working on a request
    val info = conv?.let { screens[it.id] }  // its run, working or holding its screen after answering
    val watchable = info?.port != null && info.token != null
    val speakAfter = remember { mutableSetOf<String>() }  // chats whose last message was spoken: read the answer aloud
    // The chat's pages stay loaded from turn to turn (its app is live across them), two at most: its app, and a page it waits on.
    val chatPages = remember(convId) { PageCache(keep = 2) }
    // what the open chat's now shows the owner, as TurnScreen last drew it: the note on what they send (see send)
    var shownNow by remember { mutableStateOf<Pair<String, OnScreen>?>(null) }
    DisposableEffect(chatPages) { onDispose { chatPages.clear() } }

    SideEffect { app.openChat = if (watching) null else convId }
    LaunchedEffect(runningMap.keys) { stopping = stopping.filter { it in runningMap }.toSet() }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    fun send(text: String, via: String? = null): String? {
        if (text.isBlank() && files.isEmpty()) return null
        speaker.stop()
        haptic()
        if (screen != Screen.CHAT) screen = Screen.CHAT
        // What their screen showed of the chat goes along, so the agent knows what they're looking at (see OnScreen); with
        // its app, what's picked and typed in it right now too, so "make it say 8" works on the draft they see.
        val shows = shownNow?.takeIf { it.first == convId }?.second
        fun note(seen: String = "") = listOfNotNull(via, shows?.note?.let { if (seen.isEmpty()) it else "$it, with $seen" }).joinToString("; ").ifEmpty { null }
        val page = convId?.takeIf { shows == OnScreen.APP }?.let { ScreenPage.view(it) }
        if (page != null) {
            val to = convId; val sending = files
            draft = ""; files = emptyList()
            // once: with what's on the page, or, from a page that doesn't answer in time (busy, or gone), without it
            val sent = java.util.concurrent.atomic.AtomicBoolean(false)
            fun go(seen: String) { if (sent.compareAndSet(false, true) && ownerSend(ctx, to, text.trim(), sending, note(seen)) == null) sending.forEach(::discard) }
            page.evaluateJavascript("window.droid && droid._state ? droid._state() : ''") { r ->
                go(runCatching { org.json.JSONTokener(r).nextValue() as? String }.getOrNull()?.trim().orEmpty())
            }
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ go("") }, 1500)
            return to
        }
        return ownerSend(ctx, convId, text.trim(), files, note()).also {
            if (it == null) files.forEach(::discard)  // the chat was deleted: nothing has them
            convId = it; draft = ""; files = emptyList(); query = null
        }
    }

    // Voice: it sends when the owner stops talking (what they'd typed goes first), and reads the answer aloud. Typing
    // instead keeps what it heard, to edit.
    var typed by remember { mutableStateOf("") }  // the draft from before listening started, kept in front of what's heard
    fun withTyped(t: String) = if (typed.isEmpty()) t else "$typed $t"
    fun listen() {
        speaker.stop()
        focus.clearFocus()  // the keyboard goes while you talk
        typing = false
        typed = draft.trim()
        app.runner.prewarm(convId)  // the agent starts up while the user talks
        voice.start(null,
            onPartial = { draft = withTyped(it) },
            onFinal = { text, _ ->
                val aloud = app.prefs.speakReplies.value
                send(withTyped(text), if (aloud) "I said this aloud, and your answer is read aloud to me" else "I said this aloud")?.let { id ->
                    if (aloud) { speakAfter += id; speaker.prepare() }
                }
            },
            onNothing = { draft = typed; app.runner.dropWarm(); toast("Didn't catch that") },
            onError = { draft = typed; app.runner.dropWarm(); toast(it) },
        )
    }
    /** Listens, once the voice is set up and the microphone is allowed. */
    fun talk() { if (!app.speech.installed) needVoice = true else withMic { listen() } }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    var askedNotif by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        app.runner.results.collect { (id, text) -> if (speakAfter.remove(id)) speaker.speak("auto:$id", spokenAnswer(app.store.get(id), text)) }
    }
    // no screen (any more) to work on: none, or one closing (its run gone, its port with it)
    LaunchedEffect(info?.port == null, watching) { if (info?.port == null) watching = false }
    LaunchedEffect(watching) { if (!watching) handoff = false }
    // A message typed and left unsent (the app goes out of sight) doesn't keep the agent started; it starts again when the owner is back to it.
    run {
        val draftNow by rememberUpdatedState(draft)
        val chatNow by rememberUpdatedState(convId)
        val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
        DisposableEffect(owner) {
            val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
                if (draftNow.isNotBlank()) when (e) {
                    androidx.lifecycle.Lifecycle.Event.ON_STOP -> app.runner.dropWarm()
                    androidx.lifecycle.Lifecycle.Event.ON_START -> app.runner.prewarm(chatNow)
                    else -> {}
                }
            }
            owner.lifecycle.addObserver(obs)
            onDispose { owner.lifecycle.removeObserver(obs) }
        }
    }

    /** Not now: the chat leaves Needs you (and stops calling the owner) until its next message. Undo takes it back. */
    fun setAside(id: String) {
        haptic()
        val c = app.store.get(id) ?: return
        app.prefs.setNotNow(id, c.messages.lastOrNull()?.ts ?: 0L)
        snack = Snack("Not now: " + appName(c), undo = { app.prefs.clearNotNow(id) })
    }

    /**
     * To the chat's History. Focus is let go first: a page the owner tapped holds it, and its view leaving the screen
     * would have Android search for the next focus inside History's list while that list is being built, which Compose
     * can't survive ("pending composition has not been applied", a crash of 2026-11-23).
     */
    fun toHistory() {
        rootView.findFocus()?.clearFocus()
        focus.clearFocus(force = true)
        screen = Screen.HISTORY
    }

    fun open(id: String?) {
        if (id != convId) { draft = ""; files.forEach(::discard); files = emptyList(); watching = false }
        convId = id; voice.cancel(); screen = Screen.CHAT; typing = false; query = null
        focus.clearFocus()
    }

    // First run: setup stays up until Start, so the optional steps (voice, assistant) can be done there too.
    val needed = app.setup.needed(setup)
    var onboarding by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(needed) { if (needed) onboarding = true }
    if (needed || onboarding) {
        Box(modifier.fillMaxSize().background(P.bg)) { SetupScreen(app.setup, firstRun = true) { onboarding = false } }
        return
    }
    LaunchedEffect(Unit) {
        if (!askedNotif && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        askedNotif = true
    }
    // A notification's chat: opened as from home; Dash's screen in the owner's hands only if it's up. A frame later, so
    // it grows out of this chat's window, not the one that was open.
    var handled by rememberSaveable { mutableStateOf(0L) }  // once each (not again when Setup was shown meanwhile)
    LaunchedEffect(request) {
        val r = request ?: return@LaunchedEffect
        if (r.conv == null || r.at == handled) return@LaunchedEffect
        handled = r.at
        if (r.conv != convId) { watching = false; handoff = false }  // it was another chat's
        open(r.conv)
        withFrameNanos {}
        if ((r.watch || r.handoff) && app.runner.screens.value[r.conv]?.port != null) { watching = true; handoff = r.handoff }
    }

    // Your day, when it's on: asked for as the owner comes home, once a day (see HomeDay), in its own chat, which the
    // list shows like any other.
    val onHome = screen == Screen.CHAT && (conv == null || conv.messages.isEmpty())
    LaunchedEffect(onHome, homeOn, setup.ready) {
        if (!onHome || !setup.ready) return@LaunchedEffect
        val p = app.prefs
        val chat = p.homeChat?.let { app.store.get(it) }
        val made = chat?.messages?.lastOrNull { it.kind == Kind.AGENT || it.kind == Kind.PAGE }?.ts
        if (!HomeDay.due(homeOn, made, p.homeAsked)) return@LaunchedEffect
        if (chat != null && chat.id in app.runner.running.value) return@LaunchedEffect
        p.homeAsked = System.currentTimeMillis()
        startTask(ctx, chat?.id, HomeDay.REQUEST, via = HomeDay.VIA)?.let { p.homeChat = it }
    }

    // Sheets: a chat's menu, and the questions it asks
    toDelete?.let { c ->
        ConfirmDialog("Delete “${appName(c)}”?", "Its app, its pictures and what I remember of this chat go too. This can't be undone.", "Delete",
            { deleteChat(ctx, c.id); if (convId == c.id) open(null) }, { toDelete = null }, danger = true)
    }
    toRename?.let { c -> TextDialog("Rename", appName(c), "Save", { app.store.rename(c.id, it) }) { toRename = null } }
    chatMenu?.let { c ->
        ActionSheet({ chatMenu = null }, title = appName(c)) { close ->
            val here = c.id == convId
            if (here) SheetRow(R.drawable.ic_history, "History", { close(); toHistory() }, tag = "sheet_history")
            // from the list, one that needs the owner: set it aside until there's something new
            val aside = !here && appTiles(listOf(c), runningMap.keys, null, within = Long.MAX_VALUE, notNow = notNow).firstOrNull()?.calls == true
            if (aside) SheetRow(R.drawable.ic_s_dnd, "Not now", { setAside(c.id); close() }, tag = "sheet_not_now")
            SheetRow(R.drawable.ic_edit, "Rename", { close(); toRename = c }, tag = "sheet_rename", line = here || aside)
            SheetRow(if (c.pinned) R.drawable.ic_unpin else R.drawable.ic_pin, if (c.pinned) "Unpin" else "Pin",
                { app.store.setPinned(c.id, !c.pinned); close() }, tag = "sheet_pin", line = true)
            SheetRow(R.drawable.ic_share, "Share", { shareText(ctx, transcriptText(c), c.title); close() }, tag = "sheet_share", line = true)
            SheetRow(R.drawable.ic_delete, "Delete", { close(); toDelete = c }, danger = true, tag = "sheet_delete", line = true)
        }
    }
    if (attaching) AttachSheet(pickers) { attaching = false }
    if (needVoice) ConfirmDialog("I need to download speech first",
        "860 MB, once. It stays on your phone, so what you say isn't sent anywhere to be heard.",
        "Download it", { go(Screen.SETUP) }, { needVoice = false }, dismiss = "Type instead", onSecondary = { typing = true })

    // Back: from a chat home, from a search to home, from deeper screens one step toward home
    BackHandler(conv != null && screen == Screen.CHAT && !watching) { open(null) }
    BackHandler(conv == null && query != null && screen == Screen.CHAT) { query = null }
    BackHandler(voice.listening) { voice.cancel(); app.runner.dropWarm(); draft = typed }
    BackHandler(screen != Screen.CHAT) {
        screen = when (screen) {
            Screen.SETTINGS, Screen.HISTORY -> Screen.CHAT
            Screen.ACCOUNT, Screen.KNOW, Screen.ABOUT, Screen.VOICE_MATCH -> Screen.SETTINGS
            else -> returnTo
        }
    }

    // Each screen keeps its state (e.g. Settings' scroll position) while another is on top.
    val saved = rememberSaveableStateHolder()
    Box(modifier.fillMaxSize()) {
    AnimatedContent(
        screen, Modifier.fillMaxSize().background(P.bg), label = "screen",
        transitionSpec = {
            val history = setOf(initialState, targetState) == setOf(Screen.CHAT, Screen.HISTORY)
            val forward = targetState.ordinal > initialState.ordinal
            // History is above now, as if scrolled: it comes down from the top, and goes back up
            if (history) (slideInVertically(tween(300)) { if (forward) -it / 3 else it / 3 } + fadeIn(tween(240))) togetherWith
                (slideOutVertically(tween(300)) { if (forward) it / 3 else -it / 3 } + fadeOut(tween(180)))
            else (slideInHorizontally(tween(260)) { if (forward) it / 4 else -it / 4 } + fadeIn(tween(220))) togetherWith
                (slideOutHorizontally(tween(260)) { if (forward) -it / 4 else it / 4 } + fadeOut(tween(160)))
        },
    ) { s -> saved.SaveableStateProvider(s) {
        when (s) {
            Screen.SETTINGS -> SettingsScreen(
                onBack = { screen = Screen.CHAT }, onAccount = { screen = Screen.ACCOUNT }, onKnow = { screen = Screen.KNOW },
                onVoiceMatch = { screen = Screen.VOICE_MATCH }, onSetup = { go(Screen.SETUP) }, onAbout = { screen = Screen.ABOUT },
                onDeleteAll = {
                    app.store.convs.value.forEach { deleteChat(ctx, it.id) }
                    kotlin.concurrent.thread(isDaemon = true, name = "delete-all") { Stills.deleteAll(ctx); Shown.deleteAll(ctx) }
                    open(null)
                },
            )
            Screen.ACCOUNT -> AccountScreen(onBack = { screen = Screen.SETTINGS }, onSetup = { go(Screen.SETUP) })
            Screen.KNOW -> KnowScreen(app.backend, onBack = { screen = Screen.SETTINGS }, onInstructions = { go(Screen.INSTRUCTIONS) })
            Screen.VOICE_MATCH -> VoiceMatchScreen(onBack = { screen = Screen.SETTINGS }, onSetup = { go(Screen.SETUP) })
            Screen.INSTRUCTIONS -> DocScreen(app.backend, Doc.INSTRUCTIONS, "What you told me",
                "I read this before every task. Tell me about yourself, the people and apps you use, and how you like things done.",
                "instructions", onClose = { screen = returnTo })
            Screen.ABOUT -> AboutScreen { screen = Screen.SETTINGS }
            Screen.SETUP -> SetupScreen(app.setup, firstRun = false) { screen = returnTo }
            Screen.HISTORY -> if (conv != null) HistoryScreen(conv, running, onBack = { screen = Screen.CHAT }, onSetup = { go(Screen.SETUP) })
                else LaunchedEffect(Unit) { screen = Screen.CHAT }
            Screen.CHAT -> {
              // Droid's screen in the chat: small frames, twice a second (kept under Watch too, so the chat is as it was when the owner comes back).
              // None for a turn that's over and made an app, which shows instead: a held screen no one sees can sleep (see VdHelper.held).
              val madeApp = remember(conv?.messages) { conv?.messages?.let { m -> m.currentApp() > m.indexOfLast { it.kind.opensTurn } } == true }
              val feed = if (conv != null && conv.messages.isNotEmpty())
                  rememberScreenFeed(info?.port?.takeIf { running || !madeApp }, info?.token, width = 540, fps = 2, isUp = { conv.id in app.runner.screens.value }) else null
              val home = conv == null || conv.messages.isEmpty()
              Column(Modifier.fillMaxSize().background(P.bg).imePadding()) {
                    // Where you are: search and Settings on home; back, the app's name, and Stop or its menu in a chat
                    when {
                        home && query != null -> SearchRow(query.orEmpty(), { query = it }, { query = null })
                        home -> Where(R.drawable.ic_search, "Find a chat", { query = "" }, leftTag = "search_open") {
                            IconBtn(R.drawable.ic_settings, "Settings", { go(Screen.SETTINGS) }, Modifier.testTag("settings"), size = 48.dp)
                        }
                        else -> Where(R.drawable.ic_back, "Back", { if (watching) watching = false else open(null) }, remember(conv, running) { chatTitle(conv, running) }) {
                            if (running) StopButton(stopping = conv.id in stopping) { haptic(); stopping = stopping + conv.id; app.runner.stop(conv.id) }
                            else IconBtn(R.drawable.ic_more, "Chat options", { chatMenu = conv }, Modifier.testTag("overflow"), size = 48.dp)
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (home) {
                            HomeScreen(convs, runningMap.keys, notNow, Modifier.fillMaxSize(), listening = voice.listening, level = { voice.level },
                                typing = draft.isNotBlank(), preparing = app.prefs.homeChat?.let { it in runningMap } == true, query = query,
                                // a starter goes at once; asking about what a search didn't find starts a message to finish
                                onAsk = { q -> if (query != null) { query = null; draft = q; typing = true } else send(q) },
                                onOpen = ::open, onNotNow = ::setAside, onMenu = { chatMenu = it })
                        } else {
                            val taps = remember(conv.id) { app.runner.taps.filter { it.first == conv.id }.map { it.second to it.third } }
                            val turns = remember(conv) { conv.messages.count { it.kind.opensTurn } }
                            key(conv.id) {
                                CompositionLocalProvider(LocalPageCache provides chatPages) {
                                    // (under Do it myself, out of a screen reader's way)
                                    TurnScreen(conv, running, feed, Modifier.fillMaxSize(), Modifier.fillMaxSize().then(if (watching) Modifier.clearAndSetSemantics {} else Modifier)
                                        // (not drawn under Watch, which covers it: its state and app view stay)
                                        .drawWithContent { if (!watching) drawContent() }, hidden = watching, held = watchable && !running,
                                        closesAt = info?.closesAt, taps = taps, doing = doingMap[conv.id], typing = draft.isNotBlank(),
                                        listening = voice.listening, level = { voice.level },
                                        onDoItMyself = { watching = true }, onTakeOver = { handoff = true; watching = true },
                                        onRetry = { haptic(); retryTask(ctx, conv.id) }, onSetup = { go(Screen.SETUP) },
                                        onSpeak = { speaker.toggle("dash:${conv.id}:${conv.messages.size}", it) },
                                        // up past the top: the turns before this one (none: nothing to go to)
                                        onHistory = if (turns > 1) ({ toHistory() }) else null,
                                        onShown = { shownNow = conv.id to it })
                                    DisposableEffect(Unit) { onDispose { if (shownNow?.first == conv.id) shownNow = null } }
                                }
                            }
                        }
                        // Dash's screen in the owner's hands, in place of the page: to do something on it themselves.
                        if (watching && info?.port != null && info.token != null) {
                            val watched = convId
                            // A screen held after answering stays open while it's in the owner's hands, and only while
                            // it's on screen: left behind Home or the lock screen, the hold counts down (else the
                            // screen would stay up, keeping the phone awake, until the owner came back).
                            val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
                            DisposableEffect(watched, lifecycle) {
                                fun set(on: Boolean) = watched?.let { app.runner.watching(it, on) }
                                val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
                                    if (e == androidx.lifecycle.Lifecycle.Event.ON_START) set(true) else if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) set(false)
                                }
                                lifecycle.addObserver(obs)  // (it's told ON_START at once if already started)
                                onDispose { lifecycle.removeObserver(obs); set(false) }
                            }
                            // Done gives it back: to the agent that handed it over (as the owner's message, which answers
                            // the page that handed it over, if the agent waits on it), else it just closes.
                            key(watched) { WatchScreen(info.port, info.token, onClose = { watching = false },
                                isUp = { convId in app.runner.screens.value }, onTouched = { convId?.let(app.runner::touched) },
                                onDone = { val back = handoff; watching = false; if (back) convId?.let { ownerSend(ctx, it, HANDED_BACK) } },
                                taps = remember(watched) { app.runner.taps.filter { it.first == watched }.map { it.second to it.third } },
                                modifier = Modifier.fillMaxSize()) }
                        }
                        // Talking to Dash: what he hears comes up big, above the bar, wherever you are.
                        if (voice.listening && draft.isNotBlank()) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                            .background(androidx.compose.ui.graphics.Brush.verticalGradient(0f to P.bg.copy(alpha = 0f), .35f to P.bg)).padding(top = 28.dp, bottom = 6.dp)) {
                            YouBubble(draft, big = true)
                        }
                        androidx.compose.animation.AnimatedVisibility(snack != null, Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                            enter = fadeIn(tween(160)) + slideInVertically(tween(200)) { it / 2 }, exit = fadeOut(tween(160))) {
                            val sn = remember { mutableStateOf(snack) }.apply { if (snack != null) value = snack }.value
                            if (sn != null) KitSnack(sn.text, action = sn.undo?.let { "Undo" }, onAction = { sn.undo?.invoke(); snack = null })
                        }
                    }
                    // The bar, except while Dash's screen is in the owner's hands (it has its own: Keyboard, Done, Back)
                    if (!watching) Composer(
                        text = draft, onText = {
                            if (voice.listening) voice.cancel()  // typing takes over from the voice
                            if (draft.isBlank() && it.isNotBlank()) app.runner.prewarm(convId)  // the agent starts up while the user types
                            else if (it.isBlank() && draft.isNotBlank()) app.runner.dropWarm()  // and goes again if they clear it
                            draft = it
                        },
                        files = files, onRemove = { discard(it); files = files - it },
                        listening = voice.listening, level = { voice.level },
                        onAttach = { focus.clearFocus(); attaching = true }, onSend = { voice.cancel(); send(draft); typing = false; focus.clearFocus() },
                        onVoice = { talk() },
                        onFinishListening = voice::finish, onCancelListening = { voice.cancel(); app.runner.dropWarm(); draft = typed },
                        // typing instead of talking keeps what Dash heard
                        modifier = Modifier.navigationBarsPadding(), typing = typing, onTyping = { if (it && voice.listening) voice.cancel(); typing = it },
                        typeOnly = !app.speech.installed,
                    )
              }
            }
        }
    } }
    }
}

/** "Claude Max" for the plan names Claude Code reports. */
fun planLabel(plan: String?): String? = when (plan?.lowercase()) {
    null, "" -> null
    "pro" -> "Claude Pro"
    "max" -> "Claude Max"
    "team" -> "Claude Team"
    "enterprise" -> "Claude Enterprise"
    else -> "Claude " + plan.replaceFirstChar { it.uppercase() }
}
