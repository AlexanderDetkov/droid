package com.adetkov.droid

/**
 * How the app reaches Droid's Linux and Android's privileged commands: as root through `su` (a chroot), or on phones
 * without root as the shell user through Droid's helper (proot; see [AdbHelper] and Bridge.java). Everything that
 * used to call `su -c` goes through [exec], which takes the same shell command either way.
 */
interface Host {
    /** Where the entry script (enter.sh) and the rootfs live. */
    val base: String
    val rootfs: String get() = "$base/rootfs"

    /** Starts `sh -c [cmd]`; with [mergeStderr] its stderr comes on stdout. Blocks while a helper has to start. */
    fun exec(cmd: String, mergeStderr: Boolean = true): Process
}

/** Root: /data/local/droid, a chroot entered with `su`. */
object RootHost : Host {
    override val base = "/data/local/droid"
    override fun exec(cmd: String, mergeStderr: Boolean): Process =
        ProcessBuilder("su", "-c", cmd).redirectErrorStream(mergeStderr).start()
}

/** No root: /data/local/tmp/droid, proot run by the shell user through Droid's helper, started if it isn't running. */
class ShellHost(private val helper: AdbHelper) : Host {
    override val base = SHELL_BASE
    override fun exec(cmd: String, mergeStderr: Boolean): Process = helper.ensure().exec(cmd, mergeStderr)
}
