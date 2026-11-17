package com.vayunmathur.passwords.platform.cable

import android.annotation.SuppressLint
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * caBLE v2 BLE ephemeral id (EID) construction and encryption.
 *
 * The authenticator broadcasts an encrypted EID so the browser can (a) confirm physical proximity
 * and (b) learn how to reach the same tunnel (routing id + domain). Mirrors Chromium
 * `device::cablev2::eid` (`//device/fido/cable/v2_handshake.cc`, `eid.cc`).
 *
 * Plaintext EID (16 bytes = one AES block):
 * ```
 *   [0]      reserved (0)
 *   [1..10]  nonce (10 bytes)
 *   [11..13] tunnel-server routing id (3 bytes)
 *   [14..15] tunnel-server domain id (uint16 little-endian)
 * ```
 * Advertised value (20 bytes): `AES-256(eidKey[0..32], plaintext) || HMAC-SHA256(eidKey[32..64], ct)[0..4]`.
 *
 * ⚠️ UNVERIFIED against Chromium: the exact field order/offsets, the AES mode (assumed single-block
 * AES-256, i.e. ECB over one block), the HMAC tag length (assumed 4), and the service-data UUID
 * ([SERVICE_DATA_UUID16]) all need confirmation against Chromium before relying on interop.
 */
object CableEid {
    const val EID_SIZE = 16
    const val ADVERT_SIZE = 20
    const val NONCE_SIZE = 10
    const val ROUTING_ID_SIZE = 3
    const val TAG_SIZE = 4

    private const val AES_KEY_BYTES = 32
    private const val DOMAIN_ID_LOW_OFFSET = 14
    private const val DOMAIN_ID_HIGH_OFFSET = 15
    private const val DOMAIN_ID_HIGH_SHIFT = 8
    private const val BYTE_MASK = 0xFF

    /** Google's 16-bit service-data UUID used for caBLE v2 adverts. */
    const val SERVICE_DATA_UUID16 = 0xFDE2

    private val secureRandom = SecureRandom()

    /** Random 10-byte nonce for a session. */
    fun randomNonce(): ByteArray = ByteArray(NONCE_SIZE).also { secureRandom.nextBytes(it) }

    /** Builds the 16-byte plaintext EID. */
    fun buildPlaintext(nonce: ByteArray, routingId: ByteArray, domainId: Int): ByteArray {
        require(nonce.size == NONCE_SIZE) { "nonce must be $NONCE_SIZE bytes" }
        require(routingId.size == ROUTING_ID_SIZE) { "routing id must be $ROUTING_ID_SIZE bytes" }
        val eid = ByteArray(EID_SIZE)
        System.arraycopy(nonce, 0, eid, 1, NONCE_SIZE)
        System.arraycopy(routingId, 0, eid, 1 + NONCE_SIZE, ROUTING_ID_SIZE)
        eid[DOMAIN_ID_LOW_OFFSET] = (domainId and BYTE_MASK).toByte()
        eid[DOMAIN_ID_HIGH_OFFSET] = ((domainId ushr DOMAIN_ID_HIGH_SHIFT) and BYTE_MASK).toByte()
        return eid
    }

    /** Encrypts + authenticates the 16-byte EID into the 20-byte advertised value. */
    // caBLE EID is exactly one 16-byte AES block per the Chromium spec; ECB here is the raw
    // single-block AES operation (no IV/padding/multi-block confidentiality pattern), and
    // authenticity comes from the appended HMAC tag. Any other mode would break interop.
    @SuppressLint("GetInstance")
    fun encrypt(plaintextEid: ByteArray, eidKey: ByteArray): ByteArray {
        require(plaintextEid.size == EID_SIZE) { "EID must be $EID_SIZE bytes" }
        require(eidKey.size == CableKeys.EID_KEY_SIZE) { "EID key must be ${CableKeys.EID_KEY_SIZE} bytes" }

        val aesKey = eidKey.copyOfRange(0, AES_KEY_BYTES)
        val hmacKey = eidKey.copyOfRange(AES_KEY_BYTES, CableKeys.EID_KEY_SIZE)

        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"))
        val ciphertext = cipher.doFinal(plaintextEid)

        val tag = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(hmacKey, "HmacSHA256"))
            doFinal(ciphertext)
        }.copyOfRange(0, TAG_SIZE)

        return ciphertext + tag
    }
}
