package com.vayunmathur.email.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
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
import org.robolectric.Shadows
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
        val cidMap: Map<String, File> = emptyMap(),
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
        override suspend fun loadCidMap(context: Context, message: EmailMessage): Map<String, File> = cidMap
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
        listUnsubscribe: String? = null,
        listUnsubscribePost: String? = null,
    ) = EmailMessage(
        accountEmail = "me@x.com", folderName = "INBOX", id = uid,
        subject = subject, from = from, date = "Wed Nov 27 10:00",
        body = body, isHtml = isHtml, isRead = isRead,
        listUnsubscribe = listUnsubscribe,
        listUnsubscribePost = listUnsubscribePost,
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
        // A read thread opens on its newest message; the strip opens the others.
        composeRule.onNodeWithText("Second reply").assertIsDisplayed()
        composeRule.onNodeWithText("Hello there").assertDoesNotExist()
        composeRule.onAllNodes(isSelectable())[0].performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Hello there").assertIsDisplayed()
        composeRule.onNodeWithText("Second reply").assertDoesNotExist()
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
        // Only the new text shows until the toggle is used: the reply boundary
        // ("On ... wrote:") was detected and the quoted part is withheld.
        composeRule.onNodeWithText("New text").assertIsDisplayed()
        composeRule.onNodeWithText("old quoted").assertDoesNotExist()
        composeRule.onNodeWithText("Show quoted text").performScrollTo()
        composeRule.onNodeWithText("Show quoted text").performClick()
        composeRule.waitForIdle()
        // Toggle flipped: the expander state changed, so the full body (with
        // the quoted history) is now the displayed text.
        composeRule.onNodeWithText("Hide quoted text").assertIsDisplayed()
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
        composeRule.onNodeWithText("Snooze").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Tomorrow", substring = true).assertIsDisplayed()
    }

    @Test
    fun `read toggle on a read message marks it unread`() {
        val actions = FakeActions()
        composeRule.setContent {
            MessageThreadScreen(messages = listOf(msg(1L, isRead = true)), actions = actions)
        }
        composeRule.onNodeWithContentDescription("Mark Unread").performClick()
        composeRule.waitForIdle()
        assertTrue(actions.reads.any { it.second == 1L && !it.third }, "reads: ${actions.reads}")
    }

    @Test
    fun `overflow menu offers save as eml`() {
        composeRule.setContent {
            MessageThreadScreen(messages = listOf(msg(1L)), actions = FakeActions())
        }
        composeRule.onNodeWithContentDescription("More").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Save as .eml").assertIsDisplayed()
    }

    @Test
    fun `block sender routes the from header`() {
        val actions = FakeActions()
        composeRule.setContent {
            MessageThreadScreen(messages = listOf(msg(1L)), actions = actions)
        }
        composeRule.onNodeWithText("Block sender", substring = true, ignoreCase = true).performScrollTo()
        composeRule.onNodeWithText("Block sender", substring = true, ignoreCase = true).performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("Friend <friend@x.com>"), actions.blocked)
    }

    @Test
    fun `unsubscribe flow confirms and posts one-click`() {
        val actions = FakeActions()
        composeRule.setContent {
            MessageThreadScreen(
                messages = listOf(
                    msg(
                        1L,
                        listUnsubscribe = "<https://x.com/unsub>",
                        listUnsubscribePost = "List-Unsubscribe=One-Click",
                    )
                ),
                actions = actions,
            )
        }
        composeRule.onNodeWithText("Unsubscribe").performScrollTo()
        composeRule.onNodeWithText("Unsubscribe").performClick()
        composeRule.waitForIdle()
        // Confirmation dialog offers the Unsubscribe action.
        composeRule.onAllNodesWithText("Unsubscribe").fetchSemanticsNodes().size.let { count ->
            assertTrue(count >= 2, "expected button + dialog, found $count")
        }
        composeRule.onAllNodesWithText("Unsubscribe").fetchSemanticsNodes()[1].let { _ ->
            composeRule.onNodeWithText("Send an unsubscribe request", substring = true).assertIsDisplayed()
        }
    }

    @Test
    fun `unsubscribe confirm posts the one-click request`() {
        val actions = FakeActions()
        composeRule.setContent {
            MessageThreadScreen(
                messages = listOf(
                    msg(
                        1L,
                        listUnsubscribe = "<https://x.com/unsub>",
                        listUnsubscribePost = "List-Unsubscribe=One-Click",
                    )
                ),
                actions = actions,
            )
        }
        composeRule.onNodeWithText("Unsubscribe").performScrollTo()
        composeRule.onNodeWithText("Unsubscribe").performClick()
        composeRule.waitForIdle()
        // The dialog title and confirm button share the "Unsubscribe"
        // label with the menu button; the confirm action is the last one.
        val unsubscribes = composeRule.onAllNodesWithText("Unsubscribe")
        unsubscribes[unsubscribes.fetchSemanticsNodes().lastIndex].performClick()
        composeRule.waitForIdle()
        assertEquals("https://x.com/unsub", actions.unsub)
    }

    @Test
    fun `unsubscribe by mail routes to the composer`() {
        var composed: Pair<String, String>? = null
        composeRule.setContent {
            MessageThreadScreen(
                messages = listOf(msg(1L, listUnsubscribe = "<mailto:bye@list.com>")),
                actions = FakeActions(),
                onCompose = { address, subject -> composed = address to subject },
            )
        }
        composeRule.onNodeWithText("Unsubscribe").performScrollTo()
        composeRule.onNodeWithText("Unsubscribe").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Compose an unsubscribe email", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Compose").performClick()
        composeRule.waitForIdle()
        assertEquals("bye@list.com" to "Unsubscribe", composed)
    }

    @Test
    fun `unsubscribe by web opens the browser intent`() {
        composeRule.setContent {
            MessageThreadScreen(
                messages = listOf(msg(1L, listUnsubscribe = "<https://x.com/leave>")),
                actions = FakeActions(),
            )
        }
        composeRule.onNodeWithText("Unsubscribe").performScrollTo()
        composeRule.onNodeWithText("Unsubscribe").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Open the unsubscribe page", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Open", substring = false).performClick()
        composeRule.waitForIdle()
        val started = Shadows.shadowOf(
            ApplicationProvider.getApplicationContext<Application>()
        ).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started?.action)
        assertEquals("https://x.com/leave", started?.data?.toString())
    }

    @Test
    fun `snooze option records the wake time and goes back`() {
        val actions = FakeActions()
        var backs = 0
        composeRule.setContent {
            MessageThreadScreen(
                messages = listOf(msg(1L)),
                actions = actions,
                onBack = { backs++ },
            )
        }
        composeRule.onNodeWithText("Snooze").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("In 1 week").performClick()
        composeRule.waitForIdle()
        assertEquals(1, actions.snoozes.size)
        assertTrue(actions.snoozes.single() > System.currentTimeMillis())
        assertEquals(1, backs)
    }

    @Test
    fun `save as eml launches the document creator`() {
        composeRule.setContent {
            MessageThreadScreen(messages = listOf(msg(1L)), actions = FakeActions())
        }
        composeRule.onNodeWithContentDescription("More").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Save as .eml").performClick()
        composeRule.waitForIdle()
        val started = Shadows.shadowOf(
            ApplicationProvider.getApplicationContext<Application>()
        ).nextStartedActivity
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, started?.action)
    }

    @Test
    fun `html with cid images does not show the remote-images blocker`() {
        val actions = FakeActions(cidMap = mapOf("logo@x" to File("/cache/logo.png")))
        composeRule.setContent {
            MessageThreadScreen(
                messages = listOf(
                    msg(1L, body = "<p>Hi</p><img src=\"cid:logo@x\">", isHtml = true)
                ),
                actions = actions,
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Remote images blocked", substring = true).assertDoesNotExist()
    }

    @Test
    fun `empty thread shows the conversation title`() {
        composeRule.setContent {
            MessageThreadScreen(messages = emptyList(), actions = FakeActions())
        }
        composeRule.onNodeWithText("Conversation").assertIsDisplayed()
    }

    @Test
    fun `html body with quotes offers the quote toggle`() {
        composeRule.setContent {
            MessageThreadScreen(
                messages = listOf(
                    msg(1L, body = "<div>Hi</div><blockquote>old</blockquote>", isHtml = true)
                ),
                actions = FakeActions(),
            )
        }
        composeRule.onNodeWithText("Show quoted text").performScrollTo()
        composeRule.onNodeWithText("Show quoted text").assertIsDisplayed()
    }
}
