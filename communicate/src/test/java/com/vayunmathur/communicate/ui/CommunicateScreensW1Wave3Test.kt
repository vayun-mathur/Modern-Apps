package com.vayunmathur.communicate.ui

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.CommunicateAttachment
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.data.FakeContactsProvider
import com.vayunmathur.communicate.data.FakeContactsState
import com.vayunmathur.communicate.data.LineChoice
import com.vayunmathur.communicate.data.SmsMessage
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * W1 wave 3: interactive Compose flows — the new-message picker (search,
 * group mode, create), the compose row, the poll composer, message-list
 * states and attachment fallbacks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CommunicateScreensW1Wave3Test {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @BeforeTest
    fun setUp() {
        FakeContactsState.reset()
        Robolectric.setupContentProvider(FakeContactsProvider::class.java, android.provider.ContactsContract.AUTHORITY)
        shadowOf(context as Application).grantPermissions(Manifest.permission.READ_CONTACTS)
        FakeContactsState.phoneRows = listOf(
            mapOf(
                android.provider.ContactsContract.CommonDataKinds.Phone._ID to 1L,
                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY to "Alice",
                android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER to "+14155550102",
                android.provider.ContactsContract.CommonDataKinds.Phone.TYPE to 2,
                android.provider.ContactsContract.CommonDataKinds.Phone.LABEL to null,
            ),
            mapOf(
                android.provider.ContactsContract.CommonDataKinds.Phone._ID to 2L,
                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY to "Bob",
                android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER to "+14155550103",
                android.provider.ContactsContract.CommonDataKinds.Phone.TYPE to 2,
                android.provider.ContactsContract.CommonDataKinds.Phone.LABEL to null,
            ),
        )
    }

    @Test
    fun `picker lists contacts and composes to the tapped contact`() {
        val composed = CopyOnWriteArrayList<Pair<LineChoice, String>>()
        rule.setContent {
            NewMessagePicker(
                choices = listOf(LineChoice.Sim(-1, "SIM")),
                onDismiss = {},
                onCompose = { choice, address -> composed += choice to address },
                onCreateGroup = { _, _, _, _ -> },
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("Alice").assertIsDisplayed()
        rule.onNodeWithText("Bob").assertIsDisplayed()
        rule.onNodeWithText("Bob").performClick()
        assertEquals(listOf(LineChoice.Sim(-1, "SIM") to "+14155550103"), composed.toList())
    }

    @Test
    fun `picker search filters and offers a raw number row`() {
        val composed = CopyOnWriteArrayList<Pair<LineChoice, String>>()
        rule.setContent {
            NewMessagePicker(
                choices = listOf(LineChoice.Sim(-1, "SIM")),
                onDismiss = {},
                onCompose = { choice, address -> composed += choice to address },
                onCreateGroup = { _, _, _, _ -> },
            )
        }
        rule.waitForIdle()
        rule.onNode(hasSetTextAction()).performTextInput("9999")
        rule.waitForIdle()
        rule.onAllNodesWithText("Alice").fetchSemanticsNodes().let { assertEquals(0, it.size) }
        // Raw-number fallback row appears for an unknown number.
        val sendNodes = rule.onAllNodesWithText("Send to", substring = true)
        assertTrue(sendNodes.fetchSemanticsNodes().isNotEmpty())
        sendNodes[0].performClick()
        assertEquals(listOf("9999"), composed.map { it.second })
    }

    @Test
    fun `picker group mode creates a group from selected contacts`() {
        val created = CopyOnWriteArrayList<List<String>>()
        rule.setContent {
            NewMessagePicker(
                choices = listOf(LineChoice.WhatsApp, LineChoice.Sim(-1, "SIM")),
                onDismiss = {},
                onCompose = { _, _ -> },
                onCreateGroup = { _, _, members, _ -> created += members },
            )
        }
        rule.waitForIdle()
        rule.onNode(isToggleable()).performClick() // Group switch
        rule.waitForIdle()
        rule.onNodeWithText("New group").assertIsDisplayed()
        rule.onNodeWithText("Create").performClick()
        assertEquals(0, created.size) // nobody selected yet
        // Toggleables: the Group switch, then one checkbox per contact row.
        rule.onAllNodes(isToggleable())[1].performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Create").performClick()
        assertEquals(listOf(listOf("+14155550102")), created.toList())
    }

    @Test
    fun `compose row sends attaches and removes`() {
        val events = CopyOnWriteArrayList<String>()
        val attachment = CommunicateAttachment("content://x/1", "application/pdf", "doc.pdf")
        rule.setContent {
            ComposeSmsRow(
                draft = "hello",
                onDraftChange = { events += "draft:$it" },
                attachments = listOf(attachment),
                onAttachMedia = { events += "media" },
                onAttachFile = { events += "file" },
                onCreatePoll = { events += "poll" },
                onShareContact = { events += "contact" },
                onRemoveAttachment = { events += "remove:${it.fileName}" },
                onSend = { events += "send" },
            )
        }
        rule.onNodeWithText("application/pdf").assertIsDisplayed()
        // Send button: last clickable.
        val clickables = rule.onAllNodes(hasClickAction())
        clickables[clickables.fetchSemanticsNodes().size - 1].performClick()
        assertTrue(events.contains("send"), "$events")
    }

    @Test
    fun `compose row attach menu offers poll and contact`() {
        val events = CopyOnWriteArrayList<String>()
        rule.setContent {
            ComposeSmsRow(
                draft = "",
                onDraftChange = {},
                attachments = emptyList(),
                onAttachMedia = { events += "media" },
                onAttachFile = { events += "file" },
                onCreatePoll = { events += "poll" },
                onShareContact = { events += "contact" },
                onRemoveAttachment = {},
                onSend = {},
            )
        }
        val clickables = rule.onAllNodes(hasClickAction())
        clickables[0].performClick() // attach overflow
        rule.waitForIdle()
        rule.onNodeWithText("Poll").performClick()
        rule.waitForIdle()
        assertTrue(events.contains("poll"), "$events")
    }

    @Test
    fun `poll composer creates a poll after adding an option`() {
        val created = CopyOnWriteArrayList<Pair<String, List<String>>>()
        rule.setContent {
            PollComposerDialog(
                onDismiss = {},
                onCreate = { q, opts -> created += q to opts },
            )
        }
        val fields = rule.onAllNodes(hasSetTextAction())
        fields[0].performTextInput("Lunch?")
        rule.waitForIdle()
        val fields2 = rule.onAllNodes(hasSetTextAction())
        fields2[1].performTextInput("Pizza")
        fields2[2].performTextInput("Sushi")
        rule.waitForIdle()
        rule.onNodeWithText("Add option").performClick()
        rule.waitForIdle()
        val fields3 = rule.onAllNodes(hasSetTextAction())
        fields3[3].performTextInput("Tacos")
        rule.waitForIdle()
        rule.onNodeWithText("Create").performClick()
        assertEquals(listOf("Lunch?" to listOf("Pizza", "Sushi", "Tacos")), created.toList())
    }

    private fun message(id: Long, body: String) = SmsMessage(
        id = id, threadId = 1L, address = "+14155550102", body = body,
        timestampMillis = 1_785_566_000_000L, outgoing = id % 2L == 0L, read = true,
    )

    @Test
    fun `messages content shows empty and loaded states`() {
        rule.setContent { MessagesContent(PaddingValues(0.dp), emptyList()) }
        rule.onNodeWithText("No conversations yet").assertIsDisplayed()
    }

    @Test
    fun `messages content renders bubbles`() {
        rule.setContent {
            MessagesContent(PaddingValues(0.dp), listOf(message(1L, "first"), message(2L, "second")))
        }
        rule.onNodeWithText("first").assertIsDisplayed()
        rule.onNodeWithText("second").assertIsDisplayed()
    }

    @Test
    fun `safety number banner accepts and encrypted banner renders`() {
        var accepted = 0
        rule.setContent {
            SafetyNumberBanner(safetyNumber = "12345678901234567890", onAccept = { accepted++ })
            EncryptedChatBanner(pending = true)
        }
        rule.onNodeWithText("Safety number changed", substring = true).assertIsDisplayed()
        rule.onAllNodesWithText("Accept new safety number", substring = true)[0].performClick()
        assertEquals(1, accepted)
    }

    @Test
    fun `attachment fallback row shows mime and clicks`() {
        var clicks = 0
        rule.setContent {
            AttachmentFallbackRow(
                attachment = CommunicateAttachment("content://x/1", "application/pdf", "doc.pdf"),
                bubbleColor = Color.Blue,
                contentColor = Color.White,
                shape = RoundedCornerShape(8.dp),
                onClick = { clicks++ },
            )
        }
        rule.onNodeWithText("application/pdf").assertIsDisplayed()
        rule.onNodeWithText("application/pdf").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun `displayNameOf reads the picker name from the provider`() {
        Robolectric.setupContentProvider(
            com.vayunmathur.communicate.data.FakeMmsProvider::class.java, "w1docs",
        )
        com.vayunmathur.communicate.data.FakeTelephonyState.reset()
        // No row for this uri in the fake provider: the helper must fail soft.
        assertEquals(
            null,
            displayNameOf(context, android.net.Uri.parse("content://w1docs/1")),
        )
    }
}
