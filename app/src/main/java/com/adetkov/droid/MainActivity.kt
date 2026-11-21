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
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
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

private enum class Screen { CHAT, SETTINGS, VOICE_MATCH, INSTRUCTIONS, MEMORY, SETUP, ABOUT }

@Composable
private fun Root(request: Request?, modifier: Modifier) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as DroidApp
    val scope = rememberCoroutineScope()
    val convs by app.store.convs.collectAsState()
    val runningMap by app.runner.running.collectAsState()
    val screens by app.runner.screens.collectAsState()
    val doingMap by app.runner.doing.collectAsState()
    val setup by app.setup.state.collectAsState()
    val models by app.prefs.models.collectAsState()
    val prefEngine by app.prefs.engine.collectAsState()

    var convId by rememberSaveable { mutableStateOf<String?>(null) }
    var screen by rememberSaveable { mutableStateOf(Screen.CHAT) }
    var returnTo by rememberSaveable { mutableStateOf(Screen.CHAT) }  // where Back leads from Instructions and Setup
    fun go(to: Screen) { returnTo = screen; screen = to }
    var watching by rememberSaveable { mutableStateOf(false) }
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
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val (voice, speaker) = rememberVoice()
    val withMic = rememberWithMic()
    val haptic = rememberHaptic()
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val pickers = rememberPickers { files = files + it }

    val conv = convs.firstOrNull { it.id == convId }
    val engine = conv?.engine ?: prefEngine  // a chat keeps the agent it started with
    val running = conv != null && conv.id in runningMap  // working on a request
    val info = conv?.let { screens[it.id] }  // its run, working or holding its screen after answering
    val watchable = info?.port != null && info.token != null
    val speakAfter = remember { mutableSetOf<String>() }  // chats whose last message was spoken: read the answer aloud

    SideEffect { app.openChat = if (watching) null else convId }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    fun send(text: String): String? {
        if (text.isBlank() && files.isEmpty()) return null
        speaker.stop()
        haptic()
        return ownerSend(ctx, convId, text.trim(), files).also {
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
    fun sendNow(text: String, after: (String) -> Unit = {}) {
        if (shooting || saving == false) takePhoto(send = true) { send(text)?.let(after) } else send(text)?.let(after)
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
                else sendNow(withTyped(text)) { id -> if (app.prefs.speakReplies.value) { speakAfter += id; speaker.prepare() } }
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
        app.runner.results.collect { (id, text) -> if (speakAfter.remove(id)) speaker.speak("auto:$id", text) }
    }
    // no screen (any more) to watch: none, or one closing (its run gone, its port with it)
    LaunchedEffect(info?.port == null, watching) { if (info?.port == null) watching = false }
    LaunchedEffect(watching) { if (!watching) handoff = false }

    fun open(id: String?) {
        if (id != convId) { draft = ""; files.forEach(::discard); files = emptyList() }
        convId = id; voice.cancel(); shooting = false; screen = Screen.CHAT
        focus.clearFocus()  // the drawer's search
        scope.launch { drawer.close() }
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
    // A notification's chat: opened as from the drawer; Watch only if its screen is up. A frame later, so Watch grows
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

    // Dialogs and sheets
    toDelete?.let { c ->
        ConfirmDialog("Delete chat?", "“${c.title}” and what Droid remembers of it will be deleted.", "Delete",
            { deleteChat(ctx, c.id); if (convId == c.id) open(null) }, { toDelete = null }, danger = true)
    }
    toRename?.let { c -> TextDialog("Rename chat", c.title, "Save", { app.store.rename(c.id, it) }) { toRename = null } }
    chatMenu?.let { c ->
        ActionSheet({ chatMenu = null }, title = c.title) { close ->
            SheetRow(R.drawable.ic_edit, "Rename", { close(); toRename = c }, tag = "sheet_rename")
            SheetRow(if (c.pinned) R.drawable.ic_unpin else R.drawable.ic_pin, if (c.pinned) "Unpin" else "Pin",
                { app.store.setPinned(c.id, !c.pinned); close() }, tag = "sheet_pin")
            SheetRow(R.drawable.ic_share, "Share", { shareText(ctx, transcriptText(c), c.title); close() }, tag = "sheet_share")
            SheetRow(R.drawable.ic_delete, "Delete", { close(); toDelete = c }, danger = true, tag = "sheet_delete")
        }
    }
    if (attaching) AttachSheet(onCamera = { withCamera { camera.reset(); shooting = true } }, pickers) { attaching = false }
    if (needVoice) ConfirmDialog("Set up voice", "Talking to Droid needs the speech models (820 MB), which run on the phone.",
        "Set up", { go(Screen.SETUP) }, { needVoice = false })

    BackHandler(voice.listening) { voice.cancel(); draft = typed }
    BackHandler(shooting && !voice.listening) { shooting = false }
    BackHandler(drawer.isOpen) { scope.launch { drawer.close() } }
    BackHandler(screen != Screen.CHAT) {
        screen = when (screen) { Screen.SETTINGS -> Screen.CHAT; Screen.VOICE_MATCH, Screen.MEMORY, Screen.ABOUT -> Screen.SETTINGS; else -> returnTo }
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
                onBack = { screen = Screen.CHAT }, onInstructions = { go(Screen.INSTRUCTIONS) }, onMemory = { screen = Screen.MEMORY },
                onSetup = { go(Screen.SETUP) },
                onVoiceMatch = { screen = Screen.VOICE_MATCH }, onAbout = { screen = Screen.ABOUT },
                onDeleteAll = { app.store.convs.value.forEach { deleteChat(ctx, it.id) }; Stills.deleteAll(ctx); Shown.deleteAll(ctx); open(null) },
            )
            Screen.VOICE_MATCH -> VoiceMatchScreen(onBack = { screen = Screen.SETTINGS }, onSetup = { go(Screen.SETUP) })
            Screen.INSTRUCTIONS -> DocScreen(app.backend, Doc.INSTRUCTIONS, "Instructions",
                "Droid reads this at the start of every task. Tell it about yourself, the people and apps you use, and how you like things done.",
                "instructions", onClose = { screen = returnTo })
            Screen.MEMORY -> MemoryScreen(app.backend) { screen = Screen.SETTINGS }
            Screen.ABOUT -> AboutScreen { screen = Screen.SETTINGS }
            Screen.SETUP -> SetupScreen(app.setup, firstRun = false) { screen = returnTo }
            Screen.CHAT -> {
              // Droid's screen in the chat: small frames, twice a second. The drawer's tile for this chat shares it, so
              // it opens already showing what the chat shows.
              val feed = if (conv != null && conv.messages.isNotEmpty())
                  rememberScreenFeed(info?.port, info?.token, width = 300, fps = 2, isUp = { conv.id in app.runner.screens.value }) else null
              ModalNavigationDrawer(
                drawerState = drawer,
                drawerContent = {
                    Drawer(convs, convId, runningMap.keys, setup, ::open,
                        screens = screens, doing = doingMap, visible = drawer.targetValue == DrawerValue.Open || drawer.currentValue == DrawerValue.Open,
                        chatFeed = feed,
                        isUp = { id -> id in app.runner.screens.value },
                        onLongPress = { chatMenu = it },
                        onSettings = { go(Screen.SETTINGS); scope.launch { drawer.close() } })
                },
            ) {
                Column(Modifier.fillMaxSize().background(P.bg).imePadding()) {
                    TopBar(
                        engine, models[engine].orEmpty(), onModel = { app.prefs.setModel(engine, it) }, conv = conv,
                        onMenu = { scope.launch { drawer.open() } }, onNew = { open(null) },
                        onMore = { chatMenu = conv },
                    )
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (conv == null || conv.messages.isEmpty()) {
                            // A suggestion fills the box rather than sending: these act on the phone, so the user confirms.
                            EmptyState({ draft = it }, Modifier.fillMaxSize(), listening = voice.listening, level = { voice.level }, typing = draft.isNotBlank())
                        } else {
                            val taps = remember(conv.id) { app.runner.taps.filter { it.first == conv.id }.map { it.second to it.third } }
                            key(conv.id) { Transcript(conv, running, feed, speaker.speakingKey, speaker::toggle, held = watchable && !running, closesAt = info?.closesAt, taps = taps,
                                doing = doingMap[conv.id], typing = draft.isNotBlank(), listening = voice.listening, level = { voice.level },
                                onRetry = { haptic(); retryTask(ctx, conv.id) }, onSetup = { go(Screen.SETUP) },
                                onWatch = { watching = true }, modifier = Modifier.fillMaxSize(),
                                onTakeOver = { handoff = true; watching = true }) }
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
                        text = draft, onText = { if (voice.listening) voice.cancel(); draft = it },  // typing takes over from the voice
                        files = files, onRemove = { discard(it); files = files - it },
                        listening = if (voice.listening) listenMode else null, level = { voice.level }, running = running,
                        onAttach = { attaching = true }, onSend = { voice.cancel(); sendNow(draft) },
                        onStop = { haptic(); convId?.let(app.runner::stop) },
                        onDictate = { talk(Listen.DICTATE) }, onVoice = { talk(Listen.VOICE) },
                        onFinishListening = voice::finish, onCancelListening = { voice.cancel(); draft = typed },
                        modifier = Modifier.navigationBarsPadding(),
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
    }
}

@Composable
private fun TopBar(
    engine: Engine, model: String, onModel: (String) -> Unit, conv: Conv?,
    onMenu: () -> Unit, onNew: () -> Unit, onMore: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBtn(R.drawable.ic_menu, "Menu", onMenu, Modifier.testTag("menu"), size = 44.dp)
        // Takes what the buttons leave; a long model name is cut short.
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            var open by remember { mutableStateOf(false) }
            Row(
                Modifier.clip(RoundedCornerShape(12.dp)).clickable { open = true }.padding(start = 8.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
                    .testTag("model"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Droid", style = MaterialTheme.typography.titleMedium)
                if (engine != Engine.CLAUDE) Text(" " + engine.label, style = MaterialTheme.typography.titleMedium, color = P.muted, maxLines = 1)
                if (model.isNotEmpty()) Text(" " + engine.model(model).label.substringAfterLast('/'), Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.titleMedium, color = P.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(painterResource(R.drawable.ic_expand), null, Modifier.padding(start = 2.dp).size(20.dp), tint = P.muted)
            }
            DropdownMenu(open, { open = false }, shape = RoundedCornerShape(18.dp), containerColor = P.sheet) {
                Text("Model · ${engine.label}", Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium, color = P.muted)
                engine.models.forEach { m ->
                    Row(
                        Modifier.width(280.dp).clickable { onModel(m.alias); open = false }.padding(horizontal = 16.dp, vertical = 10.dp)
                            .testTag("model_" + m.label.lowercase()),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(m.label, style = MaterialTheme.typography.titleSmall)
                            Text(m.detail, style = MaterialTheme.typography.bodySmall, color = P.muted)
                        }
                        if (m.alias == model) Icon(painterResource(R.drawable.ic_check), "Selected", Modifier.size(20.dp), tint = P.text)
                    }
                }
            }
        }
        if (conv != null) {
            IconBtn(R.drawable.ic_new_chat, "New chat", onNew, Modifier.testTag("new_chat"), size = 44.dp)
            IconBtn(R.drawable.ic_more, "Chat options", onMore, Modifier.testTag("overflow"), size = 44.dp)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Drawer(
    convs: List<Conv>, convId: String?, running: Set<String>, setup: SetupState, onOpen: (String?) -> Unit,
    /** Runs with a screen (working, or held after answering) and whether the drawer shows: those get live windows. */
    screens: Map<String, RunInfo>, visible: Boolean, isUp: (String) -> Boolean,
    onLongPress: (Conv) -> Unit, onSettings: () -> Unit,
    /** The open chat's own screen feed, if it has one: its tile uses it. */
    chatFeed: ScreenFeed? = null,
    /** What each working chat's run is doing. */
    doing: Map<String, Doing> = emptyMap(),
) {
    var query by rememberSaveable { mutableStateOf("") }
    ModalDrawerSheet(
        drawerContainerColor = P.drawer, drawerContentColor = P.text, windowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        drawerShape = RoundedCornerShape(topEnd = 0.dp, bottomEnd = 0.dp), modifier = Modifier.width(320.dp),
    ) {
        Column(Modifier.fillMaxHeight().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(22.dp)).background(P.surface).padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(painterResource(R.drawable.ic_search), null, Modifier.size(20.dp), tint = P.muted)
                    Box(Modifier.weight(1f).padding(start = 10.dp)) {
                        if (query.isEmpty()) Text("Search", style = MaterialTheme.typography.bodyLarge, color = P.muted)
                        BasicTextField(query, { query = it }, Modifier.fillMaxWidth().testTag("search"), singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = P.text), cursorBrush = SolidColor(P.text))
                    }
                    if (query.isNotEmpty()) IconBtn(R.drawable.ic_close, "Clear", { query = "" }, size = 28.dp, iconSize = 16.dp, tint = P.muted)
                }
                IconBtn(R.drawable.ic_new_chat, "New chat", { onOpen(null) }, Modifier.testTag("drawer_new"), size = 44.dp)
            }
            // Now: each chat whose run is up, as its screen, live while an app is on it.
            // (one whose agent is only exiting after its answer, with nothing left to show, is in the list again)
            val live = remember(convs, screens, running) {
                convs.filter { it.id in running || screens[it.id]?.port != null }.sortedByDescending { it.updatedAt }
            }
            if (query.isBlank() && live.isNotEmpty()) {
                // room under the label for Dash's hook (and his call's rings) over each tile
                SectionLabel("Now", Modifier.padding(start = 24.dp, top = 8.dp, bottom = 22.dp))
                LazyRow(Modifier.fillMaxWidth().testTag("now"), contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(live, key = { it.id }) { c ->
                        NowTile(c, screens[c.id], visible, working = c.id in running, doing = doing[c.id], isUp = { isUp(c.id) },
                            shared = chatFeed.takeIf { c.id == convId }) { onOpen(c.id) }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            val q = query.trim()
            val now = System.currentTimeMillis()
            val matches = remember(convs, q) {
                convs.mapNotNull { c ->
                    when {
                        q.isEmpty() || c.title.contains(q, true) -> c to null
                        else -> c.messages.firstOrNull { (it.kind == Kind.USER || it.kind == Kind.AGENT) && it.text.contains(q, true) &&
                            (!hasHtml(it.text) || htmlToText(it.text).contains(q, true)) }  // a page's words, not its markup
                            ?.let { c to snippet(it.text, q) }
                    }
                }.sortedByDescending { it.first.updatedAt }
            }
            val listed = if (q.isEmpty()) matches.filter { m -> live.none { it.id == m.first.id } } else matches  // the rest are under Now
            val pinned = if (q.isEmpty()) listed.filter { it.first.pinned } else emptyList()
            val groups = (listed - pinned.toSet()).groupBy { dayGroup(it.first.updatedAt, now) }
            LazyColumn(Modifier.weight(1f).testTag("chats"), contentPadding = PaddingValues(bottom = 12.dp)) {
                fun section(label: String, list: List<Pair<Conv, String?>>) {
                    item(key = "h$label") { SectionLabel(label, Modifier.padding(start = 24.dp, top = 16.dp, bottom = 6.dp)) }
                    items(list, key = { "${label}_${it.first.id}" }) { (c, snip) ->
                        ChatRow(c, snip, c.id == convId, c.id in running, { onOpen(c.id) }, { onLongPress(c) }, visible, doing[c.id], now)
                    }
                }
                if (pinned.isNotEmpty()) section("Pinned", pinned)
                groups.forEach { (g, list) -> section(g, list) }
                if (matches.isEmpty()) item {
                    Text(if (q.isEmpty()) "Your chats will show up here." else "No chats match “$q”.",
                        Modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium, color = P.muted)
                }
            }
            HorizontalDivider(color = P.hairline)
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onSettings).padding(horizontal = 16.dp, vertical = 12.dp).testTag("drawer_settings"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(setup.email)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(setup.email ?: "Droid", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(planLabel(setup.plan) ?: "Settings", style = MaterialTheme.typography.bodySmall, color = P.muted)
                }
                Icon(painterResource(R.drawable.ic_settings), "Settings", Modifier.size(22.dp), tint = P.muted)
            }
        }
    }
}

/**
 * A chat in the drawer's Now row: Dash, framing its run's screen (live while the drawer shows) while an app is on it and
 * a small creature while it works without one, in the pose of what it's doing; its title; and the app Droid is in (or,
 * once the run waits on a held screen, "Your turn" when the answer asked you something, else "Answered").
 * [shared]: the open chat's feed of the same screen, used instead of opening another.
 */
@Composable
private fun NowTile(c: Conv, info: RunInfo?, visible: Boolean, working: Boolean, doing: Doing?, isUp: () -> Boolean, shared: ScreenFeed?, onOpen: () -> Unit) {
    val feed = shared ?: if (visible) rememberScreenFeed(info?.port, info?.token, width = 240, fps = 1, isUp = isUp) else null
    val f = feed?.frame
    val app = rememberAppInfo(feed?.app)?.label
    Column(Modifier.width(88.dp).clickable(remember { MutableInteractionSource() }, null, onClick = onOpen).testTag("now_tile")) {
        val img = remember(f) { f?.asImageBitmap() }
        val live = feed?.app != null
        val outcome = remember(c.messages, working) { lastOutcome(c.messages, working) }
        val mood = remember(c.messages) { lastMood(c.messages) }
        val asking = remember(c.messages, working) { waitingPage(c.messages, working) != null }
        val feel = chatFeel(working, doing, outcome, mood, asking).let { if (it == Feel.HAPPY) Feel.IDLE else it }
        val rig = rememberDash(c.id, active = visible) { it.frame.snap(if (live) 1f else 0f); it.feel(feel) }
        LaunchedEffect(live) { rig.frame.t = if (live) 1f else 0f }
        LaunchedEffect(feel) { rig.feel(feel) }
        LaunchedEffect(doing?.n) { if (doing != null && doing.n > 0 && visible) rig.react(doing) }
        DashSlot(rig, img.takeIf { live }, creature = DpSize(40.dp, 55.dp), width = 88.dp, ratio = img?.let { it.height.toFloat() / it.width } ?: screenRatio(),
            label = if (live) "Droid's screen, live" else "Dash, working", onClick = onOpen)
        Text(c.title, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val asks = (!working && outcome == Outcome.ASKS) || asking
        Text(if (asks) "Your turn" else if (working) app ?: "Working" else mood?.words?.ifEmpty { null } ?: "Answered", style = MaterialTheme.typography.labelSmall,
            color = if (asks) P.call else P.faint, fontWeight = if (asks) FontWeight.Medium else null, maxLines = 1)
    }
}

/** How long a chat that asked you something, or couldn't finish, shows Dash in the drawer. */
private const val WAITS_FOR = 12 * 3600_000L

/** "Claude Max" for the plan names Claude Code reports. */
fun planLabel(plan: String?): String? = when (plan?.lowercase()) {
    null, "" -> null
    "pro" -> "Claude Pro"
    "max" -> "Claude Max"
    "team" -> "Claude Team"
    "enterprise" -> "Claude Enterprise"
    else -> "Claude " + plan.replaceFirstChar { it.uppercase() }
}

/** The words around the first match of [q] in [text], on one line. */
private fun snippet(text: String, q: String): String {
    val flat = stripMarkdown(text).replace(Regex("\\s+"), " ")
    val i = flat.indexOf(q, ignoreCase = true).coerceAtLeast(0)
    val from = (i - 24).coerceAtLeast(0).let { s -> if (s == 0) 0 else flat.indexOf(' ', s).let { if (it in s until i) it + 1 else s } }
    return (if (from > 0) "…" else "") + flat.substring(from).take(90)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatRow(c: Conv, snip: String?, selected: Boolean, running: Boolean, onClick: () -> Unit, onLongClick: () -> Unit, visible: Boolean = true,
                    doing: Doing? = null, now: Long = System.currentTimeMillis()) {
    // Dash on a chat while it works, and for half a day after it asked you something or couldn't finish
    val outcome = remember(c.messages, running) { lastOutcome(c.messages, running) }
    val mood = remember(c.messages) { lastMood(c.messages) }
    val asking = remember(c.messages, running) { waitingPage(c.messages, running) != null }
    val waiting = (!running && now - c.updatedAt < WAITS_FOR && (outcome == Outcome.ASKS || outcome == Outcome.FAILED)) || asking
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(12.dp))
            .background(if (selected) P.raised else Color.Transparent)
            .combinedClickable(onLongClick = onLongClick, onClick = onClick)
            .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp).testTag("conv_item"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(c.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (snip != null) Text(snip, style = MaterialTheme.typography.bodySmall, color = P.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            else if (waiting) Text(if (asking) "Your turn" else turnLabel(outcome, mood).orEmpty(), style = MaterialTheme.typography.bodySmall,
                color = if (outcome == Outcome.ASKS || asking) P.call else P.danger, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (running || waiting) DashMini(chatFeel(running, doing, outcome, mood, asking), 28.dp, Modifier.padding(start = 10.dp), active = visible, doing = doing,
            label = when { asking || outcome == Outcome.ASKS -> "Your turn"; running -> "Working"; else -> "Couldn't finish" })
    }
}
