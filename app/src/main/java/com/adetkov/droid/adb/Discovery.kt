package com.adetkov.droid.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * This phone's own Wireless debugging services, found by mDNS as adb finds them: "_adb-tls-pairing._tcp" while
 * Settings shows a pairing code, "_adb-tls-connect._tcp" while Wireless debugging is on. Other devices on the same
 * Wi-Fi advertise the same names, so only services at one of this phone's own addresses count.
 */
class Discovery(ctx: Context, private val type: String, private val onLost: () -> Unit = {}, private val onPort: (Int) -> Unit) {
    private val nsd = ctx.getSystemService(NsdManager::class.java)
    // NsdManager still delivers callbacks ("unregistered") after stop(), so the executor can never be shut down.
    private val executor = java.util.concurrent.Executor { it.run() }
    private val callbacks = ArrayList<NsdManager.ServiceInfoCallback>()
    @Volatile private var stopped = false

    private val listener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {}
        override fun onDiscoveryStopped(serviceType: String) {}
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { Log.w(TAG, "discovery of $type failed: $errorCode") }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        override fun onServiceLost(info: NsdServiceInfo) {}
        override fun onServiceFound(info: NsdServiceInfo) {
            if (stopped) return
            val cb = object : NsdManager.ServiceInfoCallback {
                @Volatile var mine = false
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {}
                override fun onServiceLost() { if (mine && !stopped) onLost() }  // (Wireless debugging turned off, say)
                override fun onServiceInfoCallbackUnregistered() {}
                override fun onServiceUpdated(s: NsdServiceInfo) {
                    mine = s.hostAddresses.any { it in localAddresses() }
                    Log.i(TAG, "$type ${s.serviceName} port=${s.port} mine=$mine")
                    if (mine && s.port > 0 && !stopped) onPort(s.port)
                }
            }
            synchronized(callbacks) { callbacks += cb }
            try { nsd.registerServiceInfoCallback(info, executor, cb) } catch (e: IllegalArgumentException) { Log.w(TAG, "resolve failed", e) }
        }
    }

    fun start() = nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)

    fun stop() {
        if (stopped) return
        stopped = true
        try { nsd.stopServiceDiscovery(listener) } catch (_: IllegalArgumentException) {}
        synchronized(callbacks) {
            callbacks.forEach { try { nsd.unregisterServiceInfoCallback(it) } catch (_: IllegalArgumentException) {} }
            callbacks.clear()
        }
    }

    companion object {
        const val PAIRING = "_adb-tls-pairing._tcp"
        const val CONNECT = "_adb-tls-connect._tcp"
        private const val TAG = "DroidAdb"

        private fun localAddresses(): Set<InetAddress> =
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().flatMap { it.inetAddresses.toList() }.toSet()

        /** The first port of [type] this phone advertises within [timeoutMs], or null. */
        fun find(ctx: Context, type: String, timeoutMs: Long): Int? {
            val found = java.util.concurrent.ArrayBlockingQueue<Int>(4)
            val d = Discovery(ctx, type) { found.offer(it) }
            d.start()
            try {
                return found.poll(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            } finally {
                d.stop()
            }
        }
    }
}
