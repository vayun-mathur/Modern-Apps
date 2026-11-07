package com.vayunmathur.communicate.googlevoice

import com.vayunmathur.communicate.data.googlevoice.GvSipRegisterInfo
import com.vayunmathur.communicate.data.googlevoice.call.SipClient
import com.vayunmathur.communicate.data.googlevoice.call.randomToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Slice-4 pass-2: SipClient message-building paths with no socket connected (send is a no-op). */
@RunWith(RobolectricTestRunner::class)
class SipClientSlice4Pass2Test {

    private val events = mutableListOf<String>()
    private val listener = object : SipClient.Listener {
        override fun onRegistered() { events += "registered" }
        override fun onProvisional(code: Int) { events += "prov:$code" }
        override fun onAnswered(remoteSdp: String) { events += "answered" }
        override fun onEarlyMedia(remoteSdp: String) { events += "early" }
        override fun onEnded() { events += "ended" }
        override fun onFailed(reason: String) { events += "failed:$reason" }
        override fun onIncomingInvite(remoteSdp: String, from: String) { events += "invite:$from" }
    }

    private fun client(credentials: List<String> = listOf("user=1", "secret")): SipClient {
        val info = GvSipRegisterInfo(phoneNumber = "+15550001", credentials = credentials)
        return SipClient(info, "+15550001", CoroutineScope(Dispatchers.Unconfined), listener)
    }

    @Test
    fun clientDerivesAuthIdentityFromCredentials() {
        val c = client()
        assertEquals("user=1", c.authUsername)
        assertEquals("secret", c.authPassword)
        assertEquals("user%3D1", c.aorUser)
        assertTrue(c.localHost.endsWith(".invalid"))
        assertTrue(c.callId.isNotBlank())
        assertEquals(8, c.contactUser.length)
        // No credentials: falls back to the GV number and a blank password
        val bare = client(emptyList())
        assertEquals("+15550001", bare.authUsername)
        assertEquals("", bare.authPassword)
    }

    @Test
    fun outboundRequestsBuildWithoutSocket() = runBlocking {
        val c = client()
        c.register()
        c.register(expires = 600, authHeader = "Digest abc")
        c.invite("+15559999", "v=0\r\no=- 1 1 IN IP4 0.0.0.0\r\nm=audio 9 RTP/AVP 0\r\n")
        c.ack("+15559999")
        c.bye("+15559999")
        c.answerInbound("v=0 answer")
        c.declineInbound()
        c.close()
        c.close() // idempotent
        // Nothing was connected, so no listener callbacks should have fired
        assertEquals(emptyList(), events.toList())
        Unit
    }

    @Test
    fun randomTokenShape() {
        val token = randomToken(16)
        assertEquals(16, token.length)
        assertTrue(token.all { it.isLowerCase() || it.isDigit() })
        assertEquals(1, randomToken(1).length)
    }
}
