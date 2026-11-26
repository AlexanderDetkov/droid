package com.adetkov.droid

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.edit
import com.adetkov.droid.adb.Adb
import com.adetkov.droid.adb.AdbKey
import com.adetkov.droid.adb.Discovery
import java.io.File
import java.io.IOException
import java.security.SecureRandom

/** Where the shell user keeps Droid's Linux on phones without root (the shell user can run programs from here). */
const val SHELL_BASE = "/data/local/tmp/droid"

/**
 * Droid's access on phones without root: Android's Wireless debugging. Pairing (once, with the code Settings shows)
 * lets Droid connect to the phone's own adbd; through it Droid starts [Bridge] as the shell user, once per boot, and
 * then runs everything through the bridge. Wireless debugging needs Wi-Fi, so starting the bridge does too. Once
 * started it keeps running without Wi-Fi or Wireless debugging for as long as adbd runs, so Droid keeps USB debugging
 * on (see [launchCommand]): until the phone restarts, or the owner turns USB debugging or Developer options off.
 */
class AdbHelper(private val app: Context) {
    private val prefs = app.getSharedPreferences("adb", Context.MODE_PRIVATE)
    private val key by lazy { AdbKey.load(File(app.filesDir, "adb")) }
    private val apk get() = app.applicationInfo.sourceDir

    /** Whether Droid may search the local network (Android 17+ asks), which it needs to find Wireless debugging's ports. */
    val canDiscover: Boolean
        get() = Build.VERSION.SDK_INT < 37 || app.checkSelfPermission(LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED

    /** Paired with this phone's adbd: Droid remembers its certificate. */
    val paired: Boolean get() = prefs.contains(SERVER)

    /** Droid may switch Wireless debugging on by itself, as it must to start the helper after a restart. */
    val canToggle: Boolean
        get() = app.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** Grants [canToggle], run as the shell user (only it may). Some phones refuse it until a setting allows it. */
    val grantCommand get() = "pm grant ${app.packageName} ${Manifest.permission.WRITE_SECURE_SETTINGS}"

    private fun saved(): BridgeClient? {
        val port = prefs.getInt(PORT, 0)
        val token = prefs.getString(TOKEN, null)
        return if (port > 0 && token != null) BridgeClient(port, token) else null
    }

    /**
     * The bridge, started first if it isn't running: then this blocks for up to about a minute, and throws an
     * [IOException] that tells the user what to do (no Wi-Fi, not paired, ...). With [update] (after an app update, see
     * [HelperJob]), one running an older APK's code is replaced, but only while it runs nothing (replacing it would cut
     * its tasks off); if it can't be, it serves on until the phone restarts. [ask]: the owner asked for this (Setup's
     * Start), so Droid may turn Wireless debugging on even where Android asks first whether to allow it on this Wi-Fi.
     */
    @Synchronized
    fun ensure(update: Boolean = false, ask: Boolean = false): BridgeClient {
        val old = saved()
        val ping = old?.ping()
        if (old == null || ping == null) return start(ask)
        if (!update || ping.apk == apk || ping.active > 0) return old
        return try { start(ask).also { old.quit() } } catch (_: IOException) { old }
    }

    /** Pairs with the pairing code Settings shows, the pairing service on [port] (found by [Discovery.PAIRING]). */
    fun pair(port: Int, code: String) {
        val fp = Adb.pair(key, port, code)
        prefs.edit { putString(SERVER, fp) }
        Log.i(TAG, "paired with adbd $fp")
    }

    private fun start(ask: Boolean): BridgeClient {
        if (!paired) throw IOException("Pair Droid with Wireless debugging first")
        if (!canDiscover) throw IOException(NO_LOCAL_NETWORK)
        val cr = app.contentResolver
        // Every port announced: after Wireless debugging restarts, mDNS can still announce its old port beside the new
        // one, and the old one refuses
        val found = java.util.concurrent.LinkedBlockingQueue<Int>()
        val discovery = Discovery(app, Discovery.CONNECT, onLost = { DevSettings.wirelessSeen = false }) {
            DevSettings.wirelessSeen = true
            found.offer(it)
        }.also { it.start() }
        var turnedOn = false
        val client = try {
            // Whether Wireless debugging is on: what Android says, where it does, else what this phone announces (Android
            // 17 tells apps it's off whatever it is): on already, it's announced within seconds
            val said = DevSettings.read(app, DevSettings.ADB_WIFI)
            var port = when (said) {
                true -> found.poll(20, java.util.concurrent.TimeUnit.SECONDS)
                null -> found.poll(ANNOUNCED_S, java.util.concurrent.TimeUnit.SECONDS)
                false -> null
            }
            if (port == null && said == true) {
                if (!onWifi()) throw IOException(NO_WIFI)
                throw IOException("Wireless debugging didn't answer; check that it's on")
            }
            if (port == null) {
                if (!onWifi()) throw IOException(NO_WIFI)
                if (!canToggle) throw IOException("Turn on Wireless debugging in Developer options")
                // On a Wi-Fi it doesn't trust yet, Android asks whether to allow Wireless debugging, and after a restart
                // nobody may be there to answer: ask once per Wi-Fi connection, and again only when the owner taps Start.
                if (!ask && wifiConnection() == prefs.getString(UNTRUSTED, null)) throw IOException(UNTRUSTED_WIFI)
                Settings.Global.putInt(cr, DevSettings.ADB_WIFI, 1)
                turnedOn = true
                port = found.poll(20, java.util.concurrent.TimeUnit.SECONDS)
                if (port == null) {
                    if (!onWifi()) throw IOException(NO_WIFI)
                    // Android switched it straight back off to ask, and nobody allowed it (yet): where Android won't say
                    // whether it's on, that's the likeliest
                    if (DevSettings.read(app, DevSettings.ADB_WIFI) != true) {
                        prefs.edit { putString(UNTRUSTED, wifiConnection()) }
                        throw IOException(UNTRUSTED_WIFI)
                    }
                    throw IOException("Wireless debugging didn't answer; check that it's on")
                }
            }
            val tried = mutableSetOf<Int>()
            var started: BridgeClient? = null
            while (started == null) {
                try {
                    started = launch(port!!)
                } catch (e: java.net.ConnectException) {
                    Log.i(TAG, "port $port refused, trying another")
                    tried += port!!
                    port = generateSequence { found.poll(5, java.util.concurrent.TimeUnit.SECONDS) }.firstOrNull { it !in tried }
                        ?: throw IOException("Wireless debugging didn't answer. Turn it off and on again in Developer options.")
                }
            }
            started
        } finally {
            discovery.stop()
            // Leave Wireless debugging as it was: the helper doesn't need it once started, as long as adbd keeps running.
            if (turnedOn) Settings.Global.putInt(cr, DevSettings.ADB_WIFI, 0)
        }
        // Had adbd stopped or restarted (Wireless debugging going off, USB debugging coming on), it would have taken the
        // helper with it: check it's still there, and if it doesn't answer, that it's gone, so retries don't add helpers.
        Thread.sleep(1_000)
        if (client.ping() == null) {
            client.quit()
            throw IOException("Droid's helper stopped right after it started. Check that USB debugging is on in Developer " +
                "options: Android stops adbd, and the helper with it, when neither USB nor Wireless debugging is on.")
        }
        prefs.edit { putInt(PORT, client.port); putString(TOKEN, client.token); remove(UNTRUSTED) }
        Log.i(TAG, "helper started on port ${client.port}")
        return client
    }

    /** Starts the bridge through adbd's Wireless debugging port [port]. */
    private fun launch(port: Int): BridgeClient {
        val token = randomHex()
        // adbd's certificate for connections changes with every boot, so it can't be pinned, and any app could
        // advertise a port of its own under adbd's name. So the command also writes a fresh nonce into Droid's own
        // Android/data folder, which the shell user may write and other apps may not: if it isn't there, whatever
        // answered wasn't adbd, and the token it saw is never used.
        val nonce = randomHex()
        val proof = File(app.getExternalFilesDir(null), "helper.proof").apply { delete() }
        val out = Adb.shell(key, port, launchCommand(token, nonce, proof), null).output
        val proven = proof.takeIf { it.exists() }?.readText()?.trim() == nonce
        proof.delete()
        if (!proven) throw IOException("Something other than Android's adbd answered on port $port")
        val bridgePort = Regex("(?m)^port=(\\d+)").find(out)?.groupValues?.get(1)?.toInt()
            ?: throw IOException("Droid's helper didn't start:\n${out.takeLast(800)}")
        return BridgeClient(bridgePort, token)
    }

    /**
     * Run by adbd as the shell user. Grants Droid WRITE_SECURE_SETTINGS ([canToggle]). Keeps the pairing: Android drops
     * a key unused for 7 days (adb_allowed_connection_time, Developer options' "Disable adb authorization timeout"),
     * and Droid connects only to start the helper, once per boot. Turns USB debugging on: whatever it does, the helper
     * stays in adbd's cgroup, and once neither USB nor Wireless debugging is on, Android stops adbd and the whole cgroup
     * with it. That comes first, and a moment before the helper starts: Android applies it in the background, and on
     * some phones it restarts adbd. Then starts the bridge in its own session, so it outlives this connection, and
     * kills it if it doesn't come up within 30 s (a helper whose token Droid never saved would serve nobody).
     */
    private fun launchCommand(token: String, nonce: String, proof: File) =
        "echo $nonce > '${proof.path}'; " +
            "$grantCommand 2>/dev/null; " +
            "settings put global adb_allowed_connection_time 0; " +
            "[ \"\$(settings get global ${Settings.Global.ADB_ENABLED})\" = 1 ] || { settings put global ${Settings.Global.ADB_ENABLED} 1; sleep 2; }; " +
            "mkdir -p $SHELL_BASE && chmod 700 $SHELL_BASE && cd $SHELL_BASE && rm -f bridge.log || exit 1; " +
            "DROID_TOKEN=$token CLASSPATH='$apk' setsid nohup app_process / com.adetkov.droid.Bridge > bridge.log 2>&1 < /dev/null & pid=\$!; " +
            "for i in \$(seq 300); do grep -q '^port=' bridge.log && break; sleep 0.1; done; " +
            "grep -q '^port=' bridge.log || kill \$pid; cat bridge.log"

    private fun randomHex() = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    private fun onWifi() = wifi() != null

    /** The Wi-Fi network, also under a VPN (the active network would be the VPN). */
    @Suppress("DEPRECATION")
    private fun wifi(): Network? {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        return cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    }

    /**
     * This connection to Wi-Fi, for [UNTRUSTED]: Android shows apps without the location permission no network names,
     * and a network's handle is new with each connection, but only within one boot.
     */
    private fun wifiConnection() = wifi()?.let { "${Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, 0)}:${it.networkHandle}" }

    companion object {
        /** Manifest.permission.ACCESS_LOCAL_NETWORK, Android 17. */
        const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
        const val NO_LOCAL_NETWORK = "Allow Droid to find devices on your local network (Android asks when you tap Pair or Start): " +
            "that's how it finds Wireless debugging on this phone."
        // (a first sentence short enough for Dash to say; the rest goes under him)
        private const val NO_WIFI = "I need Wi-Fi to start Wireless debugging. Connect to Wi-Fi and I'll start by myself."
        private const val UNTRUSTED_WIFI = "Wireless debugging needs your OK on this Wi-Fi. Tap Start in Setup, tick Always " +
            "allow on this network, then Allow."
        private const val TAG = "DroidAdb"
        private const val SERVER = "server"
        private const val PORT = "port"
        private const val TOKEN = "token"
        /** The Wi-Fi connection ([wifiConnection]) where Android last asked about Wireless debugging and nobody allowed it. */
        private const val UNTRUSTED = "untrusted"
        /** How long an announcement Wireless debugging already makes takes to be found (on this phone, a second or two). */
        private const val ANNOUNCED_S = 6L
    }
}
