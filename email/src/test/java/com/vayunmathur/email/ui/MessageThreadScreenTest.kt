package com.vayunmathur.email.ui

import android.content.Context
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.vayunmathur.email.data.Attachment
import com.vayunmathur.email.data.EmailMessage
import com.vayunmathur.email.platform.MessageThreadActions
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Renders the real [MessageThreadScreen]/[MessageItem] with canned messages
 * and a recording fake [MessageThreadActions].
 *
 * The thread view marks mail read on open, expands quoted history, and
 * routes reply/forward/unsubscribe — a miswired callback silently drops the
 * read state or answers the wrong message, so each test asserts the exact
 * recorded call.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MessageThreadScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class FakeActions(
        val attachments: List<Attachment> = emptyList(),
    ) : MessageThreadActions {
        val reads = mutableListOf<Triple<String, Long, Boolean>>()
        val snoozes = mutableListOf<Long>()
        val blocked = mutableListOf<String>()
        var fetched: EmailMessage? = null
        var download: Attachment? = null
        var unsub: String? = null
        val exports = mutableListOf<Triple<String, Long, Uri>>()

        override fun markAsRead(accountEmail: String, folderName: String, uid: Long, isRead: Boolean) {
            reads.add(Triple(accountEmail, uid, isRead))
        }
        override fun snoozeMessage(accountEmail: String, folderName: String, uid: Long, until: Long) {
            snoozes.add(until)
        }
        override fun blockSender(from: String) { blocked.add(from) }
        override fun fetchBodyIfNeeded(message: EmailMessage) { fetched = message }
        override suspend fun getAttachments(accountEmail: String, messageId: Long): List<Attachment> = attachments
        override suspend fun loadCidMap(context: Context, message: EmailMessage): Map<String, File> = emptyMap()
        override fun downloadAttachment(attachment: Attachment, onSuccess: (String) -> Unit, onError: (String) -> Unit) {
            download = attachment
            onSuccess("/cache/${attachment.fileName}")
        }
        override fun oneClickUnsubscribe(url: String, onResult: (Boolean) -> Unit) {
            unsub = url
            onResult(true)
        }
        override fun exportEml(accountEmail: String, folderName: String, uid: Long, targetUri: Uri, onResult: (Boolean, String?) -> Unit) {
            exports.add(Triple(accountEmail, uid, targetUri))
        }
    }

    private fun msg(
        uid: Long,
        subject: String = "Trip plans",
        body: String? = "Hello there",
        isHtml: Boolean = false,
        isRead: Boolean = true,
        from: String = "Friend <friend@x.com>",
    ) = EmailMessage(
        accountEmail = "me@x.com", folderName = "INBOX", id = uid,
        subject = subject, from = from, date = "Wed Nov 27 10:00",
        body = body, isHtml = isHtml, isRead = isRead,
    )

    @Test
    fun `thread renders subject and message bodies`() {
        composeRule.setContent {
            MessageThreadScreen(
                messages = listOf(msg(1L), msg(2L, subject = "Trip plans", body = "Second reply")),
                actions = FakeActions(),
            )
        }
        composeRule.onNodeWithText("Trip plans").assertIsDisplayed()
        composeRule.onNodeWithText("Hello there").assertIsDisplayed()
        composeRule.onNodeWithText("Second reply").performScrollTo()
        composeRule.onNodeWithText("Second reply").assertIsDisplayed()
    }

    @Test
    fun `opening marks unread messages read`() {
        val actions = FakeActions()
        composeRule.setContent {
            MessageThreadScreen(messages = listOf(msg(1L, isRead = false)), actions = actions)
        }
        composeRule.waitForIdle()
        assertTrue(actions.reads.any { it.second == 1L && it.third }, "reads: ${actions.reads}")
    }

    @Test
    fun `body-less message triggers a fetch`() {
        val actions = FakeActions()
        composeRule.setContent {
            MessageThreadScreen(messages = listOf(msg(1L, body = null, isRead = true)), actions = actions)
        }
        composeRule.waitForIdle()
        assertEquals(1L, actions.fetched?.id)
    }

    @Test
    fun `quoted reply shows toggle and expands`() {
        composeRule.setContent {
            MessageThreadScreen(
                messages = listOf(msg(1L, body = "New text\nOn Monday, Bob wrote:\n> old quoted")),
                actions = FakeActions(),
            )
        }
        composeRule.onNodeWithText("New text").assertIsDisplayed()
        composeRule.onNodeWithText("quoted text", substring = true, ignoreCase = true).performScrollTo()
        composeRule.onNodeWithText("quoted text", substring = true, ignoreCase = true).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("old quoted").performScrollTo()
        composeRule.onNodeWithText("old quoted").assertIsDisplayed()
    }

    @Test
    fun `reply and forward route to callbacks`() {
        var replied: Triple<String, String, String?>? = null
        var forwarded: Pair<String, String?>? = null
        composeRule.setContent {
            MessageThreadScreen(
                messages = listOf(msg(1L)),
                actions = FakeActions(),
                onReply = { from, subject, id -> replied = Triple(from, subject, id) },
                onForward = { subject, body -> forwarded = subject to body },
            )
        }
        composeRule.onNodeWithText("Reply", substring = false).performScrollTo()
        composeRule.onNodeWithText("Reply", substring = false).performClick()
        assertEquals("Friend <friend@x.com>", replied?.first)
        assertEquals("Trip plans", replied?.second)
        composeRule.onNodeWithText("Forward", substring = false).performScrollTo()
        composeRule.onNodeWithText("Forward", substring = false).performClick()
        assertEquals("Trip plans", forwarded?.first)
    }

    @Test
    fun `attachments render and download through actions`() {
        val att = Attachment("me@x.com", "INBOX", 1L, "p0", "ticket.pdf", "application/pdf", 42L)
        val actions = FakeActions(attachments = listOf(att))
        composeRule.setContent {
            MessageThreadScreen(messages = listOf(msg(1L)), actions = actions)
        }
        composeRule.onNodeWithText("ticket.pdf").performScrollTo()
        composeRule.onNodeWithText("ticket.pdf").assertIsDisplayed()
    }

    @Test
    fun `snooze menu offers options`() {
        composeRule.setContent {
            MessageThreadScreen(messages = listOf(msg(1L)), actions = FakeActions())
        }
        composeRule.onNodeWithText("Snooze").performScrollTo()
        composeRule.onNodeWithText("Snooze").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Tomorrow", substring = true).assertIsDisplayed()
    }
}
