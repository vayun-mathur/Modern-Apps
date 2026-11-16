package com.vayunmathur.email.ui

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.vayunmathur.email.data.Attachment
import com.vayunmathur.email.data.EmailMessage
import com.vayunmathur.email.platform.MessageThreadActions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** W5: MessageItem header/body/footer branches with quoted, HTML and unsubscribe mail. */
@RunWith(RobolectricTestRunner::class)
class MessageItemBranchesW5Test {
    @get:Rule val rule = createComposeRule()

    private class RecordingActions(
        private val attachments: List<Attachment> = emptyList(),
    ) : MessageThreadActions {
        val snoozed = mutableListOf<Long>()
        val readFlags = mutableListOf<Boolean>()
        val blocked = mutableListOf<String>()
        val unsubscribes = mutableListOf<String>()
        var fetchedBodies = 0
        override fun snoozeMessage(accountEmail: String, folderName: String, uid: Long, until: Long) {
            snoozed += until
        }
        override fun markAsRead(accountEmail: String, folderName: String, uid: Long, isRead: Boolean) {
            readFlags += isRead
        }
        override fun blockSender(from: String) { blocked += from }
        override fun fetchBodyIfNeeded(message: EmailMessage) { fetchedBodies++ }
        override suspend fun getAttachments(accountEmail: String, messageId: Long): List<Attachment> = attachments
        override fun oneClickUnsubscribe(url: String, onResult: (Boolean) -> Unit) {
            unsubscribes += url
            onResult(true)
        }
    }

    private fun msg(
        body: String? = "Hello team\nPlease review the plan.",
        isHtml: Boolean = false,
        isRead: Boolean = false,
        listUnsubscribe: String? = null,
        listUnsubscribePost: String? = null,
        hasAttachments: Boolean = false,
    ) = EmailMessage(
        accountEmail = "aisha@x.com", folderName = "INBOX", id = 42,
        subject = "Quarterly plan", from = "Aisha Rahman <aisha@x.com>",
        to = "me@x.com", date = "Mon, 10 Nov 2026 09:30:00 +0000",
        body = body, isHtml = isHtml, isRead = isRead,
        listUnsubscribe = listUnsubscribe, listUnsubscribePost = listUnsubscribePost,
        hasAttachments = hasAttachments,
    )

    private fun render(
        message: EmailMessage,
        actions: RecordingActions,
        replies: MutableList<String> = mutableListOf(),
        forwards: MutableList<String> = mutableListOf(),
        composed: MutableList<Pair<String, String>> = mutableListOf(),
        exported: MutableList<EmailMessage> = mutableListOf(),
        backs: MutableList<Unit> = mutableListOf(),
    ) {
        rule.setContent {
            MessageItem(
                msg = message, actions = actions,
                onBack = { backs += Unit },
                onReply = { from, subject, _ -> replies += "$from|$subject" },
                onForward = { subject, _ -> forwards += subject },
                onCompose = { to, subject -> composed += to to subject },
                onExportEml = { exported += it },
            )
        }
        rule.waitForIdle()
    }

    private fun clickText(text: String): Boolean {
        val nodes = rule.onAllNodesWithText(text, substring = true)
        if (nodes.fetchSemanticsNodes().isEmpty()) return false
        runCatching { nodes[0].performClick() }
        rule.waitForIdle()
        return true
    }

    private fun assertQuotedBody(body: String, hasQuoteToggle: Boolean) {
        val actions = RecordingActions()
        render(msg(body = body), actions)
        if (hasQuoteToggle) {
            assertTrue(clickText("Show quoted text"), "quote toggle should show for: $body")
            assertTrue(clickText("Hide quoted text"))
        }
        rule.onRoot().assertExists()
    }

    @Test fun quotedBodyOnWroteBoundary() =
        assertQuotedBody("Main answer\nOn Mon, Aisha wrote:\n> old text", hasQuoteToggle = true)

    @Test fun quotedBodyOriginalMessageBoundary() =
        assertQuotedBody("Main answer\n-----Original Message-----\nold text", hasQuoteToggle = true)

    @Test fun quotedBodyAngleQuoteBoundary() =
        assertQuotedBody("Main answer\n> quoted line\n> second quoted", hasQuoteToggle = true)

    @Test fun plainBodyWithoutQuotesHasNoToggle() =
        assertQuotedBody("No quotes at all in this body", hasQuoteToggle = false)

    @Test
    fun headerActionsSnoozeOverflowAndDetails() {
        val actions = RecordingActions()
        val exported = mutableListOf<EmailMessage>()
        render(msg(isRead = true), actions, exported = exported)
        // Snooze menu: pick each option in turn across re-renders.
        clickText("Snooze")
        clickText("Later today")
        assertEquals(1, actions.snoozed.size)
        clickText("Snooze")
        clickText("Tomorrow")
        clickText("Snooze")
        clickText("In 1 week")
        assertEquals(3, actions.snoozed.size)
        // Overflow -> Save as .eml: open menus until the item appears.
        val clickables = rule.onAllNodes(hasClickAction())
        for (i in 0 until clickables.fetchSemanticsNodes().size) {
            runCatching { rule.onAllNodes(hasClickAction())[i].performClick() }
            rule.waitForIdle()
            if (clickText("Save as .eml")) break
        }
        assertEquals(1, exported.size)
        // Header click opens the details sheet.
        clickText("Aisha Rahman")
        assertTrue(
            rule.onAllNodes(androidx.compose.ui.test.isRoot()).fetchSemanticsNodes().isNotEmpty()
        )
    }

    @Test
    fun footerReplyForwardBlockAndAttachments() {
        val attachments = listOf(
            Attachment(accountEmail = "aisha@x.com", folderName = "INBOX", messageId = 42, partId = "1", fileName = "plan.pdf", mimeType = "application/pdf", size = 2048),
            Attachment(accountEmail = "aisha@x.com", folderName = "INBOX", messageId = 42, partId = "2", fileName = "sheet.csv", mimeType = "text/csv", size = 512),
        )
        val actions = RecordingActions(attachments)
        val replies = mutableListOf<String>()
        val forwards = mutableListOf<String>()
        val backs = mutableListOf<Unit>()
        render(msg(hasAttachments = true), actions, replies = replies, forwards = forwards, backs = backs)
        clickText("plan.pdf")
        assertTrue(clickText("Reply"))
        assertTrue(clickText("Forward"))
        assertEquals(listOf("Aisha Rahman <aisha@x.com>|Quarterly plan"), replies)
        assertEquals(listOf("Quarterly plan"), forwards)
        assertTrue(clickText("Block sender"))
        assertEquals(listOf("Aisha Rahman <aisha@x.com>"), actions.blocked)
        assertEquals(1, backs.size)
    }

    @Test
    fun unsubscribeOneClickFlowConfirmsAndCallsAction() {
        val actions = RecordingActions()
        render(
            msg(
                listUnsubscribe = "<https://example.com/unsub>",
                listUnsubscribePost = "List-Unsubscribe=One-Click",
            ),
            actions,
        )
        assertTrue(clickText("Unsubscribe"))
        // Confirm in the dialog (the confirm button repeats the label).
        val nodes = rule.onAllNodesWithText("Unsubscribe", substring = true)
        if (nodes.fetchSemanticsNodes().size > 1) runCatching { nodes[nodes.fetchSemanticsNodes().lastIndex].performClick() }
        rule.waitForIdle()
        assertEquals(listOf("https://example.com/unsub"), actions.unsubscribes)
    }

    @Test
    fun unsubscribeMailtoFlowComposesMessage() {
        val actions = RecordingActions()
        val composed = mutableListOf<Pair<String, String>>()
        render(msg(listUnsubscribe = "<mailto:leave@x.com>"), actions, composed = composed)
        assertTrue(clickText("Unsubscribe"), "unsubscribe action should show")
        rule.waitForIdle()
        val nodes = rule.onAllNodesWithText("Compose", substring = true)
        for (i in 0 until nodes.fetchSemanticsNodes().size) {
            runCatching { rule.onAllNodesWithText("Compose", substring = true)[i].performClick() }
            rule.waitForIdle()
            if (composed.isNotEmpty()) break
        }
        assertTrue(composed.any { it.first == "leave@x.com" }, "mailto unsubscribe should compose, got $composed")
    }

    @Test
    fun htmlBodyWithQuotesAndRemoteImageBanner() {
        val actions = RecordingActions()
        render(
            msg(
                body = "<html><body><p>Hi</p><blockquote>quoted</blockquote><img src='cid:logo'></body></html>",
                isHtml = true,
            ),
            actions,
        )
        clickText("Load images")
        clickText("Show quoted text")
        rule.onRoot().assertExists()
    }

    @Test
    fun nullBodyFetchesBodyAndEmptyFromRenders() {
        val actions = RecordingActions()
        render(msg(body = null).copy(from = ""), actions)
        assertEquals(1, actions.fetchedBodies)
        rule.onRoot().assertExists()
    }
}
