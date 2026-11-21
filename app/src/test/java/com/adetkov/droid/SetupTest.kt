package com.adetkov.droid

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.nio.file.Files

class SetupTest {
    // Trimmed from https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/aarch64/latest-releases.yaml
    private val index = """
        ---
        -
          title: "Raspberry Pi"
          desc: |
            Pi 3 to Pi 5 generations (aarch64).
          flavor: alpine-rpi
          file: alpine-rpi-3.24.3-aarch64.tar.gz
          sha256: 7bc2ac2ac3bd15667f651dc8d10c8423f1272730f028bc8962384c30e5317f62
        -
          title: "Mini root filesystem"
          desc: |
            Minimal root filesystem.
            For use in containers
            and minimal chroots.
          flavor: alpine-minirootfs
          file: alpine-minirootfs-3.24.3-aarch64.tar.gz
          size: 4029999
          sha256: 4d3f80afaebd447c5591a63a73bde380a149fe8b2d85488cafabb2ff97551f50
          sha512: 75e0cc358e93a81c069662bc248fa3e993d5c35c72484a6c91de5f0fc0be8e86
        -
          title: "Netboot"
          flavor: alpine-netboot
          file: alpine-netboot-3.24.3-aarch64.tar.gz
          sha256: 0000000000000000000000000000000000000000000000000000000000000000
    """.trimIndent()

    @Test fun findsTheMinirootfs() = assertEquals(
        "alpine-minirootfs-3.24.3-aarch64.tar.gz" to "4d3f80afaebd447c5591a63a73bde380a149fe8b2d85488cafabb2ff97551f50",
        minirootfs(index),
    )

    @Test fun failsWithoutOne() {
        assertThrows(IOException::class.java) { minirootfs(index.replace("alpine-minirootfs", "alpine-other")) }
    }

    @Test fun unpacksTheTopDirectoryOnly() {
        val bytes = ByteArrayOutputStream()
        TarArchiveOutputStream(BZip2CompressorOutputStream(bytes)).use { tar ->
            fun file(name: String, text: String) {
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = text.length.toLong() })
                tar.write(text.toByteArray()); tar.closeArchiveEntry()
            }
            tar.putArchiveEntry(TarArchiveEntry("model/")); tar.closeArchiveEntry()
            file("model/tokens.txt", "a b")
            file("model/test_wavs/0.wav", "wav")
            file("model/../escape.txt", "no")
            tar.putArchiveEntry(TarArchiveEntry("model/link", TarArchiveEntry.LF_SYMLINK).apply { linkName = "/etc/passwd" }); tar.closeArchiveEntry()
        }
        val root = Files.createTempDirectory("untar").toFile()
        val to = root.resolve("parakeet")
        untarBz2(ByteArrayInputStream(bytes.toByteArray()), to)
        assertEquals("a b", to.resolve("tokens.txt").readText())
        assertEquals("wav", to.resolve("test_wavs/0.wav").readText())
        assertFalse(root.resolve("escape.txt").exists())
        assertFalse(to.resolve("link").exists())
        root.deleteRecursively()
    }

    @Test fun readsTheAuthStatusPastOtherOutput() {
        val status = authStatus("Checking...\n{\"loggedIn\": true, \"email\": \"a@b.c\"}\n")
        assertEquals(true, status?.optBoolean("loggedIn"))
        assertEquals("a@b.c", status?.optString("email"))
        // Signed out is an answer too, whatever the exit code was
        assertEquals(false, authStatus("{\"loggedIn\": false}")?.optBoolean("loggedIn", true))
    }

    @Test fun noAuthStatusWhenTheCommandFailed() {
        assertNull(authStatus(""))
        assertNull(authStatus("droid-bridge: Droid's helper went away"))
        assertNull(authStatus("{\"error\": \"timeout\"}"))
        assertNull(authStatus("{ not json"))
    }

    @Test fun newChatsGoToThePickedAgentUntilACheckSaysOtherwise() {
        val codex = mapOf(Engine.CODEX to EngineStatus(installed = true, signedIn = false, detail = "Installed, sign-in needed"))
        // A new process, before its first check reaches the Linux: the pick stands, whatever was saved.
        assertEquals(Engine.CODEX, SetupState(signedIn = true).engineFor(Engine.CODEX))
        assertEquals(Engine.CODEX, SetupState(signedIn = true, others = codex).engineFor(Engine.CODEX))
        // Checked: an agent that can't take a chat gives way to one that can, if there is one.
        assertEquals(Engine.CLAUDE, SetupState(signedIn = true, others = codex, linuxChecked = true).engineFor(Engine.CODEX))
        assertEquals(Engine.CODEX, SetupState(others = codex, linuxChecked = true).engineFor(Engine.CODEX))
        val signedIn = mapOf(Engine.CODEX to EngineStatus(installed = true, signedIn = true))
        assertEquals(Engine.CODEX, SetupState(signedIn = true, others = signedIn, linuxChecked = true).engineFor(Engine.CODEX))
    }

    @Test fun putsTheNetworksDnsFirst() {
        val public = "nameserver 1.1.1.1\nnameserver 8.8.8.8\n"
        val net = listOf("192.168.1.1", "fe80::1", "127.0.0.1", "10.0.0.2", "10.0.0.3").map(InetAddress::getByName)
        assertEquals("nameserver 192.168.1.1\nnameserver 10.0.0.2\n$public", resolvConf(net, public))
        assertEquals(public, resolvConf(emptyList(), public))
        assertTrue(resolvConf(listOf(InetAddress.getByName("2001:db8::53")), public).startsWith("nameserver 2001:db8:"))
    }
}
