package com.adetkov.droid

import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.os.Build
import android.os.PowerManager
import android.service.voice.VoiceInteractionService
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.json.JSONObject
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val ALPINE = "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases"
private const val SHERPA = "https://github.com/k2-fsa/sherpa-onnx/releases/download"
/** Settings.Secure.ASSIST_SCREENSHOT_ENABLED and ASSIST_STRUCTURE_ENABLED, which are hidden. */
private const val SCREENSHOTS = "assist_screenshot_enabled"
private const val SCREEN_TEXT = "assist_structure_enabled"
/** Remove's exit code when something is mounted under the Linux (rm and su exit with others). */
private const val MOUNTED = 3
/** The agents besides Claude Code, which Setup adds on request. */
private val OTHERS = listOf(Engine.CODEX, Engine.OPENCODE)

/** An engine besides Claude Code: installed in the chroot, and usable (signed in, or needing no sign-in). [detail] is for the UI. */
data class EngineStatus(val installed: Boolean = false, val signedIn: Boolean = false, val detail: String = "Not installed")

/** How Droid reaches Android: as root, or as the shell user through Wireless debugging (phones without root). */
enum class Access { ROOT, ADB }

data class SetupState(
    val checked: Boolean = false,
    /** `su` works for Droid. */
    val root: Boolean = false,
    /** There is an `su` Droid can run, though it may refuse until the owner allows Droid in the root manager. */
    val su: Boolean = false,
    /** Root if Droid had it at its first check, or what the owner switched to since (see [Setup.setAccess]). */
    val access: Access = Access.ROOT,
    /** Without root: paired with this phone's Wireless debugging. */
    val paired: Boolean = false,
    /** Without root: Droid's helper is running (see AdbHelper). */
    val helper: Boolean = false,
    /** Without root: why the helper isn't running, for the user, or null. */
    val helperError: String? = null,
    /**
     * Without root: Droid may switch Wireless debugging on by itself (WRITE_SECURE_SETTINGS, which starting the helper
     * grants), as it must to start the helper after a restart. Some phones refuse that grant until a setting allows it.
     */
    val secureSettings: Boolean = true,
    /** Without root: what pairing is doing now (see PairingService), or null when it isn't under way. */
    val pairing: String? = null,
    /** Without root, while pairing: how far the owner got in Settings (see [PairingService.STEPS]). */
    val pairStep: Int = 0,
    /** The Linux environment with Claude Code. */
    val installed: Boolean = false,
    /** Some of the Linux environment is there, maybe half installed: something for Remove to delete. */
    val linux: Boolean = false,
    val signedIn: Boolean = false,
    val voice: Boolean = false,
    /** Voice has the newest speech-to-text model (else [Setup.updateSpeechToText] offers it). */
    val voiceCurrent: Boolean = true,
    /** The voice match model. */
    val voiceMatch: Boolean = false,
    /** Droid is the digital assistant, through its [AssistService]. */
    val assistant: Boolean = false,
    /** Android hands the assistant a screenshot of the app it opens over ("Use screenshot"). */
    val screenshots: Boolean = false,
    /** What is running now (e.g. "Installing packages"), or null. */
    val busy: String? = null,
    /** The sign-in page while a sign-in waits for its code. */
    val signInUrl: String? = null,
    /** The last failure, with the end of its output. */
    val error: String? = null,
    /** The signed-in Claude account and its plan (e.g. "max"), when known. */
    val email: String? = null,
    val plan: String? = null,
    /** The engine new chats use (from Settings), whose sign-in decides [ready]. */
    val engine: Engine = Engine.CLAUDE,
    /** Codex and OpenCode, once checked. */
    val others: Map<Engine, EngineStatus> = emptyMap(),
    /** A check in this process reached the Linux: [installed], [signedIn] and [others] are what it found there. */
    val linuxChecked: Boolean = false,
    /** The one-time code to enter at [signInUrl] while a device-code sign-in (Codex) waits; null for Claude's paste-the-code. */
    val deviceCode: String? = null,
    /** Android refuses the input Droid injects (some makers' phones until a Developer options switch is on), so the agent can't tap or type. */
    val inputBlocked: Boolean = false,
    /** Why a task's hidden display won't start on this phone ([Setup.startDisplay]), or null while it does (or isn't known). */
    val displayError: String? = null,
) {
    /** Whether agent [e] can take a chat: Claude signed in, or the other agent installed and signed in. */
    fun usable(e: Engine) = if (e == Engine.CLAUDE) signedIn else others[e]?.let { it.installed && it.signedIn } == true
    /** The agent for new chats: the one picked in Settings, or else the first that can take one (say OpenCode's free
     *  models, for someone without a Claude account). */
    val readyEngine: Engine? get() = engine.takeIf(::usable) ?: Engine.entries.firstOrNull(::usable)
    /**
     * The agent for a new chat, [picked] being the one picked in Settings: that one, unless a check found that it can't
     * take a chat and another can. Until a check has reached the Linux, what is known may be out of date: the pick stands.
     */
    fun engineFor(picked: Engine) = if (!linuxChecked || usable(picked)) picked else Engine.entries.firstOrNull(::usable) ?: picked
    /** Droid can run commands in Android: root, or its helper. */
    val connected get() = if (access == Access.ROOT) root else helper
    val ready get() = isEmulator || (connected && installed && readyEngine != null)
}

/**
 * First-run setup, all from the app: access to Android (root, or Wireless debugging), the Linux environment (Alpine +
 * Claude Code, by install.sh), the Claude sign-in, the voice models and the assistant role. Each step can be re-run;
 * [refresh] reads what is in place.
 */
/** How long what a full check found of the agents' sign-in stands at a process start (see Setup.refreshOnStart). */
private const val FULL_CHECK_EVERY = 60 * 60_000L
/** Setup's pref: [Setup.displayKey] when a hidden display last started. */
private const val DISPLAY_WORKS = "displayWorks"
private const val CHECK_MAC = "02:00:01:00:00:01"

class Setup(private val app: DroidApp) {
    private val prefs = app.getSharedPreferences("setup", 0)
    /** Decided at the first check and changed only by the owner, so a moment without `su` never moves a rooted phone. */
    private val savedAccess get() = prefs.getString("access", null)?.let { a -> Access.entries.firstOrNull { it.name == a } }
    /** Until a check reaches Android, what the last one that did found. */
    val state = MutableStateFlow(SetupState(
        access = savedAccess ?: Access.ROOT, installed = prefs.getBoolean("installed", false), signedIn = prefs.getBoolean("signedIn", false),
        email = prefs.getString("email", null), plan = prefs.getString("plan", null), engine = app.prefs.engine.value,
        others = OTHERS.filter { prefs.contains("${it.id}.detail") }.associateWith {
            EngineStatus(prefs.getBoolean("${it.id}.installed", false), prefs.getBoolean("${it.id}.signedIn", false), prefs.getString("${it.id}.detail", null).orEmpty())
        },
    ))
    private val host get() = app.host
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var signIn: Process? = null
    @Volatile private var signInCancelled = false
    /** A pasted code Claude Code didn't take within its time: the sign-in ended for that, and says so. */
    @Volatile private var codeRefused = false

    /**
     * Whether setup still needs doing. While Droid can't reach Android (before the first check, or without root or its
     * helper just now, e.g. after a restart away from Wi-Fi), the last answer that could stands: the chats stay open,
     * and a task that can't start says why.
     */
    /** New chats use [e] (Setup's Start, when the agent picked can't take a chat but [e] can). */
    fun useEngine(e: Engine) { if (app.prefs.engine.value != e) { app.prefs.setEngine(e); state.update { it.copy(engine = e) } } }

    fun needed(s: SetupState = state.value) = !s.ready && (s.checked && s.connected || !prefs.getBoolean("ready", false))

    /** What is in place, the hidden display too: the owner is looking at Setup (a turn that failed may have sent them). */
    fun refresh() = step(null) { check(display = true) }

    /**
     * A new process's check: whether the agents are signed in is asked again only once the last time is an hour old
     * (each asks by starting its CLI, seconds of work, while the app draws its first screen); till then it stands.
     */
    fun refreshOnStart() = step(null) { check(full = System.currentTimeMillis() - prefs.getLong("fullCheckAt", 0) !in 0..FULL_CHECK_EVERY) }

    /**
     * Phones without root: waits for Settings' pairing dialog (Wireless debugging → Pair device with pairing code) and
     * asks for its code in a notification, then starts Droid's helper. See [PairingService].
     */
    fun pair() {
        DevSettings.wirelessSeen = null  // (an earlier look's may be over; the pairing's own says, see PairingService)
        val step = PairingService.step(app)
        state.update { it.copy(error = null, pairing = PairingService.STEPS[step].first, pairStep = step) }
        PairingService.start(app)
    }

    fun cancelPairing() = PairingService.cancel(app)

    /** Called by [PairingService] with what it's doing and how far the owner got, or null when it's over (then [error], if it failed). */
    fun pairingStatus(status: String?, step: Int? = null, error: String? = null) {
        state.update { it.copy(pairing = status, pairStep = step ?: it.pairStep, error = error ?: it.error) }
        if (status == null) refresh()
    }

    /**
     * Brings Droid back in front of Settings or the browser once what the owner went there for has worked (paired,
     * signed in). Through root or the helper: they may start an activity from the background, the app may not.
     */
    fun comeBack() {
        if (app.resumed) return
        run("am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n ${app.packageName}/.MainActivity")
    }

    /** First run: the install starts by itself once Droid is connected, but not after Remove (the step before uninstalling). */
    val autoInstall get() = !prefs.getBoolean("removed", false)

    /** Starts Droid's helper now (phones without root), e.g. after the phone restarted while away from Wi-Fi. */
    fun startHelper() = step("Starting Droid's helper") { check(ask = true) }

    /**
     * Switches between root and Wireless debugging (the shell user, proot). Each has its own Linux, sign-in and chat
     * history; a chat from the other one goes on in a new session (see phone-agent).
     */
    fun setAccess(a: Access) = step("Switching") {
        if (app.runner.running.value.isNotEmpty()) throw IOException("Stop the running chats first")
        app.runner.closeScreens()  // held screens and a warm run belong to the old way in
        app.runner.dropWarm()
        synchronized(checking) {  // a check under way for the old one finishes first
            // Nothing known yet about the other Linux.
            prefs.edit {
                putString("access", a.name)
                putBoolean("ready", false); putBoolean("installed", false); putBoolean("signedIn", false); remove("email"); remove("plan")
                putOthers(emptyMap())
            }
            state.update { SetupState(access = a, busy = it.busy, engine = app.prefs.engine.value) }
        }
        check()
    }

    /** Installs or updates the Linux environment; the first time this downloads Alpine (~4 MB) and Claude Code (~250 MB). */
    fun install() = step("Checking") {
        val h = host
        var tarball: File? = null
        if (run("test -x ${h.rootfs}/bin/busybox").code != 0) {
            busy("Downloading Alpine Linux")
            tarball = File(scratch(), "alpine.tar.gz").also { downloadAlpine(it) }
        }
        val src = unpackAssets()
        File(src, "resolv.conf").apply { writeText(resolvConf(networkDns(app), readText())) }
        try {
            val r = run("sh ${src.path}/install.sh ${src.path} ${app.packageName} '${app.applicationInfo.sourceDir}' ${tarball?.path.orEmpty()}", onLine = { line ->
                if (line.startsWith("step: ")) busy(line.removePrefix("step: "))
            })
            if (r.code != 0) throw installFailed("Setup failed (exit ${r.code}).", r.code, r.out)
        } finally {
            tarball?.delete()
        }
        prefs.edit { remove("removed") }
        check()
    }

    /**
     * Starts `claude auth login`. It opens its sign-in page in the phone's browser through droid-browser (its
     * `$BROWSER`), and signing in there comes back to it on localhost by itself. It also prints a page for signing in
     * elsewhere, which goes to [SetupState.signInUrl]; that one shows a code, which [submitCode] passes on.
     */
    fun signIn(console: Boolean) = step("Starting sign-in") {
        putBin(unpackAssets(), "droid-browser")  // an install from before it had one gets it now
        val p = host.exec("${host.base}/enter.sh BROWSER=/usr/local/bin/droid-browser claude auth login${if (console) " --console" else ""}")
        signIn = p
        signInCancelled = false
        val out = StringBuilder()
        val code = try {
            // The page's URL comes first; the process then waits for the code on stdin.
            p.inputStream.bufferedReader().forEachLine { line ->
                out.appendLine(line)
                Regex("https://\\S+").find(line)?.let { m -> state.update { it.copy(signInUrl = m.value, busy = null) } }
            }
            p.waitFor()
        } catch (e: IOException) {
            if (signInCancelled) 0 else throw e  // Cancel closes the stream under the reader
        } finally {
            p.destroy()
            signIn = null
            state.update { it.copy(signInUrl = null) }
        }
        if (codeRefused) { codeRefused = false; throw IOException("That code didn't work. Tap Open Claude for a new one.") }
        if (code != 0 && !signInCancelled) throw IOException(out.lines().lastOrNull { it.isNotBlank() }?.substringAfter("> ") ?: "Sign-in failed")
        check()
        if (code == 0 && state.value.signedIn) comeBack()  // from the browser, where signing in finished
    }

    /**
     * Codex's ChatGPT sign-in by device code: `codex login --device-auth` prints a page and a one-time code, then waits
     * until the code is entered there (on this phone or any other device). Nothing to paste back.
     */
    fun signInCodex() = step("Starting sign-in") {
        val p = host.exec("${host.base}/enter.sh codex login --device-auth")
        p.outputStream.close()
        signIn = p
        signInCancelled = false
        val out = StringBuilder()
        val code = try {
            p.inputStream.bufferedReader().forEachLine { raw ->
                val line = stripAnsi(raw)
                out.appendLine(line)
                val (url, otp) = parseDeviceAuth(out.toString())
                if (url != null && otp != null) state.update { it.copy(signInUrl = url, deviceCode = otp, busy = null) }
            }
            p.waitFor()
        } catch (e: IOException) {
            if (signInCancelled) 0 else throw e
        } finally {
            p.destroy()
            signIn = null
            state.update { it.copy(signInUrl = null, deviceCode = null) }
        }
        if (code != 0 && !signInCancelled) throw IOException(out.lines().lastOrNull { it.isNotBlank() } ?: "Sign-in failed")
        check()
        if (code == 0 && state.value.others[Engine.CODEX]?.signedIn == true) comeBack()  // from the browser, where the code went in
    }

    /** Signs Codex in with an OpenAI API key (billed per use), which `codex login --with-api-key` reads from stdin. */
    fun codexApiKey(key: String) = step("Saving the key") {
        val r = run("${host.base}/enter.sh codex login --with-api-key", stdin = key.trim() + "\n")
        if (r.code != 0) throw IOException(r.out.lines().lastOrNull { it.isNotBlank() } ?: "Could not save the key")
        check()
    }

    /** Saves an API key for an OpenCode provider (e.g. "anthropic", "openai", "google", "openrouter") in its auth.json. */
    fun openCodeKey(provider: String, key: String) = step("Saving the key") {
        val id = provider.trim().lowercase()
        if (!Regex("[a-z0-9][a-z0-9._-]{0,40}").matches(id)) throw IOException("A provider id is like anthropic, openai, google or openrouter")
        val r = run("${host.base}/enter.sh python3 -I -c '$SAVE_KEY' $id", stdin = key.trim() + "\n")
        if (r.code != 0) throw IOException(r.out.lines().lastOrNull { it.isNotBlank() } ?: "Could not save the key")
        check()
    }

    /**
     * Installs or updates one extra engine (downloads its musl build from GitHub, checked against GitHub's SHA-256).
     * Only that, not a whole Update, whose package upgrades and Claude Code update have nothing to do with it. Its
     * installer goes in first, as this app has it, by rename as install.sh puts it.
     */
    fun installEngine(e: Engine) = step("Installing ${e.label}") {
        putBin(unpackAssets(), "droid-install-engine")
        val r = run("${host.base}/enter.sh droid-install-engine ${e.id}")
        if (r.code != 0) throw installFailed("Could not install ${e.label} (exit ${r.code}).", r.code, r.out)
        check()
    }

    /** Puts this app's bin/[name] from [src] into the Linux, by rename as install.sh does, for a step that needs it before the next Update. */
    private fun putBin(src: File, name: String) {
        val bin = "${host.rootfs}/usr/local/bin/$name"
        val r = run("cp ${src.path}/bin/$name $bin.new && chmod 755 $bin.new && mv $bin.new $bin")
        if (r.code != 0) throw IOException("Could not update $name (exit ${r.code}).\n\n${r.out.takeLast(1500)}")
    }

    fun submitCode(code: String) {
        val p = signIn ?: return
        busy("Signing in")
        scope.launch {
            try { p.outputStream.use { it.write((code.trim() + "\n").toByteArray()) } } catch (_: IOException) {}
            // A code Claude Code takes signs in within seconds; one it doesn't leaves it waiting on and on, so end that
            delay(30_000)
            if (signIn === p) { codeRefused = true; cancelSignIn() }
        }
    }

    fun cancelSignIn() {
        signInCancelled = true
        val p = signIn
        // Off the main thread: without root, ending it is a message to Droid's helper.
        scope.launch {
            p?.destroy()
            // Destroying the local `su` client (or the helper's shell) leaves the login running: Codex keeps polling for
            // its code, and Claude Code keeps waiting for the browser on localhost.
            run("pkill -f '[c]odex login --device-auth'; pkill -f '[c]laude auth login'")
        }
    }

    /**
     * First run's Start: makes Droid the assistant and downloads voice, each if the owner left it on, in the background
     * while Droid opens. A failure shows in Settings → Setup, where either can be done again.
     */
    fun finish(assistant: Boolean, voice: Boolean) {
        if (!assistant && !voice) return
        step(if (assistant) "Setting the assistant" else "Downloading voice") {
            val failed = if (assistant) runCatching { setAssistant() }.exceptionOrNull() else null
            if (voice) { busy("Downloading voice"); fetchVoice() }
            failed?.let { throw it }
            check()
        }
    }

    /** On-device speech: Silero VAD, Parakeet Unified EN 0.6B (speech-to-text), Smart Turn, Pocket TTS (voice). About 860 MB. */
    fun downloadVoice() = step("Downloading voice") { fetchVoice(); check() }

    /** Brings speech-to-text up to [Speech.current] (about 500 MB): the newer model and Smart Turn, keeping the rest of the voice. */
    fun updateSpeechToText() = step("Downloading speech-to-text") {
        val models = app.speech.dir
        if (!models.resolve(Speech.TURN).isFile) fetchTurn(models)
        val dir = models.resolve("parakeet")
        if (!app.speech.current) {
            val tmp = File(dir.path + ".new").apply { deleteRecursively(); mkdirs() }
            try {
                fetch("$SHERPA/asr-models/${Speech.ASR}.tar.bz2", "Speech-to-text") { untarBz2(it, tmp) }
                File(tmp, "test_wavs").deleteRecursively()
                File(tmp, "model.txt").writeText(Speech.ASR)
                app.speech.release(wait = true)
                dir.deleteRecursively()
                if (!tmp.renameTo(dir)) throw IOException("Could not move the speech-to-text model into place")
            } finally {
                tmp.deleteRecursively()
            }
        }
        check()
    }

    /** Smart Turn into [dir], checked against its SHA-256. */
    private fun fetchTurn(dir: File, label: String = "Speech-to-text") {
        val tmp = File(dir, Speech.TURN + ".new")
        try {
            if (download(Speech.TURN_URL, tmp, label) != Speech.TURN_SHA256) throw IOException("Smart Turn download is corrupt, try again")
            if (!tmp.renameTo(File(dir, Speech.TURN))) throw IOException("Could not move Smart Turn into place")
        } finally {
            tmp.delete()
        }
    }

    private fun fetchVoice() {
        val dir = app.speech.dir
        val tmp = File(dir.path + ".new").apply { deleteRecursively(); mkdirs() }
        try {
            download("$SHERPA/asr-models/silero_vad.onnx", File(tmp, "silero_vad.onnx"), "Voice 1/4")
            fetch("$SHERPA/asr-models/${Speech.ASR}.tar.bz2", "Voice 2/4") { untarBz2(it, File(tmp, "parakeet")) }
            File(tmp, Speech.MARKER).writeText(Speech.ASR)
            fetchTurn(tmp, "Voice 3/4")
            fetch("$SHERPA/tts-models/sherpa-onnx-pocket-tts-int8-2026-01-26.tar.bz2", "Voice 4/4") { untarBz2(it, File(tmp, "pocket")) }
            File(tmp, "pocket/test_wavs/bria.wav").renameTo(File(tmp, "pocket/voice.wav"))  // the voice it imitates
            listOf("parakeet/test_wavs", "pocket/test_wavs").forEach { File(tmp, it).deleteRecursively() }
            app.speech.release()
            dir.deleteRecursively()
            if (!tmp.renameTo(dir)) throw IOException("Could not move the voice models into place")
        } finally {
            tmp.deleteRecursively()  // what a failed download left, up to 860 MB (once moved into place, nothing)
        }
    }

    /** The voice match model (NVIDIA TitaNet small), checked against its SHA-256. */
    fun downloadVoiceMatch() = step("Downloading voice match") {
        val model = app.voiceMatch.model
        val tmp = File(model.path + ".new").apply { parentFile?.mkdirs() }
        try {
            // "recongition" is the release tag's own spelling.
            if (download("$SHERPA/speaker-recongition-models/${VoiceMatch.MODEL}", tmp, "Voice match") != VoiceMatch.MODEL_SHA256) {
                throw IOException("Voice match download is corrupt (checksum mismatch)")
            }
            app.voiceMatch.release()
            if (!tmp.renameTo(model)) throw IOException("Could not move the voice match model into place")
        } finally {
            tmp.delete()  // what a failed download left
        }
        check()
    }

    /**
     * Makes Droid the digital assistant (long-press power), as root or the shell user: Android has no prompt for this role. Also
     * turns on Android's "Use screenshot" for it, so the panel gets the app it was opened over.
     */
    fun makeAssistant() = step("Setting the assistant") { setAssistant(); check() }

    private suspend fun setAssistant() {
        val role = RoleManager.ROLE_ASSISTANT
        val pkg = app.packageName
        // Holding the role from before Droid had its assistant service: adding it again changes nothing, so drop it first.
        val reset = if (roleHeld() && !serviceActive()) "cmd role remove-role-holder $role $pkg; " else ""
        val r = run("${reset}cmd role add-role-holder $role $pkg && ${screenshotsCmd(true)}")
        if (r.code != 0) throw IOException(r.out.ifBlank { "Could not set the assistant" })
        for (i in 1..20) if (serviceActive()) break else delay(100)  // Android switches to it in the background
    }

    /** Whether the assistant gets a screenshot of the app it opens over. */
    fun setScreenshots(on: Boolean) {
        state.update { it.copy(screenshots = on) }
        scope.launch { if (run(screenshotsCmd(on)).code != 0) check() }
    }

    /**
     * Android's "Use screenshot" setting, and "Use text from screen", without which Android takes no screenshot. Droid
     * doesn't declare usesAssistStructureScreenContent (see assist_service.xml), so it gets no text from the screen.
     */
    private fun screenshotsCmd(on: Boolean) = (if (on) 1 else 0).let {
        "settings put secure $SCREENSHOTS $it && settings put secure $SCREEN_TEXT $it"
    }

    private fun roleHeld() = app.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT)
    private fun serviceActive() = VoiceInteractionService.isActiveService(app, ComponentName(app, AssistService::class.java))

    /**
     * Deletes the Linux environment (chats' transcripts, sign-in and instructions included). Not while tasks run. With
     * root, the one Droid uses through Wireless debugging goes too: Remove is the step before uninstalling Droid.
     */
    fun remove() = step("Removing") {
        if (app.runner.running.value.isNotEmpty()) throw IOException("Stop the running chats first")
        // Screens held open after an answer: their agents exit once told to.
        app.runner.closeScreens()
        for (i in 0 until 100) { if (app.runner.screens.value.isEmpty()) break; delay(100) }
        if (app.runner.screens.value.isNotEmpty()) throw IOException("Droid's screens are still closing; try again in a moment")
        app.runner.dropWarm()?.join()  // it removes its display and association on the way out
        val h = host
        val r = if (h == RootHost) {
            // Mounts exist only inside each run's private namespace, so none can be under the base here; check anyway.
            run("grep -q ' ${h.base}' /proc/self/mountinfo && exit $MOUNTED; rm -rf ${h.base} $SHELL_BASE")
        } else {
            // All but the helper, which stays for the next install, and its folder. What the agent made read-only (Go's
            // module cache, say) is made writable first, or rm couldn't empty it.
            run("chmod -R u+w ${h.base} 2>/dev/null; rm -rf ${h.base}/*")
        }
        when (r.code) {
            0 -> prefs.edit { putBoolean("removed", true) }  // no install by itself on the setup screen that follows
            MOUNTED -> throw IOException("Something is mounted under ${h.base}; restart the phone and try again")
            else -> throw IOException("Could not remove Droid's Linux (exit ${r.code}).\n\n${r.out.takeLast(1500)}")
        }
        check()
    }

    fun dismissError() = state.update { it.copy(error = null) }

    /** A bug report's facts ([diagnosticsReport]) for the owner to copy. Blocking: call it off the main thread. */
    fun diagnostics(): String {
        val s = state.value
        val props = parseProps(runCatching { ProcessBuilder("getprop").start().inputStream.bufferedReader().readText() }.getOrDefault(""))
        val versions = if (!s.connected || !s.installed) emptyList() else buildList {
            // (one display check at a time: they share their address, and each clears what a cut-short one left)
            synchronized(checking) { startDisplay() }?.let { d ->
                add("Hidden display" to (d.error?.let { "won't start: $it" } ?: "starts") + d.notes.joinToString("") { "; $it" })
            }
            run("cat ${host.rootfs}/etc/alpine-release").takeIf { it.code == 0 }?.let { add("Alpine" to it.out.trim()) }
            for ((cmd, label) in listOf("claude" to Engine.CLAUDE.label) + s.others.keys.map { it.id to it.label }) {
                val r = run("${host.base}/enter.sh $cmd --version")
                if (r.code == 0) add("$label version" to stripAnsi(r.out).lines().firstOrNull { it.isNotBlank() }.orEmpty().trim())
            }
        }
        val droid = listOf(
            "Battery use" to if (app.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(app.packageName)) "unrestricted" else "optimized",
            "Notifications" to if (app.getSystemService(NotificationManager::class.java).areNotificationsEnabled()) "on" else "off",
        )
        return diagnosticsReport(Updates.version(app), phoneFacts(props) + droid, setupFacts(s, versions))
    }

    /** What starting a hidden display found: [error], why it didn't start (null if it did); [notes], what it did without. */
    private class DisplayCheck(val error: String?, val notes: List<String>)

    /**
     * Starts a hidden display as a task does, asks it for a screenshot, and ends it (vd-check.sh, run from the app's
     * assets: Linux installed before it had none); null if that couldn't be asked (Android out of reach, or a Linux
     * installed before vd-start.sh). Only under [checking]: checks share their address.
     */
    private fun startDisplay(): DisplayCheck? {
        fun asset(name: String) = app.assets.open("setup/$name").bufferedReader().use { it.readText() }
        // The scripts a task runs, as this app has them (a task writes them too, as it starts): what's checked is what runs
        for (f in listOf("vd-start.sh", "vd-stop.sh"))
            exec(host, "[ -d ${host.base} ] && cat > ${host.base}/$f.\$\$ && chmod 755 ${host.base}/$f.\$\$ && mv ${host.base}/$f.\$\$ ${host.base}/$f", stdin = asset(f))
        val script = asset("vd-check.sh")
        // (a made-up address phone-agent never uses: its are 02:00:00:…)
        val r = exec(host, "sh -s ${host.base} $CHECK_MAC ${shellQuote(app.applicationInfo.sourceDir)}", stdin = script, timeoutS = 45)
        val lines = r.out.lines()
        fun value(key: String) = lines.firstOrNull { it.startsWith("$key=") }?.substringAfter('=')
        val notes = lines.filter { it.startsWith("note=") }.map { it.removePrefix("note=") }
        return when {
            value("error") != null -> DisplayCheck(value("error")!!.ifBlank { "no reason given" }, notes)
            value("answered") != null -> DisplayCheck(null, notes)
            else -> null
        }
    }

    /** This Droid on this Android: a hidden display that started once is checked again only when either changes. */
    private fun displayKey() = "${Updates.version(app)} ${Build.FINGERPRINT}"

    private fun checkEngines(installed: Boolean): Map<Engine, EngineStatus> {
        if (!installed) return emptyMap()
        val bin = "${host.rootfs}/root/.local/bin"
        val last = state.value.others
        val out = HashMap<Engine, EngineStatus>()
        for (e in OTHERS) when (probe("test -x $bin/${e.id}").code) {
            0 -> out[e] = if (e == Engine.CODEX) {
                val r = probe("${host.base}/enter.sh codex login status")
                val ok = r.code == 0
                // Only Codex saying so is a sign-out; a check that failed otherwise keeps what the last one found.
                last[e]?.takeIf { !ok && "not logged in" !in stripAnsi(r.out).lowercase() }
                    ?: EngineStatus(true, ok, if (ok) stripAnsi(r.out).lines().firstOrNull { it.isNotBlank() }?.trim() ?: "Signed in" else "Installed, sign-in needed")
            } else {
                // OpenCode runs on its free models with no key, so it's usable either way; keys add providers.
                val n = Regex("(\\d+) credential").find(stripAnsi(probe("${host.base}/enter.sh opencode auth list").out))?.groupValues?.get(1)?.toIntOrNull() ?: 0
                EngineStatus(true, true, if (n > 0) "Installed, $n provider key${if (n == 1) "" else "s"}" else "Installed, free models only until you add a key")
            }
            1 -> {}  // not installed
            else -> last[e]?.let { out[e] = it }  // no answer (the helper, say): what the last check found stands
        }
        return out
    }

    /** One check at a time, so an older one never overwrites a newer answer. */
    private val checking = Any()

    /**
     * Reads what is in place. [ask]: the owner tapped Start, so starting the helper may ask them (see [AdbHelper.ensure]).
     * [display]: start a hidden display to see that it works, which otherwise happens only once per Droid and Android.
     */
    private fun check(ask: Boolean = false, full: Boolean = true, display: Boolean = false): Unit = synchronized(checking) {
        // Longer than the others: the root manager may be asking the owner whether to allow Droid.
        val root = exec(RootHost, "id", timeoutS = 90).let { it.code == 0 && "uid=0" in it.out }
        // Root stays on offer wherever there is an su (see SetupScreen): Magisk's is there while it refuses Droid (its
        // prompt missed or denied). KernelSU and APatch don't ask, and show theirs once Droid is allowed in their app,
        // which Setup notices on coming back from it.
        val su = root || System.getenv("PATH").orEmpty().split(':').any { File(it, "su").canExecute() }
        val access = savedAccess ?: (if (root) Access.ROOT else Access.ADB).also { a -> prefs.edit { putString("access", a.name) } }
        state.update { it.copy(root = root, su = su, access = access, paired = app.adb.paired) }
        var helperError: String? = null
        val helper = access == Access.ADB && app.adb.paired && try {
            app.adb.ensure(ask = ask); true
        } catch (e: Exception) {  // a damaged key or a revoked permission too: the helper is what's missing
            helperError = e.message ?: e.toString()
            HelperJob.schedule(app)  // tries again once there is Wi-Fi
            false
        }
        val connected = if (access == Access.ROOT) root else helper
        val assistant = roleHeld() && serviceActive()
        // Starting the helper grants Droid the right to switch Wireless debugging on, but some phones refuse the shell
        // user that until a setting in Developer options allows it: ask again on each check, so it's there once it does.
        val secureSettings = !helper || app.adb.canToggle || probe(app.adb.grantCommand).let { app.adb.canToggle }
        val reached = { s: SetupState ->
            s.copy(checked = true, root = root, su = su, access = access, paired = app.adb.paired, helper = helper, helperError = helperError,
                secureSettings = secureSettings, voice = app.speech.installed, voiceCurrent = app.speech.current, voiceMatch = app.voiceMatch.installed, assistant = assistant,
                engine = app.prefs.engine.value)
        }
        // Out of reach for now: what the last check found in the Linux stands (see [needed]).
        if (!connected) return state.update(reached)
        val claude = probe("test -L ${host.rootfs}/root/.local/bin/claude")  // links into the rootfs's own /root
        if (claude.code !in 0..1) return state.update(reached)  // gone out of reach after all (the helper stopped, say)
        // Some makers' phones (Xiaomi's, OPPO's) refuse input from adb until a switch in Developer options is on, which
        // would leave the agent able to see the hidden screen but not tap it. A key no app handles shows whether they do.
        val inputBlocked = inputRefused(probe("input keyevent 0").out)
        val installed = claude.code == 0
        val linux = installed || probe("test -e ${host.rootfs} -o -e ${host.rootfs}.new").code == 0
        val status = if (!installed || !full) null else authStatus(probe("${host.base}/enter.sh claude auth status --json").out)
        // No answer at all (the command itself failed): what the last check found stands, as when Android is out of reach.
        val last = state.value
        val kept = installed && status == null
        val signedIn = if (kept) last.signedIn else status?.optBoolean("loggedIn") == true
        // optString gives "null" for a JSON null, which `claude auth status` reports before it has the account's profile.
        fun field(name: String) = status?.takeIf { signedIn && !it.isNull(name) }?.optString(name)?.takeIf { it.isNotBlank() }
        val email = if (kept) last.email else field("email")
        val plan = if (kept) last.plan else field("subscriptionType")
        val others = if (full) checkEngines(installed) else last.others
        val screenshots = assistant && probe("settings get secure $SCREENSHOTS; settings get secure $SCREEN_TEXT").out.lines()
            .filter { it.isNotBlank() }.all { it.trim() == "1" }
        // Every task needs its hidden display: a phone that won't start one says so here, with why, not in each task.
        val displayError = when {
            !installed -> last.displayError
            !display && prefs.getString(DISPLAY_WORKS, null) == displayKey() -> null  // it started for this Droid and Android
            else -> when (val d = startDisplay()) {
                null -> last.displayError  // couldn't be asked: what was found stands
                else -> d.error.also { e -> prefs.edit { if (e == null) putString(DISPLAY_WORKS, displayKey()) else remove(DISPLAY_WORKS) } }
            }
        }
        state.update {
            reached(it).copy(installed = installed, linux = linux, signedIn = signedIn, screenshots = screenshots, email = email, plan = plan,
                others = others, inputBlocked = inputBlocked, displayError = displayError, linuxChecked = true)
        }
        prefs.edit {
            putBoolean("ready", state.value.ready); putBoolean("installed", installed); putBoolean("signedIn", signedIn)
            putString("email", email); putString("plan", plan)
            putOthers(others)
            if (full) putLong("fullCheckAt", System.currentTimeMillis())
        }
    }

    /** Keeps what a check found of the other agents for the next process, which needs it before its first check. */
    private fun SharedPreferences.Editor.putOthers(others: Map<Engine, EngineStatus>) = OTHERS.forEach { e ->
        val s = others[e]
        if (s == null) listOf("installed", "signedIn", "detail").forEach { remove("${e.id}.$it") }
        else { putBoolean("${e.id}.installed", s.installed); putBoolean("${e.id}.signedIn", s.signedIn); putString("${e.id}.detail", s.detail) }
    }

    private fun busy(what: String) = state.update { it.copy(busy = what) }

    /** Runs a step in the background. One labelled [what] doesn't start while another step or a sign-in is under way. */
    private fun step(what: String?, block: suspend () -> Unit) {
        if (what != null) {
            if (state.value.busy != null || signIn != null) return
            state.update { it.copy(busy = what, error = null) }
        }
        scope.launch {
            try { block() } catch (e: Exception) {
                Log.w("Droid", "setup failed", e)
                state.update { it.copy(error = e.message ?: e.toString()) }
            } finally {
                if (what != null) state.update { it.copy(busy = null) }
            }
        }
    }

    private class Out(val code: Int, val out: String)

    /** Runs [cmd] on the current host (root, or the helper), stdout and stderr together, handing each line to [onLine]. */
    private fun run(cmd: String, stdin: String? = null, onLine: (String) -> Unit = {}): Out = exec(host, cmd, stdin, onLine = onLine)

    /**
     * [run] for a check, which every step ends with: one stuck command (a sign-in status that never answers, a helper
     * that stopped answering) gives up after a while instead of holding up every step after it.
     */
    private fun probe(cmd: String): Out = exec(host, cmd, timeoutS = 30)

    /** [timeoutS]: if more than 0, the command is stopped after that many seconds, as if it had failed. */
    private fun exec(h: Host, cmd: String, stdin: String? = null, timeoutS: Long = 0, onLine: (String) -> Unit = {}): Out {
        val p = try {
            h.exec(cmd).also { p -> if (stdin != null) p.outputStream.use { it.write(stdin.toByteArray()) } else p.outputStream.close() }
        } catch (e: IOException) {
            return Out(-1, e.message.orEmpty())  // no su binary, or no helper
        }
        val out = StringBuffer()
        // Read on a thread of its own, so a command that hangs can be given up on.
        val reader = thread(isDaemon = true, name = "setup-output") {
            try { p.inputStream.bufferedReader().forEachLine { out.appendLine(it); onLine(it) } } catch (_: IOException) {}
        }
        if (timeoutS > 0 && !p.waitFor(timeoutS, TimeUnit.SECONDS)) {
            p.destroy()
            return Out(-1, "No answer within $timeoutS s: $cmd")
        }
        // A background child may hold the output open after the command exits: with a time limit, not for long.
        reader.join(if (timeoutS > 0) 5_000 else 0)
        return Out(p.waitFor(), out.toString())
    }

    /**
     * Where the app leaves files for the host to read: its private storage for root, its folder in shared storage for
     * the shell user (who may read Android/data, while other apps may not).
     */
    private fun scratch(): File = (if (host == RootHost) app.cacheDir else app.externalCacheDir ?: throw IOException("No shared storage")).apply { mkdirs() }

    /** The newest Alpine minirootfs for this CPU, checked against the release index's SHA-256. */
    private fun downloadAlpine(to: File) {
        val arch = when (Build.SUPPORTED_ABIS.firstOrNull()) {
            "arm64-v8a" -> "aarch64"
            "x86_64" -> "x86_64"
            else -> throw IOException("Droid needs an arm64 or x86_64 device")
        }
        val (file, sha256) = minirootfs(fetch("$ALPINE/$arch/latest-releases.yaml", "Downloading Alpine Linux") { it.reader().readText() })
        if (download("$ALPINE/$arch/$file", to, "Downloading Alpine Linux") != sha256) {
            to.delete()
            throw IOException("Alpine download is corrupt (checksum mismatch)")
        }
    }

    /** Downloads [url] into [to], showing progress as [label]; returns its SHA-256. */
    private fun download(url: String, to: File, label: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fetch(url, label) { input -> to.outputStream().use { DigestInputStream(input, digest).copyTo(it) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Streams [url] to [use], showing progress as [label]; a stalled connection fails rather than hanging Setup. */
    private fun <T> fetch(url: String, label: String, use: (InputStream) -> T): T {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 30_000
        c.readTimeout = 60_000
        try {
            if (c.responseCode != 200) throw IOException("Download failed (${c.responseCode}): $url")
            return Progress(c.inputStream, c.contentLengthLong, label).use(use)
        } finally {
            c.disconnect()
        }
    }

    /** Shows how many megabytes of [total] have been read, once per megabyte. */
    private inner class Progress(input: InputStream, private val total: Long, private val label: String) : FilterInputStream(input) {
        private var done = 0L

        override fun read(): Int = super.read().also { if (it >= 0) count(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count(it) }

        private fun count(n: Int) {
            val before = done shr 20
            done += n
            val mb = done shr 20
            if (mb != before) busy(if (total > 0) "$label: $mb / ${total shr 20} MB" else "$label: $mb MB")
        }
    }

    /** The setup scripts, copied from the APK's assets to where the host can read them. */
    private fun unpackAssets(): File {
        val root = scratch()
        val dir = File(root, "setup").apply { deleteRecursively() }
        fun copy(path: String) {
            val children = app.assets.list(path).orEmpty()
            if (children.isEmpty()) {
                File(root, path).apply { parentFile?.mkdirs() }.outputStream().use { out -> app.assets.open(path).use { it.copyTo(out) } }
            } else children.forEach { copy("$path/$it") }
        }
        copy("setup")
        return dir
    }
}

/** Unpacks a release archive's single top-level directory as [to], skipping anything that would land outside it. */
internal fun untarBz2(input: InputStream, to: File) {
    TarArchiveInputStream(BZip2CompressorInputStream(input.buffered(1 shl 16))).use { tar ->
        while (true) {
            val e = tar.nextEntry ?: break
            val rel = e.name.substringAfter('/', "")
            if (rel.isEmpty() || rel.split('/').any { it == ".." }) continue
            val f = File(to, rel)
            when {
                e.isDirectory -> f.mkdirs()
                e.isSymbolicLink || e.isLink -> {}  // none in these archives; never follow one out of [to]
                else -> { f.parentFile?.mkdirs(); f.outputStream().use { tar.copyTo(it) } }
            }
        }
    }
}

/** The minirootfs file name and SHA-256 from Alpine's `latest-releases.yaml`. */
internal fun minirootfs(index: String): Pair<String, String> {
    val entry = index.split("\n-").firstOrNull { "flavor: alpine-minirootfs" in it } ?: throw IOException("No Alpine minirootfs found")
    fun field(name: String) = Regex("\\n\\s*$name: (\\S+)").find(entry)?.groupValues?.get(1) ?: throw IOException("Bad Alpine index")
    return field("file") to field("sha256")
}

/** `claude auth status --json`'s answer, or null if [output] holds none (the command itself failed). */
internal fun authStatus(output: String): JSONObject? = try {
    JSONObject(output.substring(output.indexOf('{'))).takeIf { it.has("loggedIn") }
} catch (_: Exception) {
    null
}

/**
 * The Linux's resolv.conf: up to two of the network's own DNS servers, since some networks block public ones, ahead of
 * [fallback] (assets/setup/resolv.conf, public ones). musl asks the first three at once and takes the first answer.
 */
internal fun resolvConf(network: List<InetAddress>, fallback: String): String =
    network.filter { !it.isLinkLocalAddress && !it.isLoopbackAddress }.take(2)
        .joinToString("") { "nameserver ${it.hostAddress}\n" } + fallback

/** The DNS servers of the network the phone uses now (under a VPN, the VPN's). */
fun networkDns(ctx: Context): List<InetAddress> {
    val cm = ctx.getSystemService(ConnectivityManager::class.java)
    return cm.activeNetwork?.let { cm.getLinkProperties(it)?.dnsServers }.orEmpty()
}

private val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")
internal fun stripAnsi(s: String) = ANSI.replace(s, "")

/** The page and one-time code from `codex login --device-auth`'s output (either may still be missing while it prints). */
internal fun parseDeviceAuth(output: String): Pair<String?, String?> {
    val text = stripAnsi(output)
    val url = Regex("https://\\S+").find(text)?.value
    // The code is printed on its own line, after the page's link: letters and digits in groups, e.g. ABCD-12345.
    val code = Regex("(?m)^\\s*([A-Z0-9]{4,}-[A-Z0-9]{4,})\\s*$").find(text)?.groupValues?.get(1)
    return url to code
}

/** Saves one API key into OpenCode's auth.json (mode 600): argv[1] is the provider, the key comes on stdin. */
private val SAVE_KEY = """
import json, os, sys
p = os.path.expanduser('~/.local/share/opencode/auth.json')
os.makedirs(os.path.dirname(p), exist_ok=True)
try:
    d = json.load(open(p))
except Exception:
    d = {}
key = sys.stdin.readline().strip()
if not key:
    sys.exit('Empty key')
d[sys.argv[1]] = {'type': 'api', 'key': key}
fd = os.open(p + '.new', os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
with os.fdopen(fd, 'w') as f:
    json.dump(d, f)
os.replace(p + '.new', p)
""".trimIndent().replace("'", "\"")

/** curl's exit codes for a network that failed it (couldn't resolve or connect, timed out, TLS, nothing or a broken reply). */
private val NETWORK_EXITS = setOf(6, 7, 28, 35, 52, 56)
private val NETWORK_LOG = Regex("""curl: \((?:6|7|28|35|52|56)\)|Download failed|temporary error \(try again later\)|Could not resolve host""")

/**
 * An install that failed: when its log says a download did (the Wi-Fi went, say), the owner hears that in plain words;
 * else [said]. The log's end goes under it, for a bug report.
 */
internal fun installFailed(said: String, code: Int, out: String): IOException {
    val network = code in NETWORK_EXITS || NETWORK_LOG.containsMatchIn(out.takeLast(4000))
    return IOException((if (network) "I couldn't download what I need. Check your internet connection, then try again." else said) +
        "\n\n" + out.takeLast(1500))
}
