package com.vayunmathur.share.network.airdrop

import android.annotation.SuppressLint
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager

/**
 * TLS for AirDrop's HTTPS.
 *
 * AirDrop in "Everyone" mode authenticates nobody at the TLS layer: Mosey's server presents a
 * fresh self-signed RSA-2048 certificate and its client trusts any certificate (`cea`, `cev`).
 * Apple peers do the same with their own self-signed identities, so the trust-all client here
 * is the protocol, not a shortcut — sender identity is what the user approves in the Ask.
 */
object AirDropTls {

    private const val VALIDITY_MS = 365L * 24 * 60 * 60 * 1000
    private const val BACKDATE_MS = 60L * 60 * 1000
    private const val KEY_ALIAS = "selfsigned"
    val PROTOCOLS = arrayOf("TLSv1.3", "TLSv1.2")

    /** Server context with a newly minted self-signed identity. */
    fun serverContext(commonName: String = "AirDrop"): SSLContext {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val cert = selfSignedCertificate(keyPair, commonName)
        val password = CharArray(0)
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry(KEY_ALIAS, keyPair.private, password, arrayOf(cert))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, password) }
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, SecureRandom()) }
    }

    fun clientContext(): SSLContext =
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(TrustAll), SecureRandom()) }

    /** Accepts any peer certificate; see the object doc for why that is the protocol. */
    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private object TrustAll : X509ExtendedTrustManager() {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkClientTrusted(
            chain: Array<out X509Certificate>?,
            authType: String?,
            socket: Socket?,
        ) = Unit

        override fun checkServerTrusted(
            chain: Array<out X509Certificate>?,
            authType: String?,
            socket: Socket?,
        ) = Unit

        override fun checkClientTrusted(
            chain: Array<out X509Certificate>?,
            authType: String?,
            engine: SSLEngine?,
        ) = Unit

        override fun checkServerTrusted(
            chain: Array<out X509Certificate>?,
            authType: String?,
            engine: SSLEngine?,
        ) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /** A minimal X.509 v3 certificate, DER-encoded by hand and signed SHA256withRSA. */
    fun selfSignedCertificate(
        keyPair: KeyPair,
        commonName: String,
        now: Long = System.currentTimeMillis(),
    ): X509Certificate {
        val sigAlg = Der.seq(Der.oid(SHA256_WITH_RSA), Der.NULL)
        val name = Der.seq(Der.set(Der.seq(Der.oid(COMMON_NAME), Der.utf8(commonName))))
        val tbs = Der.seq(
            Der.explicit(0, Der.int(BigInteger.valueOf(2))),
            Der.int(BigInteger(127, SecureRandom()).add(BigInteger.ONE)),
            sigAlg,
            name,
            Der.seq(Der.utcTime(now - BACKDATE_MS), Der.utcTime(now + VALIDITY_MS)),
            name,
            keyPair.public.encoded,
        )
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(keyPair.private)
            update(tbs)
            sign()
        }
        val der = Der.seq(tbs, sigAlg, Der.bitString(signature))
        return CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
    }

    private val SHA256_WITH_RSA = intArrayOf(1, 2, 840, 113549, 1, 1, 11)
    private val COMMON_NAME = intArrayOf(2, 5, 4, 3)

    private object Der {
        // DER tag octets (ITU-T X.690 universal / context-specific tags used below).
        private const val TAG_INTEGER = 0x02
        private const val TAG_BIT_STRING = 0x03
        private const val TAG_NULL = 0x05
        private const val TAG_OBJECT_IDENTIFIER = 0x06
        private const val TAG_UTF8_STRING = 0x0C
        private const val TAG_UTC_TIME = 0x17
        private const val TAG_SEQUENCE = 0x30
        private const val TAG_SET = 0x31
        private const val TAG_EXPLICIT_BASE = 0xA0

        // OID arc encoding: first two arcs pack as arc0 * 40 + arc1; later arcs are
        // base-128 with the high bit as a continuation flag.
        private const val OID_FIRST_ARC_FACTOR = 40
        private const val VARINT_DATA_MASK = 0x7F
        private const val VARINT_CONTINUATION_BIT = 0x80
        private const val VARINT_SHIFT_BITS = 7

        // DER length encoding thresholds / tags and byte extraction.
        private const val LENGTH_SHORT_FORM_LIMIT = 0x80
        private const val LENGTH_ONE_BYTE_TAG = 0x81
        private const val LENGTH_ONE_BYTE_MAX = 0xFF
        private const val LENGTH_TWO_BYTE_TAG = 0x82
        private const val LENGTH_TWO_BYTE_MAX = 0xFFFF
        private const val LENGTH_THREE_BYTE_TAG = 0x83
        private const val BYTE_MASK = 0xFF
        private const val BYTE_SHIFT_BITS = 8
        private const val LENGTH_THREE_BYTE_HIGH_SHIFT = 16

        val NULL = byteArrayOf(TAG_NULL.toByte(), 0x00)

        fun seq(vararg parts: ByteArray) = tlv(TAG_SEQUENCE, concat(parts))
        fun set(vararg parts: ByteArray) = tlv(TAG_SET, concat(parts))
        fun explicit(tag: Int, inner: ByteArray) = tlv(TAG_EXPLICIT_BASE or tag, inner)
        fun int(v: BigInteger) = tlv(TAG_INTEGER, v.toByteArray())
        fun utf8(s: String) = tlv(TAG_UTF8_STRING, s.toByteArray(Charsets.UTF_8))
        fun bitString(bytes: ByteArray) = tlv(TAG_BIT_STRING, byteArrayOf(0) + bytes)

        fun utcTime(millis: Long): ByteArray {
            val fmt = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            return tlv(TAG_UTC_TIME, fmt.format(Date(millis)).toByteArray(Charsets.US_ASCII))
        }

        fun oid(arcs: IntArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(arcs[0] * OID_FIRST_ARC_FACTOR + arcs[1])
            for (arc in arcs.drop(2)) {
                val stack = ArrayDeque<Int>()
                var v = arc
                stack.addFirst(v and VARINT_DATA_MASK)
                v = v ushr VARINT_SHIFT_BITS
                while (v > 0) {
                    stack.addFirst((v and VARINT_DATA_MASK) or VARINT_CONTINUATION_BIT)
                    v = v ushr VARINT_SHIFT_BITS
                }
                stack.forEach(out::write)
            }
            return tlv(TAG_OBJECT_IDENTIFIER, out.toByteArray())
        }

        private fun tlv(tag: Int, value: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(tag)
            val len = value.size
            when {
                len < LENGTH_SHORT_FORM_LIMIT -> out.write(len)
                len <= LENGTH_ONE_BYTE_MAX -> {
                    out.write(LENGTH_ONE_BYTE_TAG)
                    out.write(len)
                }
                len <= LENGTH_TWO_BYTE_MAX -> {
                    out.write(LENGTH_TWO_BYTE_TAG)
                    out.write(len ushr BYTE_SHIFT_BITS)
                    out.write(len and BYTE_MASK)
                }
                else -> {
                    out.write(LENGTH_THREE_BYTE_TAG)
                    out.write(len ushr LENGTH_THREE_BYTE_HIGH_SHIFT)
                    out.write((len ushr BYTE_SHIFT_BITS) and BYTE_MASK)
                    out.write(len and BYTE_MASK)
                }
            }
            out.write(value)
            return out.toByteArray()
        }

        private fun concat(parts: Array<out ByteArray>): ByteArray {
            val out = ByteArrayOutputStream()
            parts.forEach(out::write)
            return out.toByteArray()
        }
    }
}
