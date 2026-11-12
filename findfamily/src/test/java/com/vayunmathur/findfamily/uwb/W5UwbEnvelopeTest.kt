package com.vayunmathur.findfamily.uwb

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-JVM tests for the UWB envelope layer: kind constants, JSON wire shape
 * (cross-platform compat with older peers), the accessory-protocol placeholder,
 * and [UwbInbox] request/cancel caching.
 */
class W5UwbEnvelopeTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun envelope(kind: String, sender: ULong = 42uL) = UwbEnvelope(
        sessionId = "session-1",
        sender = sender,
        senderPlatform = "android",
        kind = kind,
    )

    @Test
    fun kindConstants() {
        assertEquals("request", UwbEnvelopeKind.REQUEST)
        assertEquals("ack", UwbEnvelopeKind.ACK)
        assertEquals("config", UwbEnvelopeKind.CONFIG)
        assertEquals("cancel", UwbEnvelopeKind.CANCEL)
        assertEquals("pofgrant", UwbEnvelopeKind.POF_GRANT)
    }

    @Test
    fun envelopeRoundTripsJson() {
        val e = envelope(UwbEnvelopeKind.REQUEST)
        val decoded = json.decodeFromString(UwbEnvelope.serializer(), json.encodeToString(UwbEnvelope.serializer(), e))
        assertEquals(e, decoded)
    }

    @Test
    fun decodeToleratesNewerPeers() {
        // Older builds must keep decoding when a newer peer adds fields.
        val raw = """{"sessionId":"s","sender":7,"senderPlatform":"ios","kind":"request","futureField":123}"""
        val decoded = json.decodeFromString(UwbEnvelope.serializer(), raw)
        assertEquals("s", decoded.sessionId)
        assertEquals(7uL, decoded.sender)
    }

    @Test
    fun handshakeDefaultsAreAllNull() {
        val h = UwbHandshake()
        assertNull(h.addressB64)
        assertNull(h.discoveryTokenB64)
        assertNull(h.accessoryConfigDataB64)
        assertNull(h.shareableConfigDataB64)
    }

    @Test
    fun accessoryConfigStartsWithVersionBytes() {
        val local = byteArrayOf(0x11, 0x22)
        val blob = UwbAccessoryProtocol.encodeAccessoryConfigurationData(local)
        assertTrue(blob.size == local.size + 2)
        assertEquals(0x01.toByte(), blob[0])
        assertEquals(0x11.toByte(), blob[2])
    }

    @Test
    fun poweredOffGrantCarriesEpochAndKeys() {
        val grant = PoweredOffGrant(epoch = 5L, secretB64 = "s", recoveryPrivB64 = "r", sigB64 = "sig")
        val e = envelope(UwbEnvelopeKind.POF_GRANT).copy(recovery = grant)
        val decoded = json.decodeFromString(UwbEnvelope.serializer(), json.encodeToString(UwbEnvelope.serializer(), e))
        assertEquals(5L, decoded.recovery?.epoch)
        assertEquals("sig", decoded.recovery?.sigB64)
    }

    @Test
    fun cancelReasonRoundTripsJson() {
        val e = envelope(UwbEnvelopeKind.CANCEL).copy(reason = UwbCancelReason.UNSUPPORTED)
        val wire = json.encodeToString(UwbEnvelope.serializer(), e)
        assertTrue(wire.contains("\"reason\":\"unsupported\""))
        assertEquals(e, json.decodeFromString(UwbEnvelope.serializer(), wire))
    }

    @Test
    fun plainCancelOmitsReasonOnTheWire() {
        // Defaults aren't encoded, so a plain cancel is exactly what pre-#869 builds send.
        val wire = json.encodeToString(UwbEnvelope.serializer(), envelope(UwbEnvelopeKind.CANCEL))
        assertFalse(wire.contains("reason"))
        assertNull(json.decodeFromString(UwbEnvelope.serializer(), wire).reason)
    }

    @Test
    fun cancelFromOlderPeerDecodesAsPlainCancel() {
        val raw = """{"sessionId":"s","sender":7,"senderPlatform":"android","kind":"cancel"}"""
        val decoded = json.decodeFromString(UwbEnvelope.serializer(), raw)
        assertEquals(UwbEnvelopeKind.CANCEL, decoded.kind)
        assertNull(decoded.reason)
    }

    @Test
    fun olderPeerParsesCancelWithReason() {
        // An older build's envelope class has no `reason`; it must still decode the new cancel.
        val wire = json.encodeToString(
            UwbEnvelope.serializer(),
            envelope(UwbEnvelopeKind.CANCEL).copy(reason = UwbCancelReason.UNSUPPORTED),
        )
        val legacy = json.decodeFromString(LegacyUwbEnvelope.serializer(), wire)
        assertEquals(UwbEnvelopeKind.CANCEL, legacy.kind)
        assertEquals("session-1", legacy.sessionId)
    }

    @Test
    fun inboxCachesRequestsUntilConsumed() = runTest {
        val req = envelope(UwbEnvelopeKind.REQUEST, sender = 11uL)
        UwbInbox.emit(req)
        assertEquals(req, UwbInbox.consumePendingRequest(11L))
        // Consuming removes it.
        assertNull(UwbInbox.consumePendingRequest(11L))
    }

    @Test
    fun inboxCancelInvalidatesPendingRequest() = runTest {
        UwbInbox.emit(envelope(UwbEnvelopeKind.REQUEST, sender = 12uL))
        UwbInbox.emit(envelope(UwbEnvelopeKind.CANCEL, sender = 12uL))
        assertNull(UwbInbox.consumePendingRequest(12L))
    }

    @Test
    fun inboxIgnoresNonRequestKinds() = runTest {
        UwbInbox.emit(envelope(UwbEnvelopeKind.ACK, sender = 13uL))
        UwbInbox.emit(envelope(UwbEnvelopeKind.CONFIG, sender = 13uL))
        assertNull(UwbInbox.consumePendingRequest(13L))
    }

    @Test
    fun inboxKeepsLatestRequestPerPeer() = runTest {
        val first = envelope(UwbEnvelopeKind.REQUEST, sender = 14uL).copy(sessionId = "one")
        val second = envelope(UwbEnvelopeKind.REQUEST, sender = 14uL).copy(sessionId = "two")
        UwbInbox.emit(first)
        UwbInbox.emit(second)
        assertEquals("two", UwbInbox.consumePendingRequest(14L)?.sessionId)
    }
}

/** [UwbEnvelope] as builds before #869 declared it: no `reason` field. */
@Serializable
private data class LegacyUwbEnvelope(
    val sessionId: String,
    val sender: ULong,
    val senderPlatform: String,
    val kind: String,
    val payload: UwbHandshake? = null,
    val recovery: PoweredOffGrant? = null,
)
