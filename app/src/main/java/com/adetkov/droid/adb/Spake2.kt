package com.adetkov.droid.adb

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * SPAKE2 over edwards25519, as BoringSSL implements it (crypto/curve25519/spake25519.c), which adbd pairs with
 * (adb/pairing_auth). Plain BigInteger arithmetic in affine coordinates: pairing runs it once, so speed doesn't matter,
 * and the code stays short enough to check against the C.
 *
 * [alice] is the side that masks with M (adb's client); [myName] and [theirName] go into the transcript as given.
 */
internal class Spake2(private val alice: Boolean, private val myName: ByteArray, private val theirName: ByteArray) {
    private var privateKey = BigInteger.ZERO
    private var passwordScalar = BigInteger.ZERO
    private var passwordHash = ByteArray(0)
    private var myMsg = ByteArray(0)

    /** Our 32-byte message. [random] is 64 bytes of randomness; tests pass a fixed one. */
    fun generateMessage(password: ByteArray, random: ByteArray = ByteArray(64).also { SecureRandom().nextBytes(it) }): ByteArray {
        require(random.size == 64)
        // Reduced mod l, then times the cofactor 8, so that the peer's point loses any small-order part later.
        privateKey = le(random).mod(L).shiftLeft(3)
        passwordHash = MessageDigest.getInstance("SHA-512").digest(password)
        // BoringSSL's "password scalar hack": h(password) mod l, plus multiples of l until it is also a multiple of 8.
        var s = le(passwordHash).mod(L)
        if (s.testBit(0)) s += L
        if (s.testBit(1)) s += L.shiftLeft(1)
        if (s.testBit(2)) s += L.shiftLeft(2)
        passwordScalar = s
        myMsg = (Point.B * privateKey + (if (alice) M else N) * passwordScalar).encode()
        return myMsg.copyOf()
    }

    /** The 64-byte shared key, or an exception if [theirMsg] isn't a point on the curve. */
    fun processMessage(theirMsg: ByteArray): ByteArray {
        require(theirMsg.size == 32 && myMsg.isNotEmpty())
        val qStar = Point.decode(theirMsg) ?: throw IllegalArgumentException("The peer's SPAKE2 message is not a curve point")
        val q = qStar + -((if (alice) N else M) * passwordScalar)
        val dh = (q * privateKey).encode()
        val sha = MessageDigest.getInstance("SHA-512")
        fun put(b: ByteArray) {
            sha.update(ByteArray(8) { i -> (b.size.toLong() ushr (8 * i)).toByte() })  // length, 64-bit little-endian
            sha.update(b)
        }
        if (alice) { put(myName); put(theirName); put(myMsg); put(theirMsg) } else { put(theirName); put(myName); put(theirMsg); put(myMsg) }
        put(dh)
        put(passwordHash)
        return sha.digest()
    }

    /** A point (x, y) on edwards25519: -x² + y² = 1 + d·x²·y² over GF(2²⁵⁵ − 19). */
    class Point(val x: BigInteger, val y: BigInteger) {
        // The twisted Edwards addition law with a = -1; complete on this curve, so it also doubles.
        operator fun plus(o: Point): Point {
            val t = D * x * o.x % P * y % P * o.y % P
            val x3 = (x * o.y + y * o.x) % P * inv(BigInteger.ONE + t) % P
            val y3 = (y * o.y + x * o.x) % P * inv(BigInteger.ONE - t) % P
            return Point(x3, y3)
        }

        operator fun unaryMinus() = Point((P - x).mod(P), y)

        operator fun times(k: BigInteger): Point {
            var r = IDENTITY
            for (i in k.bitLength() - 1 downTo 0) {
                r += r
                if (k.testBit(i)) r += this
            }
            return r
        }

        /** y little-endian, with x's low bit in the top bit. */
        fun encode(): ByteArray {
            val out = toLe(y.mod(P))
            if (x.mod(P).testBit(0)) out[31] = (out[31].toInt() or 0x80).toByte()
            return out
        }

        override fun equals(other: Any?) = other is Point && x.mod(P) == other.x.mod(P) && y.mod(P) == other.y.mod(P)
        override fun hashCode() = y.hashCode()

        companion object {
            val IDENTITY = Point(BigInteger.ZERO, BigInteger.ONE)
            val B = Point(
                BigInteger("15112221349535400772501151409588531511454012693041857206046113283949847762202"),
                BigInteger("46316835694926478169428394003475163141307993866256225615783033603165251855960"),
            )

            /** The point [s] encodes, or null if there is none (as ge_frombytes_vartime and ed25519.py's decodepoint). */
            fun decode(s: ByteArray): Point? {
                require(s.size == 32)
                val y = le(s.copyOf().also { it[31] = (it[31].toInt() and 0x7f).toByte() }).mod(P)
                val u = (y * y - BigInteger.ONE).mod(P)
                val v = (D * y % P * y + BigInteger.ONE).mod(P)
                val xx = u * inv(v) % P
                var x = xx.modPow((P + BigInteger.valueOf(3)).shiftRight(3), P)
                if ((x * x - xx).mod(P).signum() != 0) x = x * SQRT_M1 % P
                if ((x * x - xx).mod(P).signum() != 0) return null
                if (x.testBit(0) != (s[31].toInt() and 0x80 != 0)) x = (P - x).mod(P)
                return Point(x, y)
            }

            /** BoringSSL's M and N: the first SHA-256 chain value from the seed that decodes as a point. */
            fun fromSeed(seed: String): Point {
                var v = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
                while (true) {
                    decode(v)?.let { return it }
                    v = MessageDigest.getInstance("SHA-256").digest(v)
                }
            }
        }
    }

    companion object {
        val P: BigInteger = BigInteger.ONE.shiftLeft(255) - BigInteger.valueOf(19)
        /** The order of the prime-order subgroup. */
        val L: BigInteger = BigInteger.ONE.shiftLeft(252) + BigInteger("27742317777372353535851937790883648493")
        private val D: BigInteger = (BigInteger.valueOf(-121665) * inv(BigInteger.valueOf(121666))).mod(P)
        private val SQRT_M1: BigInteger = BigInteger.TWO.modPow((P - BigInteger.ONE).shiftRight(2), P)
        val M: Point by lazy { Point.fromSeed("edwards25519 point generation seed (M)") }
        val N: Point by lazy { Point.fromSeed("edwards25519 point generation seed (N)") }

        private fun inv(a: BigInteger): BigInteger = a.mod(P).modInverse(P)

        /** Little-endian bytes as a non-negative integer. */
        fun le(b: ByteArray): BigInteger = BigInteger(1, b.reversedArray())

        fun toLe(n: BigInteger, size: Int = 32): ByteArray {
            val be = n.toByteArray()
            val out = ByteArray(size)
            for (i in 0 until minOf(size, be.size)) out[i] = be[be.size - 1 - i]
            return out
        }
    }
}
