package com.vayunmathur.passwords.platform.cable

import java.security.MessageDigest
import java.security.PublicKey
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Noise `KNpsk0` handshake over `P256 / AES256-GCM / SHA256`, responder side, matching Chromium
 * `RespondToHandshake` / `Noise` (`//device/fido/cable/{v2_handshake,noise}.cc`). The desktop
 * browser is the initiator; the phone (this app) is the responder.
 *
 * Responder sequence for the QR flow:
 * ```
 *   Init(KNpsk0); MixHash([1]/*prologue*/); MixHash(peerIdentity); MixKeyAndHash(psk)
 *   read msg1  = peerEphemeral(65) || ciphertext(16, empty payload):
 *                MixHash(peerEphemeral); MixKey(peerEphemeral); DecryptAndHash(ciphertext)
 *   write msg2 = ephemeral(65) || EncryptAndHash({}):
 *                MixHash(ephemeral); MixKey(ephemeral); MixKey(ee); MixKey(se)
 *   traffic keys = HKDF2(ck, {})  ->  Crypter(readKey=k1, writeKey=k2)
 * ```
 *
 * Verified against Chromium `//device/fido/cable/` source (2024). The handshake AEAD nonce is
 * `big-endian uint32(counter) || 8 zero bytes` with the counter reset on every MixKey; associated
 * data is the running transcript hash `h`. (The *transport* [Crypter] uses a different nonce.)
 */
class NoiseResponder(
    /** Initiator's static public key, decompressed from the 33-byte QR value. */
    initiatorStatic: PublicKey,
    /** Pre-shared key from [CableKeys.psk]. */
    psk: ByteArray,
) {
    private val symmetric = SymmetricState()
    private val initiatorStaticBytes = P256.toUncompressed(initiatorStatic)
    private val initiatorStaticKey = initiatorStatic
    private val ephemeral = P256.generateKeyPair()
    private var peerEphemeral: PublicKey? = null

    init {
        require(psk.size == PSK_BYTES) { "Noise psk must be $PSK_BYTES bytes" }
        symmetric.mixHash(byteArrayOf(1))          // KN prologue byte
        symmetric.mixHash(initiatorStaticBytes)    // pre-message `-> s`
        symmetric.mixKeyAndHash(psk)               // psk0
    }

    /** Reads the initiator's first handshake message (`peerEphemeral(65) || tag(16)`). */
    fun readMessage1(message: ByteArray) {
        require(message.size >= P256.UNCOMPRESSED_SIZE) { "Noise msg1 too short" }
        val rePublic = message.copyOfRange(0, P256.UNCOMPRESSED_SIZE)
        peerEphemeral = P256.decodePoint(rePublic)
        symmetric.mixHash(rePublic)
        symmetric.mixKey(rePublic)

        val ciphertext = message.copyOfRange(P256.UNCOMPRESSED_SIZE, message.size)
        val payload = symmetric.decryptAndHash(ciphertext)
        require(payload.isEmpty()) { "Noise msg1 payload must be empty" }
    }

    /**
     * Writes the responder's reply (`ephemeral(65) || EncryptAndHash({})`) and finalizes the
     * handshake, returning the message to send plus the post-handshake transport [Crypter].
     */
    fun writeMessage2(): Pair<ByteArray, Crypter> {
        val re = peerEphemeral ?: error("readMessage1 must be called first")

        val ePublic = P256.toUncompressed(ephemeral.public)
        symmetric.mixHash(ePublic)
        symmetric.mixKey(ePublic)
        symmetric.mixKey(P256.ecdh(ephemeral.private, re))               // ee
        symmetric.mixKey(P256.ecdh(ephemeral.private, initiatorStaticKey)) // se

        val ciphertext = symmetric.encryptAndHash(ByteArray(0))
        val message = ePublic + ciphertext

        val (readKey, writeKey) = symmetric.trafficKeys()
        return message to Crypter(readKey, writeKey)
    }

    /** Noise SymmetricState (h, ck, and the handshake CipherState). */
    private class SymmetricState {
        private companion object {
            private const val NONCE_INDEX_0 = 0
            private const val NONCE_INDEX_1 = 1
            private const val NONCE_INDEX_2 = 2
            private const val NONCE_INDEX_3 = 3
        }
        private var ck: ByteArray
        private var h: ByteArray
        private var key: ByteArray? = null
        private var nonce = 0

        init {
            val name = PROTOCOL_NAME.toByteArray(Charsets.US_ASCII)
            h = name + ByteArray(HASH_BYTES - name.size)  // name (31 bytes) zero-padded to 32
            ck = h.copyOf()
        }

        fun mixHash(data: ByteArray) {
            h = sha256(h + data)
        }

        fun mixKey(ikm: ByteArray) {
            val (newCk, tempK) = hkdf2(ck, ikm)
            ck = newCk
            key = tempK
            nonce = 0
        }

        fun mixKeyAndHash(ikm: ByteArray) {
            val (newCk, tempH, tempK) = hkdf3(ck, ikm)
            ck = newCk
            mixHash(tempH)
            key = tempK
            nonce = 0
        }

        fun encryptAndHash(plaintext: ByteArray): ByteArray {
            val k = key ?: return plaintext.also { mixHash(it) }
            val ciphertext = aead(Cipher.ENCRYPT_MODE, k, plaintext)
            mixHash(ciphertext)
            return ciphertext
        }

        fun decryptAndHash(ciphertext: ByteArray): ByteArray {
            val k = key ?: return ciphertext.also { mixHash(it) }
            val plaintext = aead(Cipher.DECRYPT_MODE, k, ciphertext)
            mixHash(ciphertext)
            return plaintext
        }

        fun trafficKeys(): Pair<ByteArray, ByteArray> = hkdf2(ck, ByteArray(0))

        /** AES-256-GCM with AD = h and nonce = big-endian uint32(counter) || 8 zeros. */
        private fun aead(mode: Int, k: ByteArray, input: ByteArray): ByteArray {
            val nonceBytes = ByteArray(NONCE_BYTES)
            nonceBytes[NONCE_INDEX_0] = ((nonce ushr BYTE_SHIFT_HIGH) and BYTE_MASK).toByte()
            nonceBytes[NONCE_INDEX_1] = ((nonce ushr BYTE_SHIFT_MID_HIGH) and BYTE_MASK).toByte()
            nonceBytes[NONCE_INDEX_2] = ((nonce ushr BYTE_SHIFT_MID_LOW) and BYTE_MASK).toByte()
            nonceBytes[NONCE_INDEX_3] = (nonce and BYTE_MASK).toByte()
            nonce++
            return Cipher.getInstance("AES/GCM/NoPadding").run {
                init(mode, SecretKeySpec(k, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonceBytes))
                updateAAD(h)
                doFinal(input)
            }
        }
    }

    /** Shared constants and helpers for NoiseResponder. */
    companion object {
        const val PROTOCOL_NAME = "Noise_KNpsk0_P256_AESGCM_SHA256"
        private const val PSK_BYTES = 32
        private const val HASH_BYTES = 32
        private const val NONCE_BYTES = 12
        private const val BYTE_MASK = 0xFF
        private const val BYTE_SHIFT_HIGH = 24
        private const val BYTE_SHIFT_MID_HIGH = 16
        private const val BYTE_SHIFT_MID_LOW = 8
        private const val GCM_TAG_BITS = 128

        private fun sha256(data: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(data)

        private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(key, "HmacSHA256"))
                doFinal(data)
            }

        /** Noise HKDF (RFC 5869 expand, empty info) with two 32-byte outputs. */
        private fun hkdf2(ck: ByteArray, ikm: ByteArray): Pair<ByteArray, ByteArray> {
            val tempKey = hmac(ck, ikm)
            val o1 = hmac(tempKey, byteArrayOf(0x01))
            val o2 = hmac(tempKey, o1 + 0x02)
            return o1 to o2
        }

        /** Noise HKDF with three 32-byte outputs. */
        private fun hkdf3(ck: ByteArray, ikm: ByteArray): Triple<ByteArray, ByteArray, ByteArray> {
            val tempKey = hmac(ck, ikm)
            val o1 = hmac(tempKey, byteArrayOf(0x01))
            val o2 = hmac(tempKey, o1 + 0x02)
            val o3 = hmac(tempKey, o2 + 0x03)
            return Triple(o1, o2, o3)
        }
    }
}
