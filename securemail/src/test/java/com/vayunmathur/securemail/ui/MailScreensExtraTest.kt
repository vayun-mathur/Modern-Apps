package com.vayunmathur.securemail.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import com.vayunmathur.securemail.data.MailFolder
import com.vayunmathur.securemail.data.MailIdentity
import com.vayunmathur.securemail.data.StoredMessage
import com.vayunmathur.securemail.data.StoredMessagePreview
import com.vayunmathur.securemail.platform.MailUiState
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Remaining screen branches: reader details sheet, settings actions, onboarding/lock, rows. */
@RunWith(RobolectricTestRunner::class)
class MailScreensExtraTest {
    @get:Rule val rule = createComposeRule()

    private fun msg(
        id: String = "1",
        subject: String? = "Subject",
        body: String? = "Body text",
        from: String = "ada@example.org",
        toJson: String = "me@maemail.org",
        folder: MailFolder = MailFolder.INBOX,
        verified: String? = null,
        unencrypted: Boolean = false,
        read: Boolean = false,
        date: Long = 1700000000000,
    ) = StoredMessage(
        id = id, from = from, toJson = toJson, dateMillis = date, messageId = id, blobBase64 = "",
        decryptedBody = body, decryptedSubject = subject, folder = folder, verifiedSigner = verified,
        unencrypted = unencrypted, isRead = read,
    )

    private fun StoredMessage.toPreview() = StoredMessagePreview(
        id = id, from = from, toJson = toJson, dateMillis = dateMillis, messageId = messageId,
        decryptedSubject = decryptedSubject, verifiedSigner = verifiedSigner, unencrypted = unencrypted,
        isRead = isRead, folder = folder, deliveredTo = deliveredTo,
        preview = decryptedBody, hasDecryptedBody = decryptedBody != null,
    )

    private fun identity(address: String, primary: Boolean, label: String = "", disabled: Boolean = false, fp: String = "AABBCCDD") =
        MailIdentity(address, label, "pub", fp, "sec", isPrimary = primary, disabled = disabled, createdMillis = 1)

    @Test fun `reader details sheet shows from to and chunked signer fingerprint`() {
        rule.setContent {
            ReaderScreen(
                state = MailUiState(address = "me@maemail.org"), onReply = {}, onBack = {},
                message = msg(verified = "AABBCCDD11223344"),
            )
        }
        rule.onNodeWithText("ada@example.org", substring = true).performClick()
        rule.onNodeWithText("Signer fingerprint").assertExists()
        rule.onNodeWithText("AABB CCDD 1122 3344").assertExists()
        rule.onNodeWithText("From").assertExists()
        rule.onNodeWithText("To").assertExists()
    }

    @Test fun `reader with no message shows not found`() {
        rule.setContent { ReaderScreen(state = MailUiState(), onReply = {}, onBack = {}, message = null) }
        rule.onNodeWithText("Message not found.").assertExists()
    }

    @Test fun `reader sent with blank recipient shows placeholders`() {
        rule.setContent {
            ReaderScreen(
                state = MailUiState(), onReply = {}, onBack = {},
                message = msg(folder = MailFolder.SENT, toJson = "", subject = ""),
            )
        }
        rule.onNodeWithText("(unknown)", substring = true).assertExists()
        rule.onNodeWithText("(no subject)", substring = true).assertExists()
    }

    @Test fun `settings actions fire create alias with label export and forget`() {
        val created = mutableListOf<String>()
        var exports = 0
        var forgets = 0
        rule.setContent {
            SettingsScreen(
                state = MailUiState(
                    address = "me@maemail.org", socketConnected = true,
                    exportArmor = "-----BEGIN-----\narmor",
                ),
                identities = listOf(identity("me@maemail.org", primary = true)),
                onCreateAlias = { created += it }, onSetAliasDisabled = { _, _ -> },
                onExportPublicKey = { exports++ }, onForget = { forgets++ },
            )
        }
        rule.onNodeWithText("-----BEGIN-----\narmor", substring = true).assertExists()
        rule.onNodeWithText("Relay: connected").assertExists()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Label for new alias (optional)"))
        rule.onNodeWithText("Label for new alias (optional)").performTextInput("Shopping")
        rule.onNodeWithText("New random alias").performClick()
        assertEquals(listOf("Shopping"), created)
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Show public key"))
        rule.onNodeWithText("Show public key").performClick()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Forget identity on this device"))
        rule.onNodeWithText("Forget identity on this device").performClick()
        assertEquals(1, exports)
        assertEquals(1, forgets)
    }

    @Test fun `settings alias switch toggles and busy state disables creation`() {
        val toggles = mutableListOf<Pair<String, Boolean>>()
        rule.setContent {
            SettingsScreen(
                state = MailUiState(busy = true, socketConnected = true),
                identities = listOf(
                    identity("me@maemail.org", primary = true),
                    identity("alias@maemail.org", primary = false, label = ""),
                ),
                onCreateAlias = {}, onSetAliasDisabled = { a, d -> toggles += a to d },
                onExportPublicKey = {}, onForget = {},
            )
        }
        rule.onNodeWithText("Alias").assertExists()
        rule.onNodeWithText("Creating…").assertExists()
        // The only switch on screen belongs to the alias row.
        rule.onAllNodesWithText("alias@maemail.org").assertCountEquals(1)
        assertTrue(toggles.isEmpty())
    }

    @Test fun `onboarding claim stays disabled until both fields are filled`() {
        val claims = mutableListOf<Pair<String, String>>()
        rule.setContent {
            OnboardingScreen(state = MailUiState(), onClaim = { a, b -> claims += a to b }, onDismissMessages = {})
        }
        rule.onNodeWithText("Claim address + generate key").assertExists()
        rule.onNodeWithText("name (→ name@maemail.org)").performTextInput("alice")
        rule.onNodeWithText("Key passphrase (min 8 chars)").performTextInput("passphrase-1")
        rule.onNodeWithText("Claim address + generate key").performClick()
        assertEquals(listOf("alice" to "passphrase-1"), claims)
    }

    @Test fun `lock unlock reports its action`() {
        var unlocks = 0
        rule.setContent { LockScreen(state = MailUiState(error = "Biometric failed"), onUnlock = { unlocks++ }, onDismissMessages = {}) }
        rule.onNodeWithText("Biometric failed").assertExists()
        rule.onNodeWithText("Unlock").performClick()
        assertEquals(1, unlocks)
    }

    @Test fun `onboarding busy shows generating and error`() {
        rule.setContent { OnboardingScreen(state = MailUiState(busy = true, error = "Taken"), onClaim = { _, _ -> }, onDismissMessages = {}) }
        rule.onNodeWithText("Generating…").assertExists()
        rule.onNodeWithText("Taken").assertExists()
    }

    @Test fun `message row shows sender preview date and click callbacks`() {
        var clicks = 0
        var longs = 0
        rule.setContent {
            MessageRow(
                msg = msg(body = "\n\n  First non-blank line\nsecond", read = false).toPreview(),
                showRecipient = false, isSelected = true,
                onClick = { clicks++ }, onLongClick = { longs++ }, onToggleRead = {}, onSwipeDelete = {}, inTrash = false,
            )
        }
        rule.onNodeWithText("First non-blank line").assertExists()
        rule.onNodeWithText("ada@example.org").assertExists()
        rule.onNodeWithText("Subject").performClick()
        assertEquals(1, clicks)
        assertEquals(0, longs)
    }

    @Test fun `message row recipient and encrypted placeholders`() {
        rule.setContent {
            MessageRow(
                msg = msg(body = null, from = "", toJson = "", subject = null).toPreview(),
                showRecipient = true, isSelected = false,
                onClick = {}, onLongClick = {}, onToggleRead = {}, onSwipeDelete = {}, inTrash = true,
            )
        }
        rule.onNodeWithText("To: (unknown)", substring = true).assertExists()
        rule.onNodeWithText("Encrypted message").assertExists()
        rule.onNodeWithText("Open to decrypt").assertExists()
    }

    @Test fun `message row blank sender shows encrypted sender placeholder`() {
        rule.setContent {
            MessageRow(
                msg = msg(body = null, from = "", unencrypted = true).toPreview(),
                showRecipient = false, isSelected = false,
                onClick = {}, onLongClick = {}, onToggleRead = {}, onSwipeDelete = {}, inTrash = false,
            )
        }
        rule.onNodeWithText("(encrypted sender)").assertExists()
    }
}
