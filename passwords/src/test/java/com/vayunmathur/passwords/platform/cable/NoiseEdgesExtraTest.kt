package com.vayunmathur.passwords.platform.cable

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NoiseEdgesExtraTest {

    @Test fun responderRejectsBadPskAndOutOfOrderCalls() {
        val staticPair = P256.generateKeyPair()
        assertFailsWith<IllegalArgumentException> {
            NoiseResponder(staticPair.public, ByteArray(31))
        }
        assertFailsWith<IllegalArgumentException> {
            NoiseResponder(staticPair.public, ByteArray(33))
        }
        val responder = NoiseResponder(staticPair.public, ByteArray(32) { 0x42 })
        // msg2 cannot be written before msg1 has been read.
        assertFailsWith<IllegalStateException> { responder.writeMessage2() }
        // A truncated first message is rejected before any state is mixed.
        assertFailsWith<IllegalArgumentException> { responder.readMessage1(ByteArray(10)) }
        assertEquals("Noise_KNpsk0_P256_AESGCM_SHA256", NoiseResponder.PROTOCOL_NAME)
    }

    @Test fun responderRejectsForgedMessage1Ciphertext() {
        val staticPair = P256.generateKeyPair()
        val responder = NoiseResponder(staticPair.public, ByteArray(32) { 1 })
        val ephemeral = P256.generateKeyPair()
        // 65-byte ephemeral + 16 zero bytes as the (invalid) AEAD tag for the empty payload.
        val forged = P256.toUncompressed(ephemeral.public) + ByteArray(16)
        assertFailsWith<Exception> { responder.readMessage1(forged) }
        assertTrue(forged.size == 65 + 16)
    }

    @Test fun responderRejectsNonEmptyMessage1Payload() {
        // Build a protocol-valid msg1 whose encrypted payload is one byte instead
        // of empty: decryption must succeed and the payload check must reject it.
        val staticPair = P256.generateKeyPair()
        val psk = ByteArray(32) { 0x2A }
        val responder = NoiseResponder(staticPair.public, psk)
        val msg1 = Initiator(staticPair, psk).buildMessage(byteArrayOf(0x99.toByte()))
        val error = assertFailsWith<IllegalArgumentException> { responder.readMessage1(msg1) }
        assertEquals("Noise msg1 payload must be empty", error.message)
    }

    /** Minimal KNpsk0 initiator, sufficient to produce a valid first handshake message. */
    private class Initiator(staticPair: java.security.KeyPair, psk: ByteArray) {
        private var ck: ByteArray
        private var h: ByteArray
        private var key: ByteArray? = null
        private var nonce = 0
        private val ephemeral = P256.generateKeyPair()

        init {
            val name = NoiseResponder.PROTOCOL_NAME.toByteArray(Charsets.US_ASCII)
            h = name + ByteArray(32 - name.size)
            ck = h.copyOf()
            mixHash(byteArrayOf(1))
            mixHash(P256.toUncompressed(staticPair.public))
            val (c, hh, k) = hkdf3(ck, psk)
            ck = c
            mixHash(hh)
            key = k
            nonce = 0
        }

        fun buildMessage(payload: ByteArray): ByteArray {
            val ePub = P256.toUncompressed(ephemeral.public)
            mixHash(ePub)
            val (c, k) = hkdf2(ck, ePub)
            ck = c
            key = k
            nonce = 0
            return ePub + encryptAndHash(payload)
        }

        private fun mixHash(data: ByteArray) {
            h = java.security.MessageDigest.getInstance("SHA-256").digest(h + data)
        }

        private fun encryptAndHash(plaintext: ByteArray): ByteArray {
            val k = key ?: return plaintext.also { mixHash(it) }
            val n = ByteArray(12)
            n[0] = ((nonce ushr 24) and 0xFF).toByte()
            n[1] = ((nonce ushr 16) and 0xFF).toByte()
            n[2] = ((nonce ushr 8) and 0xFF).toByte()
            n[3] = (nonce and 0xFF).toByte()
            nonce++
            val ct = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").run {
                init(
                    javax.crypto.Cipher.ENCRYPT_MODE,
                    javax.crypto.spec.SecretKeySpec(k, "AES"),
                    javax.crypto.spec.GCMParameterSpec(128, n),
                )
                updateAAD(h)
                doFinal(plaintext)
            }
            mixHash(ct)
            return ct
        }

        private fun hmac(keyBytes: ByteArray, data: ByteArray): ByteArray =
            javax.crypto.Mac.getInstance("HmacSHA256").run {
                init(javax.crypto.spec.SecretKeySpec(keyBytes, "HmacSHA256"))
                doFinal(data)
            }

        private fun hkdf2(ckIn: ByteArray, ikm: ByteArray): Pair<ByteArray, ByteArray> {
            val tk = hmac(ckIn, ikm)
            val o1 = hmac(tk, byteArrayOf(1))
            val o2 = hmac(tk, o1 + 2)
            return o1 to o2
        }

        private fun hkdf3(ckIn: ByteArray, ikm: ByteArray): Triple<ByteArray, ByteArray, ByteArray> {
            val tk = hmac(ckIn, ikm)
            val o1 = hmac(tk, byteArrayOf(1))
            val o2 = hmac(tk, o1 + 2)
            val o3 = hmac(tk, o2 + 3)
            return Triple(o1, o2, o3)
        }
    }
}
