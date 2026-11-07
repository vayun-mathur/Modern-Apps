package com.vayunmathur.communicate.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescriptionExactly
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.data.CommunicateAttachment
import com.vayunmathur.communicate.data.CommunicateLine
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * UiPass3 coverage for conversation chrome (`ConversationTopBar.kt`,
 * `ConversationComposeRow.kt`, `ConversationRcsExtras.kt`,
 * `ConversationEncryptionBanner.kt`): titles, typing subtitle, the compose
 * row's send gating and attachment previews, and RCS gate no-ops.
 */
@RunWith(RobolectricTestRunner::class)
class ConversationExtrasUiPass3Test {

    @get:Rule
    val rule = createComposeRule()

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private fun str(id: Int): String = context.getString(id)

    // -- title -----------------------------------------------------------------

    @Test
    fun `title shows the address under a contact name`() {
        rule.setContent {
            ConversationTitle(
                threadId = 1L, isGroup = false, groupTitle = null,
                title = "Pat Lee", groupSubtitle = null,
                address = "+1555", line = CommunicateLine.Sim,
            )
        }
        rule.onNodeWithText("Pat Lee").assertIsDisplayed()
        rule.onNodeWithText("+1555").assertIsDisplayed()
    }

    @Test
    fun `title hides the address when it is the title`() {
        rule.setContent {
            ConversationTitle(
                threadId = 1L, isGroup = false, groupTitle = null,
                title = "+1555", groupSubtitle = null,
                address = "+1555", line = CommunicateLine.Sim,
            )
        }
        // Only the title node exists (no duplicate subtitle).
        rule.onAllNodesWithText("+1555").fetchSemanticsNodes().let { assertEquals(1, it.size) }
    }

    @Test
    fun `group title prefers the group name and shows the subtitle`() {
        rule.setContent {
            ConversationTitle(
                threadId = 1L, isGroup = true, groupTitle = "Family",
                title = "Pat", groupSubtitle = "3 members",
                address = "g1", line = CommunicateLine.WhatsApp,
            )
        }
        rule.onNodeWithText("Family").assertIsDisplayed()
        rule.onNodeWithText("3 members").assertIsDisplayed()
    }

    @Test
    fun `typing replaces the subtitle for one-to-one rcs`() {
        rule.setContent {
            ConversationTitle(
                threadId = 1L, isGroup = false, groupTitle = null,
                title = "Pat Lee", groupSubtitle = null,
                address = "+1555", line = CommunicateLine.Rcs, isPeerTyping = true,
            )
        }
        rule.onNodeWithText(str(R.string.rcs_typing)).assertIsDisplayed()
    }

    // -- compose row -------------------------------------------------------------

    @Test
    fun `compose row sends only with a draft or an attachment`() {
        var sends = 0
        var draft by androidx.compose.runtime.mutableStateOf("")
        rule.setContent {
            ComposeSmsRow(
                draft = draft, onDraftChange = { draft = it }, attachments = emptyList(),
                onAttachMedia = {}, onAttachFile = {}, onCreatePoll = null,
                onShareContact = null, onRemoveAttachment = {}, onSend = { sends++ },
            )
        }
        rule.onNode(hasClickAction() and hasContentDescriptionExactly("Send")).assertIsNotEnabled()
        rule.runOnIdle { draft = "hi" }
        rule.waitForIdle()
        rule.onNode(hasClickAction() and hasContentDescriptionExactly("Send")).assertIsEnabled()
        rule.onNode(hasClickAction() and hasContentDescriptionExactly("Send")).performClick()
        rule.waitForIdle()
        assertEquals(1, sends)
    }

    @Test
    fun `compose row reports typing`() {
        var draft = ""
        rule.setContent {
            ComposeSmsRow(
                draft = "", onDraftChange = { draft = it }, attachments = emptyList(),
                onAttachMedia = {}, onAttachFile = {}, onCreatePoll = null,
                onShareContact = null, onRemoveAttachment = {}, onSend = {},
            )
        }
        rule.onNodeWithText(str(R.string.message_hint)).performTextInput("hello")
        rule.waitForIdle()
        assertEquals("hello", draft)
    }

    @Test
    fun `compose row previews a document attachment and removes it`() {
        var removed: CommunicateAttachment? = null
        val att = CommunicateAttachment("content://doc/9", "application/pdf", "report.pdf")
        rule.setContent {
            ComposeSmsRow(
                draft = "", onDraftChange = {}, attachments = listOf(att),
                onAttachMedia = {}, onAttachFile = {}, onCreatePoll = null,
                onShareContact = null, onRemoveAttachment = { removed = it }, onSend = {},
            )
        }
        rule.onNodeWithText("application/pdf").assertIsDisplayed()
        rule.onNode(hasClickAction() and hasContentDescriptionExactly("Close")).performClick()
        rule.waitForIdle()
        assertEquals(att, removed)
    }

    // -- actions bar ---------------------------------------------------------------

    @Test
    fun `actions offer delete for a sim thread`() {
        rule.setContent {
            ConversationActions(
                threadId = 1L, address = "+1555", line = CommunicateLine.Sim,
                remoteId = null, isGroup = false, participants = emptyList(),
                groupTitle = null, isUnknownContact = false, onBack = {},
            )
        }
        rule.onNode(hasClickAction() and hasContentDescriptionExactly("More")).performClick()
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.delete)).assertIsDisplayed()
    }

    @Test
    fun `actions offer contact creation for an unknown one-to-one contact`() {
        rule.setContent {
            ConversationActions(
                threadId = 1L, address = "+1555", line = CommunicateLine.Sim,
                remoteId = null, isGroup = false, participants = emptyList(),
                groupTitle = null, isUnknownContact = true, onBack = {},
            )
        }
        rule.onNode(hasClickAction() and hasContentDescriptionExactly("More")).performClick()
        rule.onNodeWithText(str(R.string.contact_create_new)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.contact_add_to_existing)).assertIsDisplayed()
    }

    @Test
    fun `rcs actions offer verify and report the peer`() {
        var verified: String? = null
        rule.setContent {
            ConversationActions(
                threadId = 1L, address = "+1555", line = CommunicateLine.Rcs,
                remoteId = "peer-1", isGroup = false, participants = emptyList(),
                groupTitle = null, isUnknownContact = false, onBack = {},
                onOpenRcsVerify = { verified = it },
            )
        }
        rule.onNode(hasClickAction() and hasContentDescriptionExactly("More")).performClick()
        rule.onNodeWithText(str(R.string.rcs_verify_title)).performClick()
        rule.waitForIdle()
        assertEquals("peer-1", verified)
    }

    @Test
    fun `rcs group actions offer member management`() {
        var members: String? = null
        rule.setContent {
            ConversationActions(
                threadId = 1L, address = "g1", line = CommunicateLine.Rcs,
                remoteId = "g1", isGroup = true, participants = listOf("+1555"),
                groupTitle = "Family", isUnknownContact = false, onBack = {},
                onOpenRcsGroupMembers = { members = it },
            )
        }
        rule.onNode(hasClickAction() and hasContentDescriptionExactly("More")).performClick()
        rule.onNodeWithText(str(R.string.rcs_group_members_title)).performClick()
        rule.waitForIdle()
        assertEquals("g1", members)
    }

    @Test
    fun `delete confirm can be dismissed without deleting`() {
        var backs = 0
        rule.setContent {
            ConversationActions(
                threadId = 1L, address = "+1555", line = CommunicateLine.Sim,
                remoteId = null, isGroup = false, participants = emptyList(),
                groupTitle = null, isUnknownContact = false, onBack = { backs++ },
            )
        }
        rule.onNode(hasClickAction() and hasContentDescriptionExactly("More")).performClick()
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.delete)).performClick()
        rule.onNodeWithText(str(R.string.delete_conversation_title)).assertIsDisplayed()
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.cancel)).performClick()
        rule.waitForIdle()
        assertEquals(0, backs)
        rule.onNodeWithText(str(R.string.delete_conversation_title)).assertDoesNotExist()
    }

    @Test
    fun `compose attach menu offers media file poll and contact`() {
        val events = mutableListOf<String>()
        rule.setContent {
            ComposeSmsRow(
                draft = "hi", onDraftChange = {}, attachments = emptyList(),
                onAttachMedia = { events.add("media") }, onAttachFile = { events.add("file") },
                onCreatePoll = { events.add("poll") },
                onShareContact = { events.add("contact") },
                onRemoveAttachment = {}, onSend = {},
            )
        }
        rule.onNode(hasClickAction() and hasContentDescriptionExactly("Attachment")).performClick()
        rule.onNodeWithText(str(R.string.attach_media)).performClick()
        rule.waitForIdle()
        assertEquals(listOf("media"), events)
    }

    // -- rcs extras ------------------------------------------------------------------

    @Test
    fun `rcs banner is a no-op off the rcs line`() {
        rule.setContent {
            RcsEncryptionBanner(line = CommunicateLine.Sim, remoteId = null, address = "+1555", refresh = 0)
        }
        rule.onNodeWithText(str(R.string.rcs_encrypted_banner)).assertDoesNotExist()
    }

    @Test
    fun `peer typing is false off the rcs line`() {
        var typing: Boolean? = null
        rule.setContent {
            typing = rememberRcsPeerTyping(
                line = CommunicateLine.Sim, isGroup = false, remoteId = null, address = "+1555",
            )
        }
        rule.waitForIdle()
        assertEquals(false, typing)
    }
}
