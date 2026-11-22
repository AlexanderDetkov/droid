package com.adetkov.droid

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the owner reads aloud to enroll: requests like the ones they'll make, with a spread of sounds. */
private val SENTENCES = listOf(
    "Hey Droid, what's on my calendar for tomorrow morning?",
    "Send a message to Sam saying I'll be there in ten minutes.",
    "Set a timer for twenty-five minutes and turn on do not disturb.",
    "Play something quiet and turn the volume down a little.",
    "Remind me to call the pharmacy when I get home tonight.",
    "Find the email about my flight and check the hotel booking.",
)
/** An enrollment recording with less speech than this wasn't the whole sentence. */
private const val MIN_ENROLL_SPEECH = MIC_RATE * 7 / 5

private enum class Mode { IDLE, ENROLL, TRY }

/** Voice match settings: download the model, record the voiceprint, try it, turn it on or off, delete it. */
@Composable
fun VoiceMatchScreen(onBack: () -> Unit, onSetup: () -> Unit) {
    val app = LocalContext.current.applicationContext as DroidApp
    val match = app.voiceMatch
    val setup by app.setup.state.collectAsState()
    val enabled by match.enabled.collectAsState()
    val print by match.print.collectAsState()
    val misses by match.misses.collectAsState()
    val (voice, _) = rememberVoice()
    val withMic = rememberWithMic()
    val scope = rememberCoroutineScope()

    var mode by remember { mutableStateOf(Mode.IDLE) }
    val clips = remember { mutableListOf<FloatArray>() }
    var step by remember { mutableIntStateOf(0) }          // the sentence being read
    var heard by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }     // making the voiceprint or a score
    var score by remember { mutableStateOf<Float?>(null) }
    var tried by remember { mutableStateOf(false) }       // a try has a result (score null: too short)
    var confirmForget by remember { mutableStateOf(false) }
    var making by remember { mutableStateOf<Job?>(null) }  // the voiceprint being made

    LaunchedEffect(setup.voiceMatch) { match.warmUp() }

    fun stop() { making?.cancel(); making = null; working = false; voice.cancel(); mode = Mode.IDLE; heard = ""; clips.clear() }
    BackHandler(mode != Mode.IDLE) { stop() }

    fun record(onAudio: (FloatArray) -> Unit): Unit = withMic {
        heard = ""
        note = null
        voice.start(null,
            onPartial = { heard = it },
            onFinal = { text, audio -> heard = text; onAudio(audio) },
            onNothing = { note = "Didn't catch that. Tap the mic to try again." },
            onError = { note = it },
        )
    }

    fun finishEnroll() {
        working = true
        val recorded = clips.toList()
        making = scope.launch {
            // Cancel meanwhile (stop) cancels this, and withContext then returns no further: nothing is kept.
            val made = withContext(Dispatchers.Default) { runCatching { match.voiceprint(recorded) }.getOrNull() }
            made?.let { match.keep(it.first) }
            making = null
            stop()
            val on = if (match.enabled.value) "Voice match is on." else "Your voiceprint is saved; voice match is off."
            note = when {
                made == null -> "Couldn't make the voiceprint. Try again."
                made.second < VoiceMatch.THRESHOLD -> "$on One recording sounded different from the rest; if it often misses you, set it up again somewhere quiet."
                else -> on
            }
        }
    }
    fun readSentence(): Unit = record { audio ->
        if (audio.size - Voice.PADDING < MIN_ENROLL_SPEECH) { note = "That was short. Tap the mic and read the whole sentence."; return@record }
        clips += audio
        if (clips.size < SENTENCES.size) { step = clips.size; heard = ""; readSentence() } else finishEnroll()
    }
    fun startEnroll() { clips.clear(); step = 0; mode = Mode.ENROLL; readSentence() }

    fun tryIt() {
        mode = Mode.TRY
        tried = false
        record { audio ->
            working = true
            scope.launch {
                score = withContext(Dispatchers.Default) { runCatching { match.score(audio) }.getOrNull() }
                working = false
                tried = true
            }
        }
    }

    if (confirmForget) ConfirmDialog(
        "Delete your voiceprint?", "Voice match turns off, and requests from the lock screen need your fingerprint again.",
        "Delete", { match.forget(); note = null }, { confirmForget = false }, danger = true,
    )

    SubScreen("Voice match", onBack = { if (mode != Mode.IDLE) stop() else onBack() }) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Talk to Droid from the lock screen without touching the phone. When a request sounds like you, it goes " +
                "straight through; otherwise Droid asks for your fingerprint as usual.",
                Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 8.dp), style = MaterialTheme.typography.bodyMedium, color = P.muted)

            when {
                !setup.voice -> Group(null) {
                    NavItem(R.drawable.ic_voice_settings, "Speech models", "Voice match listens with them; install them first", onSetup, "vm_speech")
                }
                !setup.voiceMatch -> Group(null) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_voice), null, Modifier.size(22.dp), tint = P.text)
                        Column(Modifier.weight(1f).padding(start = 16.dp)) {
                            Text("Voice match model", style = MaterialTheme.typography.bodyLarge)
                            Text("${VoiceMatch.MODEL_MB} MB, runs on the phone", style = MaterialTheme.typography.bodySmall, color = P.muted)
                        }
                        TextButton({ app.setup.downloadVoiceMatch() }, Modifier.testTag("vm_download"), enabled = setup.busy == null) {
                            Text("Download", color = P.text)
                        }
                    }
                }
                mode == Mode.ENROLL -> Card {
                    Text("Read this aloud · ${step + 1} of ${SENTENCES.size}", style = MaterialTheme.typography.labelMedium, color = P.muted)
                    Text(SENTENCES[step], Modifier.padding(top = 12.dp).testTag("vm_sentence"), fontSize = 22.sp, lineHeight = 30.sp,
                        style = MaterialTheme.typography.bodyLarge)
                    Listening(voice, heard, working, if (working) "Making your voiceprint…" else null) { if (voice.listening) voice.finish() else readSentence() }
                    TextButton(::stop, Modifier.align(Alignment.End)) { Text("Cancel", color = P.muted) }
                }
                mode == Mode.TRY -> Card {
                    Text("Say anything", style = MaterialTheme.typography.labelMedium, color = P.muted)
                    val result = when {
                        !tried -> null
                        score == null -> "Too short to tell. Say a whole sentence."
                        score!! >= VoiceMatch.THRESHOLD -> "Matches your voice · %.2f".format(score)
                        else -> "Doesn't match · %.2f, needs %.2f".format(score, VoiceMatch.THRESHOLD)
                    }
                    Listening(voice, heard, working, result) { if (voice.listening) voice.finish() else tryIt() }
                    TextButton(::stop, Modifier.align(Alignment.End).testTag("vm_done")) { Text("Done", color = P.muted) }
                }
                print == null -> Group(null) {
                    NavItem(R.drawable.ic_mic, "Set up your voice", "Read ${SENTENCES.size} short sentences aloud, somewhere quiet", ::startEnroll, "vm_enroll")
                }
                else -> Group(null) {
                    ToggleItem(R.drawable.ic_shield, "On the lock screen", "Requests in your voice skip the fingerprint", enabled, match::setEnabled, "vm_enabled")
                    Divider()
                    NavItem(R.drawable.ic_mic, "Try it", "See how well it recognizes you", ::tryIt, "vm_try")
                    Divider()
                    NavItem(R.drawable.ic_retry, "Set up again", "Record your voice again", ::startEnroll, "vm_reenroll")
                    Divider()
                    Row(Modifier.fillMaxWidth().clickable { confirmForget = true }.padding(16.dp).testTag("vm_forget"), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_delete), null, Modifier.size(22.dp), tint = P.danger)
                        Text("Delete voiceprint", Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyLarge, color = P.danger)
                    }
                }
            }

            if (mode == Mode.IDLE && enabled && print != null && misses >= VoiceMatch.MAX_MISSES) Text(
                "Paused after ${VoiceMatch.MAX_MISSES} misses in a row. It comes back the next time you unlock the phone.",
                Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodyMedium, color = P.muted)
            if (mode == Mode.IDLE) note?.let { Text(it, Modifier.padding(horizontal = 4.dp).testTag("vm_note"), style = MaterialTheme.typography.bodyMedium) }
            setup.busy?.let {
                Column(Modifier.padding(horizontal = 4.dp)) {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = P.muted)
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(2.dp)), color = P.ink, trackColor = P.raised)
                }
            }
            setup.error?.let { Text(it, Modifier.padding(horizontal = 4.dp).clickable(onClick = app.setup::dismissError), color = P.danger,
                style = MaterialTheme.typography.bodyMedium) }

            Column(Modifier.padding(top = 16.dp, bottom = 24.dp)) {
                SectionLabel("What it checks", Modifier.padding(start = 4.dp, bottom = 6.dp))
                Text("Voice match checks that a voice sounds like yours, not that you're the one speaking. A recording or " +
                    "an AI copy of your voice can get through, and whoever it lets through can have Droid do anything it " +
                    "can do on this phone. After ${VoiceMatch.MAX_MISSES} misses in a row it pauses until you next unlock. " +
                    "Your voiceprint stays on the phone.",
                    Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, color = P.muted)
            }
        }
    }
}

@Composable
private fun Card(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(P.surface).padding(20.dp), content = content)

/** The live transcript or a result, and the mic: lit while listening, tap to start (or to stop early). */
@Composable
private fun Listening(voice: Voice, heard: String, working: Boolean, result: String?, onMic: () -> Unit) {
    Text(
        when {
            result != null && !voice.listening -> result
            heard.isNotEmpty() -> heard
            voice.listening -> "Listening…"
            else -> "Tap the mic"
        },
        Modifier.fillMaxWidth().padding(top = 16.dp).heightIn(min = 22.dp).testTag("vm_heard"),
        color = if (result != null && !voice.listening) P.text else P.muted, style = MaterialTheme.typography.bodyMedium,
    )
    Box(Modifier.fillMaxWidth().padding(top = 16.dp), contentAlignment = Alignment.Center) {
        val lit = voice.listening
        Box(Modifier.size(64.dp).graphicsLayer { val s = if (lit) 1f + voice.level * .28f else 1f; scaleX = s; scaleY = s }
            .clip(CircleShape).background(if (lit) P.call else Lavender).clickable(enabled = !working, onClick = onMic).testTag("vm_mic"),
            contentAlignment = Alignment.Center) {
            if (working) Spinner(size = 22.dp, color = P.text, stroke = 2.dp)
            else Icon(painterResource(R.drawable.ic_mic), if (lit) "Done" else "Speak", Modifier.size(26.dp), tint = if (lit) OnCall else CallText)
        }
    }
}
