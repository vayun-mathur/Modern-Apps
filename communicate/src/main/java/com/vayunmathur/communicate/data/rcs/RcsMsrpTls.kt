package com.vayunmathur.communicate.data.rcs

import android.content.Context
import com.vayunmathur.library.log.Log
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * MSRP-over-TLS identity + socket layer (RFC 4976, RFC 4572).
 *
 * UP transports negotiate `TCP/TLS/MSRP` (`msrps://` paths) with certificate
 * fingerprints in SDP (`a=fingerprint:SHA-256 <hex>`). There is no PKI on the
 * IMS PDN — both ends use self-signed certs and verify the peer against the
 * fingerprint from its SDP (RFC 4572 §5, RFC 4976 §3.1). This object owns:
 *
 * - Identity: one EC P-256 keypair + self-signed cert, generated once via
 *   the platform `KeyPairGenerator` and persisted as raw DER in a private
 *   app file (no KeyStore dance, no new dependency — the X.509 writer below
 *   is a minimal hand-rolled DER encoder for exactly this shape).
 * - Client: TLS over the IMS-PDN TCP socket ([RcsImsNetwork.createSocket]),
 *   verifying the peer cert fingerprint against the SDP-advertised value.
 * - Server: `SSLContext` with our cert for the listen side; client certs are
 *   requested and verified when the expected peer fingerprint is known
 *   (from their offer SDP), accepted-unverified otherwise (the MSRP layer
 *   still routes by To-Path, and chat payloads are MLS-encrypted anyway).
 *
 * Every entry point early-returns null when the dev gate is off and never
 * throws; callers fall back to plaintext `msrp://` (downgrade) or fail the
 * session per their policy.
 */
object RcsMsrpTls {
    private const val TAG = "RcsMsrpTls"

    /** Fingerprint hash named in SDP (RFC 4572 §5: `SHA-256`, uppercase). */
    const val FINGERPRINT_HASH = "SHA-256"

    private const val IDENTITY_DIR = "rcs-msrp-tls"
    private const val CERT_FILE = "identity.der"
    private const val KEY_FILE = "identity.pk8"
    private const val CERT_VALIDITY_DAYS = 10L
    private const val DAY_MS = 24 * 3600 * 1000L
    private const val YEAR_DAYS = 365
    private const val ASN1_SEQUENCE = 0x30
    private const val ASN1_SET = 0x31
    private const val ASN1_CONTEXT_BASE = 0xA0
    private const val ASN1_HIGH_BIT = 0x80
    private const val ASN1_INTEGER = 0x02
    private const val ASN1_OID = 0x06
    private const val ASN1_NULL = 0x05
    private const val ASN1_UTF8STRING = 0x0C
    private const val ASN1_UTCTIME = 0x17
    private const val ASN1_OCTETSTRING = 0x04
    private const val ASN1_BITSTRING = 0x03
    private const val ASN1_ZERO = 0x00
    private const val ASN1_SHORT_LEN_LIMIT = 128
    private const val BYTE_MASK = 0xFF
    private const val VARINT_MASK = 0x7F
    private const val VARINT_CONT = 0x80
    private const val VARINT_BITS = 7
    private const val BYTE_BITS = 8
    private const val YEAR_CENTURY = 100
    private const val SERIAL_BYTES = 9
    private const val OID_FIRST_FACTOR = 40

    /** Our TLS identity: DER cert bytes + fingerprint + key. */
    data class Identity(
        val certDer: ByteArray,
        val fingerprint: String,
        internal val privateKey: PrivateKey,
    ) {
        /** `a=fingerprint:SHA-256 <FP>` SDP line for offers/answers. */
        fun sdpLine(): String = "a=fingerprint:$FINGERPRINT_HASH $fingerprint"

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Identity) return false
            return fingerprint == other.fingerprint && certDer.contentEquals(other.certDer)
        }

        override fun hashCode(): Int = fingerprint.hashCode()
    }

    @Volatile
    private var cached: Identity? = null

    /**
     * Load-or-generate our TLS identity. Suspends on Dispatchers.IO (key
     * generation blocks). Null when the gate is off or generation failed —
     * callers offer plaintext `msrp://` instead.
     */
    suspend fun ensureIdentity(context: Context): Identity? = withContext(Dispatchers.IO) {
        if (!RcsFeature.enabled) return@withContext null
        cached?.let { return@withContext it }
        runCatching {
            val dir = File(context.filesDir, IDENTITY_DIR).apply { mkdirs() }
            val certFile = File(dir, CERT_FILE)
            val keyFile = File(dir, KEY_FILE)
            if (certFile.exists() && keyFile.exists()) {
                loadIdentity(certFile.readBytes(), keyFile.readBytes())?.let {
                    cached = it
                    return@runCatching it
                }
            }
            val fresh = generateIdentity() ?: return@runCatching null
            certFile.writeBytes(fresh.certDer)
            keyFile.writeBytes(fresh.privateKey.encoded)
            cached = fresh
            fresh
        }.getOrElse {
            Log.status(TAG, "TLS identity unavailable", it)
            null
        }
    }

    /**
     * TLS client socket to [host]:[port]: TCP via the IMS PDN, then TLS with
     * SNI-less handshake, verifying the peer cert fingerprint against
     * [expectedFingerprint] (RFC 4572 format, case-insensitive). Null when
     * the TCP leg, handshake, or fingerprint check fails.
     */
    suspend fun clientSocket(
        context: Context,
        host: String,
        port: Int,
        expectedFingerprint: String?,
        connectTimeoutMs: Int = 10_000,
    ): SSLSocket? = withContext(Dispatchers.IO) {
        if (!RcsFeature.enabled) return@withContext null
        if (expectedFingerprint.isNullOrBlank()) {
            Log.status(TAG, "No peer fingerprint — refusing TLS without pinning")
            return@withContext null
        }
        val identity = ensureIdentity(context) ?: return@withContext null
        val tcp = RcsImsNetwork.createSocket(context, host, port, connectTimeoutMs)
            ?: return@withContext null
        runCatching {
            val ssl = clientContext(identity).socketFactory
                .createSocket(tcp, host, port, true) as SSLSocket
            ssl.soTimeout = 0
            ssl.startHandshake()
            val peerCert = ssl.session.peerCertificates
                .firstOrNull() as? X509Certificate
                ?: throw javax.net.ssl.SSLPeerUnverifiedException("No peer cert")
            val actual = fingerprintOf(peerCert.encoded)
            if (!actual.equals(expectedFingerprint.trim(), ignoreCase = true)) {
                throw javax.net.ssl.SSLPeerUnverifiedException(
                    "MSRP fingerprint mismatch (SNI-less DANE-less pinning)",
                )
            }
            Log.status(TAG, "TLS client established to $host:$port (pinned)")
            ssl
        }.getOrElse {
            Log.status(TAG, "TLS client handshake failed", it)
            runCatching { tcp.close() }
            null
        }
    }

    /**
     * Server `SSLContext` presenting our cert for the listen side. Client
     * certs are requested (not required); verification against the expected
     * peer fingerprint happens per-socket in [verifyServerSide] because the
     * expected value is per-session, not per-context.
     */
    suspend fun serverContext(context: Context): SSLContext? = withContext(Dispatchers.IO) {
        if (!RcsFeature.enabled) return@withContext null
        val identity = ensureIdentity(context) ?: return@withContext null
        runCatching {
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            val ks = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType())
            ks.load(null, null)
            val cf = CertificateFactory.getInstance("X.509")
            val cert = cf.generateCertificate(identity.certDer.inputStream())
            ks.setKeyEntry("msrp", identity.privateKey, CharArray(0), arrayOf(cert))
            kmf.init(ks, CharArray(0))
            SSLContext.getInstance("TLS").apply {
                // Trust-all client certs at the TLS layer; per-session pinning
                // in verifyServerSide decides.
                init(kmf.keyManagers, arrayOf<TrustManager>(PermissiveTrustManager), SecureRandom())
            }
        }.getOrElse {
            Log.status(TAG, "TLS server context failed", it)
            null
        }
    }

    /**
     * Verify a server-side (accepted) socket's peer cert against
     * [expectedFingerprint]. True when it matches, or when [expectedFingerprint]
     * is blank (offer carried no fingerprint — accept unverified, matching the
     * plaintext-era behavior for non-TLS peers). False on mismatch.
     */
    fun verifyServerSide(socket: SSLSocket, expectedFingerprint: String?): Boolean {
        if (expectedFingerprint.isNullOrBlank()) return true
        return runCatching {
            val peerCert = socket.session.peerCertificates
                .firstOrNull() as? X509Certificate ?: return false
            fingerprintOf(peerCert.encoded)
                .equals(expectedFingerprint.trim(), ignoreCase = true)
        }.getOrDefault(false)
    }

    /** Parse an SDP `a=fingerprint:` line into (hash, value), or null. */
    fun parseFingerprint(sdp: String?): Pair<String, String>? {
        if (sdp.isNullOrBlank()) return null
        val m = Regex(
            "a=fingerprint:(\\S+)\\s+([0-9A-Fa-f:]+)",
            RegexOption.IGNORE_CASE,
        ).find(sdp) ?: return null
        val (hash, value) = m.destructured
        if (value.isBlank()) return null
        return hash to value.trim()
    }

    /** RFC 4572 fingerprint: uppercase colon-hex SHA-256 of the DER cert. */
    internal fun fingerprintOf(certDer: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(certDer)
        return digest.joinToString(":") { "%02X".format(it) }
    }

    private fun loadIdentity(certDer: ByteArray, keyPkcs8: ByteArray): Identity? {
        return runCatching {
            val kf = KeyFactory.getInstance("EC")
            val key = kf.generatePrivate(PKCS8EncodedKeySpec(keyPkcs8))
            Identity(certDer, fingerprintOf(certDer), key)
        }.getOrNull()
    }

    private fun clientContext(identity: Identity): SSLContext {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        val ks = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType())
        ks.load(null, null)
        val cf = CertificateFactory.getInstance("X.509")
        val cert = cf.generateCertificate(identity.certDer.inputStream())
        ks.setKeyEntry("msrp", identity.privateKey, CharArray(0), arrayOf(cert))
        kmf.init(ks, CharArray(0))
        return SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, arrayOf<TrustManager>(PermissiveTrustManager), SecureRandom())
        }
    }

    private object PermissiveTrustManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    // -- Minimal X.509 writer (self-signed EC P-256, SHA256withECDSA) --

    private fun generateIdentity(): Identity? {
        return runCatching {
            val kpg = KeyPairGenerator.getInstance("EC")
            kpg.initialize(java.security.spec.ECGenParameterSpec("secp256r1"), SecureRandom())
            val kp = kpg.generateKeyPair()
            val now = System.currentTimeMillis()
            val certDer = selfSignedCert(
                publicKey = kp.public.encoded,
                privateKey = kp.private,
                notBeforeMs = now - DAY_MS,
                notAfterMs = now + CERT_VALIDITY_DAYS * YEAR_DAYS * DAY_MS,
                // IMS-PDN IPs change across attaches; a stable pseudonymous CN
                // is honest (RFC 4572 identity comes from the fingerprint,
                // not the subject).
                commonName = "rcs-msrp-tls",
            )
            Identity(certDer, fingerprintOf(certDer), kp.private)
        }.getOrElse {
            Log.status(TAG, "TLS identity generation failed", it)
            null
        }
    }

    /**
     * Build a v3 self-signed cert DER for [publicKey] (X.509 SubjectPublicKeyInfo
     * DER), signed by [privateKey] (EC). Minimal encoder: SEQUENCE-heavy,
     * length-prefixed — sized for this exact shape (no extensions).
     */
    internal fun selfSignedCert(
        publicKey: ByteArray,
        privateKey: PrivateKey,
        notBeforeMs: Long,
        notAfterMs: Long,
        commonName: String,
    ): ByteArray {
        // 1.2.840.10045.4.3.2 = ecdsa-with-SHA256.
        val sigAlgId = Der.sequence(
            Der.oid("1.2.840.10045.4.3.2"),
            Der.nullValue(),
        )
        val name = Der.sequence(
            Der.set(
                Der.sequence(
                    // 2.5.4.3 = commonName.
                    Der.oid("2.5.4.3"),
                    Der.utf8String(commonName),
                ),
            ),
        )
        val serial = ByteArray(SERIAL_BYTES).also {
            SecureRandom().nextBytes(it)
            it[0] = (it[0].toInt() and VARINT_MASK).toByte()
        }
        val tbs = Der.sequence(
            // TBSCertificate order (RFC 5280 §4.1.2): version [0] EXPLICIT,
            // then serialNumber. (These two were swapped — the serial sat in
            // the version slot, which no X.509 parser accepts.)
            Der.explicit(0, Der.integer(byteArrayOf(2))), // v3
            Der.integer(serial),
            sigAlgId,
            name, // issuer
            Der.sequence(Der.utcTime(notBeforeMs), Der.utcTime(notAfterMs)),
            name, // subject
            Der.raw(publicKey), // SubjectPublicKeyInfo DER, embedded as-is
        )
        val signature = Der.ecdsaSign(tbs, privateKey)
        return Der.sequence(tbs, sigAlgId, Der.bitString(signature))
    }

    /** Minimal DER primitives for the cert writer above. */
    internal object Der {
        fun sequence(vararg parts: ByteArray): ByteArray =
            byteArrayOf(ASN1_SEQUENCE.toByte()) + lengthPrefix(parts.sumOf { it.size }) + parts.reduce { a, b -> a + b }

        fun set(vararg parts: ByteArray): ByteArray =
            byteArrayOf(ASN1_SET.toByte()) + lengthPrefix(parts.sumOf { it.size }) + parts.reduce { a, b -> a + b }

        fun explicit(tag: Int, content: ByteArray): ByteArray =
            byteArrayOf((ASN1_CONTEXT_BASE + tag).toByte()) + lengthPrefix(content.size) + content

        fun integer(bytes: ByteArray): ByteArray {
            var start = 0
            while (start < bytes.size - 1 && bytes[start] == 0.toByte()) start++
            var v = bytes.copyOfRange(start, bytes.size)
            if (v[0].toInt() and ASN1_HIGH_BIT != 0) v = byteArrayOf(0) + v
            return byteArrayOf(ASN1_INTEGER.toByte()) + lengthPrefix(v.size) + v
        }

        fun oid(dotted: String): ByteArray {
            val arcs = dotted.split(".").map { it.toInt() }
            val first = byteArrayOf(((arcs[0] * OID_FIRST_FACTOR + arcs[1]).toByte()))
            val rest = arcs.drop(2).fold(byteArrayOf()) { acc, a -> acc + base128(a) }
            val body = first + rest
            return byteArrayOf(ASN1_OID.toByte()) + lengthPrefix(body.size) + body
        }

        fun nullValue(): ByteArray = byteArrayOf(ASN1_NULL.toByte(), ASN1_ZERO.toByte())

        fun utf8String(s: String): ByteArray {
            val b = s.toByteArray(Charsets.UTF_8)
            return byteArrayOf(ASN1_UTF8STRING.toByte()) + lengthPrefix(b.size) + b
        }

        fun utcTime(ms: Long): ByteArray {
            val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            cal.timeInMillis = ms
            val str = "%02d%02d%02d%02d%02d%02dZ".format(
                cal.get(java.util.Calendar.YEAR) % YEAR_CENTURY,
                cal.get(java.util.Calendar.MONTH) + 1,
                cal.get(java.util.Calendar.DAY_OF_MONTH),
                cal.get(java.util.Calendar.HOUR_OF_DAY),
                cal.get(java.util.Calendar.MINUTE),
                cal.get(java.util.Calendar.SECOND),
            ).toByteArray(Charsets.US_ASCII)
            return byteArrayOf(ASN1_UTCTIME.toByte()) + lengthPrefix(str.size) + str
        }

        fun bitString(signatureDer: ByteArray): ByteArray =
            byteArrayOf(ASN1_BITSTRING.toByte()) + lengthPrefix(signatureDer.size + 1) +
                byteArrayOf(ASN1_ZERO.toByte()) + signatureDer

        /** Embed already-encoded DER without re-wrapping. */
        fun raw(der: ByteArray): ByteArray = der

        fun ecdsaSign(tbs: ByteArray, key: PrivateKey): ByteArray {
            val sig = java.security.Signature.getInstance("SHA256withECDSA")
            sig.initSign(key, SecureRandom())
            sig.update(tbs)
            return sig.sign() // DER SEQUENCE { r, s } — exactly what BIT STRING holds
        }

        private fun lengthPrefix(n: Int): ByteArray = when {
            n < ASN1_SHORT_LEN_LIMIT -> byteArrayOf(n.toByte())
            else -> {
                var v = n
                val bytes = mutableListOf<Byte>()
                while (v > 0) {
                    bytes.add(0, (v and BYTE_MASK).toByte())
                    v = v ushr BYTE_BITS
                }
                byteArrayOf((ASN1_HIGH_BIT + bytes.size).toByte()) + bytes.toByteArray()
            }
        }

        private fun base128(v: Int): ByteArray {
            if (v == 0) return byteArrayOf(0)
            var x = v
            val out = mutableListOf<Byte>()
            while (x > 0) {
                out.add(0, (x and VARINT_MASK).toByte())
                x = x ushr VARINT_BITS
            }
            for (i in 0 until out.size - 1) out[i] = (out[i].toInt() or VARINT_CONT).toByte()
            return out.toByteArray()
        }
    }
}
