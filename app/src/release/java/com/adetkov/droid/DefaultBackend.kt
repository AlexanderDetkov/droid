package com.adetkov.droid

import android.content.Context
import android.content.Intent

fun defaultBackend(app: Context, host: () -> Host): Backend = HostBackend(app, host)

val isEmulator = false

fun testWav(ctx: Context, i: Intent): String? = null

fun testEnrollWavs(ctx: Context, i: Intent): List<String>? = null
