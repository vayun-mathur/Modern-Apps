package com.vayunmathur.communicate

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.notifications.ConversationSpace
import com.vayunmathur.communicate.notifications.ConversationTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Slice 5/6 (root package A-L): [DeepLinks.parse] over real [Intent]/[Uri] under Robolectric.
 *
 * `android.net.Uri` is a stub on the JVM, so these paths (`parse`, `fromUri`, `fromConversationExtras`)
 * have zero coverage from the JVM suite. Uses real `Uri.parse` strings plus the real
 * [ConversationSpace.putExtras] writer so the extras contract is verified end to end.
 */
@RunWith(RobolectricTestRunner::class)
class DeepLinksParseSlice5Test {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun intent(action: String, data: String, text: String? = null): Intent =
        Intent(action, Uri.parse(data)).apply {
            if (text != null) putExtra(Intent.EXTRA_TEXT, text)
        }

    @Test
    fun nullIntentReturnsNull() {
        assertNull(DeepLinks.parse(null))
    }

    @Test
    fun intentWithoutDataReturnsNull() {
        assertNull(DeepLinks.parse(Intent(Intent.ACTION_VIEW)))
    }

    @Test
    fun unsupportedActionIsIgnored() {
        assertNull(DeepLinks.parse(intent(Intent.ACTION_SEND, "smsto:+15551234567")))
    }

    @Test
    fun smsViewIntentParsesToSimLine() {
        val result = DeepLinks.parse(intent(Intent.ACTION_VIEW, "smsto:+15551234567"))
            as DeepLink.Conversation
        assertEquals("+15551234567", result.address)
        assertEquals(CommunicateLine.Sim, result.line)
    }

    @Test
    fun smsSendToIntentCarriesTheSharedText() {
        val result = DeepLinks.parse(intent(Intent.ACTION_SENDTO, "sms:+15551234567", text = "hi"))
            as DeepLink.Conversation
        assertEquals("hi", result.body)
    }

    @Test
    fun dialIntentMapsToSimLine() {
        val result = DeepLinks.parse(intent(Intent.ACTION_DIAL, "tel:+15551234567"))
            as DeepLink.Conversation
        assertEquals(CommunicateLine.Sim, result.line)
        assertEquals("+15551234567", result.address)
    }

    @Test
    fun callIntentMapsToSimLine() {
        val result = DeepLinks.parse(intent(Intent.ACTION_CALL, "tel:+15551234567"))
            as DeepLink.Conversation
        assertEquals(CommunicateLine.Sim, result.line)
    }

    @Test
    fun waMeViewIntentParsesToWhatsAppLine() {
        val result = DeepLinks.parse(intent(Intent.ACTION_VIEW, "https://wa.me/15551234567"))
            as DeepLink.Conversation
        assertEquals("+15551234567", result.address)
        assertEquals(CommunicateLine.WhatsApp, result.line)
    }

    @Test
    fun apiWhatsAppLinkCarriesPhoneAndTextQueryParams() {
        val result = DeepLinks.parse(
            intent(Intent.ACTION_VIEW, "https://api.whatsapp.com/send?phone=15551234567&text=hello"),
        ) as DeepLink.Conversation
        assertEquals("+15551234567", result.address)
        assertEquals("hello", result.body)
    }

    @Test
    fun signalMeLinkParsesFragmentNumber() {
        val result = DeepLinks.parse(intent(Intent.ACTION_VIEW, "https://signal.me/#p/+15551234567"))
            as DeepLink.Conversation
        assertEquals("+15551234567", result.address)
        assertEquals(CommunicateLine.Signal, result.line)
    }

    @Test
    fun whatsAppGroupInviteReturnsUnsupported() {
        assertTrue(
            DeepLinks.parse(intent(Intent.ACTION_VIEW, "https://chat.whatsapp.com/ABC123"))
                is DeepLink.UnsupportedGroupInvite,
        )
    }

    @Test
    fun unknownLinkReturnsNull() {
        assertNull(DeepLinks.parse(intent(Intent.ACTION_VIEW, "https://example.com/15551234567")))
    }

    @Test
    fun conversationExtrasRoundTripThroughPutExtras() {
        val target = ConversationTarget(
            line = CommunicateLine.WhatsApp,
            address = "+15551234567",
            remoteId = "remote-1",
            threadId = 42L,
            isGroup = true,
            title = "Group",
            personName = "Alice",
            subscriptionId = 3,
        )
        val tap = ConversationSpace.putExtras(
            Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW),
            target,
        )
        val result = DeepLinks.parse(tap) as DeepLink.Conversation
        assertEquals("+15551234567", result.address)
        assertEquals(CommunicateLine.WhatsApp, result.line)
        assertEquals("remote-1", result.remoteId)
        assertEquals(42L, result.threadId)
        assertEquals(true, result.isGroup)
        assertEquals("Group", result.groupTitle)
        assertEquals(3, result.subscriptionId)
    }

    @Test
    fun conversationExtrasWinOverDataUri() {
        val tap = ConversationSpace.putExtras(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/19998887777")),
            ConversationTarget(
                line = CommunicateLine.Sim,
                address = "+15551234567",
                personName = "Bob",
            ),
        )
        val result = DeepLinks.parse(tap) as DeepLink.Conversation
        assertEquals(CommunicateLine.Sim, result.line)
        assertEquals("+15551234567", result.address)
    }

    @Test
    fun conversationExtrasWithMissingLineFallsBackToUri() {
        val tap = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/15551234567"))
            .putExtra(ConversationSpace.EXTRA_ADDRESS, "+10000000000")
        val result = DeepLinks.parse(tap) as DeepLink.Conversation
        assertEquals(CommunicateLine.WhatsApp, result.line)
        assertEquals("+15551234567", result.address)
    }

    @Test
    fun conversationExtrasWithBadLineNameFallsBackToUri() {
        val tap = Intent(Intent.ACTION_VIEW, Uri.parse("smsto:+15551234567"))
            .putExtra(ConversationSpace.EXTRA_LINE, "NotALine")
        val result = DeepLinks.parse(tap) as DeepLink.Conversation
        assertEquals(CommunicateLine.Sim, result.line)
    }

    @Test
    fun conversationExtrasWithoutSubIdLeaveItNull() {
        val tap = Intent(Intent.ACTION_VIEW)
            .putExtra(ConversationSpace.EXTRA_LINE, CommunicateLine.Signal.name)
            .putExtra(ConversationSpace.EXTRA_ADDRESS, "+15551234567")
        val result = DeepLinks.parse(tap) as DeepLink.Conversation
        assertEquals(CommunicateLine.Signal, result.line)
        assertEquals("+15551234567", result.address)
        assertEquals(null, result.subscriptionId)
        assertEquals(-1L, result.threadId)
        assertEquals(false, result.isGroup)
        assertEquals(null, result.groupTitle)
        assertEquals(null, result.remoteId)
    }

    @Test
    fun fromUriDefaultOverloadWorks() {
        val result = DeepLinks.fromUri(Uri.parse("smsto:+15551234567")) as DeepLink.Conversation
        assertEquals("+15551234567", result.address)
    }

    @Test
    fun fromUriWithUnknownSchemeReturnsNull() {
        assertNull(DeepLinks.fromUri(Uri.parse("https://example.com/x")))
    }
}
