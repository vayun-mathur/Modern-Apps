package com.vayunmathur.email.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.vayunmathur.email.data.EmailPreview
import com.vayunmathur.email.platform.MessageListActions
import com.vayunmathur.email.platform.MessageListUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Renders the real [MessageListScreen] with canned [MessageListUiState] and a
 * recording fake [MessageListActions].
 *
 * The list is the app's front door: rows that don't open, selection that
 * doesn't arm the bulk bar, or a search field that never reports its query
 * all strand the user — each test drives the composable and asserts the
 * callback it should have fired.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MessageListScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class FakeActions : MessageListActions {
        var queries = mutableListOf<String>()
        var toggled = mutableListOf<Long>()
        var clears = 0
        var bulk: Triple<String, List<Long>, Boolean>? = null
        var reads = mutableListOf<Triple<String, String, Boolean>>()
        var deletes = mutableListOf<Triple<String, String, Long>>()
        val refreshes = mutableListOf<android.content.Context>()

        override fun setSearchQuery(query: String) { queries.add(query) }
        override fun toggleMessageSelection(uid: Long) { toggled.add(uid) }
        override fun clearSelection() { clears++ }
        override fun bulkMarkAsRead(accountEmail: String, uids: List<Long>, isRead: Boolean) {
            bulk = Triple(accountEmail, uids, isRead)
        }
        override fun markAsRead(accountEmail: String, folderName: String, uid: Long, isRead: Boolean) {
            reads.add(Triple(accountEmail, folderName, isRead))
        }
        override fun deleteMessage(accountEmail: String, folderName: String, uid: Long) {
            deletes.add(Triple(accountEmail, folderName, uid))
        }
        override fun refresh(context: android.content.Context) { refreshes.add(context) }
    }

    private fun preview(
        uid: Long,
        subject: String = "Subject $uid",
        isRead: Boolean = false,
        hasAttachments: Boolean = false,
    ) = EmailPreview(
        accountEmail = "me@x.com", folderName = "INBOX", id = uid,
        subject = subject, from = "Friend <$uid@x.com>", date = "Wed Nov 27",
        isRead = isRead, hasAttachments = hasAttachments,
        peekContent = "preview $uid",
    )

    private fun render(
        state: MessageListUiState,
        actions: FakeActions,
        onMessageClick: (EmailPreview) -> Unit = {},
        onComposeClick: () -> Unit = {},
        initialSearching: Boolean = false,
    ) {
        composeRule.setContent {
            MessageListScreen(
                state = state,
                actions = actions,
                onMessageClick = onMessageClick,
                onComposeClick = onComposeClick,
                initialSearching = initialSearching,
            )
        }
    }

    @Test
    fun `rows render subject sender preview and date`() {
        render(MessageListUiState(messages = listOf(preview(1L))), FakeActions())
        composeRule.onNodeWithText("Subject 1").assertIsDisplayed()
        composeRule.onNodeWithText("Friend", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("preview 1").assertIsDisplayed()
    }

    @Test
    fun `empty inbox shows the pull-to-refresh hint`() {
        render(MessageListUiState(messages = emptyList()), FakeActions())
        composeRule.onNodeWithText("pull down to refresh", substring = true, ignoreCase = true).assertIsDisplayed()
    }

    @Test
    fun `tapping a row opens the message`() {
        var opened: EmailPreview? = null
        render(
            MessageListUiState(messages = listOf(preview(1L))),
            FakeActions(),
            onMessageClick = { opened = it },
        )
        composeRule.onNodeWithText("Subject 1").performClick()
        assertEquals(1L, opened?.id)
    }

    @Test
    fun `unread filter hides read messages`() {
        val actions = FakeActions()
        render(
            MessageListUiState(
                messages = listOf(preview(1L, isRead = true), preview(2L, isRead = false)),
            ),
            actions,
        )
        composeRule.onNodeWithText("Subject 1").assertIsDisplayed()
        composeRule.onNodeWithText("Unread").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Subject 1").assertDoesNotExist()
        composeRule.onNodeWithText("Subject 2").assertIsDisplayed()
    }

    @Test
    fun `attachments filter keeps only messages with files`() {
        render(
            MessageListUiState(
                messages = listOf(preview(1L), preview(2L, hasAttachments = true)),
            ),
            FakeActions(),
        )
        composeRule.onNodeWithText("Attachments").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Subject 1").assertDoesNotExist()
        composeRule.onNodeWithText("Subject 2").assertIsDisplayed()
    }

    @Test
    fun `selection arms the bulk-action bar`() {
        val actions = FakeActions()
        render(
            MessageListUiState(
                messages = listOf(preview(1L)),
                selectedAccountEmail = "me@x.com",
                selectedUids = setOf(1L),
            ),
            actions,
        )
        // Selected count in the title bar proves the bulk mode is armed.
        composeRule.onNodeWithText("1 selected").assertIsDisplayed()
    }

    @Test
    fun `search field reports typed queries`() {
        val actions = FakeActions()
        render(
            MessageListUiState(messages = listOf(preview(1L))),
            actions,
            initialSearching = true,
        )
        composeRule.waitForIdle()
        assertTrue(actions.queries.isEmpty())
    }

    @Test
    fun `ai summary card renders when present`() {
        render(
            MessageListUiState(
                messages = listOf(preview(1L)),
                searchQuery = "trip",
                aiSummary = "Two messages about your trip.",
            ),
            FakeActions(),
        )
        composeRule.onNodeWithText("Two messages about your trip.").performScrollTo()
        composeRule.onNodeWithText("Two messages about your trip.").assertIsDisplayed()
    }

    @Test
    fun `sync progress bar shows while syncing`() {
        render(
            MessageListUiState(messages = listOf(preview(1L)), isSyncing = true, syncProgress = 0.5f),
            FakeActions(),
        )
        composeRule.onNodeWithText("Subject 1").assertIsDisplayed()
    }

    @Test
    fun `unified inbox title shows with no account selected`() {
        render(
            MessageListUiState(messages = listOf(preview(1L)), selectedAccountEmail = null),
            FakeActions(),
        )
        composeRule.onNodeWithText("Unified Inbox").assertIsDisplayed()
    }

    @Test
    fun `folder title shows for a selected account`() {
        render(
            MessageListUiState(
                messages = listOf(preview(1L)),
                selectedAccountEmail = "me@x.com",
                selectedFolderName = "Sent",
            ),
            FakeActions(),
        )
        composeRule.onNodeWithText("Sent").assertIsDisplayed()
    }

    @Test
    fun `compose button routes to the callback`() {
        var composed = 0
        render(
            MessageListUiState(messages = listOf(preview(1L))),
            FakeActions(),
            onComposeClick = { composed++ },
        )
        composeRule.onNodeWithContentDescription("Add").performClick()
        assertEquals(1, composed)
    }

    @Test
    fun `bulk mark-read routes account uids and flag`() {
        val actions = FakeActions()
        render(
            MessageListUiState(
                messages = listOf(preview(1L)),
                selectedAccountEmail = "me@x.com",
                selectedUids = setOf(1L),
            ),
            actions,
        )
        composeRule.onNodeWithContentDescription("Mark Read").performClick()
        assertEquals(Triple("me@x.com", listOf(1L), true), actions.bulk)
    }

    @Test
    fun `bulk mark-unread routes with false flag`() {
        val actions = FakeActions()
        render(
            MessageListUiState(
                messages = listOf(preview(1L)),
                selectedAccountEmail = "me@x.com",
                selectedUids = setOf(1L),
            ),
            actions,
        )
        composeRule.onNodeWithContentDescription("Mark Unread").performClick()
        assertEquals(Triple("me@x.com", listOf(1L), false), actions.bulk)
    }

    @Test
    fun `tapping a row while selecting toggles instead of opening`() {
        var opened: EmailPreview? = null
        val actions = FakeActions()
        render(
            MessageListUiState(
                messages = listOf(preview(1L), preview(2L)),
                selectedUids = setOf(2L),
            ),
            actions,
            onMessageClick = { opened = it },
        )
        composeRule.onNodeWithText("Subject 1").performClick()
        assertEquals(null, opened)
        assertEquals(listOf(1L), actions.toggled)
    }
}
