package com.adetkov.droid

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread

/**
 * The assistant (long-press power): a panel that slides up over the current app or the lock screen and listens
 * at once. What is said is sent when the user stops talking (when locked: if it is the owner's voice, see
 * [VoiceMatch], or after the fingerprint or PIN), the agent's
 * progress and answer show in the panel, and the answer is read aloud. Tap outside or Back to close it; the task
 * keeps running and notifies when done. The keyboard button types the request instead (what was heard so far is kept
 * to edit); a typed request waits for the unlock on the lock screen, and its answer isn't read aloud. Opened over an
 * app, the first request takes a screenshot of it along (see [AssistService]), unless the user takes it off. Or the user turns on Droid's camera ([Camera]) and points it while
 * they talk: its picture goes instead.
 */
class AssistActivity : ComponentActivity() {
    /** Bumped by each long-press, so the panel listens again. */
    private var invocation by mutableIntStateOf(0)
    private var testWav: String? = null
    /** The screenshot of the app the panel opened over, until a request takes it along or it is dropped. */
    var shot by mutableStateOf<Attachment?>(null)
    var withShot by mutableStateOf(true)
    // The request and the chat it went to; a new long-press starts them over (see onNewIntent).
    var convId by mutableStateOf<String?>(null)
    var heard by mutableStateOf("")               // the request being spoken, or the one awaiting unlock
    var pending by mutableStateOf<String?>(null)  // a finished request waiting for the unlock
    var notice by mutableStateOf<String?>(null)
    var sentShot by mutableStateOf<Attachment?>(null)  // went with the request on show
    var camOn by mutableStateOf(false)  // the camera, not the screen, goes with the request
    var typing by mutableStateOf(false)  // the keyboard instead of the voice, for this request
    var draft by mutableStateOf("")
    var typed = false  // the request on show was typed: its answer isn't read aloud
    private var stopped = false

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        testWav = testWav(this, intent)
        if (savedInstanceState == null) invocation = 1  // not when rebuilt, e.g. after the process died
        setContent { DroidTheme { Panel(invocation, testWav, Modifier.semantics { testTagsAsResourceId = true }) } }
        val app = application as DroidApp
        lifecycleScope.launch {
            app.assistShot.collect { a ->
                if (a != null && app.assistShot.compareAndSet(a, null)) { shot?.let(::discard); shot = a; withShot = true }
            }
        }
    }

    override fun onDestroy() {
        if (isFinishing) shot?.let(::discard).also { shot = null }
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        testWav = testWav(this, intent)
        // A long-press is a new request, not a follow-up to the chat the panel last showed: after Home or a link the
        // panel stays alive, maybe for hours. (This long-press's screenshot comes after: the session saves it first.)
        shot?.let(::discard)
        shot = null; withShot = true
        convId = null; heard = ""; pending = null; notice = null; sentShot = null; camOn = false; typing = false; draft = ""; typed = false
        invocation++
    }

    override fun onResume() {
        super.onResume()
        (application as DroidApp).resumed = true
        (application as DroidApp).voiceMatch.resetIfUnlocked()
        // Once the phone locks again, the chat the panel showed shouldn't stay on the lock screen. Checked here, after a
        // stop: a long-press's onNewIntent comes before this, and has started the panel over.
        if (stopped && convId != null && locked) finish()
        stopped = false
    }
    override fun onPause() { (application as DroidApp).resumed = false; super.onPause() }
    override fun onStop() { stopped = true; super.onStop() }

    /** Runs [then] once the keyguard is gone (asking for the fingerprint or PIN if needed); [cancelled] otherwise. */
    fun unlocked(then: () -> Unit, cancelled: () -> Unit) {
        val km = getSystemService(KeyguardManager::class.java)
        val match = (application as DroidApp).voiceMatch
        if (!km.isKeyguardLocked) { match.resetIfUnlocked(); return then() }
        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() { match.resetIfUnlocked(); then() }
            override fun onDismissCancelled() = cancelled()
            override fun onDismissError() = cancelled()
        })
    }

    val locked get() = getSystemService(KeyguardManager::class.java).isKeyguardLocked
}

/**
 * A page's calls in the panel ([actions]: as in the chat). On the lock screen the panel is open to whoever holds the
 * phone, and a voice match lets through only the request it heard: what a page does for the owner (send, open, hand
 * over the screen) waits for the unlock ([unlocked]), and a command isn't run (the owner taps again once unlocked).
 */
internal class PanelPageActions(
    private val actions: PageActions, private val locked: () -> Boolean, private val unlocked: (() -> Unit) -> Unit,
) : PageActions by actions {
    override fun send(text: String, data: String?) = unlocked { actions.send(text, data) }
    override fun open(url: String) = unlocked { actions.open(url) }
    override fun screen() = unlocked { actions.screen() }
    override fun run(command: String): Pair<String, Int> =
        if (locked()) { unlocked {}; "The phone is locked. Unlock it, then try again." to 1 } else actions.run(command)
}

@Composable
private fun AssistActivity.Panel(invocation: Int, testWav: String?, modifier: Modifier) {
    val app = application as DroidApp
    val (voice, speaker) = rememberVoice()
    val withMic = rememberWithMic { notice = it }
    val convs by app.store.convs.collectAsState()
    val running by app.runner.running.collectAsState()
    val match = app.voiceMatch
    val scope = rememberCoroutineScope()

    val camera = remember { Camera() }
    val withCamera = rememberWith(Manifest.permission.CAMERA) { notice = it }
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }

    val conv = convs.firstOrNull { it.id == convId }
    val isRunning = convId != null && convId in running
    val answer = conv?.messages?.takeLastWhile { !it.kind.opensTurn }?.lastOrNull { it.kind == Kind.AGENT }?.text
    // a page the agent waits on, mid-task: answered right here
    val waiting = conv?.let { waitingPage(it.messages, isRunning) }

    fun close() { pending = null; voice.cancel(); speaker.stop(); app.runner.dropWarm(); shown.targetState = false }
    LaunchedEffect(shown.currentState, shown.targetState) { if (!shown.targetState && shown.isIdle) finish() }
    SideEffect { app.openChat = convId }

    fun send(text: String) {
        if (!shown.targetState || isFinishing || isDestroyed) return  // closed while a voice match or unlock was on its way
        pending = null
        heard = text
        // The screenshot is of what the user looked at when they asked first; a follow-up doesn't take it again.
        val s = shot.also { shot = null }
        val screen = s?.takeIf { withShot && !camOn }
        if (screen == null) s?.let(::discard)
        val photo = if (camOn) camera.picture() else null
        camOn = false
        sentShot = screen
        speaker.prepare()
        // What the agent hears of how it was asked: typed or said (and read back), over which app, or on the lock screen
        val via = (if (typed) "I typed this" else "I said this") +
            (if (locked) " on the lock screen" else app.assistOver?.let { " in the assistant, over $it" } ?: " in the assistant") +
            (if (typed) "" else ", and your answer is read aloud to me")
        if (photo == null) { convId = ownerSend(app, convId, text, listOfNotNull(screen), via); return }
        // Saved off the main thread; the request goes once it is, even if the panel is closed by then.
        val conv = convId
        thread(name = "photo") {
            val a = savePhoto(app, photo)
            runOnUiThread { sentShot = a; convId = ownerSend(app, conv, text, listOfNotNull(a), via) }
        }
    }
    fun unlockAndSend(why: String = "Unlock to send") = unlocked({ pending?.let(::send) }, { notice = why })
    fun deliver(text: String, audio: FloatArray) {
        if (camOn) camera.picture()  // what they pointed at while talking: it stays on screen while they unlock
        heard = text
        pending = text
        notice = null
        if (!locked) return unlockAndSend()
        if (!match.available) {
            val paused = match.enabled.value && match.print.value != null && match.misses.value >= VoiceMatch.MAX_MISSES
            return unlockAndSend(if (paused) "Voice match is paused until you unlock" else "Unlock to send")
        }
        scope.launch {
            val verdict = withContext(Dispatchers.Default) { match.verify(audio) }
            if (pending !== text) return@launch  // listening again since: this request was dropped
            when (verdict) {
                VoiceMatch.Verdict.MATCH -> send(text)
                VoiceMatch.Verdict.NO_MATCH -> unlockAndSend(
                    if (match.misses.value >= VoiceMatch.MAX_MISSES) "Didn't recognize your voice. Voice match is paused until you unlock"
                    else "Didn't recognize your voice. Unlock to send")
                VoiceMatch.Verdict.TOO_SHORT -> unlockAndSend("Too short to recognize your voice. Unlock to send")
                VoiceMatch.Verdict.UNAVAILABLE -> unlockAndSend()
            }
        }
    }
    fun listen(wav: String? = null) {
        speaker.stop()
        notice = null
        pending = null
        heard = ""
        sentShot = null
        typing = false; draft = ""; typed = false
        app.runner.prewarm(convId)  // the agent starts up while the user talks
        if (locked && match.available) match.warmUp()
        voice.start(wav?.let(::WavMic),
            onPartial = { heard = it },
            onFinal = ::deliver,
            onNothing = { notice = "Didn't catch that"; app.runner.dropWarm() },
            onError = { notice = it; app.runner.dropWarm() },
            onPause = { if (camOn) camera.mark() },
        )
    }
    /** Runs [action] with [permission], asked for through [with]: its dialog doesn't show over the lock screen, so then after the unlock. */
    fun allowing(permission: String, with: (() -> Unit) -> Unit, what: String, action: () -> Unit) {
        val allowed = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        if (allowed || !locked) with(action) else unlocked({ with(action) }, { notice = "Unlock to allow the $what" })
    }
    /** The keyboard instead of the voice: what was heard so far (or awaits the unlock) is kept, to edit. */
    fun startTyping() {
        if (app.setup.needed()) { notice = "Open Droid to finish setting it up"; return }
        draft = if (voice.listening || pending != null) heard else ""
        voice.cancel(); speaker.stop()
        notice = null; pending = null; heard = ""; sentShot = null
        typing = true
        app.runner.prewarm(convId)  // the agent starts up while the user types
    }
    /** Listens, once Droid and its voice are set up and the microphone is allowed; without voice, the keyboard. */
    fun talk(wav: String? = null) {
        when {
            app.setup.needed() -> notice = "Open Droid to finish setting it up"
            !app.speech.installed -> { startTyping(); notice = "To talk, set up voice in Droid: Settings → Setup" }
            else -> allowing(Manifest.permission.RECORD_AUDIO, withMic, "microphone") { listen(wav) }
        }
    }
    /** Sends what was typed, as a spoken request; a voice match can't vouch for it, so when locked it waits for the unlock. */
    fun submit() {
        val text = draft.trim().ifEmpty { return }
        if (camOn) camera.picture()  // the frame now (or the one tapped), on screen while they unlock
        typing = false; draft = ""; typed = true
        heard = text; pending = text; notice = null
        unlockAndSend()
    }
    /** Turns the camera on in place of the screen, and listens if it isn't already (or being typed to). */
    fun openCamera() = allowing(Manifest.permission.CAMERA, withCamera, "camera") {
        camera.reset(); camOn = true; withShot = false; if (!voice.listening && !typing && pending == null) talk()
    }
    fun openChat() = unlocked({
        startActivity(Intent(this, MainActivity::class.java).putExtra(EXTRA_CONV, convId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        voice.cancel(); speaker.stop(); finish()
    }, {})
    /** The screen the answer handed over: in the app, with the owner's touches going to it. */
    fun takeOver() = unlocked({
        startActivity(Intent(this, MainActivity::class.java).putExtra(EXTRA_CONV, convId).putExtra(EXTRA_HANDOFF, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        voice.cancel(); speaker.stop(); finish()
    }, {})
    /** What a page in the panel may do (see [PanelPageActions]). */
    fun pageActions(conv: String, pageId: String?) =
        PanelPageActions(ChatPageActions(this, conv, pageId, onScreen = ::takeOver), { locked }) { then ->
            runOnUiThread { unlocked(then) { notice = "Unlock to use the page" } }
        }

    LaunchedEffect(invocation) { if (invocation > 0) talk(testWav) }
    LaunchedEffect(Unit) {
        app.runner.results.collect { (id, text) -> if (id == convId && shown.targetState && !typed) speaker.speak("assist:$id", spokenAnswer(app.store.get(id), text)) }
    }
    BackHandler(shown.targetState) { close() }

    val scrim by animateFloatAsState(if (shown.targetState) .55f else 0f, tween(220), label = "scrim")
    // Dash's frame around your screen or the camera: what goes with the request. It lets go when that is off or sent.
    val framed by animateFloatAsState(if (shown.targetState && (camOn || shot != null && withShot)) 1f else 0f,
        spring(dampingRatio = .9f, stiffness = 260f), label = "framed")
    val rig = rememberDash(Unit)
    rig.level = { voice.level }
    // the notch is Dash: listening to you, then in the pose of what the run does, then what the answer leaves for you
    val doings by app.runner.doing.collectAsState()
    val doing = convId?.let { doings[it] }
    val outcome = remember(conv?.messages, isRunning) { conv?.let { lastOutcome(it.messages, isRunning) } }
    val mood = remember(conv?.messages) { conv?.let { lastMood(it.messages) } }
    val notchFeel = when {
        voice.listening -> Feel.LISTENING
        waiting != null -> Feel.NEEDS_YOU
        isRunning && answer == null -> doing?.work?.feel() ?: Feel.THINKING
        outcome == Outcome.FAILED -> Feel.CONFUSED
        mood != null -> mood.feel
        outcome == Outcome.ASKS -> Feel.NEEDS_YOU
        answer != null -> Feel.HAPPY
        else -> Feel.IDLE
    }
    LaunchedEffect(notchFeel) { rig.feel(notchFeel); if (notchFeel == Feel.HAPPY) { delay(1400); rig.feel(Feel.IDLE) } }
    LaunchedEffect(doing?.n) { if (doing != null && doing.n > 0) rig.react(doing) }
    Box(modifier.fillMaxSize().background(Color.Black.copy(alpha = scrim))
        .clickable(remember { MutableInteractionSource() }, indication = null, onClick = ::close)) {
        // The camera fills the screen behind the panel; a tap on it takes the picture (again, if one was taken).
        AnimatedVisibility(camOn, Modifier.fillMaxSize(), enter = fadeIn(tween(160)), exit = fadeOut(tween(200))) {
            CameraView(camera, onTap = camera::take, Modifier.fillMaxSize().testTag("camera"), hint = false)
        }
        Box(Modifier.fillMaxSize().drawBehind {
            val fr = framed.coerceIn(0f, 1f)
            if (fr > .01f) {
                val e = 3.dp.toPx() * fr; val inset = (1f - fr) * 18.dp.toPx() + e / 2
                drawRoundRect(DashColors.Accent.copy(alpha = fr), Offset(inset, inset), Size(size.width - 2 * inset, size.height - 2 * inset),
                    CornerRadius(28.dp.toPx()), style = Stroke(e))
            }
        })
        AnimatedVisibility(shown, Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(spring(stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn(),
            exit = slideOutVertically(tween(200)) { it } + fadeOut(tween(200))) {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
          if (camOn) CameraHint(camera, Modifier.padding(bottom = 10.dp))
            // Droid's four rows, in a sheet over the app you're in: Dash's line, what goes with the request, the page or
            // answer, and the bar.
            Column(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(P.bg)
                    .clickable(remember { MutableInteractionSource() }, indication = null) {}  // taps inside don't close it
                    .navigationBarsPadding().imePadding().padding(top = 10.dp)
                    .animateContentSize(spring(stiffness = Spring.StiffnessMediumLow))
                    .testTag("assistant"),
            ) {
                val sent = convId != null && pending == null && !voice.listening && !typing
                val working = isRunning && answer == null
                // the sheet's top: its grabber, and on the right what takes you on: Stop while he works, else the chat
                Box(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 12.dp)) {
                    Box(Modifier.align(Alignment.TopCenter).size(36.dp, 4.dp).clip(CircleShape).background(P.hairline))
                    Row(Modifier.align(Alignment.CenterEnd)) {
                        if (working) StopButton { convId?.let(app.runner::stop) }
                        else if (convId != null) Text("Open", Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = ::openChat)
                            .padding(horizontal = 14.dp, vertical = 10.dp).testTag("open_chat"), style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
                            color = P.muted)
                    }
                }
                val words = when {
                    voice.listening -> "I'm listening…"
                    notice != null && heard.isEmpty() && !typing -> notice!!
                    pending != null -> "Unlock, and I'll start."
                    waiting != null -> mood?.words?.takeIf { it.isNotBlank() } ?: "Your turn"
                    working -> mood?.words?.takeIf { it.isNotBlank() } ?: "On it!"
                    sent && answer != null -> mood?.words?.takeIf { it.isNotBlank() } ?: if (outcome == Outcome.FAILED) "That didn't work" else "Here you go"
                    else -> "What should I do?"
                }
                // while he works his words hold still; what he's doing is the grey line under them
                DashLine(words, rig = rig, modifier = Modifier.testTag("dash"),
                    sub = if (working && waiting == null) conv?.let { latestStep(it.messages) } ?: "Getting started" else null)
                // What goes with the request: before it goes, the screen (over an app) or the camera; then what went.
                val canTalk = !working
                val went = sentShot
                if (went != null) Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp)) { SentChip(went, Modifier) }
                else if (shot != null || canTalk) Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    shot?.let { ScreenChip(it, on = withShot, onToggle = { withShot = !withShot; if (withShot) camOn = false }) }
                    if (canTalk) CameraChip(on = camOn, onToggle = { if (camOn) camOn = false else openCamera() })
                }
                // what Dash hears, big, until it goes (then it's the chat's)
                if (heard.isNotEmpty() && !typing && !sent) YouBubble(heard, Modifier.padding(top = 8.dp).testTag("heard"), big = true)
                notice?.takeIf { heard.isNotEmpty() || typing }?.let {
                    Text(it, Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp).testTag("notice"),
                        color = P.muted, style = MaterialTheme.typography.bodyMedium, textAlign = if (heard.isNotEmpty()) TextAlign.End else TextAlign.Start)
                }
                val cid = convId
                if (waiting != null && cid != null) {
                    val info = PageInfo.of(waiting)
                    val dark = P.dark
                    val actions = remember(cid, info.id) { pageActions(cid, info.id) }
                    Box(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp)) {
                        InlinePage("assist:${info.id}:${waiting.text.hashCode()}", "page:$cid:${info.id}", dark,
                            { cx -> pageDocument(cx, waiting.text, dark, PageMode.FLOAT) }, namedPaths(waiting.text), actions,
                            Modifier.testTag("page"), mode = PageMode.FLOAT, maxHeight = 420.dp)
                    }
                }
                if (sent && answer != null) {
                    val scroll = rememberScrollState()
                    val drag = rememberListDrag(scroll)
                    Box(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp).heightIn(max = 340.dp).verticalScroll(scroll).testTag("answer")) {
                        if (hasHtml(answer) && cid != null && conv != null) {
                            // an answer with a page: the page, as in the chat
                            val dark = P.dark
                            val index = conv.messages.indexOfLast { it.kind == Kind.AGENT }
                            val actions = remember(cid, index) { pageActions(cid, null) }
                            CompositionLocalProvider(LocalListDrag provides drag) {
                                InlinePage("a:$cid:$index:${answer.hashCode()}", "answer:$cid:$index", dark, { cx -> answerDocument(cx, answer, dark) },
                                    namedPaths(answer), actions)
                            }
                        } else CompositionLocalProvider(LocalContentColor provides P.text) { Markdown(answerText(answer)) }
                    }
                }
                Spacer(Modifier.size(14.dp))
                if (pending != null && !voice.listening) NextStep("Unlock to send", { unlockAndSend() }, tag = "orb")
                when {
                    voice.listening -> ListenBar({ voice.level }, onDone = voice::finish, onCancel = { voice.cancel(); heard = ""; app.runner.dropWarm() },
                        onType = ::startTyping)
                    typing -> TypedRequest(draft, { draft = it }, ::submit, onTalk = if (app.speech.installed) ({ talk() }) else null)
                    else -> TalkBar({ if (speaker.speakingKey != null) speaker.stop(); talk() }, ::startTyping, typeOnly = !app.speech.installed)
                }
            }
          }
        }
    }
}

/**
 * The request, typed: the bar's typing mode, as in the app (talk instead on the left, send on the right), with the
 * keyboard up at once and its cursor after what's kept.
 */
@Composable
private fun TypedRequest(text: String, onText: (String) -> Unit, onSend: () -> Unit, onTalk: (() -> Unit)? = null) {
    val focus = remember { FocusRequester() }
    var field by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val style = MaterialTheme.typography.bodyLarge.copy(color = P.text, fontSize = 18.sp, lineHeight = 25.sp)
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp).heightIn(min = 64.dp).clip(RoundedCornerShape(30.dp))
        .border(2.dp, P.call, RoundedCornerShape(30.dp)).padding(start = if (onTalk != null) 0.dp else 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (onTalk != null) IconBtn(R.drawable.ic_mic, "Talk instead", onTalk, Modifier.testTag("mic"), tint = CallText, size = 56.dp, iconSize = 22.dp)
        Box(Modifier.weight(1f).padding(vertical = 8.dp)) {
            if (field.text.isEmpty()) Text("Message Droid", style = style.copy(color = P.muted))
            BasicTextField(field, { field = it; onText(it.text) }, Modifier.fillMaxWidth().focusRequester(focus).testTag("typed"),
                textStyle = style, cursorBrush = SolidColor(P.call), maxLines = 6,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }))
        }
        Box(Modifier.padding(start = 6.dp).size(48.dp).clip(CircleShape).background(if (field.text.isBlank()) P.raised else P.call)
            .clickable(enabled = field.text.isNotBlank(), onClick = onSend).testTag("send"), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_send), "Send", Modifier.size(22.dp), tint = if (field.text.isBlank()) P.faint else OnCall)
        }
    }
}

/** The phone-shaped picture of a screenshot, or of a photo from the full-screen camera. */
@Composable
private fun ChipThumb(path: String, on: Boolean = true) = Thumb(path, 64, Modifier.padding(end = 8.dp).size(width = 15.dp, height = 28.dp)
    .clip(RoundedCornerShape(4.dp)).border(1.dp, P.hairline, RoundedCornerShape(4.dp)).graphicsLayer { alpha = if (on) 1f else .35f })

/** The screenshot of the app the panel opened over, a pick: tap to take it off, or to put it back. */
@Composable
private fun ScreenChip(shot: Attachment, on: Boolean, onToggle: () -> Unit) =
    Pick(on, onToggle, Modifier.testTag("screen_chip"), if (on) "Don't send the screen" else "Send the screen") {
        ChipThumb(shot.path, on)
        PickText("Your screen", on)
    }

/** Droid's camera, a pick: on, it fills the screen behind the panel, in Dash's ring, and its picture goes with the request. */
@Composable
private fun CameraChip(on: Boolean, onToggle: () -> Unit) =
    Pick(on, onToggle, Modifier.testTag("camera_chip"), if (on) "Don't send the camera" else "Show Droid your camera") {
        Icon(painterResource(R.drawable.ic_camera), null, Modifier.padding(end = 8.dp).size(20.dp), tint = if (on) CallText else P.text)
        PickText("Camera", on)
    }

/** What went with the request: the screenshot or the photo. */
@Composable
private fun SentChip(a: Attachment, modifier: Modifier) = Pick(true, {}, modifier.testTag("sent_chip")) {
    ChipThumb(a.path)
    PickText(if (a.source == Source.CAMERA) "Photo" else "Your screen", true)
}
