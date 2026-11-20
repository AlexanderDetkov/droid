package com.adetkov.droid.adb

import android.annotation.SuppressLint
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

/**
 * Droid's identity towards adbd: an RSA-2048 key and a self-signed certificate for it, in the app's private files.
 * Pairing hands adbd the public key; every later connection presents the certificate in its TLS handshake.
 */
class AdbKey private constructor(val privateKey: PrivateKey, val cert: X509Certificate) {
    val publicKey get() = cert.publicKey as RSAPublicKey

    /** The public key as adb writes it (android_pubkey_encode, base64), then " [name]" and a NUL: what pairing sends. */
    fun adbPublicKey(name: String): ByteArray {
        val n = publicKey.modulus
        val words = MODULUS_BYTES / 4
        val b = ByteBuffer.allocate(3 * 4 + 2 * MODULUS_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(words)
        val r32 = BigInteger.ONE.shiftLeft(32)
        b.putInt((r32 - n.mod(r32).modInverse(r32)).toInt())  // n0inv = -1 / n[0] mod 2^32
        b.put(Spake2.toLe(n, MODULUS_BYTES))
        b.put(Spake2.toLe(BigInteger.ONE.shiftLeft(MODULUS_BYTES * 8).modPow(BigInteger.TWO, n), MODULUS_BYTES))  // R^2 mod n
        b.putInt(publicKey.publicExponent.toInt())
        return Base64.getEncoder().encode(b.array()) + " $name\u0000".toByteArray()
    }

    /**
     * TLS for adbd: presents our certificate, and accepts a server certificate only if [pin] says so (null: any, which
     * only pairing uses, where the pairing code itself authenticates the server through the exported key).
     */
    fun sslContext(pin: ((X509Certificate) -> Boolean)? = null): SSLContext {
        val km = object : X509ExtendedKeyManager() {
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
            override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
            override fun getCertificateChain(alias: String?) = arrayOf(cert)
            override fun getPrivateKey(alias: String?) = privateKey
        }
        val tm = PinningTrustManager(pin)
        return SSLContext.getInstance("TLSv1.3").apply { init(arrayOf(km), arrayOf(tm), SecureRandom()) }
    }

    /** adbd's certificates are self-signed, so there is no chain to check: the server is trusted by its key alone. */
    @SuppressLint("CustomX509TrustManager")
    private class PinningTrustManager(private val pin: ((X509Certificate) -> Boolean)?) : X509ExtendedTrustManager() {
        private fun check(chain: Array<out X509Certificate>?) {
            val leaf = chain?.firstOrNull() ?: throw java.security.cert.CertificateException("No server certificate")
            if (pin != null && !pin(leaf)) throw java.security.cert.CertificateException("Not this phone's adbd")
        }
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw UnsupportedOperationException()
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = throw UnsupportedOperationException()
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = throw UnsupportedOperationException()
        override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
    }

    companion object {
        private const val ALIAS = "droid"
        private const val MODULUS_BYTES = 256

        /** The key in [dir], made on first use. */
        @Synchronized
        fun load(dir: File): AdbKey {
            val keyFile = File(dir, "adbkey.pk8")
            val certFile = File(dir, "adbkey.crt")
            if (keyFile.exists() && certFile.exists()) {
                val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                val cert = CertificateFactory.getInstance("X.509").generateCertificate(certFile.inputStream()) as X509Certificate
                return AdbKey(key, cert)
            }
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val certDer = selfSigned(pair.public.encoded, pair.private, "Droid")
            dir.mkdirs()
            fun put(f: File, b: ByteArray) = File(f.path + ".new").apply { writeBytes(b) }.renameTo(f)
            put(certFile, certDer)
            put(keyFile, pair.private.encoded)
            return AdbKey(pair.private, CertificateFactory.getInstance("X.509").generateCertificate(certDer.inputStream()) as X509Certificate)
        }

        /** A minimal X.509 v3 certificate for [spki], signed by its own key (SHA-256 with RSA), valid until 2049. */
        internal fun selfSigned(spki: ByteArray, key: PrivateKey, cn: String): ByteArray {
            val sha256Rsa = seq(oid(byteArrayOf(0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 1, 1, 11)), byteArrayOf(5, 0))
            val name = seq(tlv(0x31, seq(oid(byteArrayOf(0x55, 4, 3)), tlv(0x0c, cn.toByteArray()))))
            val utc = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            val validity = seq(tlv(0x17, utc.format(Date(System.currentTimeMillis() - 86_400_000)).toByteArray()), tlv(0x17, "491231235959Z".toByteArray()))
            val serial = BigInteger(64, SecureRandom()).toByteArray()
            val tbs = seq(tlv(0xa0, tlv(2, byteArrayOf(2))), tlv(2, serial), sha256Rsa, name, validity, name, spki)
            val sig = Signature.getInstance("SHA256withRSA").apply { initSign(key); update(tbs) }.sign()
            return seq(tbs, sha256Rsa, tlv(3, byteArrayOf(0) + sig))
        }

        private fun oid(body: ByteArray) = tlv(6, body)
        private fun seq(vararg parts: ByteArray) = tlv(0x30, parts.fold(ByteArray(0)) { a, b -> a + b })

        /** DER: tag, definite length, value. */
        private fun tlv(tag: Int, value: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(tag)
            val n = value.size
            when {
                n < 0x80 -> out.write(n)
                n < 0x100 -> { out.write(0x81); out.write(n) }
                n < 0x10000 -> { out.write(0x82); out.write(n shr 8); out.write(n and 0xff) }
                else -> { out.write(0x83); out.write(n shr 16); out.write((n shr 8) and 0xff); out.write(n and 0xff) }
            }
            out.write(value)
            return out.toByteArray()
        }

        /** SHA-256 of a certificate's public key: how Droid remembers which adbd it paired with. */
        fun fingerprint(cert: X509Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded).joinToString("") { "%02x".format(it) }
    }
}
