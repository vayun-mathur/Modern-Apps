package com.vayunmathur.communicate.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.CommunicateAttachment
import com.vayunmathur.communicate.data.CommunicateCallLogEntry
import com.vayunmathur.communicate.data.CommunicateCallType
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.data.MessageStatus
import com.vayunmathur.communicate.data.SmsMessage
import com.vayunmathur.communicate.data.whatsapp.Reaction
import com.vayunmathur.communicate.data.whatsapp.WhatsAppServiceData
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * W1: Compose coverage for `CallLogsScreenKt` (content rows + delete confirm),
 * `ConversationMessageBubbleKt` (bubbles, ticks, quotes, polls, reactions) and
 * the pure number/region helpers in `NewMessagePickerKt`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CommunicateScreensW1Test {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun entry(
        id: Long, name: String?, number: String, type: CommunicateCallType, duration: Long = 65L,
    ) = CommunicateCallLogEntry(
        id = id, displayName = name, phoneNumber = number, type = type,
        timestampMillis = 1_785_566_000_000L, durationSeconds = duration,
        line = if (id % 2L == 0L) CommunicateLine.GoogleVoice else CommunicateLine.Sim,
    )

    @Test
    fun `call logs content renders every call type and routes clicks`() {
        val called = CopyOnWriteArrayList<CommunicateCallLogEntry>()
        val entries = listOf(
            entry(1L, "Alice", "+14155550102", CommunicateCallType.Incoming),
            entry(2L, null, "+14155550103", CommunicateCallType.Missed, duration = 0L),
            entry(3L, "Carol", "+14155550104", CommunicateCallType.Outgoing, duration = 3_661L),
            entry(4L, null, "+14155550105", CommunicateCallType.Rejected, duration = 5L),
            entry(5L, "Eve", "+14155550106", CommunicateCallType.Blocked, duration = 125L),
            entry(6L, null, "+14155550107", CommunicateCallType.Voicemail, duration = 9L),
            entry(7L, "Gus", "+14155550108", CommunicateCallType.Unknown, duration = 60L),
        )
        rule.setContent { CallLogsContent(entries = entries, onCall = { called += it }, onDelete = {}) }
        rule.onNodeWithText("Alice").assertIsDisplayed()
        rule.onNodeWithText("+14155550103").assertIsDisplayed()
        rule.onNodeWithText("Carol").performClick()
        rule.waitForIdle()
        assertEquals(listOf(3L), called.map { it.id })
    }

    @Test
    fun `call logs delete flows through the confirm dialog`() {
        val deleted = CopyOnWriteArrayList<CommunicateCallLogEntry>()
        val entries = listOf(entry(1L, "Alice", "+14155550102", CommunicateCallType.Incoming))
        rule.setContent { CallLogsContent(entries = entries, onCall = {}, onDelete = { deleted += it }) }
        // Overflow menu -> Delete -> confirm. The overflow button is the
        // last clickable in the row (row, call button, overflow).
        rule.onNodeWithText("Alice").assertIsDisplayed()
        val clickables = rule.onAllNodes(androidx.compose.ui.test.hasClickAction())
        clickables[clickables.fetchSemanticsNodes().size - 1].performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Delete").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Delete").performClick()
        rule.waitForIdle()
        assertEquals(listOf(1L), deleted.map { it.id })
    }

    private fun message(
        body: String = "hello", outgoing: Boolean = true, line: CommunicateLine = CommunicateLine.Sim,
        status: MessageStatus = MessageStatus.None, serviceData: String? = null,
        senderAddress: String? = null, attachments: List<CommunicateAttachment> = emptyList(),
    ) = SmsMessage(
        id = 1L, threadId = 1L, address = "+14155550102", body = body,
        timestampMillis = 1_785_566_000_000L, outgoing = outgoing, read = true,
        line = line, serviceData = serviceData, senderAddress = senderAddress,
        status = status, attachments = attachments,
    )

    @Test
    fun `message bubble renders outgoing text`() {
        rule.setContent { MessageBubble(message(outgoing = true)) }
        rule.onNodeWithText("hello").assertIsDisplayed()
    }

    @Test
    fun `message bubble renders incoming text`() {
        rule.setContent { MessageBubble(message(body = "reply", outgoing = false)) }
        rule.onNodeWithText("reply").assertIsDisplayed()
    }

    @Test
    fun `status tick sent renders a single check`() {
        rule.setContent { MessageStatusTicks(MessageStatus.Sent) }
        rule.onNodeWithText("✓").assertIsDisplayed()
    }

    @Test
    fun `status tick delivered renders a double check`() {
        rule.setContent { MessageStatusTicks(MessageStatus.Delivered) }
        rule.onNodeWithText("✓✓").assertIsDisplayed()
    }

    @Test
    fun `status tick failed renders an exclamation`() {
        rule.setContent { MessageStatusTicks(MessageStatus.Failed) }
        rule.onNodeWithText("!").assertIsDisplayed()
    }

    @Test
    fun `message bubble shows read ticks for outgoing messages`() {
        rule.setContent {
            MessageBubble(message(outgoing = true, status = MessageStatus.Read))
        }
        rule.onNodeWithText("✓✓").assertIsDisplayed()
    }

    @Test
    fun `whatsApp bubble renders quote poll reactions and edited marker`() {
        val sd = WhatsAppServiceData(
            senderName = "Alice",
            reactions = listOf(Reaction(emoji = "👍", count = 2), Reaction(emoji = "❤️", count = 1)),
            isEdited = true,
            pollQuestion = "Lunch?",
            pollOptions = listOf(
                com.vayunmathur.communicate.data.whatsapp.PollOptionData("Pizza", 3),
                com.vayunmathur.communicate.data.whatsapp.PollOptionData("Sushi", 1),
            ),
            quotedBody = "original text",
            quotedSender = "Bob",
        )
        rule.setContent {
            MessageBubble(
                message(
                    outgoing = false, line = CommunicateLine.WhatsApp, serviceData = sd.serialize(),
                ),
            )
        }
        rule.onNodeWithText("Alice").assertIsDisplayed()
        rule.onNodeWithText("Bob: original text").assertIsDisplayed()
        rule.onNodeWithText("📊 Lunch?").assertIsDisplayed()
        rule.onNodeWithText("• Pizza (3)").assertIsDisplayed()
        rule.onNodeWithText("• Sushi (1)").assertIsDisplayed()
        rule.onNodeWithText("👍 2").assertIsDisplayed()
        rule.onNodeWithText("❤️").assertIsDisplayed()
        rule.onNodeWithText("edited").assertIsDisplayed()
    }

    @Test
    fun `revoked whatsApp bubble shows the deleted placeholder`() {
        val sd = WhatsAppServiceData(isRevoked = true)
        rule.setContent {
            MessageBubble(
                message(
                    body = "secret", outgoing = false,
                    line = CommunicateLine.WhatsApp, serviceData = sd.serialize(),
                ),
            )
        }
        rule.onNodeWithText("🚫 This message was deleted").assertIsDisplayed()
    }

    @Test
    fun `signal bubble renders sender and reactions`() {
        val sd = com.vayunmathur.communicate.data.signal.SignalServiceData(
            senderName = "Signal Alice",
            reactions = listOf(
                com.vayunmathur.communicate.data.signal.SignalReaction(emoji = "🎉", count = 1),
            ),
        )
        rule.setContent {
            MessageBubble(
                message(
                    body = "signal hi", outgoing = false,
                    line = CommunicateLine.Signal, serviceData = sd.serialize(),
                ),
            )
        }
        rule.onNodeWithText("signal hi").assertIsDisplayed()
        rule.onNodeWithText("Signal Alice").assertIsDisplayed()
        rule.onNodeWithText("🎉").assertIsDisplayed()
    }

    @Test
    fun `contact pick row renders and clicks`() {
        var clicks = 0
        rule.setContent {
            ContactPickRow(title = "Alice", subtitle = "+14155550102", selected = true) { clicks++ }
        }
        rule.onNodeWithText("Alice").assertIsDisplayed()
        rule.onNodeWithText("+14155550102").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun `contact pick row without subtitle or checkbox clicks`() {
        var clicks = 0
        rule.setContent { ContactPickRow(title = "Bob", subtitle = null) { clicks++ } }
        rule.onNodeWithText("Bob").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun `device region falls back to locale and formatNumber formats`() {
        val tm = context.getSystemService(android.telephony.TelephonyManager::class.java)
        org.robolectric.shadow.api.Shadow.extract<org.robolectric.shadows.ShadowTelephonyManager>(tm)
            .setSimCountryIso("us")
        assertEquals("US", deviceRegion(context))
        assertEquals("(415) 555-0102", formatNumber("4155550102", "US"))
        assertEquals("(415) 555-0102", formatNumber("+14155550102", "US"))
        assertEquals("not a number", formatNumber("not a number", "US"))
        assertEquals("", formatNumber("", "US"))
    }
}
