package com.vayunmathur.email.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
 * Renders the real [AttachmentItem]: a stored local URI offers Open, a remote
 * attachment downloads through [MessageThreadActions]. A swapped branch would
 * re-download cached files (or offer to open nothing), so each state asserts
 * its exact affordance and callback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AttachmentItemTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class RecordingActions(
        private val downloadError: String? = null,
    ) : MessageThreadActions {
        var downloaded: Attachment? = null
        override fun markAsRead(accountEmail: String, folderName: String, uid: Long, isRead: Boolean) {}
        override fun snoozeMessage(accountEmail: String, folderName: String, uid: Long, until: Long) {}
        override fun blockSender(from: String) {}
        override fun fetchBodyIfNeeded(message: EmailMessage) {}
        override suspend fun getAttachments(accountEmail: String, messageId: Long): List<Attachment> = emptyList()
        override suspend fun loadCidMap(context: Context, message: EmailMessage): Map<String, File> = emptyMap()
        override fun downloadAttachment(attachment: Attachment, onSuccess: (String) -> Unit, onError: (String) -> Unit) {
            downloaded = attachment
            val error = downloadError
            if (error != null) onError(error) else onSuccess("/downloads/${attachment.fileName}")
        }
        override fun oneClickUnsubscribe(url: String, onResult: (Boolean) -> Unit) {}
        override fun exportEml(accountEmail: String, folderName: String, uid: Long, targetUri: Uri, onResult: (Boolean, String?) -> Unit) {}
    }

    private fun attachment(localUri: String? = null) = Attachment(
        accountEmail = "me@x.com", folderName = "INBOX", messageId = 1L,
        partId = "1", fileName = "ticket.pdf", mimeType = "application/pdf",
        size = 42L, localUri = localUri,
    )

    @Test
    fun `cached attachment offers open`() {
        composeRule.setContent {
            AttachmentItem(attachment = attachment(localUri = "/cache/ticket.pdf"), actions = RecordingActions())
        }
        composeRule.onNodeWithText("ticket.pdf").assertIsDisplayed()
        composeRule.onNodeWithText("Open").assertIsDisplayed()
    }

    @Test
    fun `remote attachment downloads through actions`() {
        val actions = RecordingActions()
        composeRule.setContent {
            AttachmentItem(attachment = attachment(localUri = null), actions = actions)
        }
        composeRule.onNodeWithText("ticket.pdf").assertIsDisplayed()
        composeRule.onNodeWithText("Open").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Download").performClick()
        composeRule.waitForIdle()
        assertEquals("ticket.pdf", actions.downloaded?.fileName)
        // Success flips the row to the Open affordance.
        composeRule.onNodeWithText("Open").assertIsDisplayed()
    }

    @Test
    fun `open click launches a view chooser for the cached file`() {
        composeRule.setContent {
            AttachmentItem(
                attachment = attachment(localUri = "content://media/ticket.pdf"),
                actions = RecordingActions(),
            )
        }
        composeRule.onNodeWithText("Open").performClick()
        composeRule.waitForIdle()
        val started = Shadows.shadowOf(
            ApplicationProvider.getApplicationContext<Application>()
        ).nextStartedActivity
        // The row wraps ACTION_VIEW in a chooser.
        val target = started?.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) ?: started
        assertEquals(Intent.ACTION_VIEW, target?.action)
        assertEquals("content://media/ticket.pdf", target?.data?.toString())
        assertTrue((target?.flags ?: 0) and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun `download failure restores the download affordance`() {
        val actions = RecordingActions(downloadError = "server gone")
        composeRule.setContent {
            AttachmentItem(attachment = attachment(localUri = null), actions = actions)
        }
        composeRule.onNodeWithContentDescription("Download").performClick()
        composeRule.waitForIdle()
        assertEquals("ticket.pdf", actions.downloaded?.fileName)
        composeRule.onNodeWithText("Open").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Download").assertIsDisplayed()
    }

    @Test
    fun `file name always renders`() {
        composeRule.setContent {
            AttachmentItem(attachment = attachment(localUri = null), actions = RecordingActions())
        }
        composeRule.onNodeWithText("ticket.pdf").assertIsDisplayed()
    }
}
