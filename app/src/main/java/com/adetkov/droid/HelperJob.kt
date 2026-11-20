package com.adetkov.droid

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlin.concurrent.thread

/**
 * Phones without root: starts Droid's helper once there is Wi-Fi (Wireless debugging works only on Wi-Fi), after the
 * phone restarts, after an app update (so the helper runs the new code), or after a try that failed. Retries with
 * backoff until it works.
 */
class HelperJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val app = application as DroidApp
        thread {
            // Anything else, too (a damaged key, a revoked permission): on this thread it would crash the app at boot.
            val ok = try { app.adb.ensure(update = true); true } catch (e: Exception) { Log.i("DroidAdb", "helper not started: $e"); false }
            jobFinished(params, !ok)
            if (ok) app.setup.refresh()
        }
        return true
    }

    override fun onStopJob(params: JobParameters) = true

    companion object {
        private const val ID = 7200

        /** Schedules a start if Droid runs without root and is paired; a no-op otherwise. */
        fun schedule(ctx: Context) {
            val app = ctx.applicationContext as DroidApp
            if (app.setup.state.value.access != Access.ADB || !app.adb.paired) return
            val job = JobInfo.Builder(ID, ComponentName(ctx, HelperJob::class.java))
                // Wi-Fi, which Wireless debugging needs; under a VPN too
                .setRequiredNetwork(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build())
                .setBackoffCriteria(60_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build()
            ctx.getSystemService(JobScheduler::class.java).schedule(job)
        }
    }
}

/** After a restart or an app update: start the helper again (see [HelperJob]). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) HelperJob.schedule(context)
    }
}
