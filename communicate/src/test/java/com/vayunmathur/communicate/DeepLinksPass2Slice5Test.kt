package com.vayunmathur.communicate

import android.content.Intent
import android.net.Uri
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.notifications.ConversationSpace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Slice 5/6 (root package A-L), second pass: the branches the first pass left in [DeepLinks].
 *
 * The Robolectric exec data now reaches the merged report, so `parse` / `fromUri` /
 * `fromConversationExtras` are covered here directly: a missing address extra, a negative
 * subscription id, a lower-case line name, and an opaque `whatsapp:` URI whose query accessor
 * throws (the `runCatching` in `fromUri`'s query lambda). The rest drive [DeepLinks.resolve]
 * over plain components for the null-path and case branches.
 */
@RunWith(RobolectricTestRunner::class)
class DeepLinksPass2Slice5Test {

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

    // ---- fromConversationExtras branches (via parse) ----

    @Test
    fun extrasWithoutAddressDefaultToEmptyAddress() {
        val tap = Intent(Intent.ACTION_VIEW)
            .putExtra(ConversationSpace.EXTRA_LINE, CommunicateLine.Rcs.name)
        val result = DeepLinks.parse(tap) as DeepLink.Conversation
        assertEquals("", result.address)
        assertEquals(CommunicateLine.Rcs, result.line)
        assertNull(result.remoteId)
        assertNull(result.subscriptionId)
    }

    @Test
    fun extrasWithNegativeSubIdLeaveItNullButZeroIsKept() {
        val negative = Intent(Intent.ACTION_VIEW)
            .putExtra(ConversationSpace.EXTRA_LINE, CommunicateLine.Sim.name)
            .putExtra(ConversationSpace.EXTRA_ADDRESS, "+15551234567")
            .putExtra(ConversationSpace.EXTRA_SUB_ID, -5)
        assertNull((DeepLinks.parse(negative) as DeepLink.Conversation).subscriptionId)

        val zero = Intent(Intent.ACTION_VIEW)
            .putExtra(ConversationSpace.EXTRA_LINE, CommunicateLine.Sim.name)
            .putExtra(ConversationSpace.EXTRA_ADDRESS, "+15551234567")
            .putExtra(ConversationSpace.EXTRA_SUB_ID, 0)
        assertEquals(0, (DeepLinks.parse(zero) as DeepLink.Conversation).subscriptionId)
    }

    @Test
    fun extrasWithLowerCaseLineNameAreRejected() {
        // valueOf is case-sensitive: a lower-case line extra is not a conversation extra,
        // so parsing falls through to the (absent) data URI and returns null.
        val tap = Intent(Intent.ACTION_VIEW)
            .putExtra(ConversationSpace.EXTRA_LINE, "sim")
            .putExtra(ConversationSpace.EXTRA_ADDRESS, "+15551234567")
        assertNull(DeepLinks.parse(tap))
    }

    @Test
    fun extrasForGoogleVoiceLineParse() {
        val tap = Intent(Intent.ACTION_VIEW)
            .putExtra(ConversationSpace.EXTRA_LINE, CommunicateLine.GoogleVoice.name)
            .putExtra(ConversationSpace.EXTRA_ADDRESS, "+15551234567")
            .putExtra(ConversationSpace.EXTRA_THREAD_ID, 7L)
            .putExtra(ConversationSpace.EXTRA_IS_GROUP, true)
            .putExtra(ConversationSpace.EXTRA_TITLE, "Team")
        val result = DeepLinks.parse(tap) as DeepLink.Conversation
        assertEquals(CommunicateLine.GoogleVoice, result.line)
        assertEquals(7L, result.threadId)
        assertTrue(result.isGroup)
        assertEquals("Team", result.groupTitle)
    }

    // ---- parse / fromUri over real Uris ----

    @Test
    fun opaqueWhatsAppUriReturnsNullInsteadOfThrowing() {
        // An opaque URI has no query accessor: getQueryParameter throws, fromUri's
        // runCatching turns that into a null phone, and there is no path to fall back to.
        assertNull(DeepLinks.fromUri(Uri.parse("whatsapp:send?phone=15551234567")))
    }

    @Test
    fun sgnlSchemeSignalLinkParsesFragmentNumber() {
        val result = DeepLinks.fromUri(Uri.parse("sgnl://signal.me/#p/15551234567"))
            as DeepLink.Conversation
        assertEquals("+15551234567", result.address)
        assertEquals(CommunicateLine.Signal, result.line)
    }

    @Test
    fun signalGroupHostReturnsUnsupportedInvite() {
        val result = DeepLinks.parse(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://signal.group/#CjQK"))
        )
        assertEquals(DeepLink.UnsupportedGroupInvite(CommunicateLine.Signal), result)
    }

    @Test
    fun intentWithoutActionIgnoresItsData() {
        val intent = Intent().apply { data = Uri.parse("smsto:+15551234567") }
        assertNull(DeepLinks.parse(intent))
    }

    @Test
    fun waMeTextQueryIsDecodedAndBeatsExtraText() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/15551234567?text=hello%20there"))
            .putExtra(Intent.EXTRA_TEXT, "from-share")
        val result = DeepLinks.parse(intent) as DeepLink.Conversation
        assertEquals("hello there", result.body)
    }

    @Test
    fun smsUriWithQueryBodyKeepsOnlyTheNumber() {
        val result = DeepLinks.fromUri(Uri.parse("sms:+15551234567?body=hi"))
            as DeepLink.Conversation
        assertEquals("+15551234567", result.address)
        assertNull(result.body)
    }

    // ---- resolve: null-path, case and trimming branches ----

    @Test
    fun whatsAppWithNullPathAndNoPhoneIsIgnored() {
        assertNull(link(host = "wa.me", path = null))
        assertNull(link(host = "api.whatsapp.com", path = null, params = mapOf("phone" to " ")))
    }

    @Test
    fun whatsAppWithNullPathUsesPhoneParam() {
        val result = link(
            host = "api.whatsapp.com", path = null,
            params = mapOf("phone" to "+1 (555) 123-4567"),
        ) as DeepLink.Conversation
        assertEquals("+15551234567", result.address)
    }

    @Test
    fun groupInviteHostsAreCaseInsensitive() {
        assertEquals(
            DeepLink.UnsupportedGroupInvite(CommunicateLine.WhatsApp),
            link(host = "CHAT.WHATSAPP.COM", path = "/ABC"),
        )
        assertEquals(
            DeepLink.UnsupportedGroupInvite(CommunicateLine.Signal),
            link(host = "SIGNAL.GROUP"),
        )
    }

    @Test
    fun telSchemeIsCaseInsensitiveAndTrimsAddress() {
        val result = link(scheme = "TEL", opaque = "  +15551234567  ") as DeepLink.Conversation
        assertEquals("+15551234567", result.address)
        assertEquals(CommunicateLine.Sim, result.line)
    }

    @Test
    fun smsWithOnlyAQueryInTheOpaquePartIsIgnored() {
        assertNull(link(scheme = "sms", opaque = "?body=hi"))
        assertNull(link(scheme = "sms", opaque = ""))
    }

    @Test
    fun signalFragmentIsTrimmedAroundThePrefix() {
        val result = link(host = "signal.me", fragment = "  p/+1 (555) 123-4567  ")
            as DeepLink.Conversation
        assertEquals("+15551234567", result.address)
        assertNull(link(host = "signal.me", fragment = "p/"))
        assertNull(link(host = "signal.me", fragment = "   "))
    }

    @Test
    fun whatsAppPathMixingLettersAndDigitsIsNormalised() {
        val result = link(host = "wa.me", path = "/call-15551234567/") as DeepLink.Conversation
        assertEquals("+15551234567", result.address)
    }

    @Test
    fun resolveWithEverythingNullIsIgnored() {
        assertNull(
            DeepLinks.resolve(
                scheme = null, host = null, path = null,
                fragment = null, opaque = null, query = { null },
            )
        )
    }

    @Test
    fun conversationCopyAndInviteEquality() {
        val convo = DeepLink.Conversation("+1555", CommunicateLine.Sim, body = "hi")
        assertEquals(convo, convo.copy())
        assertEquals("+1555", convo.copy(address = "+1555").address)
        assertTrue(convo.toString().contains("+1555"))
        assertEquals(
            DeepLink.UnsupportedGroupInvite(CommunicateLine.WhatsApp),
            DeepLink.UnsupportedGroupInvite(CommunicateLine.WhatsApp).copy(),
        )
    }
}
