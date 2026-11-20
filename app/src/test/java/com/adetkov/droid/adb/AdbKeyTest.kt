package com.adetkov.droid.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

class AdbKeyTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    @Test fun certificateIsSelfSignedAndKept() {
        val key = AdbKey.load(tmp.root)
        key.cert.checkValidity()
        key.cert.verify(key.publicKey)  // signed by its own key
        assertEquals("CN=Droid", key.cert.subjectX500Principal.name)
        val again = AdbKey.load(tmp.root)
        assertArrayEquals(key.cert.encoded, again.cert.encoded)
        assertEquals(AdbKey.fingerprint(key.cert), AdbKey.fingerprint(again.cert))
    }

    @Test fun publicKeyInAdbsFormat() {
        val key = AdbKey.load(tmp.root)
        val text = String(key.adbPublicKey("droid@test"))
        assertTrue(text.endsWith(" droid@test\u0000"))
        val raw = Base64.getDecoder().decode(text.substringBefore(' '))
        assertEquals(3 * 4 + 2 * 256, raw.size)
        val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(64, b.int)  // modulus size in 32-bit words
        val n0inv = b.int.toLong() and 0xffffffffL
        val n = BigInteger(1, ByteArray(256).also { b.get(it) }.reversedArray())
        assertEquals(key.publicKey.modulus, n)
        // n0inv * n[0] = -1 (mod 2^32)
        assertEquals(0xffffffffL, (n0inv * (n.toLong() and 0xffffffffL)) and 0xffffffffL)
        val rr = BigInteger(1, ByteArray(256).also { b.get(it) }.reversedArray())
        assertEquals(BigInteger.ONE.shiftLeft(4096).mod(n), rr)
        assertEquals(key.publicKey.publicExponent.toInt(), b.int)
    }

    @Test fun hkdfMatchesRfc5869() {
        // RFC 5869, test case 3: SHA-256, no salt, no info.
        assertArrayEquals(
            hex("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8"),
            Adb.hkdf(ByteArray(22) { 0x0b }, ByteArray(0), 42),
        )
    }
}
