package com.vayunmathur.passwords.platform.cable

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Post-handshake transport encryption for caBLE v2, matching Chromium `cablev2::Crypter`
 * (`//device/fido/cable/v2_handshake.cc`).
 *
 * Each direction has its own 32-byte key and a 32-bit sequence counter. The AES-256-GCM nonce is
 * `8 zero bytes || big-endian uint32(sequence)` (note: this differs from the Noise *handshake*
 * nonce, which puts the counter in the first 4 bytes). No associated data is used. Before
 * encryption the plaintext is padded to a multiple of 32 bytes: zero bytes are appended and the
 * final byte records how many zeros were added.
 *
 * For the responder (this app): `readKey` = first traffic key, `writeKey` = second.
 */
class Crypter(private val readKey: ByteArray, private val writeKey: ByteArray) {
    private var readSeq = 0
    private var writeSeq = 0

    fun encrypt(plaintext: ByteArray): ByteArray {
        val padded = pad(plaintext)
        val nonce = nonce(writeSeq++)
        return gcm(Cipher.ENCRYPT_MODE, writeKey, nonce).doFinal(padded)
    }

    fun decrypt(ciphertext: ByteArray): ByteArray {
        val nonce = nonce(readSeq++)
        val padded = gcm(Cipher.DECRYPT_MODE, readKey, nonce).doFinal(ciphertext)
        return unpad(padded)
    }

    private fun gcm(mode: Int, key: ByteArray, nonce: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        }

    /** Shared constants and helpers for Crypter. */
    companion object {
        private const val PADDING_GRANULARITY = 32
        private const val NONCE_BYTES = 12
        private const val NONCE_SEQ_OFFSET_0 = 8
        private const val NONCE_SEQ_OFFSET_1 = 9
        private const val NONCE_SEQ_OFFSET_2 = 10
        private const val NONCE_SEQ_OFFSET_3 = 11
        private const val BYTE_MASK = 0xFF
        private const val BYTE_SHIFT_HIGH = 24
        private const val BYTE_SHIFT_MID_HIGH = 16
        private const val BYTE_SHIFT_MID_LOW = 8
        private const val GCM_TAG_BITS = 128

        /** `8 zero bytes || big-endian uint32(sequence)`. */
        fun nonce(sequence: Int): ByteArray {
            val n = ByteArray(NONCE_BYTES)
            n[NONCE_SEQ_OFFSET_0] = ((sequence ushr BYTE_SHIFT_HIGH) and BYTE_MASK).toByte()
            n[NONCE_SEQ_OFFSET_1] = ((sequence ushr BYTE_SHIFT_MID_HIGH) and BYTE_MASK).toByte()
            n[NONCE_SEQ_OFFSET_2] = ((sequence ushr BYTE_SHIFT_MID_LOW) and BYTE_MASK).toByte()
            n[NONCE_SEQ_OFFSET_3] = (sequence and BYTE_MASK).toByte()
            return n
        }

        /** Appends zeros to reach a multiple of 32; the final byte is the number of zeros added. */
        fun pad(message: ByteArray): ByteArray {
            val paddedSize = ((message.size + 1 + PADDING_GRANULARITY - 1) / PADDING_GRANULARITY) * PADDING_GRANULARITY
            val numZeros = paddedSize - message.size - 1
            val out = ByteArray(paddedSize)
            System.arraycopy(message, 0, out, 0, message.size)
            out[paddedSize - 1] = numZeros.toByte()
            return out
        }

        /** Strips the padding written by [pad]. */
        fun unpad(padded: ByteArray): ByteArray {
            require(padded.isNotEmpty()) { "empty caBLE message" }
            val paddingLength = padded[padded.size - 1].toInt() and 0xFF
            require(paddingLength + 1 <= padded.size) { "invalid caBLE padding" }
            return padded.copyOfRange(0, padded.size - paddingLength - 1)
        }
    }
}
