package com.vayunmathur.passwords.cable

/**
 * Parser for the `FIDO:/…` caBLE v2 QR contents shown by a desktop browser during
 * "use a passkey from another device".
 *
 * Wire format (FIDO CTAP 2.2, "Hybrid transports"; ref. Chromium `//device/fido/cable/`):
 *  - Literal prefix `FIDO:/`.
 *  - A compact base-10 encoding of a CBOR byte string. Each 7-byte chunk of the CBOR is written
 *    as exactly 17 zero-padded decimal digits (little-endian); a trailing partial chunk of
 *    `b` bytes is written as [partialChunkDigits] `[b]` digits.
 *  - The decoded CBOR is a map with integer keys:
 *      0 = initiator's compressed P-256 public key (33 bytes)
 *      1 = QR secret (16 bytes)
 *      2 = number of assigned tunnel-server domains known to the initiator
 *      3 = current time, seconds since epoch (optional)
 *      4 = whether the initiator supports state-assisted / linked transactions (optional bool)
 *      5 = request-type hint: "ga" (getAssertion) or "mc" (makeCredential) (optional)
 */
data class CableQrData(
    val peerPublicKey: ByteArray,   // 33-byte compressed P-256 point
    val qrSecret: ByteArray,        // 16 bytes
    val numKnownDomains: Int,
    val timestampSeconds: Long?,
    val supportsLinking: Boolean?,
    val requestTypeHint: String?,
) {
    companion object {
        const val URI_PREFIX = "FIDO:/"
        const val PEER_KEY_SIZE = 33
        const val QR_SECRET_SIZE = 16

        /** bytes-in-partial-chunk -> number of decimal digits (index 1..6 valid; 0/7 unused). */
        private val PARTIAL_CHUNK_DIGITS = intArrayOf(0, 3, 5, 8, 10, 13, 15, 0)
        private const val CHUNK_BYTES = 7
        private const val CHUNK_DIGITS = 17

        /** Parses a full `FIDO:/…` URI. */
        fun parse(uri: String): CableQrData {
            val trimmed = uri.trim()
            val body = when {
                trimmed.startsWith(URI_PREFIX) -> trimmed.removePrefix(URI_PREFIX)
                trimmed.startsWith("fido:/") -> trimmed.removePrefix("fido:/")
                else -> error("Not a FIDO caBLE URI: $uri")
            }
            return fromCbor(digitDecode(body))
        }

        /** Decodes the compact base-10 digit encoding back into raw CBOR bytes. */
        fun digitDecode(digits: String): ByteArray {
            require(digits.all { it in '0'..'9' }) { "caBLE QR body contains non-digit characters" }
            val out = ArrayList<Byte>(digits.length)
            var i = 0
            while (digits.length - i >= CHUNK_DIGITS) {
                appendLittleEndian(out, digits.substring(i, i + CHUNK_DIGITS).toLong(), CHUNK_BYTES)
                i += CHUNK_DIGITS
            }
            val remainingDigits = digits.length - i
            if (remainingDigits > 0) {
                val byteCount = PARTIAL_CHUNK_DIGITS.indexOf(remainingDigits)
                require(byteCount in 1..6) { "Invalid trailing digit count: $remainingDigits" }
                appendLittleEndian(out, digits.substring(i).toLong(), byteCount)
            }
            return out.toByteArray()
        }

        private fun appendLittleEndian(out: MutableList<Byte>, value: Long, byteCount: Int) {
            var v = value
            repeat(byteCount) {
                out.add((v and 0xFF).toByte())
                v = v ushr 8
            }
        }

        /** Builds a [CableQrData] from the decoded CBOR map. */
        fun fromCbor(cbor: ByteArray): CableQrData {
            val map = CborReader(cbor).readValue() as? Map<*, *> ?: error("caBLE QR: not a CBOR map")
            val peerKey = map[0L] as? ByteArray ?: error("caBLE QR: missing peer public key (0)")
            val secret = map[1L] as? ByteArray ?: error("caBLE QR: missing QR secret (1)")
            require(peerKey.size == PEER_KEY_SIZE) { "caBLE QR: peer key must be $PEER_KEY_SIZE bytes, got ${peerKey.size}" }
            require(secret.size == QR_SECRET_SIZE) { "caBLE QR: secret must be $QR_SECRET_SIZE bytes, got ${secret.size}" }
            return CableQrData(
                peerPublicKey = peerKey,
                qrSecret = secret,
                numKnownDomains = (map[2L] as? Long)?.toInt() ?: 0,
                timestampSeconds = map[3L] as? Long,
                supportsLinking = map[4L] as? Boolean,
                requestTypeHint = map[5L] as? String,
            )
        }
    }
}
