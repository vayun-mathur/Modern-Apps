package com.vayunmathur.communicate.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.data.CommunicateAttachment
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.data.MessageStatus
import com.vayunmathur.communicate.data.SmsMessage
import com.vayunmathur.communicate.data.whatsapp.WhatsAppServiceData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * UiPass3 coverage for message bubbles and conversation extras
 * (`ConversationMessageBubble.kt`, `ConversationMessagesList.kt`,
 * `ConversationAttachments.kt`): bodies, ticks, quoted/poll/revoked extras,
 * safety-number + encryption banners and attachment rows.
 */
@RunWith(RobolectricTestRunner::class)
class ConversationBubbleUiPass3Test {

    @get:Rule
    val rule = createComposeRule()

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private fun str(id: Int): String = context.getString(id)

    private fun message(
        id: Long = 1L,
        body: String = "hello there",
        outgoing: Boolean = false,
        line: CommunicateLine = CommunicateLine.Sim,
        serviceData: String? = null,
        status: MessageStatus = MessageStatus.None,
        attachments: List<CommunicateAttachment> = emptyList(),
        senderAddress: String? = null,
    ) = SmsMessage(
        id = id,
        threadId = 7L,
        address = "+1555",
        body = body,
        timestampMillis = System.currentTimeMillis(),
        outgoing = outgoing,
        read = true,
        line = line,
        serviceData = serviceData,
        status = status,
        attachments = attachments,
        senderAddress = senderAddress,
    )

    @Test
    fun `incoming and outgoing bodies render`() {
        var outgoing by androidx.compose.runtime.mutableStateOf(false)
        rule.setContent { MessageBubble(message(body = if (outgoing) "from me" else "from them", outgoing = outgoing)) }
        rule.onNodeWithText("from them").assertIsDisplayed()
        rule.runOnIdle { outgoing = true }
        rule.waitForIdle()
        rule.onNodeWithText("from me").assertIsDisplayed()
    }

    @Test
    fun `outgoing bubble shows delivery ticks`() {
        var status by androidx.compose.runtime.mutableStateOf(MessageStatus.Read)
        rule.setContent { MessageBubble(message(outgoing = true, status = status)) }
        rule.onNodeWithText("✓✓").assertIsDisplayed()
        rule.runOnIdle { status = MessageStatus.Failed }
        rule.waitForIdle()
        rule.onNodeWithText("!").assertIsDisplayed()
    }

    @Test
    fun `revoked whatsapp message hides its body`() {
        val sd = WhatsAppServiceData(isRevoked = true).serialize()
        rule.setContent {
            MessageBubble(message(body = "secret", line = CommunicateLine.WhatsApp, serviceData = sd))
        }
        rule.onNodeWithText("🚫 This message was deleted").assertIsDisplayed()
        rule.onNodeWithText("secret").assertDoesNotExist()
    }

    @Test
    fun `edited whatsapp message is marked edited`() {
        val sd = WhatsAppServiceData(isEdited = true).serialize()
        rule.setContent {
            MessageBubble(message(body = "fixed typo", line = CommunicateLine.WhatsApp, serviceData = sd))
        }
        rule.onNodeWithText("fixed typo").assertIsDisplayed()
        rule.onNodeWithText("edited").assertIsDisplayed()
    }

    @Test
    fun `quoted reply preview shows sender and body`() {
        val sd = WhatsAppServiceData(quotedBody = "original text", quotedSender = "Pat").serialize()
        rule.setContent {
            MessageBubble(message(body = "reply!", line = CommunicateLine.WhatsApp, serviceData = sd))
        }
        rule.onNodeWithText("Pat: original text").assertIsDisplayed()
        rule.onNodeWithText("reply!").assertIsDisplayed()
    }

    @Test
    fun `poll renders question and options with votes`() {
        val sd = WhatsAppServiceData(
            pollQuestion = "Lunch?",
            pollOptions = listOf(
                com.vayunmathur.communicate.data.whatsapp.PollOptionData("Pizza", 3),
                com.vayunmathur.communicate.data.whatsapp.PollOptionData("Sushi", 1),
            ),
        ).serialize()
        rule.setContent {
            MessageBubble(message(body = "pick one", line = CommunicateLine.WhatsApp, serviceData = sd))
        }
        rule.onNodeWithText("📊 Lunch?").assertIsDisplayed()
        rule.onNodeWithText("• Pizza (3)").assertIsDisplayed()
        rule.onNodeWithText("• Sushi (1)").assertIsDisplayed()
    }

    @Test
    fun `group sender name from service data labels the bubble`() {
        val sd = WhatsAppServiceData(senderName = "Group Pat").serialize()
        rule.setContent {
            MessageBubble(message(line = CommunicateLine.WhatsApp, serviceData = sd))
        }
        rule.waitForIdle()
        rule.onNodeWithText("Group Pat").assertIsDisplayed()
    }

    @Test
    fun `document attachment renders a fallback row with its mime type`() {
        val att = CommunicateAttachment("content://doc/1", "application/pdf", "report.pdf")
        rule.setContent { MessageBubble(message(attachments = listOf(att))) }
        rule.onNodeWithText("application/pdf").assertIsDisplayed()
    }

    @Test
    fun `reactions render as chips with counts`() {
        val sd = WhatsAppServiceData(
            reactions = listOf(
                com.vayunmathur.communicate.data.whatsapp.Reaction("👍", 2),
                com.vayunmathur.communicate.data.whatsapp.Reaction("❤️", 1),
            ),
        ).serialize()
        rule.setContent {
            MessageBubble(message(line = CommunicateLine.WhatsApp, serviceData = sd))
        }
        rule.onNodeWithText("👍 2").assertIsDisplayed()
        rule.onNodeWithText("❤️").assertIsDisplayed()
    }

    @Test
    fun `incoming group bubble falls back to the sender address`() {
        rule.setContent {
            MessageBubble(message(senderAddress = "+1666"))
        }
        rule.waitForIdle()
        rule.onNodeWithText("+1666").assertIsDisplayed()
    }

    // -- messages list states -------------------------------------------------

    @Test
    fun `messages content shows empty state for no rows`() {
        rule.setContent { MessagesContent(PaddingValues(0.dp), emptyList()) }
        rule.onNodeWithText(str(R.string.empty_messages)).assertIsDisplayed()
    }

    @Test
    fun `messages content lists bubbles`() {
        rule.setContent {
            MessagesContent(
                PaddingValues(0.dp),
                listOf(message(id = 1L, body = "one"), message(id = 2L, body = "two")),
            )
        }
        rule.onNodeWithText("one").assertIsDisplayed()
        rule.onNodeWithText("two").assertIsDisplayed()
    }

    // -- banners ---------------------------------------------------------------

    @Test
    fun `safety number banner formats the number and accepts`() {
        var accepted = 0
        rule.setContent {
            SafetyNumberBanner(safetyNumber = "1234567890", onAccept = { accepted++ })
        }
        rule.onNodeWithText(str(R.string.signal_safety_number_changed_title)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.signal_safety_number_accept)).performClick()
        rule.waitForIdle()
        assertEquals(1, accepted)
    }

    @Test
    fun `encrypted banner distinguishes pending from active`() {
        var pending by androidx.compose.runtime.mutableStateOf(false)
        rule.setContent { EncryptedChatBanner(pending = pending) }
        rule.onNodeWithText(str(R.string.rcs_encrypted_banner)).assertIsDisplayed()
        rule.runOnIdle { pending = true }
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.rcs_encrypted_pending)).assertIsDisplayed()
    }

    // -- attachments -------------------------------------------------------------

    @Test
    fun `attachment fallback row reports clicks`() {
        var clicks = 0
        val att = CommunicateAttachment("content://doc/2", "text/plain", "notes.txt")
        rule.setContent {
            AttachmentFallbackRow(
                attachment = att,
                bubbleColor = androidx.compose.ui.graphics.Color.Gray,
                contentColor = androidx.compose.ui.graphics.Color.White,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                onClick = { clicks++ },
            )
        }
        rule.onNodeWithText("text/plain").assertIsDisplayed()
        rule.onNodeWithText("text/plain").performClick()
        rule.waitForIdle()
        assertEquals(1, clicks)
    }

    @Test
    fun `displayNameOf returns null for an unresolvable uri`() {
        assertNull(displayNameOf(context, android.net.Uri.parse("content://missing/1")))
    }
}
