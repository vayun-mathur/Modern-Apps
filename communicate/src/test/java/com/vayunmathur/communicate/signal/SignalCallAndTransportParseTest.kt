package com.vayunmathur.communicate.signal

import com.vayunmathur.communicate.data.signal.call.SignalCallMessage
import com.vayunmathur.communicate.data.signal.call.SignalRemote
import com.vayunmathur.communicate.data.signal.call.isVideo
import com.vayunmathur.communicate.data.signal.call.toContent
import com.vayunmathur.communicate.data.signal.call.toProto
import com.vayunmathur.communicate.data.signal.call.toRingRtc
import com.vayunmathur.communicate.data.signal.transport.SignalAttachmentUpload
import com.vayunmathur.communicate.data.signal.transport.SignalCallingApi
import com.vayunmathur.communicate.data.signal.transport.SignalCdsi
import org.signal.ringrtc.CallManager
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wave 1 (w6 slice 2/6): call signaling mappings, relay/form/credential parsers.
 * Pure JVM — no RingRTC init, no network.
 */
@RunWith(RobolectricTestRunner::class)
class SignalCallAndTransportParseTest {

    // ---- SignalRemote ----

    @Test
    fun remote_matchesCaseInsensitively() {
        val aci = "3f0e5a2c-1111-2222-3333-444455556666"
        val remote = SignalRemote(aci)
        assertTrue(remote.recipientEquals(SignalRemote(aci.uppercase())))
        assertFalse(remote.recipientEquals(SignalRemote("other")))
        assertFalse(remote.recipientEquals(null))
        assertFalse(remote.recipientEquals(object : org.signal.ringrtc.Remote {
            override fun recipientEquals(remote: org.signal.ringrtc.Remote?): Boolean = false
        }))
    }

    // ---- call message -> Content ----

    @Test
    fun offerContent_videoAndAudioTypes() {
        val video = SignalCallMessage.Offer(1L, byteArrayOf(9), video = true).toContent(null)
        assertEquals(
            SignalServiceProtos.CallMessage.Offer.Type.OFFER_VIDEO_CALL,
            video.callMessage.offer.type,
        )
        assertTrue(video.callMessage.offer.opaque.toByteArray().contentEquals(byteArrayOf(9)))
        assertEquals(1L, video.callMessage.offer.id)
        assertFalse(video.callMessage.hasDestinationDeviceId())

        val audio = SignalCallMessage.Offer(2L, byteArrayOf(8), video = false).toContent(3)
        assertEquals(
            SignalServiceProtos.CallMessage.Offer.Type.OFFER_AUDIO_CALL,
            audio.callMessage.offer.type,
        )
        assertEquals(3, audio.callMessage.destinationDeviceId)
    }

    @Test
    fun answerIceHangupBusy_contents() {
        val answer = SignalCallMessage.Answer(5L, byteArrayOf(1, 2)).toContent(null)
        assertEquals(5L, answer.callMessage.answer.id)
        assertTrue(answer.callMessage.answer.opaque.toByteArray().contentEquals(byteArrayOf(1, 2)))

        val ice = SignalCallMessage.Ice(6L, listOf(byteArrayOf(1), byteArrayOf(2, 3))).toContent(4)
        assertEquals(2, ice.callMessage.iceUpdateCount)
        assertEquals(6L, ice.callMessage.getIceUpdate(0).id)
        assertEquals(4, ice.callMessage.destinationDeviceId)

        val emptyIce = SignalCallMessage.Ice(6L, emptyList()).toContent(null)
        assertEquals(0, emptyIce.callMessage.iceUpdateCount)

        val hangup = SignalCallMessage.Hangup(7L, CallManager.HangupType.BUSY, deviceId = 2).toContent(null)
        assertEquals(7L, hangup.callMessage.hangup.id)
        assertEquals(SignalServiceProtos.CallMessage.Hangup.Type.HANGUP_BUSY, hangup.callMessage.hangup.type)
        assertEquals(2, hangup.callMessage.hangup.deviceId)

        val busy = SignalCallMessage.Busy(8L).toContent(null)
        assertEquals(8L, busy.callMessage.busy.id)
    }

    @Test
    fun hangupType_mapsBothDirections() {
        val pairs = listOf(
            CallManager.HangupType.NORMAL to SignalServiceProtos.CallMessage.Hangup.Type.HANGUP_NORMAL,
            CallManager.HangupType.ACCEPTED to SignalServiceProtos.CallMessage.Hangup.Type.HANGUP_ACCEPTED,
            CallManager.HangupType.DECLINED to SignalServiceProtos.CallMessage.Hangup.Type.HANGUP_DECLINED,
            CallManager.HangupType.BUSY to SignalServiceProtos.CallMessage.Hangup.Type.HANGUP_BUSY,
            CallManager.HangupType.NEED_PERMISSION to SignalServiceProtos.CallMessage.Hangup.Type.HANGUP_NEED_PERMISSION,
        )
        for ((rtc, proto) in pairs) {
            assertEquals(proto, rtc.toProto())
            assertEquals(rtc, proto.toRingRtc())
        }
    }

    @Test
    fun callMediaType_isVideoOnlyForVideoCall() {
        assertTrue(CallManager.CallMediaType.VIDEO_CALL.isVideo())
        assertFalse(CallManager.CallMediaType.AUDIO_CALL.isVideo())
    }

    // ---- calling relays parse ----

    private fun relayJson(
        urls: String = """["turn:1.2.3.4:3478"]""",
        withCreds: Boolean = true,
        hostname: String? = "t.signal.org",
    ): String = buildString {
        append("""{"relays":[{""")
        if (withCreds) append(""""username":"u","password":"p",""")
        append(""""urls":$urls""")
        if (hostname != null) append(""","hostname":"$hostname"""")
        append("}]}")
    }

    @Test
    fun relays_prefersUrlsWithIpsAndAppliesAuth() {
        val body = """{"relays":[{
          "username":"user","password":"pass","hostname":"relay.signal.org",
          "urls":["turn:host:3478"],"urlsWithIps":["turn:1.2.3.4:3478","turn:[::1]:3478"]
        }]}"""
        val servers = SignalCallingApi.parseRelays(body, warn = {})
        assertEquals(1, servers.size)
        assertEquals(listOf("turn:1.2.3.4:3478", "turn:[::1]:3478"), servers[0].urls)
        assertEquals("user", servers[0].username)
        assertEquals("pass", servers[0].password)
        assertEquals("relay.signal.org", servers[0].hostname)
    }

    @Test
    fun relays_skipsEntriesWithoutUrls() {
        val body = """{"relays":[{},{"username":"u"},"urls-missing":[]]}"""
        assertTrue(SignalCallingApi.parseRelays(body, warn = {}).isEmpty())
        assertTrue(SignalCallingApi.parseRelays("junk", warn = {}).isEmpty())
        assertTrue(SignalCallingApi.parseRelays("""{}""", warn = {}).isEmpty())
        assertTrue(SignalCallingApi.parseRelays("""{"relays":[]}""", warn = {}).isEmpty())
    }

    @Test
    fun relays_toleratesMissingCredentials() {
        val servers = SignalCallingApi.parseRelays(relayJson(withCreds = false), warn = {})
        assertEquals(1, servers.size)
    }

    // ---- attachment upload form parse ----

    @Test
    fun uploadForm_acceptsCompleteForm() {
        val body = """{"cdn":3,"key":"k123","signedUploadLocation":"https://cdn/x",
          "headers":{"authorization":"sig","host":"cdn"}}"""
        val form = SignalAttachmentUpload.parseForm(body, warn = {})!!
        assertEquals(3, form.cdn)
        assertEquals("k123", form.key)
        assertEquals("https://cdn/x", form.signedUploadLocation)
        assertEquals("sig", form.headers["authorization"])
    }

    @Test
    fun uploadForm_rejectsIncompleteOrJunk() {
        assertNull(SignalAttachmentUpload.parseForm("""{"cdn":3}""", warn = {}))
        assertNull(SignalAttachmentUpload.parseForm("""{"key":"k"}""", warn = {}))
        assertNull(SignalAttachmentUpload.parseForm("junk", warn = {}))
        assertNull(SignalAttachmentUpload.parseForm("""{"cdn":3,"key":"","signedUploadLocation":"x"}""", warn = {}))
        // Non-object headers degrade to empty rather than failing the form.
        val form = SignalAttachmentUpload.parseForm(
            """{"cdn":3,"key":"k","signedUploadLocation":"x","headers":[1]}""", warn = {},
        )!!
        assertTrue(form.headers.isEmpty())
    }

    @Test
    fun upload_refusesNonCdn3() {
        // upload() with a non-CDN3 form returns false without touching the network
        // (runs under Robolectric so android.util.Log is shadowed).
        val form = SignalAttachmentUpload.UploadForm(
            cdn = 2, key = "k", headers = emptyMap(), signedUploadLocation = "https://x",
        )
        var result: Boolean? = null
        val t = Thread { result = kotlinx.coroutines.runBlocking {
            SignalAttachmentUpload.upload(form, byteArrayOf(1), null)
        } }
        t.start()
        t.join(10_000)
        assertEquals(false, result)
    }

    // ---- CDSI credentials parse ----

    @Test
    fun cdsiCredentials_acceptsUsernameAndPassword() {
        val creds = SignalCdsi.parseCredentials("""{"username":"u","password":"p"}""", warn = {})!!
        assertEquals("u", creds.username)
        assertEquals("p", creds.password)
    }

    @Test
    fun cdsiCredentials_rejectsMissingOrJunk() {
        assertNull(SignalCdsi.parseCredentials("""{"username":"u"}""", warn = {}))
        assertNull(SignalCdsi.parseCredentials("""{"username":"","password":""}""", warn = {}))
        assertNull(SignalCdsi.parseCredentials("junk", warn = {}))
    }

    @Test
    fun cdsiLookup_emptySetsReturnEmptyWithoutNetwork() {
        var result: SignalCdsi.LookupResult? = null
        val t = Thread { result = kotlinx.coroutines.runBlocking {
            SignalCdsi.lookup(SignalCdsi.Credentials("u", "p"), emptySet(), emptySet(), null)
        } }
        t.start()
        t.join(10_000)
        assertNotNull(result)
        assertTrue(result!!.discovered.isEmpty())
    }
}
