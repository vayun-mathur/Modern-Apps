package com.vayunmathur.library.wearlink

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Capability/presence discovery + trust handshake.
 *
 * Discovery replaces CapabilityClient: a bonded peer is a candidate merely
 * by accepting our RFCOMM UUID; presence is only proven when the handshake
 * completes for our [appId]. The string that used to be a GMS capability
 * (`appstore_wear_store`, `calendar_wear_companion`, …) is reused verbatim as
 * the WearLink appId, so per-app isolation is unchanged.
 *
 * Trust model (honest): Bluetooth bonding proves *this paired device* and
 * encrypts the link; it does not prove which app is on the other end. So the
 * handshake additionally requires (a) equal appId, (b) a token bound to the
 * APK signing certificate: `SHA-256(certHashBytes || peerNonce || appId)`.
 * Each side derives its expected token from *its own* PackageManager signing
 * certificate, so a peer signed by a different key (or unsigned/spoofed with
 * no knowledge of the session nonce) fails. The nonce binding stops replay
 * of a captured hello into a later session. Limitation, stated plainly: an
 * APK's certificate is public, so a malicious app already installed on the
 * already-bonded watch could compute a token — the handshake is app
 * identity + anti-cross-talk/anti-replay, not a substitute for the bonded,
 * user-paired device trust underneath it. No stronger proof is available to
 * a third-party app: the APK signing *private* key is not accessible to app
 * code, so challenge-signing is impossible.
 */
object WearLinkHandshake {
    const val NONCE_BYTES = 16

    fun newNonce(random: SecureRandom = SecureRandom()): ByteArray =
        ByteArray(NONCE_BYTES).also { random.nextBytes(it) }

    /** SHA-256 hex of the DER signing certificate. */
    fun certHashHex(certBytes: ByteArray): String = sha256Hex(certBytes)

    fun token(certHashHex: String, peerNonce: ByteArray, appId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(hexToBytes(certHashHex))
        digest.update(peerNonce)
        digest.update(appId.encodeToByteArray())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.lowercase()
        require(clean.length % 2 == 0) { "odd hex" }
        return ByteArray(clean.length / 2) {
            clean.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
    }
}

/** HELLO / HELLO_ACK body. */
data class HelloMessage(
    val version: Int,
    val appId: String,
    val packageName: String,
    /** Random per-process id; simultaneous-dial tie-break key. */
    val instanceId: String,
    val nonce: ByteArray,
    val token: String,
) {
    fun encode(): ByteArray {
        val app = appId.encodeToByteArray()
        val pkg = packageName.encodeToByteArray()
        val inst = instanceId.encodeToByteArray()
        val tok = token.encodeToByteArray()
        val out = java.io.ByteArrayOutputStream()
        out.write(version)
        writeField(out, app); writeField(out, pkg); writeField(out, inst)
        writeField(out, nonce); writeField(out, tok)
        return out.toByteArray()
    }

    override fun equals(other: Any?): Boolean {
        if (other !is HelloMessage) return false
        return version == other.version && appId == other.appId &&
            packageName == other.packageName && instanceId == other.instanceId &&
            nonce.contentEquals(other.nonce) && token == other.token
    }

    override fun hashCode(): Int = appId.hashCode() * 31 + nonce.contentHashCode()

    companion object {
        fun decode(bytes: ByteArray): HelloMessage? = runCatching {
            val input = java.io.ByteArrayInputStream(bytes)
            val version = input.read()
            val app = readField(input) ?: return null
            val pkg = readField(input) ?: return null
            val inst = readField(input) ?: return null
            val nonce = readField(input) ?: return null
            val tok = readField(input) ?: return null
            HelloMessage(
                version, app.decodeToString(), pkg.decodeToString(),
                inst.decodeToString(), nonce, tok.decodeToString(),
            )
        }.getOrNull()

        private fun writeField(out: java.io.ByteArrayOutputStream, bytes: ByteArray) {
            out.write((bytes.size ushr 8) and 0xFF); out.write(bytes.size and 0xFF)
            out.write(bytes)
        }

        private fun readField(input: java.io.ByteArrayInputStream): ByteArray? {
            val hi = input.read(); val lo = input.read()
            if (hi < 0 || lo < 0) return null
            val size = (hi shl 8) or lo
            if (size > 4096) return null
            val bytes = ByteArray(size)
            var off = 0
            while (off < size) {
                val n = input.read(bytes, off, size - off)
                if (n < 0) return null
                off += n
            }
            return bytes
        }
    }
}

/** Pure handshake verification + simultaneous-dial tie-break. */
object HandshakeVerifier {
    sealed interface Result {
        data class Accepted(val peer: HelloMessage) : Result
        data class Rejected(val reason: String) : Result
    }

    /**
     * Verify a peer HELLO against our identity. [peerNonceForToken] is the
     * nonce *we* sent (for HELLO_ACK) or the peer's own nonce (for HELLO):
     * HELLO token binds the sender's nonce, ACK token binds both — here the
     * caller supplies the nonce the token was computed over.
     */
    fun verify(
        hello: HelloMessage,
        expectedAppId: String,
        ownCertHashHex: String,
        tokenNonce: ByteArray,
    ): Result {
        if (hello.version != WearLinkProtocol.PROTOCOL_VERSION) {
            return Result.Rejected("protocol version ${hello.version}")
        }
        if (hello.appId != expectedAppId) return Result.Rejected("app mismatch")
        val expected = WearLinkHandshake.token(ownCertHashHex, tokenNonce, expectedAppId)
        val ok = MessageDigest.isEqual(
            expected.encodeToByteArray(), hello.token.encodeToByteArray(),
        )
        return if (ok) Result.Accepted(hello) else Result.Rejected("signing token mismatch")
    }

    /**
     * When both sides dialled at once there are two socket pairs. Keep the
     * pair initiated by the smaller instanceId — both ends compute the same
     * winner from the two instance ids they know (their own + the peer's).
     */
    fun keepInitiatedBySmallerInstance(
        existingInitiatorInstance: String,
        candidateInitiatorInstance: String,
    ): Boolean = candidateInitiatorInstance < existingInitiatorInstance
}
