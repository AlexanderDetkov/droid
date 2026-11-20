package com.adetkov.droid

import android.os.Build

/**
 * What a bug report needs: this phone, this Droid and how far its setup got. Nothing private goes in: no account or
 * email, no chats, files or instructions. Setup gathers it ([Setup.diagnostics]) and its screen copies it.
 */
internal fun diagnosticsReport(version: String, phone: List<Pair<String, String>>, setup: List<Pair<String, String>>): String =
    (listOf("Droid" to version) + phone + setup).joinToString("\n") { (k, v) -> "$k: ${v.replace('\n', ' ').trim()}" }

/** The phone: model, Android and its maker's skin, from [props] (`getprop`'s, as a map). */
internal fun phoneFacts(props: Map<String, String>): List<Pair<String, String>> = buildList {
    add("Phone" to "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
    add("Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), patch ${Build.VERSION.SECURITY_PATCH}")
    add("Build" to Build.DISPLAY)
    add("ABIs" to Build.SUPPORTED_ABIS.joinToString())
    skin(props)?.let { add("Skin" to it) }
}

/** The maker's version of Android, where its build says (Samsung, Xiaomi, OPPO and OnePlus, vivo, Huawei). */
internal fun skin(props: Map<String, String>): String? = listOf(
    "ro.build.version.oneui" to "One UI", "ro.mi.os.version.name" to "HyperOS", "ro.miui.ui.version.name" to "MIUI",
    "ro.build.version.opporom" to "ColorOS", "ro.vivo.os.version" to "vivo OS", "ro.build.version.emui" to "EMUI",
).firstNotNullOfOrNull { (key, name) -> props[key]?.takeIf { it.isNotBlank() }?.let { "$name $it" } }

/** `getprop`'s output ("[key]: [value]" lines) as a map. */
internal fun parseProps(out: String): Map<String, String> =
    Regex("""^\[([^\]]+)]: \[(.*)]$""", RegexOption.MULTILINE).findAll(out).associate { it.groupValues[1] to it.groupValues[2] }

/** How far setup got and what failed, from [s]; [versions] are the agents' and Alpine's, where installed. */
internal fun setupFacts(s: SetupState, versions: List<Pair<String, String>> = emptyList()): List<Pair<String, String>> = buildList {
    add("Access" to when (s.access) {
        Access.ROOT -> if (s.root) "root" else "root, not granted"
        Access.ADB -> "Wireless debugging, " + when {
            s.helper -> "helper running"
            s.paired -> "paired, helper not running" + (s.helperError?.let { " ($it)" } ?: "")
            else -> "not paired"
        }
    })
    if (s.access == Access.ADB && s.su) add("su" to "present")
    add("Linux" to when { s.installed -> "installed"; s.linux -> "partly installed"; else -> "not installed" })
    add("Claude" to if (s.signedIn) "signed in" + (s.plan?.let { ", $it" } ?: "") else "not signed in")
    for (e in listOf(Engine.CODEX, Engine.OPENCODE)) add(e.label to s.others[e].let { st ->
        when { st == null -> "not installed"; st.signedIn -> "ready"; else -> "installed, not signed in" }
    })
    add("New chats" to (s.readyEngine?.label ?: "no agent ready") + if (s.readyEngine != s.engine) " (picked: ${s.engine.label})" else "")
    addAll(versions)
    if (s.checked && s.connected) add("Input" to if (s.inputBlocked) "refused: the shell user may not tap or type" else "allowed")
    add("Voice" to if (s.voice) "installed" else "not installed")
    add("Assistant" to if (s.assistant) "Droid" + if (s.screenshots) ", with screenshots" else ", no screenshots" else "not Droid")
    s.error?.let { add("Last error" to it.takeLast(800)) }
}

/** Whether `input`'s output says Android refused the injection (Xiaomi's phones, say, until a Developer options switch is on). */
internal fun inputRefused(out: String) = "INJECT_EVENTS" in out || "SecurityException" in out
