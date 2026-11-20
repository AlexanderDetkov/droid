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
        val wasOn = Settings.Global.getInt(cr, ADB_WIFI, 0) == 1
        val canToggle = app.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
        if (!wasOn) {
            if (!onWifi()) throw IOException(NO_WIFI)
            if (!canToggle) throw IOException("Turn on Wireless debugging in Developer options")
            // On a Wi-Fi it doesn't trust yet, Android asks whether to allow Wireless debugging, and after a restart nobody
            // may be there to answer: ask once per Wi-Fi connection, and again only when the owner taps Start.
            if (!ask && wifiConnection() == prefs.getString(UNTRUSTED, null)) throw IOException(UNTRUSTED_WIFI)
            Settings.Global.putInt(cr, ADB_WIFI, 1)
        }
        val client = try {
            val port = Discovery.find(app, Discovery.CONNECT, 20_000)
            if (port == null) {
                if (!onWifi()) throw IOException(NO_WIFI)
                // Android switched it straight back off to ask, and nobody allowed it (yet)
                if (!wasOn && Settings.Global.getInt(cr, ADB_WIFI, 0) == 0) {
                    prefs.edit { putString(UNTRUSTED, wifiConnection()) }
                    throw IOException(UNTRUSTED_WIFI)
                }
                throw IOException("Wireless debugging didn't answer; check that it's on")
            }
            launch(port)
        } finally {
            // Leave Wireless debugging as it was: the helper doesn't need it once started, as long as adbd keeps running.
            if (!wasOn) Settings.Global.putInt(cr, ADB_WIFI, 0)
        }
        // Had adbd stopped as Wireless debugging went off, it would have taken the helper with it: check it's still there.
        if (!wasOn) Thread.sleep(1_000)
        client.ping() ?: throw IOException("Droid's helper stopped right after it started. Check that USB debugging is on in " +
            "Developer options: Android stops adbd, and the helper with it, when neither USB nor Wireless debugging is on.")
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
     * Run by adbd as the shell user. Grants Droid WRITE_SECURE_SETTINGS, so it can switch Wireless debugging on by
     * itself after a restart (a permission only the shell user can grant). Turns USB debugging on: whatever it does,
     * the helper stays in adbd's cgroup, and once neither USB nor Wireless debugging is on, Android stops adbd and the
     * whole cgroup with it. That comes before the helper starts, in case turning it on restarts adbd. Then starts the
     * bridge in its own session, so it outlives this connection, and kills it if it doesn't come up within 30 s (a
     * helper whose token Droid never saved would serve nobody).
     */
    private fun launchCommand(token: String, nonce: String, proof: File) =
        "echo $nonce > '${proof.path}'; " +
            "pm grant ${app.packageName} ${Manifest.permission.WRITE_SECURE_SETTINGS} 2>/dev/null; " +
            "settings put global ${Settings.Global.ADB_ENABLED} 1; " +
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
        private const val NO_WIFI = "Connect to Wi-Fi: Droid starts its helper through Wireless debugging, which needs Wi-Fi"
        private const val UNTRUSTED_WIFI = "Allow Wireless debugging on this Wi-Fi: in Setup, tap Start, and when Android " +
            "asks, tick Always allow on this network and tap Allow."
        private const val TAG = "DroidAdb"
        private const val SERVER = "server"
        private const val PORT = "port"
        private const val TOKEN = "token"
        /** The Wi-Fi connection ([wifiConnection]) where Android last asked about Wireless debugging and nobody allowed it. */
        private const val UNTRUSTED = "untrusted"
        /** Settings.Global.ADB_WIFI_ENABLED, which is hidden. */
        private const val ADB_WIFI = "adb_wifi_enabled"
    }
}
