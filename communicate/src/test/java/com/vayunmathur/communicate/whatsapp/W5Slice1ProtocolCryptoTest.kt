package com.vayunmathur.communicate.whatsapp

import com.vayunmathur.communicate.data.whatsapp.WhatsAppProtocol
import com.vayunmathur.communicate.data.whatsapp.buildPollVoteMessage
import com.vayunmathur.communicate.data.whatsapp.decryptAppStateValue
import com.vayunmathur.communicate.data.whatsapp.decryptMedia
import com.vayunmathur.communicate.data.whatsapp.decryptPollVote
import com.vayunmathur.communicate.data.whatsapp.encryptMedia
import com.vayunmathur.communicate.data.whatsapp.expandAppStateKeys
import com.vayunmathur.communicate.data.whatsapp.generateMessageId
import com.vayunmathur.communicate.data.whatsapp.getMediaKeys
import com.vayunmathur.communicate.data.whatsapp.padMessage
import com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto
import com.vayunmathur.communicate.data.whatsapp.unpadMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Slice 1 (w5): crypto helpers in WhatsAppProtocolCrypto.kt.
 * Pure JVM (java.security only). The poll-vote path here mirrors the wire derivation
 * (HKDF info "Poll Vote", AES-GCM) so build→decrypt must round-trip.
 */
class W5Slice1ProtocolCryptoTest {

    // ---- pad / unpad ----

    @Test
    fun pad_addsBetween1And15BytesWithPadCountValue() {
        val plain = "hello".toByteArray()
        val padded = WhatsAppProtocol.padMessage(plain)
        val padSize = padded.size - plain.size
        assertTrue(padSize in 1..15, "pad=$padSize")
        assertTrue(padded.take(plain.size).toByteArray().contentEquals(plain))
        assertTrue(padded.drop(plain.size).all { it == padSize.toByte() })
    }

    @Test
    fun unpad_recoversPlaintext() {
        val plain = "round trip payload".toByteArray()
        assertTrue(WhatsAppProtocol.unpadMessage(WhatsAppProtocol.padMessage(plain)).contentEquals(plain))
    }

    @Test
    fun unpad_edgeCases_returnInputUnchanged() {
        assertEquals(0, WhatsAppProtocol.unpadMessage(ByteArray(0)).size)
        // padSize 0 or larger than input: returned as-is
        val zeroPad = byteArrayOf(1, 2, 0)
        assertTrue(WhatsAppProtocol.unpadMessage(zeroPad).contentEquals(zeroPad))
        val huge = byteArrayOf(1, 2, 3, 100)
        assertTrue(WhatsAppProtocol.unpadMessage(huge).contentEquals(huge))
    }

    // ---- media keys + encrypt/decrypt ----

    @Test
    fun mediaKeys_haveExpectedLengthsAndDeterminism() {
        val key = ByteArray(32) { it.toByte() }
        val a = WhatsAppProtocol.getMediaKeys(key, WhatsAppProtocol.MEDIA_KEY_IMAGE)
        val b = WhatsAppProtocol.getMediaKeys(key, WhatsAppProtocol.MEDIA_KEY_IMAGE)
        assertEquals(16, a.iv.size)
        assertEquals(32, a.cipherKey.size)
        assertEquals(32, a.macKey.size)
        assertEquals(32, a.refKey.size)
        assertTrue(a.cipherKey.contentEquals(b.cipherKey))
        // different media type -> different keys
        val video = WhatsAppProtocol.getMediaKeys(key, WhatsAppProtocol.MEDIA_KEY_VIDEO)
        assertTrue(!a.cipherKey.contentEquals(video.cipherKey))
    }

    @Test
    fun media_encryptDecrypt_roundTrips() {
        val plain = "fake image bytes 1234567890".toByteArray()
        val enc = WhatsAppProtocol.encryptMedia(plain, WhatsAppProtocol.MEDIA_KEY_IMAGE)
        assertEquals(plain.size.toLong(), enc.fileLength)
        assertEquals(32, enc.mediaKey.size)
        assertEquals(32, enc.fileSha256.size)
        assertEquals(32, enc.fileEncSha256.size)
        val dec = WhatsAppProtocol.decryptMedia(enc.encryptedData, enc.mediaKey, WhatsAppProtocol.MEDIA_KEY_IMAGE)
        assertTrue(dec.contentEquals(plain))
    }

    @Test
    fun media_decrypt_tamperedCiphertext_throws() {
        val plain = "tamper me".toByteArray()
        val enc = WhatsAppProtocol.encryptMedia(plain, WhatsAppProtocol.MEDIA_KEY_AUDIO)
        val tampered = enc.encryptedData.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertFailsWith<Exception> {
            WhatsAppProtocol.decryptMedia(tampered, enc.mediaKey, WhatsAppProtocol.MEDIA_KEY_AUDIO)
        }
    }

    @Test
    fun media_decrypt_wrongKey_throws() {
        val enc = WhatsAppProtocol.encryptMedia("x".toByteArray(), WhatsAppProtocol.MEDIA_KEY_DOCUMENT)
        assertFailsWith<Exception> {
            WhatsAppProtocol.decryptMedia(enc.encryptedData, ByteArray(32) { 7 }, WhatsAppProtocol.MEDIA_KEY_DOCUMENT)
        }
    }

    // ---- app-state keys ----

    @Test
    fun expandAppStateKeys_returnsFive32ByteKeys() {
        val keys = WhatsAppProtocol.expandAppStateKeys(ByteArray(32) { (it * 3).toByte() })
        assertEquals(5, keys.size)
        keys.forEach { assertEquals(32, it.size) }
        // distinct sub-keys
        assertTrue(!keys[0].contentEquals(keys[1]))
        assertTrue(!keys[3].contentEquals(keys[4]))
    }

    @Test
    fun decryptAppStateValue_tooShort_returnsNull() {
        assertEquals(null, WhatsAppProtocol.decryptAppStateValue(ByteArray(10), ByteArray(32)))
    }

    @Test
    fun decryptAppStateValue_badKey_returnsNull() {
        val blob = ByteArray(16 + 16 + 32) { it.toByte() }
        assertEquals(null, WhatsAppProtocol.decryptAppStateValue(blob, ByteArray(16)))
    }

    // ---- message IDs ----

    @Test
    fun generateMessageId_hasPrefixAndHexShape() {
        val id = WhatsAppProtocol.generateMessageId("15551234567@s.whatsapp.net")
        assertTrue(id.startsWith("3EB0"), id)
        assertEquals(4 + 18, id.length)
        assertTrue(id.drop(4).all { it in '0'..'9' || it in 'A'..'F' }, id)
    }

    @Test
    fun generateMessageId_nullJid_stillWellFormed() {
        val id = WhatsAppProtocol.generateMessageId(null)
        assertTrue(id.startsWith("3EB0"), id)
    }

    @Test
    fun generateMessageId_isUnique() {
        val a = WhatsAppProtocol.generateMessageId("1@s.whatsapp.net")
        val b = WhatsAppProtocol.generateMessageId("1@s.whatsapp.net")
        assertNotEquals(a, b)
    }

    // ---- primitives ----

    @Test
    fun primitives_knownVectors() {
        // SHA-256("abc")
        val sha = WhatsAppProtocol.sha256("abc".toByteArray())
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha.joinToString("") { "%02x".format(it) },
        )
        // HKDF is deterministic and length-correct (RFC 5869 shape, not full vector)
        val k1 = WhatsAppProtocol.hkdfSha256("ikm".toByteArray(), null, "info".toByteArray(), 42)
        val k2 = WhatsAppProtocol.hkdfSha256("ikm".toByteArray(), null, "info".toByteArray(), 42)
        assertEquals(42, k1.size)
        assertTrue(k1.contentEquals(k2))
        // HMAC differs by key
        val m1 = WhatsAppProtocol.hmacSha256("k1".toByteArray(), "d".toByteArray())
        val m2 = WhatsAppProtocol.hmacSha256("k2".toByteArray(), "d".toByteArray())
        assertTrue(!m1.contentEquals(m2))
    }

    @Test
    fun noiseHandshake_encryptDecryptRoundTrips() {
        val hs = WhatsAppProtocol.NoiseHandshake()
        hs.start(WhatsAppProtocol.NOISE_START_PATTERN, WhatsAppProtocol.WA_CONN_HEADER)
        val ct = hs.encrypt("handshake probe".toByteArray())
        // A fresh handshake with the same start state decrypts its own ciphertext only if
        // counters line up: encrypt then decrypt on separate instances both at counter 0.
        val hs2 = WhatsAppProtocol.NoiseHandshake()
        hs2.start(WhatsAppProtocol.NOISE_START_PATTERN, WhatsAppProtocol.WA_CONN_HEADER)
        assertEquals("handshake probe", String(hs2.decrypt(ct)))
    }

    @Test
    fun noiseHandshake_finish_returnsKeyPair() {
        val hs = WhatsAppProtocol.NoiseHandshake()
        hs.start(WhatsAppProtocol.NOISE_START_PATTERN, WhatsAppProtocol.WA_CONN_HEADER)
        val (w, r) = hs.finish()
        assertEquals(32, w.encoded.size)
        assertEquals(32, r.encoded.size)
    }
}
