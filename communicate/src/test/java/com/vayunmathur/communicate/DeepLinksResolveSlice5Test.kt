package com.vayunmathur.communicate

import com.vayunmathur.communicate.data.CommunicateLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice 5/6 (root package A-L): edge branches of [DeepLinks.resolve] not covered by [DeepLinksTest].
 *
 * Drives the component-based parser directly, since `android.net.Uri` is a stub on the JVM.
 */
class DeepLinksResolveSlice5Test {
    @Test
    fun parseOfNullIntentReturnsNull() {
        // The only `parse` branch reachable without Android framework classes.
        assertNull(DeepLinks.parse(null))
    }

    private fun link(
        scheme: String? = "https",
        host: String? = null,
        path: String? = null,
        fragment: String? = null,
        opaque: String? = null,
        params: Map<String, String> = emptyMap(),
        body: String? = null,
    ) = DeepLinks.resolve(
        scheme = scheme,
        host = host,
        path = path,
        fragment = fragment,
        opaque = opaque,
        query = { params[it] },
        body = body,
    )

    @Test
    fun schemeMatchingIsCaseInsensitive() {
        val result = link(scheme = "SMSTO", opaque = "+15551234567") as DeepLink.Conversation
        assertEquals(CommunicateLine.Sim, result.line)
        assertEquals("+15551234567", result.address)
    }

    @Test
    fun hostMatchingIsCaseInsensitive() {
        val result = link(host = "WA.ME", path = "/15551234567") as DeepLink.Conversation
        assertEquals(CommunicateLine.WhatsApp, result.line)
        assertEquals("+15551234567", result.address)
    }

    @Test
    fun mmsSchemesMapToTheSimLine() {
        for (scheme in listOf("mms", "mmsto", "sms")) {
            val result = link(scheme = scheme, opaque = "+15551234567") as DeepLink.Conversation
            assertEquals(CommunicateLine.Sim, result.line, "scheme $scheme")
        }
    }

    @Test
    fun smsFallsBackToPathWhenOpaqueIsNull() {
        // The path fallback keeps the leading slash as-is (opaque is the normal carrier for sms: URIs).
        val result = link(scheme = "sms", opaque = null, path = "/+15551234567") as DeepLink.Conversation
        assertEquals("/+15551234567", result.address)
    }

    @Test
    fun smsWithNeitherOpaqueNorPathIsIgnored() {
        assertNull(link(scheme = "sms", opaque = null, path = null))
    }

    @Test
    fun smsWithBlankAddressIsIgnored() {
        assertNull(link(scheme = "sms", opaque = "   "))
    }

    @Test
    fun smsBodyExtraIsCarriedAsTheDraft() {
        val result = link(scheme = "sms", opaque = "+15551234567", body = "hello") as DeepLink.Conversation
        assertEquals("hello", result.body)
    }

    @Test
    fun whatsAppBlankPhoneParamFallsBackToPath() {
        val result = link(
            host = "api.whatsapp.com",
            path = "/15551234567",
            params = mapOf("phone" to "   "),
        ) as DeepLink.Conversation
        assertEquals("+15551234567", result.address)
    }

    @Test
    fun whatsAppWithNoNumberAnywhereIsIgnored() {
        assertNull(link(host = "wa.me", path = "/send"))
        assertNull(link(host = "api.whatsapp.com", path = "/send"))
    }

    @Test
    fun whatsAppQueryTextBeatsTheShareBody() {
        val result = link(
            host = "wa.me",
            path = "/15551234567",
            params = mapOf("text" to "from-link"),
            body = "from-share",
        ) as DeepLink.Conversation
        assertEquals("from-link", result.body)
    }

    @Test
    fun whatsAppFallsBackToShareBodyWithoutQueryText() {
        val result = link(
            host = "wa.me",
            path = "/15551234567",
            body = "from-share",
        ) as DeepLink.Conversation
        assertEquals("from-share", result.body)
    }

    @Test
    fun whatsAppPhoneWithNoDigitsNormalisesToEmpty() {
        val result = link(
            host = "api.whatsapp.com",
            path = "/send",
            params = mapOf("phone" to "!!!"),
        ) as DeepLink.Conversation
        assertEquals("", result.address)
    }

    @Test
    fun signalWithNoDigitsInFragmentIsIgnored() {
        assertNull(link(host = "signal.me", fragment = "p/abc"))
    }

    @Test
    fun signalWithNullFragmentIsIgnored() {
        assertNull(link(host = "signal.me", fragment = null))
    }

    @Test
    fun signalFragmentWithoutPrefixStillParses() {
        val result = link(host = "signal.me", fragment = "+15551234567") as DeepLink.Conversation
        assertEquals("+15551234567", result.address)
        assertEquals(CommunicateLine.Signal, result.line)
    }

    @Test
    fun signalCarriesTheShareBody() {
        val result = link(
            host = "signal.me",
            fragment = "p/+15551234567",
            body = "hi",
        ) as DeepLink.Conversation
        assertEquals("hi", result.body)
    }

    @Test
    fun nullSchemeWithUnknownHostIsIgnored() {
        assertNull(link(scheme = null, host = "example.com", path = "/x"))
        assertNull(link(scheme = "ftp", host = "example.com", path = "/x"))
    }

    @Test
    fun unsupportedGroupInvitesCarryTheRightLine() {
        val wa = link(host = "chat.whatsapp.com", path = "/ABC") as DeepLink.UnsupportedGroupInvite
        assertEquals(CommunicateLine.WhatsApp, wa.line)
        val signal = link(host = "signal.group") as DeepLink.UnsupportedGroupInvite
        assertEquals(CommunicateLine.Signal, signal.line)
    }

    @Test
    fun telWithEmptyOpaqueIsIgnored() {
        assertNull(link(scheme = "tel", opaque = ""))
    }

    @Test
    fun conversationDefaultsAreSane() {
        val convo = DeepLink.Conversation(address = "x", line = CommunicateLine.Sim)
        assertEquals(null, convo.body)
        assertEquals(null, convo.remoteId)
        assertEquals(-1L, convo.threadId)
        assertEquals(false, convo.isGroup)
        assertEquals(null, convo.groupTitle)
        assertEquals(null, convo.subscriptionId)
    }

    @Test
    fun conversationAndInviteAreDistinctDeepLinks() {
        val convo: DeepLink = DeepLink.Conversation(address = "x", line = CommunicateLine.Sim)
        val invite: DeepLink = DeepLink.UnsupportedGroupInvite(CommunicateLine.Signal)
        assertTrue(convo is DeepLink.Conversation)
        assertTrue(invite is DeepLink.UnsupportedGroupInvite)
        assertEquals(convo, DeepLink.Conversation(address = "x", line = CommunicateLine.Sim))
    }
}
