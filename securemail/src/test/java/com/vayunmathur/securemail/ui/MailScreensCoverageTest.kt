package com.vayunmathur.securemail.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import com.vayunmathur.securemail.data.MailFolder
import com.vayunmathur.securemail.data.MailIdentity
import com.vayunmathur.securemail.data.OutboxMessage
import com.vayunmathur.securemail.data.StoredMessage
import com.vayunmathur.securemail.data.StoredMessagePreview
import com.vayunmathur.securemail.platform.MailUiState
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Behaviour-rich Compose coverage beyond [MailScreensTest]: folders, trust states, compose gating. */
@RunWith(RobolectricTestRunner::class)
class MailScreensCoverageTest {
    @get:Rule val rule = createComposeRule()

    private fun msg(
        id: String,
        subject: String?,
        body: String? = "body",
        read: Boolean = false,
        folder: MailFolder = MailFolder.INBOX,
        from: String = "ada@example.org",
        toJson: String = "me@maemail.org",
        unencrypted: Boolean = false,
        verified: String? = null,
        html: String? = null,
    ) = StoredMessage(
        id = id, from = from, toJson = toJson, dateMillis = 1700000000000, messageId = id,
        blobBase64 = "", decryptedBody = body, decryptedSubject = subject, isRead = read,
        folder = folder, unencrypted = unencrypted, verifiedSigner = verified, decryptedHtml = html,
    )

    private fun StoredMessage.toPreview() = StoredMessagePreview(
        id = id, from = from, toJson = toJson, dateMillis = dateMillis, messageId = messageId,
        decryptedSubject = decryptedSubject, verifiedSigner = verifiedSigner, unencrypted = unencrypted,
        isRead = isRead, folder = folder, deliveredTo = deliveredTo,
        preview = decryptedBody, hasDecryptedBody = decryptedBody != null,
    )

    private fun folderActions(
        opened: MutableList<String> = mutableListOf(),
        composed: MutableList<Boolean> = mutableListOf(),
    ) = FolderActions(
        onOpen = { opened += it }, onCompose = { composed += true }, onSetRead = { _, _ -> }, onTrash = {},
        onMoveTo = { _, _ -> }, onRestore = {}, onDeleteForever = {}, onEmptyTrash = {}, onConsumeMessage = {},
    )

    @Test fun `inbox empty text for inbox`() = assertInboxEmpty(MailFolder.INBOX, "No mail yet")
    @Test fun `inbox empty text for sent`() = assertInboxEmpty(MailFolder.SENT, "Nothing sent yet.")
    @Test fun `inbox empty text for spam`() = assertInboxEmpty(MailFolder.SPAM, "No spam.")
    @Test fun `inbox empty text for trash`() = assertInboxEmpty(MailFolder.TRASH, "Trash is empty.")
    @Test fun `inbox empty text for drafts`() = assertInboxEmpty(MailFolder.DRAFTS, "No drafts.")

    private fun assertInboxEmpty(folder: MailFolder, text: String) {
        rule.setContent {
            InboxScreen(state = MailUiState(), folder = folder, actions = folderActions(), messages = emptyList())
        }
        rule.onNodeWithText(text, substring = true).assertExists()
    }

    @Test fun `inbox shows state error and compose fab fires callback`() {
        val composed = mutableListOf<Boolean>()
        rule.setContent {
            InboxScreen(
                state = MailUiState(error = "Relay offline — kept in outbox."),
                folder = MailFolder.INBOX, actions = folderActions(composed = composed),
                messages = listOf(msg("1", "Hello").toPreview()),
            )
        }
        rule.onNodeWithText("Relay offline — kept in outbox.").assertExists()
        rule.onNodeWithText("Hello").assertExists()
        assertTrue(composed.isEmpty())
    }

    @Test fun `inbox trash with messages offers empty trash and encrypted placeholder`() {
        rule.setContent {
            InboxScreen(
                state = MailUiState(), folder = MailFolder.TRASH, actions = folderActions(),
                messages = listOf(msg("t1", null, body = null).toPreview()),
            )
        }
        rule.onNodeWithText("Empty trash").assertExists()
        rule.onNodeWithText("Encrypted message").assertExists()
        rule.onNodeWithText("Open to decrypt", substring = true).assertExists()
    }

    @Test fun `inbox sent folder shows recipient instead of sender`() {
        rule.setContent {
            InboxScreen(
                state = MailUiState(), folder = MailFolder.SENT, actions = folderActions(),
                messages = listOf(msg("s1", "Sent one", toJson = "bob@example.org").toPreview()),
            )
        }
        rule.onNodeWithText("To: bob@example.org", substring = true).assertExists()
        rule.onNodeWithText("Sent one").assertExists()
    }

    @Test fun `reader verified trust line`() {
        rule.setContent {
            ReaderScreen(state = MailUiState(address = "me@maemail.org"), onReply = {}, onBack = {},
                message = msg("v", "Verified", verified = "AABBCCDD"))
        }
        rule.onNodeWithText("Encrypted · signature verified", substring = true).assertExists()
        rule.onNodeWithText("ada@example.org", substring = true).assertExists()
    }

    @Test fun `reader plain trust line`() {
        rule.setContent {
            ReaderScreen(state = MailUiState(), onReply = {}, onBack = {},
                message = msg("p", "Plain", unencrypted = true))
        }
        rule.onNodeWithText("Not encrypted", substring = true).assertExists()
    }

    @Test fun `reader sent trust line`() {
        rule.setContent {
            ReaderScreen(state = MailUiState(), onReply = {}, onBack = {},
                message = msg("s", "Mine", folder = MailFolder.SENT))
        }
        rule.onNodeWithText("Sent encrypted", substring = true).assertExists()
        rule.onNodeWithText("from me", substring = true).assertExists()
    }

    @Test fun `reader locked trust line`() {
        rule.setContent {
            ReaderScreen(state = MailUiState(), onReply = {}, onBack = {},
                message = msg("l", "", body = null))
        }
        rule.onNodeWithText("Locked · decrypting", substring = true).assertExists()
        rule.onNodeWithText("(no subject)", substring = true).assertExists()
    }

    @Test fun `reader reply button and app bar reply report the message`() {
        val replies = mutableListOf<StoredMessage>()
        val message = msg("r1", "Re me", body = "please answer")
        rule.setContent {
            ReaderScreen(state = MailUiState(), onReply = { replies += it }, onBack = {}, message = message)
        }
        rule.onNodeWithText("Reply").performClick()
        assertEquals(listOf(message), replies)
        rule.onNodeWithText("please answer", substring = true).assertExists()
    }

    @Test fun `reader sent unencrypted trust line warns`() {
        rule.setContent {
            ReaderScreen(state = MailUiState(), onReply = {}, onBack = {},
                message = msg("su", "Plain sent", folder = MailFolder.SENT, unencrypted = true))
        }
        rule.onNodeWithText("Sent unencrypted", substring = true).assertExists()
    }

    @Test fun `message body view collapses quoted history until toggled`() {
        val body = "Top answer\nOn Mon, Ada wrote:\n> old question"
        rule.setContent { MessageBodyView(msg("q", "S", body = body)) }
        rule.onNodeWithText("Top answer", substring = true).assertExists()
        rule.onNodeWithText("Show quoted text").assertExists()
        rule.onAllNodes(hasText("old question", substring = true)).assertCountEquals(0)
        rule.onNodeWithText("Show quoted text").performClick()
        rule.onNodeWithText("old question", substring = true).assertExists()
        rule.onNodeWithText("Hide quoted text").assertExists()
    }

    @Test fun `message body view shows decrypting placeholder without a body`() {
        rule.setContent { MessageBodyView(msg("d", "S", body = null)) }
        rule.onNodeWithText("(decrypting…)", substring = true).assertExists()
    }

    @Test fun `message body view renders links text verbatim`() {
        rule.setContent { MessageBodyView(msg("ln", "S", body = "See https://example.org/path and ada@example.org")) }
        rule.onNodeWithText("https://example.org/path", substring = true).assertExists()
    }

    @Test fun `compose shows cc bcc fields from picker and discard`() {
        var discarded = 0
        val sent = mutableListOf<List<String>>()
        rule.setContent {
            ComposeScreen(
                state = MailUiState(address = "me@maemail.org"),
                identities = listOf(
                    MailIdentity("me@maemail.org", "", "pub", "AA", "sec", isPrimary = true, createdMillis = 1),
                    MailIdentity("alias@maemail.org", "Shopping", "pub", "BB", "sec", createdMillis = 2),
                ),
                draftFrom = "alias@maemail.org", onSelectFrom = {},
                draftTo = listOf("ada@example.org"), draftCc = listOf("cc@example.org"), draftBcc = emptyList(),
                initialSubject = "Plans", initialBody = "Body text",
                onAddRecipient = { _, _ -> true }, onRemoveRecipient = { _, _ -> },
                onSend = { to, _, _, _, _, _ -> sent += to }, onLeave = { _, _ -> }, onDiscard = { discarded++ },
            )
        }
        rule.onNodeWithText("From: alias@maemail.org", substring = true).assertExists()
        rule.onNodeWithText("ada@example.org").assertExists()
        rule.onNodeWithText("cc@example.org").assertExists()
        rule.onNodeWithText("To").assertExists()
        rule.onNodeWithText("Cc").assertExists()
        rule.onNodeWithText("Bcc").assertExists()
        rule.onNodeWithText("Plans").assertExists()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Discard"))
        rule.onNodeWithText("Discard").performClick()
        assertEquals(1, discarded)
        assertTrue(sent.isEmpty())
    }

    @Test fun `compose send fires with draft recipients subject and body`() {
        var sentArgs: List<Any?>? = null
        rule.setContent {
            ComposeScreen(
                state = MailUiState(address = "me@maemail.org"), identities = emptyList(), draftFrom = null,
                onSelectFrom = {}, draftTo = listOf("ada@example.org"), draftCc = emptyList(), draftBcc = listOf("hidden@example.org"),
                initialSubject = "Hi there", initialBody = "Full body",
                onAddRecipient = { _, _ -> true }, onRemoveRecipient = { _, _ -> },
                onSend = { to, cc, bcc, subject, body, reply -> sentArgs = listOf(to, cc, bcc, subject, body, reply) },
                onLeave = { _, _ -> }, onDiscard = {},
            )
        }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Send"))
        rule.onNodeWithText("Send").performClick()
        rule.waitUntil(timeoutMillis = 5_000) { sentArgs != null }
        assertEquals(
            listOf(listOf("ada@example.org"), emptyList<String>(), listOf("hidden@example.org"), "Hi there", "Full body", null),
            sentArgs,
        )
    }

    @Test fun `compose shows state error`() {
        rule.setContent {
            ComposeScreen(
                state = MailUiState(error = "Key changed for ada@example.org."), identities = emptyList(), draftFrom = null,
                onSelectFrom = {}, draftTo = emptyList(), draftCc = emptyList(), draftBcc = emptyList(),
                initialSubject = "", initialBody = "",
                onAddRecipient = { _, _ -> true }, onRemoveRecipient = { _, _ -> },
                onSend = { _, _, _, _, _, _ -> }, onLeave = { _, _ -> }, onDiscard = {},
            )
        }
        rule.onNodeWithText("Key changed for ada@example.org.").assertExists()
    }

    @Test fun `out screen shows outbox errors retry and sent rows`() {
        var retries = 0
        val actions = OutActions(
            onCompose = {}, onOpenDraft = {}, onDeleteDraft = {}, onRestoreDraft = {},
            onRetryOutbox = { retries++ }, onOpenSent = {}, onTrashSent = {}, onUntrashSent = {}, onSetRead = { _, _ -> },
        )
        rule.setContent {
            OutScreen(
                drafts = emptyList(),
                outbox = listOf(
                    OutboxMessage(rowId = 3, toJson = "ada@example.org", subject = "Queued one", body = "b",
                        createdMillis = 1, lastError = "publish failed: no session", attemptCount = 2),
                ),
                sent = listOf(msg("s1", "Already sent", folder = MailFolder.SENT).toPreview()),
                actions = actions,
            )
        }
        rule.onNodeWithText("Outbox").assertExists()
        rule.onNodeWithText("Queued one").assertExists()
        rule.onNodeWithText("publish failed: no session", substring = true).assertExists()
        rule.onNodeWithText("2 tries", substring = true).assertExists()
        rule.onNodeWithText("Retry all").assertExists()
        rule.onNodeWithText("Already sent").assertExists()
        rule.onNodeWithText("Retry all").performClick()
        assertEquals(1, retries)
    }

    @Test fun `out screen empty sections explain themselves`() {
        val actions = OutActions(
            onCompose = {}, onOpenDraft = {}, onDeleteDraft = {}, onRestoreDraft = {},
            onRetryOutbox = {}, onOpenSent = {}, onTrashSent = {}, onUntrashSent = {}, onSetRead = { _, _ -> },
        )
        rule.setContent { OutScreen(drafts = emptyList(), outbox = emptyList(), sent = emptyList(), actions = actions) }
        rule.onNodeWithText("No drafts.").assertExists()
        rule.onNodeWithText("Nothing waiting to send.").assertExists()
        rule.onNodeWithText("Nothing sent yet.").assertExists()
    }

    @Test fun `bottom bar labels and folder tab mapping for every folder`() {
        assertEquals(MailTab.INBOX, MailFolder.INBOX.tab())
        assertEquals(MailTab.SPAM, MailFolder.SPAM.tab())
        assertEquals(MailTab.TRASH, MailFolder.TRASH.tab())
        assertEquals(MailTab.OUT, MailFolder.SENT.tab())
        assertEquals(MailTab.OUT, MailFolder.DRAFTS.tab())
        rule.setContent { MailBottomBar(selected = MailTab.OUT, outboxCount = 0, onSelect = {}) }
        rule.onNodeWithText("Inbox").assertExists()
        rule.onNodeWithText("Spam").assertExists()
        rule.onNodeWithText("Out").assertExists()
        rule.onNodeWithText("Trash").assertExists()
        rule.onNodeWithText("Settings").assertExists()
    }

    @Test fun `settings shows fingerprint aliases and disabled state`() {
        val identities = listOf(
            MailIdentity("me@maemail.org", "", "pub", "AA", "sec", isPrimary = true, createdMillis = 1),
            MailIdentity("alias@maemail.org", "Shopping", "pub", "BB", "sec", disabled = true, createdMillis = 2),
        )
        rule.setContent {
            SettingsScreen(
                state = MailUiState(address = "me@maemail.org", fingerprintHex = "AABBCC"),
                identities = identities, onCreateAlias = {}, onSetAliasDisabled = { _, _ -> },
                onExportPublicKey = {}, onForget = {},
            )
        }
        rule.onNodeWithText("Primary", substring = true).assertExists()
        rule.onNodeWithText("Shopping", substring = true).assertExists()
        rule.onNodeWithText("Key: BB", substring = true).assertExists()
        rule.onNodeWithText("disabled, mail held", substring = true).assertExists()
        rule.onNodeWithText("Relay: offline", substring = true).assertExists()
    }

    @Test fun `inbox trash empty dialog deletes everything or cancels`() {
        var emptied = 0
        val deleted = mutableListOf<List<String>>()
        val actions = FolderActions(
            onOpen = {}, onCompose = {}, onSetRead = { _, _ -> }, onTrash = {},
            onMoveTo = { _, _ -> }, onRestore = {}, onDeleteForever = { deleted += it },
            onEmptyTrash = { emptied++ }, onConsumeMessage = {},
        )
        rule.setContent {
            InboxScreen(state = MailUiState(), folder = MailFolder.TRASH, actions = actions,
                messages = listOf(msg("t1", "Trash one").toPreview(), msg("t2", "Trash two").toPreview()))
        }
        rule.onNodeWithText("Empty trash").performClick()
        rule.onNodeWithText("Empty trash?").assertExists()
        rule.onNodeWithText("2 messages will be permanently deleted", substring = true).assertExists()
        rule.onNodeWithText("Cancel").performClick()
        rule.onAllNodes(hasText("Empty trash?")).assertCountEquals(0)
        assertEquals(0, emptied)
        rule.onNodeWithText("Empty trash").performClick()
        rule.onNodeWithText("Delete").performClick()
        assertEquals(1, emptied)
        assertTrue(deleted.isEmpty())
    }

    @Test fun `inbox long press opens selection bar`() {
        rule.setContent {
            InboxScreen(state = MailUiState(), folder = MailFolder.INBOX, actions = folderActions(),
                messages = listOf(msg("1", "Selectable").toPreview(), msg("2", "Other row").toPreview()))
        }
        rule.onNodeWithText("Selectable").performTouchInput { longClick() }
        rule.onNodeWithText("1 selected").assertExists()
        // Tapping another row while selecting toggles it into the selection.
        rule.onNodeWithText("Other row").performClick()
        rule.onNodeWithText("2 selected").assertExists()
    }

    @Test fun `message body view splits on original message marker`() {
        val body = "Fresh text\n----- Original Message -----\nquoted history"
        rule.setContent { MessageBodyView(msg("o", "S", body = body)) }
        rule.onNodeWithText("Fresh text", substring = true).assertExists()
        rule.onAllNodes(hasText("quoted history", substring = true)).assertCountEquals(0)
        rule.onNodeWithText("Show quoted text").performClick()
        rule.onNodeWithText("quoted history", substring = true).assertExists()
    }

    @Test fun `message body view html blocks remote content until load is tapped`() {
        val html = "<p>Hello html</p><img src=\"https://tracker.example.org/x.png\">"
        rule.setContent { MessageBodyView(msg("h", "S", body = null, html = html)) }
        rule.onNodeWithText("Remote content blocked").assertExists()
        rule.onNodeWithText("Load").performClick()
        rule.onAllNodes(hasText("Remote content blocked")).assertCountEquals(0)
    }

    @Test fun `message body view html quoted sections start hidden`() {
        val html = "<p>Main part</p><div class=\"gmail_quote\">quoted part</div>"
        rule.setContent { MessageBodyView(msg("hq", "S", body = null, html = html)) }
        rule.onNodeWithText("Show quoted text").assertExists()
        rule.onNodeWithText("Show quoted text").performClick()
        rule.onNodeWithText("Hide quoted text").assertExists()
    }

    @Test fun `compose typing an address and tapping a chip routes callbacks`() {
        val added = mutableListOf<Pair<Int, String>>()
        val removed = mutableListOf<Pair<Int, String>>()
        rule.setContent {
            ComposeScreen(
                state = MailUiState(address = "me@maemail.org"), identities = emptyList(), draftFrom = null,
                onSelectFrom = {}, draftTo = listOf("ada@example.org"), draftCc = emptyList(), draftBcc = emptyList(),
                initialSubject = "", initialBody = "Body",
                onAddRecipient = { target, address -> added += target to address; true },
                onRemoveRecipient = { target, address -> removed += target to address },
                onSend = { _, _, _, _, _, _ -> }, onLeave = { _, _ -> }, onDiscard = {},
            )
        }
        rule.onNodeWithText("ada@example.org").performClick()
        assertEquals(listOf(0 to "ada@example.org"), removed)
        assertTrue(added.isEmpty())
    }

    @Test fun `recipient chip field submits on ime action and rejects via callback`() {
        val added = mutableListOf<String>()
        var invalid = 0
        var accept = true
        rule.setContent {
            RecipientChipField(
                label = "To", recipients = listOf("ada@example.org"),
                onAdd = { added += it; accept },
                onRemove = {},
                onPickContact = {},
                onInvalid = { invalid++ },
            )
        }
        rule.onNode(hasSetTextAction()).performTextInput("new@example.org")
        rule.onNode(hasSetTextAction()).performImeAction()
        assertEquals(listOf("new@example.org"), added)
        assertEquals(0, invalid)
        accept = false
        rule.onNode(hasSetTextAction()).performTextReplacement("bad@example.org")
        rule.onNode(hasSetTextAction()).performImeAction()
        assertEquals(listOf("new@example.org", "bad@example.org"), added)
        assertEquals(1, invalid)
    }

    @Test fun `compose from picker selects another identity`() {
        var selected: String? = null
        rule.setContent {
            ComposeScreen(
                state = MailUiState(address = "me@maemail.org"),
                identities = listOf(
                    MailIdentity("me@maemail.org", "", "pub", "AA", "sec", isPrimary = true, createdMillis = 1),
                    MailIdentity("alias@maemail.org", "Shopping", "pub", "BB", "sec", createdMillis = 2),
                ),
                draftFrom = null, onSelectFrom = { selected = it },
                draftTo = listOf("ada@example.org"), draftCc = emptyList(), draftBcc = emptyList(),
                initialSubject = "", initialBody = "Body",
                onAddRecipient = { _, _ -> true }, onRemoveRecipient = { _, _ -> },
                onSend = { _, _, _, _, _, _ -> }, onLeave = { _, _ -> }, onDiscard = {},
            )
        }
        rule.onNodeWithText("From: me@maemail.org", substring = true).performClick()
        rule.onNodeWithText("alias@maemail.org").performClick()
        assertEquals("alias@maemail.org", selected)
    }

    @Test fun `lock screen renders its gate`() {
        rule.setContent { LockScreen(state = MailUiState(locked = true, error = "Unlock to read your mail."), onUnlock = {}, onDismissMessages = {}) }
        rule.onNodeWithText("Unlock to read your mail.", substring = true).assertExists()
    }

    @Test fun `onboarding renders its gate`() {
        rule.setContent { OnboardingScreen(state = MailUiState(busy = true), onClaim = { _, _ -> }, onDismissMessages = {}) }
        rule.onNodeWithText("Set up SecureMail").assertExists()
    }
}
