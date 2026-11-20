package com.adetkov.droid.adb

import android.net.ssl.SSLSockets
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.cert.X509Certificate
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLSocket

/**
 * A minimal client for this phone's own adbd over Wireless debugging (Android 11+): pairing with the six-digit code
 * once, then TLS connections that run one `shell:` command each. Droid uses it only to start its helper as the shell
 * user (see Bridge); everything else goes through the helper.
 *
 * Protocols: adb/pairing_connection and pairing_auth (pairing), adb/protocol.txt with STLS (connections).
 */
object Adb {
    private const val TIMEOUT_MS = 15_000
    /** How long a [shell] command may go without printing: Droid's helper launch waits up to 30 s before it does. */
    private const val OUTPUT_TIMEOUT_MS = 60_000

    /**
     * Pairs [key] with the adbd pairing server on 127.0.0.1:[port] using the [code] Settings shows. Returns the
     * fingerprint of the server's certificate, for [shell] to recognise this adbd by. Throws on a wrong code.
     */
    fun pair(key: AdbKey, port: Int, code: String): String {
        val raw = Socket().apply { connect(InetSocketAddress("127.0.0.1", port), TIMEOUT_MS); soTimeout = TIMEOUT_MS; tcpNoDelay = true }
        raw.use {
            val tls = key.sslContext().socketFactory.createSocket(raw, "127.0.0.1", port, true) as SSLSocket
            tls.startHandshake()
            val server = tls.session.peerCertificates.first() as X509Certificate
            // The exported key ties the PAKE to this TLS session, so nothing can sit in the middle.
            val ekm = SSLSockets.exportKeyingMaterial(tls, "adb-label\u0000", null, 64) ?: throw IOException("No TLS exporter")
            val spake = Spake2(true, "adb pair client\u0000".toByteArray(), "adb pair server\u0000".toByteArray())
            val input = DataInputStream(tls.inputStream)
            val output = tls.outputStream

            writePacket(output, SPAKE2_MSG, spake.generateMessage(code.trim().toByteArray() + ekm))
            val theirMsg = readPacket(input, SPAKE2_MSG)
            val cipher = PairingCipher(hkdf(spake.processMessage(theirMsg), "adb pairing_auth aes-128-gcm key".toByteArray(), 16))

            val info = ByteArray(PEER_INFO_SIZE)
            info[0] = 0  // ADB_RSA_PUB_KEY
            key.adbPublicKey("droid@${android.os.Build.MODEL}").copyInto(info, 1)
            writePacket(output, PEER_INFO, cipher.encrypt(info))
            val reply = try {
                cipher.decrypt(readPacket(input, PEER_INFO))
            } catch (e: javax.crypto.AEADBadTagException) {
                throw IOException("Wrong pairing code")
            }
            if (reply.size != PEER_INFO_SIZE) throw IOException("Unexpected pairing reply")
            return AdbKey.fingerprint(server)
        }
    }

    /**
     * Runs [command] through `shell:` on 127.0.0.1:[port] (Wireless debugging's connect port) and returns its
     * output, stdout and stderr together. [serverFingerprint], if given, must match the server's certificate.
     */
    fun shell(key: AdbKey, port: Int, command: String, serverFingerprint: String?): Shell {
        val raw = Socket().apply { connect(InetSocketAddress("127.0.0.1", port), TIMEOUT_MS); soTimeout = TIMEOUT_MS; tcpNoDelay = true }
        raw.use {
            send(raw.outputStream, CNXN, VERSION, MAX_PAYLOAD, "host::\u0000".toByteArray())
            val first = read(DataInputStream(raw.inputStream))
            if (first.command != STLS) throw IOException("This port doesn't speak Wireless debugging's TLS (got ${name(first.command)})")
            send(raw.outputStream, STLS, STLS_VERSION, 0, ByteArray(0))
            val pin = serverFingerprint?.let { fp -> { cert: X509Certificate -> AdbKey.fingerprint(cert) == fp } }
            val tls = key.sslContext(pin).socketFactory.createSocket(raw, "127.0.0.1", port, true) as SSLSocket
            try {
                tls.startHandshake()
            } catch (e: IOException) {
                throw IOException("adbd refused Droid's key: pair again (${e.message})", e)
            }
            val seen = AdbKey.fingerprint(tls.session.peerCertificates.first() as X509Certificate)
            val input = DataInputStream(tls.inputStream)
            val output = tls.outputStream
            val hello = read(input)
            if (hello.command != CNXN) throw IOException("Expected CNXN, got ${name(hello.command)}")
            tls.soTimeout = OUTPUT_TIMEOUT_MS

            val local = 1
            send(output, OPEN, local, 0, "shell:$command\u0000".toByteArray())
            var remote = 0
            val out = StringBuilder()
            while (true) {
                val m = try { read(input) } catch (_: EOFException) { break }
                when (m.command) {
                    OKAY -> remote = m.arg0
                    WRTE -> {
                        out.append(String(m.data))
                        send(output, OKAY, local, m.arg0, ByteArray(0))
                    }
                    CLSE -> {
                        if (remote != 0) send(output, CLSE, local, remote, ByteArray(0))
                        break
                    }
                }
            }
            return Shell(out.toString(), seen)
        }
    }

    /** A command's [output], and the [fingerprint] of the adbd certificate it ran under. */
    class Shell(val output: String, val fingerprint: String)

    // -- pairing ------------------------------------------------------------------------------------------------

    private const val SPAKE2_MSG: Byte = 0
    private const val PEER_INFO: Byte = 1
    private const val PEER_INFO_SIZE = 8192

    private fun writePacket(out: OutputStream, type: Byte, payload: ByteArray) {
        val header = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN).put(1).put(type).putInt(payload.size).array()
        out.write(header + payload)
        out.flush()
    }

    private fun readPacket(input: DataInputStream, type: Byte): ByteArray {
        val header = ByteArray(6).also { input.readFully(it) }
        val b = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        val version = b.get()
        val t = b.get()
        val size = b.int
        if (version.toInt() != 1 || t != type || size <= 0 || size > 2 * PEER_INFO_SIZE) throw IOException("Bad pairing packet")
        return ByteArray(size).also { input.readFully(it) }
    }

    /** AES-128-GCM with the message number (little-endian, zero-padded to 12 bytes) as the nonce, each way. */
    private class PairingCipher(key: ByteArray) {
        private val k = SecretKeySpec(key, "AES")
        private var enc = 0L
        private var dec = 0L
        private fun nonce(n: Long) = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(n).array()
        fun encrypt(b: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run { init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(128, nonce(enc++))); doFinal(b) }
        fun decrypt(b: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run { init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, nonce(dec++))); doFinal(b) }
    }

    /** HKDF-SHA256 (RFC 5869) with no salt. */
    internal fun hkdf(ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(ByteArray(32), "HmacSHA256")); doFinal(ikm) }
        val out = ByteArray(length)
        var t = ByteArray(0)
        var done = 0
        var i = 1
        while (done < length) {
            t = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(prk, "HmacSHA256")); update(t); update(info); update(i.toByte()); doFinal() }
            val n = minOf(t.size, length - done)
            t.copyInto(out, done, 0, n)
            done += n
            i++
        }
        return out
    }

    // -- the adb protocol ------------------------------------------------------------------------------------

    private const val CNXN = 0x4e584e43
    private const val OPEN = 0x4e45504f
    private const val OKAY = 0x59414b4f
    private const val CLSE = 0x45534c43
    private const val WRTE = 0x45545257
    private const val STLS = 0x534c5453
    private const val VERSION = 0x01000001  // checksums may be left out
    private const val STLS_VERSION = 0x01000000
    private const val MAX_PAYLOAD = 1024 * 1024

    private class Message(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    private fun send(out: OutputStream, command: Int, arg0: Int, arg1: Int, data: ByteArray) {
        val b = ByteBuffer.allocate(24 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(command).putInt(arg0).putInt(arg1).putInt(data.size).putInt(0).putInt(command.inv()).put(data)
        out.write(b.array())
        out.flush()
    }

    private fun read(input: InputStream): Message {
        val header = ByteArray(24)
        (input as? DataInputStream ?: DataInputStream(input)).readFully(header)
        val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = b.int
        val arg0 = b.int
        val arg1 = b.int
        val length = b.int
        b.int  // checksum
        if (b.int != command.inv()) throw IOException("Bad adb message")
        if (length < 0 || length > MAX_PAYLOAD) throw IOException("adb message too long")
        val data = ByteArray(length)
        (input as? DataInputStream ?: DataInputStream(input)).readFully(data)
        return Message(command, arg0, arg1, data)
    }

    private fun name(command: Int) = String(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(command).array())
}
