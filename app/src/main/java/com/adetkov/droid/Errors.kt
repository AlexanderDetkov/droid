package com.adetkov.droid

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * A failed turn's status put for the owner: what happened and what to do, whether Setup is where to do it, and whether
 * it was Droid's helper not running (without root), which once it runs again makes the turn worth trying again.
 */
data class Plain(val text: String, val setup: Boolean = false, val helper: Boolean = false)

private val SIGNED_OUT = Regex("""please run /login|invalid api key|oauth token (?:has expired|revoked)|login expired|session has expired|not logged in""", RegexOption.IGNORE_CASE)
private val CLAUDE_LIMIT = Regex("""Claude AI usage limit reached\|(\d{9,})""")

/**
 * The plain version of a turn's [status] ("Error: …" or "Failed: …", as its agent said it) on [engine], when the
 * agent's words are meant for somewhere else (a terminal's /login, a link to a plan) or are just a code; null when
 * they already say it. The chat shows this and keeps the agent's words a tap away.
 */
internal fun plainStatus(status: String, engine: Engine, zone: ZoneId = ZoneId.systemDefault()): Plain? {
    val said = status.removePrefix("Error: ").removePrefix("Failed: ")
    return when {
        // the app was closed while it worked (by Android, or an update): nothing went wrong with the task itself
        said == "Interrupted" -> Plain("Your phone closed me partway. Try again, and I'll go on from there.")
        "Free usage limit reached" in said ->
            Plain("OpenCode's free models are used up for now. Sign in to Claude, or give OpenCode a provider key, in Setup.", setup = true)
        "Credit balance is too low" in said ->
            Plain("Your Anthropic Console account is out of credit. Add some at platform.claude.com, or sign in with a Claude plan in Setup.", setup = true)
        engine == Engine.CLAUDE && SIGNED_OUT.containsMatchIn(said) ->
            Plain("Claude Code isn't signed in anymore. Sign in again in Setup.", setup = true)
        // Without root: Droid's own words about Wireless debugging and its helper (AdbHelper) already say what to do,
        // and Setup is where it's done
        "Wireless debugging" in said || "Droid's helper" in said -> Plain(said, setup = true, helper = true)
        // phone-agent couldn't start the task's hidden display (vd-start.sh): Setup checks it again and shows why
        said.startsWith("The hidden display did not start") ->
            Plain("This phone wouldn't open my own screen, so I couldn't start. Setup shows why.", setup = true)
        else -> CLAUDE_LIMIT.find(said)?.let { m ->
            // (a time it can't be: no time said)
            val at = m.groupValues[1].toLongOrNull()?.takeIf { it in 0..253_402_300_799L }
            Plain("You've reached your Claude usage limit." + (at?.let { " It resets ${resetsAt(it, zone)}." } ?: ""))
        }
    }
}

/** "at 3:20 PM", or "on Sat at 9:00 AM" when that isn't today. */
private fun resetsAt(epochSeconds: Long, zone: ZoneId): String {
    val t = Instant.ofEpochSecond(epochSeconds).atZone(zone)
    val time = t.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
    return if (t.toLocalDate() == Instant.now().atZone(zone).toLocalDate()) "at $time" else "on ${t.format(DateTimeFormatter.ofPattern("EEE"))} at $time"
}
