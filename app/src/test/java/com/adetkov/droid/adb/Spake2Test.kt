package com.adetkov.droid.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigInteger

class Spake2Test {
    private val client = "adb pair client\u0000".toByteArray()
    private val server = "adb pair server\u0000".toByteArray()

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test fun basePointRoundTrips() {
        val b = Spake2.Point.B
        assertEquals("5866666666666666666666666666666666666666666666666666666666666666", hex(b.encode()))
        assertEquals(b, Spake2.Point.decode(b.encode()))
        // l·B is the identity: B generates the prime-order subgroup.
        assertEquals(Spake2.Point.IDENTITY, b * Spake2.L)
    }

    @Test fun maskPointsMatchBoringSsl() {
        // The encodings spake25519.c lists for its N and M.
        assertEquals("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778", hex(Spake2.N.encode()))
        assertEquals("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e", hex(Spake2.M.encode()))
    }

    @Test fun bothSidesAgree() {
        val password = "123456".toByteArray() + ByteArray(64) { it.toByte() }
        val alice = Spake2(true, client, server)
        val bob = Spake2(false, server, client)
        val a = alice.generateMessage(password)
        val b = bob.generateMessage(password)
        assertArrayEquals(alice.processMessage(b), bob.processMessage(a))
    }

    @Test fun wrongPasswordDisagrees() {
        val alice = Spake2(true, client, server)
        val bob = Spake2(false, server, client)
        val a = alice.generateMessage("123456".toByteArray())
        val b = bob.generateMessage("123457".toByteArray())
        assert(!alice.processMessage(b).contentEquals(bob.processMessage(a)))
    }

    @Test fun fixedRandomness() {
        // The private keys from BoringSSL's spake25519_test.cc, with a password the shape adb uses (code + exported key).
        val password = hex("353932373831E63DD959651C211600F3B6561D0B9D90AF09D0A4A453EE2059A480CC7C5A94D4D48933F9FFF5FE43317D52FA7BFF8F8BC4F3488B8007330FEC7C7EDC91C20E5D")
        val alice = Spake2(true, client, server)
        val bob = Spake2(false, server, client)
        val a = alice.generateMessage(password, hex("47f6c458e5f062db8427d2d9bb20c954a76d6943959756a18d11d45e1ad190f980a86d185a93ca1d3025c5febe3aac4045b34a39b1f511385ca97fc4332137f3"))
        val b = bob.generateMessage(password, hex("a6bf9f9bf7819e0ded8c2dd82a1aa38acb2f8a6403429cff33d64ea9c40439d5fd7029811a5f5a8f7c89c8b44ac0b421f6b24ca2ba18d2069995831730cd8c5a"))
        println("alice_msg ${hex(a)}\nbob_msg ${hex(b)}")
        val ka = alice.processMessage(b)
        println("alice_key ${hex(ka)}")
        assertArrayEquals(ka, bob.processMessage(a))
    }

    @Test fun rejectsNonPoints() {
        // y = 2 has no x on the curve.
        assertNull(Spake2.Point.decode(Spake2.toLe(BigInteger.TWO)))
    }
}
