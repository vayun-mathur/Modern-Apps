package com.vayunmathur.communicate

import com.vayunmathur.communicate.intents.DeepLink
import com.vayunmathur.communicate.intents.DeepLinks

import com.vayunmathur.communicate.data.CommunicateLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Vectors for the vendor link formats. Each vendor puts the number somewhere different — `wa.me` in the path,
 * `api.whatsapp.com` in a query parameter, and `signal.me` in the **fragment**, where a path-based parse finds
 * nothing at all.
 *
 * Drives the component-based parser directly, since `android.net.Uri` is a stub on the JVM.
 */
class DeepLinksTest {
    private fun link(
        scheme: String? = "https",
        host: String? = null,
        path: String? = null,
        fragment: String? = null,
        opaque: String? = null,
        params: Map<String, String> = emptyMap(),
    ) = DeepLinks.resolve(
        scheme = scheme,
        host = host,
        path = path,
        fragment = fragment,
        opaque = opaque,
        query = { params[it] },
    )

    @Test
    fun waMePutsTheNumberInThePath() {
        val result = link(host = "wa.me", path = "/15551234567") as DeepLink.Conversation
        assertEquals("+15551234567", result.address)
        assertEquals(CommunicateLine.WhatsApp, result.line)
    }

    @Test
    fun apiWhatsAppUsesAQueryParameterAndCarriesText() {
        val result = link(
            host = "api.whatsapp.com",
            path = "/send",
            params = mapOf("phone" to "15551234567", "text" to "hello"),
        ) as DeepLink.Conversation
        assertEquals("+15551234567", result.address)
        assertEquals("hello", result.body)
    }

    @Test
    fun whatsAppPrivateSchemeWorks() {
        val result = link(
            scheme = "whatsapp",
            path = "/send",
            params = mapOf("phone" to "15551234567"),
        ) as DeepLink.Conversation
        assertEquals("+15551234567", result.address)
        assertEquals(CommunicateLine.WhatsApp, result.line)
    }

    @Test
    fun signalMeUsesTheFragmentNotThePath() {
        val result = link(host = "signal.me", path = "/", fragment = "p/+15551234567") as DeepLink.Conversation
        assertEquals("+15551234567", result.address)
        assertEquals(CommunicateLine.Signal, result.line)
    }

    @Test
    fun smsSchemeMapsToTheSimLine() {
        val result = link(scheme = "smsto", opaque = "+15551234567") as DeepLink.Conversation
        assertEquals("+15551234567", result.address)
        assertEquals(CommunicateLine.Sim, result.line)
    }

    @Test
    fun smsWithBodyKeepsOnlyTheNumberInTheAddress() {
        val result = link(scheme = "sms", opaque = "+15551234567?body=hi") as DeepLink.Conversation
        assertEquals("+15551234567", result.address)
    }

    @Test
    fun telIsTreatedAsTheSimLine() {
        val result = link(scheme = "tel", opaque = "+15551234567") as DeepLink.Conversation
        assertEquals(CommunicateLine.Sim, result.line)
    }

    @Test
    fun groupInvitesAreReportedRatherThanSwallowed() {
        assertTrue(link(host = "chat.whatsapp.com", path = "/ABC123") is DeepLink.UnsupportedGroupInvite)
        assertTrue(link(host = "signal.group", fragment = "CjQK") is DeepLink.UnsupportedGroupInvite)
    }

    @Test
    fun unrelatedLinksAreIgnored() {
        assertNull(link(host = "example.com", path = "/15551234567"))
        assertNull(link(host = "wa.me", path = "/"))
    }

    @Test
    fun aNumberThatAlreadyHasAPlusIsNotDoubled() {
        val result = link(host = "wa.me", path = "/+15551234567") as DeepLink.Conversation
        assertEquals("+15551234567", result.address)
    }

    @Test
    fun formattingCharactersAreStripped() {
        val result = link(host = "wa.me", path = "/1 (555) 123-4567") as DeepLink.Conversation
        assertEquals("+15551234567", result.address)
    }
}
