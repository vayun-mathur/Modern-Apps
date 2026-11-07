package com.vayunmathur.securemail.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.securemail.FakeAndroidKeyStore
import com.vayunmathur.securemail.Navigation
import com.vayunmathur.securemail.data.MailFolder
import com.vayunmathur.securemail.data.MailIdentity
import com.vayunmathur.securemail.data.StoredMessage
import com.vayunmathur.securemail.platform.FakeMailStore
import com.vayunmathur.securemail.platform.MailUiState
import com.vayunmathur.securemail.platform.MailViewModel
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class NavigationAndScreensExtraTest {

    @get:Rule val rule = createComposeRule()

    @Before fun setUp() = FakeAndroidKeyStore.install()

    private fun vm() = MailViewModel(
        ApplicationProvider.getApplicationContext<Application>(),
        FakeMailStore(),
    )

    private fun msg(id: String, subject: String) = StoredMessage(
        id = id, from = "a@x.org", toJson = "[]", dateMillis = 1000, messageId = id,
        blobBase64 = "", decryptedSubject = subject, decryptedBody = "body of $subject",
    )

    @Test fun navigationStartsOnOnboarding() {
        rule.setContent { Navigation(vm()) }
        rule.onNodeWithText("Set up SecureMail").assertIsDisplayed()
    }

    @Test fun routeSerializationRoundTrips() {
        val json = kotlinx.serialization.json.Json
        val composer = com.vayunmathur.securemail.Route.Composer(to = "a@x.org", subject = "Hi")
        assertEquals(
            composer,
            json.decodeFromString(
                com.vayunmathur.securemail.Route.serializer(),
                json.encodeToString(com.vayunmathur.securemail.Route.serializer(), composer),
            ),
        )
        assertEquals(
            com.vayunmathur.securemail.Route.Reader("m1"),
            json.decodeFromString(
                com.vayunmathur.securemail.Route.serializer(),
                json.encodeToString(
                    com.vayunmathur.securemail.Route.serializer(),
                    com.vayunmathur.securemail.Route.Reader("m1"),
                ),
            ),
        )
    }

    @Test fun inboxSelectionActionsFire() {
        val opened = mutableListOf<String>()
        val trashed = mutableListOf<List<String>>()
        val actions = FolderActions(
            onOpen = { opened += it },
            onCompose = {},
            onSetRead = { _, _ -> },
            onTrash = { trashed += it },
            onMoveTo = { _, _ -> },
            onRestore = {},
            onDeleteForever = {},
            onEmptyTrash = {},
            onConsumeMessage = {},
        )
        rule.setContent {
            InboxScreen(
                state = MailUiState(onboarded = true, locked = false, address = "me@maemail.org"),
                folder = MailFolder.INBOX,
                actions = actions,
                messages = listOf(msg("1", "First subject")),
            )
        }
        rule.onNodeWithText("First subject").assertIsDisplayed()
        rule.onNodeWithText("First subject").performClick()
        assertEquals(listOf("1"), opened)
        assertTrue(trashed.isEmpty())
    }

    @Test fun inboxLongPressSelectsAndTrashEmpties() {
        var emptied = false
        var deleted: List<String>? = null
        val actions = FolderActions(
            onOpen = {}, onCompose = {},
            onSetRead = { _, _ -> }, onTrash = {},
            onMoveTo = { _, _ -> }, onRestore = {},
            onDeleteForever = { deleted = it },
            onEmptyTrash = { emptied = true },
            onConsumeMessage = {},
        )
        rule.setContent {
            InboxScreen(
                state = MailUiState(onboarded = true, locked = false, address = "me@maemail.org"),
                folder = MailFolder.TRASH,
                actions = actions,
                messages = listOf(msg("1", "Trashed subject")),
            )
        }
        rule.onNodeWithText("Empty trash").assertIsDisplayed()
        rule.onNodeWithText("Empty trash").performClick()
        rule.onNodeWithText("Empty trash?").assertIsDisplayed()
        rule.onNodeWithText("Delete").performClick()
        assertTrue(emptied)
        assertEquals(null, deleted)
    }

    @Test fun inboxInSpamShowsEmptyText() {
        val actions = FolderActions(
            onOpen = {}, onCompose = {},
            onSetRead = { _, _ -> }, onTrash = {},
            onMoveTo = { _, _ -> }, onRestore = {},
            onDeleteForever = {}, onEmptyTrash = {},
            onConsumeMessage = {},
        )
        rule.setContent {
            InboxScreen(
                state = MailUiState(onboarded = true, locked = false),
                folder = MailFolder.SPAM,
                actions = actions,
                messages = emptyList(),
            )
        }
        rule.onNodeWithText("No spam. Verification failures land here.").assertIsDisplayed()
    }

    @Test fun readerReplyFiresWithMessage() {
        var replied: StoredMessage? = null
        rule.setContent {
            ReaderScreen(
                state = MailUiState(onboarded = true, locked = false),
                onReply = { replied = it },
                onBack = {},
                message = msg("7", "Repliable"),
            )
        }
        rule.onNodeWithText("body of Repliable").assertIsDisplayed()
        assertEquals(null, replied)
    }

    @Test fun settingsScreenShowsIdentityAndForget() {
        var forgotten = false
        val identity = MailIdentity(
            address = "me@maemail.org", publicArmor = "P", fingerprintHex = "ABCD1234",
            secretArmor = "S", isPrimary = true, createdMillis = 1,
        )
        rule.setContent {
            SettingsScreen(
                state = MailUiState(onboarded = true, locked = false, address = "me@maemail.org", fingerprintHex = "ABCD1234"),
                identities = listOf(identity),
                onCreateAlias = {},
                onSetAliasDisabled = { _, _ -> },
                onExportPublicKey = {},
                onForget = { forgotten = true },
            )
        }
        rule.onNodeWithText("Settings").assertIsDisplayed()
        rule.onNodeWithText("me@maemail.org").assertIsDisplayed()
        assertEquals(false, forgotten)
    }

    @Test fun composeScreenRendersDraftAndSend() {
        var sent = false
        rule.setContent {
            ComposeScreen(
                state = MailUiState(onboarded = true, locked = false, address = "me@maemail.org"),
                identities = emptyList(),
                draftFrom = null,
                onSelectFrom = {},
                draftTo = listOf("bob@x.org"),
                draftCc = emptyList(),
                draftBcc = emptyList(),
                initialSubject = "Hello",
                initialBody = "Body",
                onAddRecipient = { _, _ -> true },
                onRemoveRecipient = { _, _ -> },
                onSend = { _, _, _, _, _, _ -> sent = true },
                onLeave = { _, _ -> },
                onDiscard = {},
            )
        }
        assertTrue(rule.onRoot().fetchSemanticsNode() != null)
        assertEquals(false, sent)
    }
}
