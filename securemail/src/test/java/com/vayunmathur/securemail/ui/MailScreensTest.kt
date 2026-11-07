package com.vayunmathur.securemail.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.securemail.data.MailFolder
import com.vayunmathur.securemail.data.MailIdentity
import com.vayunmathur.securemail.data.OutboxMessage
import com.vayunmathur.securemail.data.StoredMessage
import com.vayunmathur.securemail.platform.MailUiState
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class MailScreensTest {
    @get:Rule val rule = createComposeRule()
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun msg(id: String, subject: String, body: String = "body", read: Boolean = false, folder: MailFolder = MailFolder.INBOX) =
        StoredMessage(id = id, from = "ada@example.org", toJson = "[]", dateMillis = 1700000000000, messageId = id,
            blobBase64 = "", decryptedBody = body, decryptedSubject = subject, isRead = read, folder = folder)

    private fun folderActions(opened: MutableList<String> = mutableListOf()) = FolderActions(
        onOpen = { opened += it }, onCompose = {}, onSetRead = { _, _ -> }, onTrash = {},
        onMoveTo = { _, _ -> }, onRestore = {}, onDeleteForever = {}, onEmptyTrash = {}, onConsumeMessage = {},
    )

    @Test fun `inbox renders messages and empty folders show nothing to open`() {
        val opened = mutableListOf<String>()
        rule.setContent {
            InboxScreen(state = MailUiState(address = "me@maemail.org"), folder = MailFolder.INBOX,
                actions = folderActions(opened), messages = listOf(msg("1", "Hello there"), msg("2", "Second", read = true)))
        }
        rule.onNodeWithText("Hello there").assertExists()
        rule.onNodeWithText("Second").assertExists()
        rule.onNodeWithText("Hello there").performClick()
        assertEquals(listOf("1"), opened)
    }

    @Test fun `inbox empty state renders no message rows`() {
        rule.setContent {
            InboxScreen(state = MailUiState(), folder = MailFolder.SPAM, actions = folderActions(), messages = emptyList())
        }
        rule.onAllNodes(hasText("Hello there")).assertCountEquals(0)
    }

    @Test fun `reader shows subject body and missing message placeholder`() {
        val replies = mutableListOf<StoredMessage>()
        rule.setContent {
            ReaderScreen(state = MailUiState(), onReply = { replies += it }, onBack = {},
                message = msg("1", "Quarterly report", body = "See attached numbers"))
        }
        rule.onNodeWithText("Quarterly report").assertExists()
        rule.onNodeWithText("See attached numbers", substring = true).assertExists()
        assertTrue(replies.isEmpty())
    }

    @Test fun `reader without message shows generic title`() {
        rule.setContent { ReaderScreen(state = MailUiState(), onReply = {}, onBack = {}, message = null) }
        rule.onNodeWithText("Message").assertExists()
    }

    @Test fun `message body view renders decrypted body`() {
        rule.setContent { MessageBodyView(msg("1", "S", body = "Plain body text here")) }
        rule.onNodeWithText("Plain body text here", substring = true).assertExists()
    }

    @Test fun `lock screen offers unlock and onboarding claims`() {
        var unlocks = 0
        rule.setContent { LockScreen(state = MailUiState(locked = true), onUnlock = { unlocks++ }, onDismissMessages = {}) }
        rule.onNodeWithText("SecureMail locked").assertExists()
        assertEquals(0, unlocks)
    }

    @Test fun `onboarding explains claim flow`() {
        rule.setContent { OnboardingScreen(state = MailUiState(), onClaim = { _, _ -> }, onDismissMessages = {}) }
        rule.onNodeWithText("Set up SecureMail").assertExists()
        rule.onNodeWithText("Claim an @maemail.org address.", substring = true).assertExists()
        rule.onNodeWithText("Your address").assertExists()
    }

    @Test fun `settings lists identities and out screen lists drafts`() {
        val identities = listOf(
            MailIdentity("me@maemail.org", "Primary", "pub", "AA", "sec", isPrimary = true, createdMillis = 1),
            MailIdentity("alias@maemail.org", "Shopping", "pub", "BB", "sec", createdMillis = 2),
        )
        rule.setContent {
            SettingsScreen(state = MailUiState(address = "me@maemail.org", fingerprintHex = "AABB"), identities = identities,
                onCreateAlias = {}, onSetAliasDisabled = { _, _ -> }, onExportPublicKey = {}, onForget = {})
        }
        rule.onNodeWithText("Settings").assertExists()
        rule.onNodeWithText("me@maemail.org", substring = true).assertExists()
    }

    @Test fun `out screen renders drafts outbox and sent sections`() {
        val actions = OutActions(onCompose = {}, onOpenDraft = {}, onDeleteDraft = {}, onRestoreDraft = {},
            onRetryOutbox = {}, onOpenSent = {}, onTrashSent = {}, onUntrashSent = {}, onSetRead = { _, _ -> })
        rule.setContent {
            OutScreen(
                drafts = listOf(msg("d1", "Draft subject", folder = MailFolder.DRAFTS)),
                outbox = listOf(OutboxMessage(toJson = "[]", subject = "Queued", body = "b", createdMillis = 1)),
                sent = listOf(msg("s1", "Sent subject", folder = MailFolder.SENT)),
                actions = actions,
            )
        }
        rule.onNodeWithText("Drafts").assertExists()
        rule.onNodeWithText("Draft subject").assertExists()
    }

    @Test fun `bottom bar tabs map folders`() {
        assertEquals(MailTab.INBOX, MailFolder.INBOX.tab())
        assertEquals(MailTab.TRASH, MailFolder.TRASH.tab())
        var selected: MailTab? = null
        rule.setContent { MailBottomBar(selected = MailTab.INBOX, outboxCount = 2, onSelect = { selected = it }) }
        assertEquals(null, selected)
    }

    @Test fun `compose screen renders recipient fields`() {
        rule.setContent {
            ComposeScreen(
                state = MailUiState(address = "me@maemail.org"), identities = emptyList(), draftFrom = null,
                onSelectFrom = {}, draftTo = listOf("ada@example.org"), draftCc = emptyList(), draftBcc = emptyList(),
                initialSubject = "Hi", initialBody = "Body text",
                onAddRecipient = { _, _ -> true }, onRemoveRecipient = { _, _ -> },
                onSend = { _, _, _, _, _, _ -> }, onLeave = { _, _ -> }, onDiscard = {},
            )
        }
        rule.onNodeWithText("ada@example.org").assertExists()
        rule.onNodeWithText("Hi").assertExists()
    }
}
