package com.adetkov.droid

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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

private enum class Screen { CHAT, APPS, SETTINGS, ADVANCED, VOICE_MATCH, INSTRUCTIONS, MEMORY, SETUP, ABOUT }

@Composable
private fun Root(request: Request?, modifier: Modifier) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as DroidApp
    val scope = rememberCoroutineScope()
    val convs by app.store.convs.collectAsState()
    val homePage = remember(convs) { homePageOf(convs) }  // the agent's page for the home screen, if it made one
    val homeOn by app.prefs.homePage.collectAsState()
    val runningMap by app.runner.running.collectAsState()
    val screens by app.runner.screens.collectAsState()
    val doingMap by app.runner.doing.collectAsState()
    val setup by app.setup.state.collectAsState()

    var convId by rememberSaveable { mutableStateOf<String?>(null) }
    var screen by rememberSaveable { mutableStateOf(Screen.CHAT) }
    var returnTo by rememberSaveable { mutableStateOf(Screen.CHAT) }  // where Back leads from Instructions and Setup
    fun go(to: Screen) { returnTo = screen; screen = to }
    var watching by rememberSaveable { mutableStateOf(false) }
    var wholeChat by rememberSaveable { mutableStateOf(false) }  // the chat turn by turn, rather than its latest turn's screen
    var footer by remember { mutableStateOf(false) }  // the answer's page ends with its next step, which the talk bar makes way for
    var typing by rememberSaveable { mutableStateOf(false) }  // the message box is open to type in
    var handoff by rememberSaveable { mutableStateOf(false) }  // watching because the answer handed over its screen
    var draft by rememberSaveable { mutableStateOf("") }
    var files by remember { mutableStateOf(listOf<Attachment>()) }
    var attaching by remember { mutableStateOf(false) }
    var shooting by remember { mutableStateOf(false) }  // Droid's camera is open over the chat
    val camera = remember { Camera() }
    val withCamera = rememberWith(Manifest.permission.CAMERA)
    var chatMenu by remember { mutableStateOf<Conv?>(null) }
    var toDelete by remember { mutableStateOf<Conv?>(null) }
    var toRename by remember { mutableStateOf<Conv?>(null) }
    var needVoice by remember { mutableStateOf(false) }
    var listenMode by remember { mutableStateOf<Listen?>(null) }
    val (voice, speaker) = rememberVoice()
    val withMic = rememberWithMic()
    val haptic = rememberHaptic()
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val pickers = rememberPickers { files = files + it }

    val conv = convs.firstOrNull { it.id == convId }
    val running = conv != null && conv.id in runningMap  // working on a request
    val info = conv?.let { screens[it.id] }  // its run, working or holding its screen after answering
    val watchable = info?.port != null && info.token != null
    val speakAfter = remember { mutableSetOf<String>() }  // chats whose last message was spoken: read the answer aloud

    SideEffect { app.openChat = if (watching) null else convId }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    fun send(text: String, via: String? = null): String? {
        if (text.isBlank() && files.isEmpty()) return null
        speaker.stop()
        haptic()
        // Said or typed with the agent's page on screen: it also learns what's picked and typed on the page right now,
        // so "make it say 8" works on the draft they're looking at.
        val page = convId?.takeIf { !wholeChat }?.let { ScreenPage.view(it) }
        if (page != null) {
            val to = convId; val sending = files
            draft = ""; files = emptyList()
            page.evaluateJavascript("window.droid && droid._state ? droid._state() : ''") { r ->
                val seen = runCatching { org.json.JSONTokener(r).nextValue() as? String }.getOrNull()?.trim().orEmpty()
                val v = if (seen.isEmpty()) via else listOfNotNull(via, "your page was on my screen, with $seen").joinToString("; ")
                if (ownerSend(ctx, to, text.trim(), sending, v) == null) sending.forEach(::discard)
            }
            return to
        }
        return ownerSend(ctx, convId, text.trim(), files, via).also {
            if (it == null) files.forEach(::discard)  // the chat was deleted: nothing has them
            convId = it; draft = ""; files = emptyList()
        }
    }
    var saving by remember { mutableStateOf<Boolean?>(null) }  // the camera's picture is being kept (true: to send)
    var queued by remember { mutableStateOf<(() -> Unit)?>(null) }
    /**
     * Puts the camera's picture in the message box and closes the camera; [then] runs once it is there. Meanwhile a
     * second picture isn't taken: a Send (say, voice's, after a tap) waits for the one being kept and goes with it, and
     * a second Send does nothing (it would send the message again).
     */
    fun takePhoto(send: Boolean = false, then: () -> Unit = {}) {
        when (saving) {
            null -> {}
            false -> { if (send) queued = then; return }
            true -> return
        }
        saving = send
        val photo = camera.picture()
        scope.launch {
            try {
                val a = photo?.let { withContext(Dispatchers.IO) { savePhoto(ctx, it) } }
                if (a != null) files = files + a else if (photo != null) toast("Couldn't save the picture")
                shooting = false
                then()
                queued?.also { queued = null }?.invoke()
            } finally { saving = null }
        }
    }
    /** Sends [text], with the camera's picture if it's open, and then calls [after] with the chat. */
    fun sendNow(text: String, via: String? = null, after: (String) -> Unit = {}) {
        if (shooting || saving == false) takePhoto(send = true) { send(text, via)?.let(after) } else send(text, via)?.let(after)
    }

    // Voice: dictation fills the box; voice mode sends when the user stops talking and reads the answer aloud.
    var typed by remember { mutableStateOf("") }  // the draft from before listening started, kept in front of the transcript
    fun withTyped(t: String) = if (typed.isEmpty()) t else "$typed $t"
    fun listen(mode: Listen) {
        speaker.stop()
        focus.clearFocus()  // the keyboard goes while you talk (the box fills in as it hears you)
        typed = draft.trim()
        listenMode = mode
        if (mode == Listen.VOICE) app.runner.prewarm(convId)  // the agent starts up while the user talks
        fun dropWarm() { if (mode == Listen.VOICE) app.runner.dropWarm() }
        voice.start(null,
            onPartial = { draft = withTyped(it) },
            onFinal = { text, _ ->
                if (mode == Listen.DICTATE) draft = withTyped(text)
                else {
                    val aloud = app.prefs.speakReplies.value
                    sendNow(withTyped(text), if (aloud) "I said this aloud, and your answer is read aloud to me" else "I said this aloud") { id ->
                        if (aloud) { speakAfter += id; speaker.prepare() }
                    }
                }
            },
            onNothing = { draft = typed; dropWarm(); toast("Didn't catch that") },
            onError = { draft = typed; dropWarm(); toast(it) },
            onPause = { if (shooting) camera.mark() },
        )
    }
    /** Listens, once the voice is set up and the microphone is allowed. */
    fun talk(mode: Listen) { if (!app.speech.installed) needVoice = true else withMic { listen(mode) } }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    var askedNotif by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        app.runner.results.collect { (id, text) -> if (speakAfter.remove(id)) speaker.speak("auto:$id", spokenAnswer(app.store.get(id), text)) }
    }
    // no screen (any more) to watch: none, or one closing (its run gone, its port with it)
    LaunchedEffect(info?.port == null, watching) { if (info?.port == null) watching = false }
    LaunchedEffect(watching) { if (!watching) handoff = false }

    fun open(id: String?) {
        if (id != convId) { draft = ""; files.forEach(::discard); files = emptyList(); footer = false }
        convId = id; voice.cancel(); shooting = false; screen = Screen.CHAT; wholeChat = false; typing = false
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
    // A notification's chat: opened as from Your apps; Watch only if its screen is up. A frame later, so Watch grows
    // out of this chat's window, not the one that was open.
    var handled by rememberSaveable { mutableStateOf(0L) }  // once each (not again when Setup was shown meanwhile)
    LaunchedEffect(request) {
        val r = request ?: return@LaunchedEffect
        if (r.conv == null || r.at == handled) return@LaunchedEffect
        handled = r.at
        if (r.conv != convId) { watching = false; handoff = false }  // Watch was another chat's
        open(r.conv)
        withFrameNanos {}
        if ((r.watch || r.handoff) && app.runner.screens.value[r.conv]?.port != null) { watching = true; handoff = r.handoff }
    }

    // Dash's page for the day: asked for as the owner comes to the home screen, once a day (see HomeDay), in its own chat.
    val onHome = screen == Screen.CHAT && (conv == null || conv.messages.isEmpty())
    LaunchedEffect(onHome, homeOn, setup.ready) {
        if (!onHome || !setup.ready) return@LaunchedEffect
        val p = app.prefs
        if (!HomeDay.due(homeOn, homePageOf(app.store.convs.value)?.second?.ts, p.homeAsked)) return@LaunchedEffect
        val chat = p.homeChat?.takeIf { app.store.get(it) != null }
        if (chat != null && chat in app.runner.running.value) return@LaunchedEffect
        p.homeAsked = System.currentTimeMillis()
        startTask(ctx, chat, HomeDay.REQUEST, via = HomeDay.VIA)?.let { p.homeChat = it }
    }

    // Dialogs and sheets
    toDelete?.let { c ->
        ConfirmDialog("Delete chat?", "“${c.title}” and what Droid remembers of it will be deleted.", "Delete",
            { deleteChat(ctx, c.id); if (convId == c.id) open(null) }, { toDelete = null }, danger = true)
    }
    toRename?.let { c -> TextDialog("Rename chat", c.title, "Save", { app.store.rename(c.id, it) }) { toRename = null } }
    chatMenu?.let { c ->
        ActionSheet({ chatMenu = null }, title = c.title) { close ->
            if (!wholeChat && c.id == convId && c.messages.isNotEmpty())
                SheetRow(R.drawable.ic_notes, "See what Dash did", { close(); wholeChat = true }, tag = "sheet_whole")
            SheetRow(R.drawable.ic_edit, "Rename", { close(); toRename = c }, tag = "sheet_rename")
            SheetRow(if (c.pinned) R.drawable.ic_unpin else R.drawable.ic_pin, if (c.pinned) "Unpin" else "Pin",
                { app.store.setPinned(c.id, !c.pinned); close() }, tag = "sheet_pin")
            SheetRow(R.drawable.ic_share, "Share", { shareText(ctx, transcriptText(c), c.title); close() }, tag = "sheet_share")
            SheetRow(R.drawable.ic_delete, "Delete", { close(); toDelete = c }, danger = true, tag = "sheet_delete")
        }
    }
    if (attaching) AttachSheet(onCamera = { withCamera { camera.reset(); shooting = true } }, pickers) { attaching = false }
    if (needVoice) ConfirmDialog("Set up voice", "Talking to Droid needs the speech models (860 MB), which run on the phone.",
        "Set up", { go(Screen.SETUP) }, { needVoice = false })

    // Back from a chat: from the whole chat to its screen, and from there home
    BackHandler(conv != null && screen == Screen.CHAT) { if (wholeChat) wholeChat = false else open(null) }
    BackHandler(voice.listening) { voice.cancel(); draft = typed }
    BackHandler(shooting && !voice.listening) { shooting = false }
    BackHandler(screen != Screen.CHAT) {
        screen = when (screen) {
            Screen.SETTINGS, Screen.APPS -> Screen.CHAT; Screen.ADVANCED, Screen.ABOUT -> Screen.SETTINGS; Screen.VOICE_MATCH, Screen.MEMORY -> Screen.ADVANCED
            else -> returnTo
        }
    }

    // Each screen keeps its state (e.g. Settings' scroll position) while another is on top.
    val saved = rememberSaveableStateHolder()
    Box(modifier.fillMaxSize()) {
    AnimatedContent(
        screen, Modifier.fillMaxSize().background(P.bg), label = "screen",
        transitionSpec = {
            val forward = targetState.ordinal > initialState.ordinal
            (slideInHorizontally(tween(260)) { if (forward) it / 4 else -it / 4 } + fadeIn(tween(220))) togetherWith
                (slideOutHorizontally(tween(260)) { if (forward) -it / 4 else it / 4 } + fadeOut(tween(160)))
        },
    ) { s -> saved.SaveableStateProvider(s) {
        when (s) {
            Screen.SETTINGS -> SettingsScreen(
                onBack = { screen = Screen.CHAT }, onInstructions = { go(Screen.INSTRUCTIONS) }, onSetup = { go(Screen.SETUP) },
                onAbout = { screen = Screen.ABOUT }, onAdvanced = { screen = Screen.ADVANCED },
            )
            Screen.ADVANCED -> AdvancedScreen(
                onBack = { screen = Screen.SETTINGS }, onMemory = { screen = Screen.MEMORY }, onSetup = { go(Screen.SETUP) },
                onVoiceMatch = { screen = Screen.VOICE_MATCH },
                onDeleteAll = { app.store.convs.value.forEach { deleteChat(ctx, it.id) }; Stills.deleteAll(ctx); Shown.deleteAll(ctx); open(null) },
            )
            Screen.VOICE_MATCH -> VoiceMatchScreen(onBack = { screen = Screen.ADVANCED }, onSetup = { go(Screen.SETUP) })
            Screen.INSTRUCTIONS -> DocScreen(app.backend, Doc.INSTRUCTIONS, "Instructions",
                "Droid reads this at the start of every task. Tell it about yourself, the people and apps you use, and how you like things done.",
                "instructions", onClose = { screen = returnTo })
            Screen.MEMORY -> MemoryScreen(app.backend) { screen = Screen.ADVANCED }
            Screen.ABOUT -> AboutScreen { screen = Screen.SETTINGS }
            Screen.SETUP -> SetupScreen(app.setup, firstRun = false) { screen = returnTo }
            Screen.APPS -> AppsScreen(convs, runningMap.keys, app.prefs.homeChat, onOpen = ::open, onLongPress = { chatMenu = it },
                onBack = { screen = Screen.CHAT })
            Screen.CHAT -> {
              // Droid's screen in the chat: small frames, twice a second.
              val feed = if (conv != null && conv.messages.isNotEmpty())
                  rememberScreenFeed(info?.port, info?.token, width = 540, fps = 2, isUp = { conv.id in app.runner.screens.value }) else null
              run {
                Column(Modifier.fillMaxSize().background(P.bg).imePadding()) {
                    // the page's next step at the bottom takes the talk bar's place; a microphone up here instead
                    val pinned = footer && !running && !wholeChat
                    if (conv == null || conv.messages.isEmpty()) HomeBar(onMenu = { go(Screen.APPS) }, onSettings = { go(Screen.SETTINGS) })
                    else TurnBar(appName(conv), onBack = { if (wholeChat) wholeChat = false else open(null) }, onMore = { chatMenu = conv },
                        onTalk = if (pinned) ({ if (app.speech.installed) talk(Listen.VOICE) else typing = true }) else null,
                        typeOnly = !app.speech.installed)
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (conv == null || conv.messages.isEmpty()) {
                            // A starter goes at a tap: nothing to type (the agent asks before it sends anything anywhere).
                            // Dash's page for the day, if he made one: his greeting over it, and when he made it.
                            val greeting = homePage?.let { (c, m) -> convs.firstOrNull { it.id == c }?.messages?.let { ms -> moodAt(ms, ms.indexOf(m)) }?.words?.takeIf { it.isNotBlank() } }
                            val preparing = app.prefs.homeChat?.let { it in runningMap } == true
                            EmptyState({ sendNow(it) }, Modifier.fillMaxSize(), listening = voice.listening, level = { voice.level }, typing = draft.isNotBlank(),
                                home = homePage?.let { (c, m) -> { HomePage(c, m) { id -> open(id) } } }, greeting = greeting,
                                made = homePage?.second?.ts?.let { HomeDay.madeWhen(it) }, preparing = preparing,
                                apps = remember(convs, runningMap.keys) { appTiles(convs, runningMap.keys, app.prefs.homeChat) }, onOpen = ::open)
                        } else {
                            val taps = remember(conv.id) { app.runner.taps.filter { it.first == conv.id }.map { it.second to it.third } }
                            if (wholeChat) key(conv.id) { Transcript(conv, running, feed, speaker.speakingKey, speaker::toggle, held = watchable && !running, closesAt = info?.closesAt, taps = taps,
                                doing = doingMap[conv.id], typing = draft.isNotBlank(), listening = voice.listening, level = { voice.level },
                                onRetry = { haptic(); retryTask(ctx, conv.id) }, onSetup = { go(Screen.SETUP) },
                                onWatch = { watching = true }, modifier = Modifier.fillMaxSize(),
                                onTakeOver = { handoff = true; watching = true }) }
                            else key(conv.id) { TurnScreen(conv, running, feed, Modifier.fillMaxSize(), held = watchable && !running, closesAt = info?.closesAt,
                                taps = taps, doing = doingMap[conv.id], typing = draft.isNotBlank(), listening = voice.listening, level = { voice.level },
                                onWatch = { watching = true }, onTakeOver = { handoff = true; watching = true },
                                onRetry = { haptic(); retryTask(ctx, conv.id) }, onSetup = { go(Screen.SETUP) }, onHistory = { wholeChat = true },
                                onSpeak = { speaker.toggle("dash:${conv.id}:${conv.messages.size}", it) }, onFooter = { footer = it }) }
                        }
                        // Droid's camera, in Dash's frame over the chat: a tap puts its picture in the message box.
                        // (Spelled out: inside the Column, the short name means ColumnScope's, which can't be used here.)
                        androidx.compose.animation.AnimatedVisibility(shooting, Modifier.fillMaxSize(), enter = fadeIn(tween(160)), exit = fadeOut(tween(160))) {
                            val frame = RoundedCornerShape(28.dp)
                            Box(Modifier.fillMaxSize().background(P.bg).padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp)) {
                                CameraView(camera, onTap = { haptic(); takePhoto() },
                                    Modifier.fillMaxSize().clip(frame).border(3.dp, DashColors.Accent, frame).testTag("camera"))
                                IconBtn(R.drawable.ic_close, "Close the camera", { shooting = false },
                                    Modifier.padding(12.dp).clip(CircleShape).background(Color(0x800D0D0D)).testTag("close_camera"),
                                    tint = Color.White, size = 44.dp)
                            }
                        }
                    }
                    Composer(
                        text = draft, onText = {
                            if (voice.listening) voice.cancel()  // typing takes over from the voice
                            if (draft.isBlank() && it.isNotBlank()) app.runner.prewarm(convId)  // the agent starts up while the user types
                            draft = it
                        },
                        files = files, onRemove = { discard(it); files = files - it },
                        listening = if (voice.listening) listenMode else null, level = { voice.level }, running = running,
                        onAttach = { attaching = true }, onSend = { voice.cancel(); sendNow(draft) },
                        onStop = { haptic(); convId?.let(app.runner::stop) },
                        onDictate = { talk(Listen.DICTATE) }, onVoice = { talk(Listen.VOICE) },
                        onFinishListening = voice::finish, onCancelListening = { voice.cancel(); draft = typed },
                        modifier = Modifier.navigationBarsPadding(), typing = typing, onTyping = { typing = it },
                        hidden = pinned,
                    )
                }
              }
            }
        }
    } }
    // Watch sits over the chat, so it grows out of the chat's frame and shrinks back into it.
        if (watching && info?.port != null && info.token != null) {
            val watched = convId
            // A screen held after answering stays open while it's watched.
            // Watched only while it's on screen: Watch left open behind Home or the lock screen lets the hold count down
            // (else the screen would stay up, keeping the phone awake, until the owner came back).
            val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
            DisposableEffect(watched, lifecycle) {
                fun set(on: Boolean) = watched?.let { app.runner.watching(it, on) }
                val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
                    if (e == androidx.lifecycle.Lifecycle.Event.ON_START) set(true) else if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) set(false)
                }
                lifecycle.addObserver(obs)  // (it's told ON_START at once if already started)
                onDispose { lifecycle.removeObserver(obs); set(false) }
            }
            key(watched) { WatchScreen(info.port, info.token, latestStatus(conv.messages), working = running,
                isUp = { convId in app.runner.screens.value },
                onStop = { convId?.let(app.runner::stop); watching = false }, onClose = { watching = false },
                onTouched = { convId?.let(app.runner::touched) },
                // Handed over by the answer: the owner's touches go to it at once, and Done gives it back to the agent.
                // (as the owner's message: it answers the page that handed the screen over, if the agent waits on it)
                startInControl = handoff, onHandBack = if (handoff) ({ handoff = false; watching = false; convId?.let { ownerSend(ctx, it, HANDED_BACK) } }) else null,
                taps = remember(watched) { app.runner.taps.filter { it.first == watched }.map { it.second to it.third } },
                doing = doingMap[conv.id], asks = !running && lastOutcome(conv.messages, false) == Outcome.ASKS,
                mood = lastMood(conv.messages), waiting = waitingPage(conv.messages, running) != null) }
        }
        // Talking to Dash: full screen, his ears up and your words big as he hears them.
        if (voice.listening && listenMode == Listen.VOICE && screen == Screen.CHAT) ListeningScreen(draft, { voice.level },
            onCancel = { voice.cancel(); draft = typed }, onDone = voice::finish, onType = { voice.cancel(); draft = typed; typing = true })
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
