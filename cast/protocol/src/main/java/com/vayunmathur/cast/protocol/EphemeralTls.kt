package com.vayunmathur.cast.protocol

import android.annotation.SuppressLint
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.net.InetAddress
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * TLS for the media proxy, pinned to the session instead of to a certificate authority.
 *
 * The phone mints a fresh self-signed certificate per session and sends its fingerprint to the
 * TV over the control channel, which is already AES-256-GCM under a key derived from the ML-KEM
 * handshake. The TV pins that fingerprint and trusts nothing else. That is stronger than PKI
 * here rather than weaker: a CA would only attest to a name, while the fingerprint arrives over
 * a channel the pairing already authenticated.
 *
 * It also avoids the two things a name-based scheme would have cost - a cleartext exemption in
 * the network security config, which `targetSdk 37` would otherwise require, and a certificate
 * that outlives the session it was made for.
 *
 * The certificate is assembled by hand out of `java.security` primitives. `AndroidKeyStore`
 * would generate one for us, but its keys cannot leave the device and it does not exist off it,
 * so the whole proxy would then be untestable without a TV in the room. Nothing here needs
 * hardware key storage: the key is thrown away when the session ends.
 */
object EphemeralTls {

    /** Server credentials plus the fingerprint the TV has to be told. */
    class ServerCredentials(
        val sslContext: SSLContext,
        val certificate: X509Certificate,
    ) {
        /** SHA-256 over the certificate's DER encoding - what the TV pins. */
        val fingerprint: ByteArray get() = fingerprintOf(certificate)
    }

    /**
     * A certificate is valid from a day ago because the two ends do not share a clock.
     *
     * A TV that has just been unplugged for a week can come up minutes or hours behind. Our own
     * trust manager pins a fingerprint and never looks at the dates, but anything else in the
     * stack that does look would reject a certificate stamped in what it believes is the future.
     */
    private val BACKDATE = 1L to ChronoUnit.DAYS

    /** Long enough for any session, short enough that a leaked key is worth little. */
    private val LIFETIME = 7L to ChronoUnit.DAYS

    private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

    /** ecdsa-with-SHA256. */
    private const val OID_ECDSA_SHA256 = "1.2.840.10045.4.3.2"

    /** id-at-commonName. */
    private const val OID_COMMON_NAME = "2.5.4.3"

    /** id-ce-subjectAltName. */
    private const val OID_SUBJECT_ALT_NAME = "2.5.29.17"

    /** id-ce-extKeyUsage. */
    private const val OID_EXT_KEY_USAGE = "2.5.29.37"

    /** id-kp-serverAuth. */
    private const val OID_SERVER_AUTH = "1.3.6.1.5.5.7.3.1"

    /**
     * Mints a key pair and a self-signed certificate covering [addresses].
     *
     * The addresses go in as `iPAddress` subject alternative names because the TV reaches the
     * phone by address, not by name. Without them the certificate would be trusted by our pin
     * and then rejected by the hostname check that runs afterwards, which is a confusing way to
     * fail and one no amount of pinning fixes.
     */
    fun server(addresses: List<InetAddress>): ServerCredentials {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        val keyPair = generator.generateKeyPair()

        val now = Instant.now()
        val tbs = tbsCertificate(
            publicKeyInfo = keyPair.public.encoded,
            commonName = "Modern Apps cast",
            addresses = addresses,
            notBefore = now.minus(BACKDATE.first, BACKDATE.second),
            notAfter = now.plus(LIFETIME.first, LIFETIME.second),
        )

        val signer = Signature.getInstance(SIGNATURE_ALGORITHM)
        signer.initSign(keyPair.private)
        signer.update(tbs)
        val signature = signer.sign()

        val der = Der.sequence(tbs, algorithmIdentifier(), Der.bitString(signature))
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(der)) as X509Certificate

        // PKCS12 rather than JKS: JKS is not a type Android's provider offers, and the store
        // never leaves memory so the password is a formality the API insists on.
        val password = CharArray(32) { (SecureRandom().nextInt(26) + 'a'.code).toChar() }
        val store = KeyStore.getInstance("PKCS12")
        store.load(null, null)
        store.setKeyEntry("cast-proxy", keyPair.private, password, arrayOf(certificate))

        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(store, password) }
            .keyManagers
        password.fill('\u0000')

        val context = SSLContext.getInstance("TLS")
        context.init(keyManagers, null, SecureRandom())
        return ServerCredentials(context, certificate)
    }

    /**
     * A client context that trusts exactly one certificate, by fingerprint.
     *
     * Not a permissive trust manager. The control channel exists so that this end can be told
     * precisely which certificate to expect, and accepting any certificate would throw away the
     * only guarantee the pairing bought.
     */
    fun client(fingerprint: ByteArray): SSLContext {
        val pinned = PinnedTrustManager(fingerprint.copyOf())
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<javax.net.ssl.TrustManager>(pinned), SecureRandom())
        return context
    }

    fun fingerprintOf(certificate: X509Certificate): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(certificate.encoded)

    // CustomX509TrustManager suppressed with justification: this is not a
    // permissive trust manager. checkServerTrusted accepts exactly one leaf
    // whose SHA-256 fingerprint equals the session pin (constant-time
    // MessageDigest.isEqual) and throws CertificateException otherwise;
    // checkClientTrusted always throws and no issuers are accepted. The pin
    // arrives over the pairing-authenticated, AES-256-GCM control channel, so
    // certificate validation is strengthened, not disabled. Wire format and
    // crypto behaviour are unchanged.
    @SuppressLint("CustomX509TrustManager")
    private class PinnedTrustManager(private val fingerprint: ByteArray) : X509TrustManager {

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("no certificate offered")
            // The chain is irrelevant beyond its leaf: there is no issuer to walk up to, and
            // the pin already says which single certificate is acceptable.
            if (!MessageDigest.isEqual(fingerprint, fingerprintOf(leaf))) {
                throw CertificateException("certificate does not match the pinned session fingerprint")
            }
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            // The proxy never asks for a client certificate, so being asked to check one means
            // this context is being used for something it was not built for.
            throw CertificateException("the media proxy does not authenticate clients by certificate")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    // ------------------------------------------------------------------
    // Certificate assembly
    // ------------------------------------------------------------------

    private fun algorithmIdentifier(): ByteArray = Der.sequence(Der.oid(OID_ECDSA_SHA256))

    private fun tbsCertificate(
        publicKeyInfo: ByteArray,
        commonName: String,
        addresses: List<InetAddress>,
        notBefore: Instant,
        notAfter: Instant,
    ): ByteArray {
        // 20 bytes, forced positive: a negative serial number is malformed and some parsers say
        // so only by failing to read the certificate at all.
        val serial = BigInteger(159, SecureRandom()).add(BigInteger.ONE)
        val name = Der.sequence(Der.set(Der.sequence(Der.oid(OID_COMMON_NAME), Der.utf8(commonName))))

        val extensions = buildList {
            if (addresses.isNotEmpty()) {
                val names = addresses.map { Der.tagged(0x87, it.address) }
                add(extension(OID_SUBJECT_ALT_NAME, Der.sequence(*names.toTypedArray())))
            }
            add(extension(OID_EXT_KEY_USAGE, Der.sequence(Der.oid(OID_SERVER_AUTH))))
        }

        return Der.sequence(
            // v3, which is what having extensions at all requires.
            Der.explicit(0, Der.integer(2)),
            Der.integer(serial),
            algorithmIdentifier(),
            name,
            Der.sequence(Der.utcTime(notBefore), Der.utcTime(notAfter)),
            // Issuer and subject are the same name: this certificate signs itself.
            name,
            publicKeyInfo,
            Der.explicit(Der.EXTENSIONS_TAG, Der.sequence(*extensions.toTypedArray())),
        )
    }

    private fun extension(oid: String, value: ByteArray): ByteArray =
        Der.sequence(Der.oid(oid), Der.octetString(value))

    /**
     * Just enough DER to write one certificate.
     *
     * A public key already arrives as a DER `SubjectPublicKeyInfo` from [java.security.Key.getEncoded],
     * so it is embedded verbatim and none of the elliptic-curve parameter encoding has to be
     * written out here.
     */
    private object Der {

        private const val TAG_SEQUENCE = 0x30
        private const val TAG_SET = 0x31
        private const val TAG_INTEGER = 0x02
        private const val TAG_BIT_STRING = 0x03
        private const val TAG_OCTET_STRING = 0x04
        private const val TAG_OID = 0x06
        private const val TAG_UTF8_STRING = 0x0C
        private const val TAG_UTC_TIME = 0x17
        private const val TAG_EXPLICIT_BASE = 0xA0
        private const val LONG_FORM_FLAG = 0x80
        private const val SHORT_FORM_LIMIT = 0x80
        private const val FIRST_ARC_FACTOR = 40
        private const val SEPTET_MASK = 0x7fL
        private const val SEPTET_BITS = 7
        private const val CONTINUATION_BIT = 0x80
        private const val BYTE_MASK = 0xff
        private const val BYTE_SHIFT = 8
        const val EXTENSIONS_TAG = 3

        fun tagged(tag: Int, content: ByteArray): ByteArray =
            byteArrayOf(tag.toByte()) + length(content.size) + content

        fun sequence(vararg parts: ByteArray): ByteArray = tagged(TAG_SEQUENCE, concat(parts))

        fun set(vararg parts: ByteArray): ByteArray = tagged(TAG_SET, concat(parts))

        /** Context-specific, constructed - the `[n] EXPLICIT` of the X.509 definitions. */
        fun explicit(number: Int, content: ByteArray): ByteArray =
            tagged(TAG_EXPLICIT_BASE or number, content)

        fun integer(value: Int): ByteArray = integer(BigInteger.valueOf(value.toLong()))

        /** [BigInteger.toByteArray] is already two's-complement big-endian, which is DER's INTEGER. */
        fun integer(value: BigInteger): ByteArray = tagged(TAG_INTEGER, value.toByteArray())

        /** The leading zero is the count of unused bits in the final byte, always none here. */
        fun bitString(bytes: ByteArray): ByteArray = tagged(TAG_BIT_STRING, byteArrayOf(0) + bytes)

        fun octetString(bytes: ByteArray): ByteArray = tagged(TAG_OCTET_STRING, bytes)

        fun utf8(text: String): ByteArray = tagged(TAG_UTF8_STRING, text.toByteArray(Charsets.UTF_8))

        /** UTCTime, which X.509 requires for anything before 2050. */
        fun utcTime(instant: Instant): ByteArray {
            val time = instant.atOffset(ZoneOffset.UTC)
            val text = "%02d%02d%02d%02d%02d%02dZ".format(
                time.year % 100,
                time.monthValue,
                time.dayOfMonth,
                time.hour,
                time.minute,
                time.second,
            )
            return tagged(TAG_UTC_TIME, text.toByteArray(Charsets.US_ASCII))
        }

        /** The first two arcs share a byte; the rest are base-128 with a continuation bit. */
        fun oid(dotted: String): ByteArray {
            val arcs = dotted.split('.').map { it.toLong() }
            val out = ArrayList<Byte>()
            out.add((arcs[0] * FIRST_ARC_FACTOR + arcs[1]).toByte())
            for (arc in arcs.drop(2)) {
                val septets = ArrayList<Byte>()
                var value = arc
                do {
                    septets.add(0, (value and SEPTET_MASK).toByte())
                    value = value ushr SEPTET_BITS
                } while (value > 0)
                for (i in septets.indices) {
                    val last = i == septets.size - 1
                    out.add(if (last) septets[i] else (septets[i].toInt() or CONTINUATION_BIT).toByte())
                }
            }
            return tagged(TAG_OID, out.toByteArray())
        }

        private fun length(size: Int): ByteArray {
            if (size < SHORT_FORM_LIMIT) return byteArrayOf(size.toByte())
            val bytes = ArrayList<Byte>()
            var value = size
            while (value > 0) {
                bytes.add(0, (value and BYTE_MASK).toByte())
                value = value ushr BYTE_SHIFT
            }
            return byteArrayOf((LONG_FORM_FLAG or bytes.size).toByte()) + bytes.toByteArray()
        }

        private fun concat(parts: Array<out ByteArray>): ByteArray {
            val out = ByteArray(parts.sumOf { it.size })
            var at = 0
            for (part in parts) {
                part.copyInto(out, at)
                at += part.size
            }
            return out
        }
    }
}
