package com.vayunmathur.securemail.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.securemail.Navigation
import com.vayunmathur.securemail.Route
import com.vayunmathur.securemail.data.MailFolder
import com.vayunmathur.securemail.data.MailIdentity
import com.vayunmathur.securemail.data.OutboxMessage
import com.vayunmathur.securemail.data.StoredMessage
import com.vayunmathur.securemail.platform.FakeMailStore
import com.vayunmathur.securemail.platform.MailViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.robolectric.annotation.Config
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [Navigation] driven by a real [MailViewModel] backed by [FakeMailStore]:
 * covers the entry wiring (inbox/out/composer/reader/settings), the
 * bottom-bar tab mapping for the top route, and the composer/reader effects.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowIdentities::class])
class NavigationTest {
    @get:Rule val rule = createComposeRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun message(id: String, subject: String, folder: MailFolder) = StoredMessage(
        id = id, from = "ada@example.org", toJson = "me@maemail.org", dateMillis = 1700000000000,
        messageId = id, blobBase64 = "", decryptedBody = "Body of $id",
        decryptedSubject = subject, folder = folder,
    )

    private fun viewModel(store: FakeMailStore) = MailViewModel(app, store)

    @Test fun `navigation without an initial route lands on onboarding`() {
        rule.setContent { Navigation(viewModel(FakeMailStore())) }
        rule.onNodeWithText("Set up SecureMail").assertExists()
        rule.onNodeWithText("Claim an @maemail.org address.", substring = true).assertExists()
    }

    @Test fun `navigation inbox route renders folder messages from the view model store`() {
        val store = FakeMailStore()
        runBlocking {
            store.messages.upsert(message("n1", "Nav inbox one", MailFolder.INBOX))
            store.messages.upsert(message("n2", "Spam one", MailFolder.SPAM))
        }
        rule.setContent { Navigation(viewModel(store), initialRoute = Route.Inbox) }
        rule.onNodeWithText("Nav inbox one").assertExists()
        rule.onNodeWithText("Inbox").assertExists()
    }

    @Test fun `navigation out route renders drafts outbox and sent from the store`() {
        val store = FakeMailStore()
        runBlocking {
            store.messages.upsert(message("d1", "Nav draft", MailFolder.DRAFTS))
            store.messages.upsert(message("s1", "Nav sent", MailFolder.SENT))
            store.outbox.insert(
                OutboxMessage(toJson = "ada@example.org", subject = "Nav queued", body = "b", createdMillis = 1),
            )
        }
        rule.setContent { Navigation(viewModel(store), initialRoute = Route.Out) }
        rule.onNodeWithText("Nav draft").assertExists()
        rule.onNodeWithText("Nav queued").assertExists()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Nav sent"))
        rule.onNodeWithText("Nav sent").assertExists()
        rule.onNodeWithText("Out").assertExists()
    }

    @Test fun `navigation reader route shows the stored message and reader chrome`() {
        val store = FakeMailStore()
        runBlocking { store.messages.upsert(message("r1", "Nav reader", MailFolder.INBOX)) }
        val viewModel = viewModel(store)
        rule.setContent { Navigation(viewModel, initialRoute = Route.Reader("r1")) }
        rule.onNodeWithText("Nav reader").assertExists()
        rule.onNodeWithText("Body of r1", substring = true).assertExists()
        rule.onNodeWithText("Reply").assertExists()
        // Reader entry marks the message read through the ViewModel.
        rule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { store.messages.get("r1")?.isRead == true }
        }
        assertEquals(true, runBlocking { store.messages.get("r1")?.isRead })
    }

    @Test fun `navigation reader route for a missing message shows not found`() {
        rule.setContent { Navigation(viewModel(FakeMailStore()), initialRoute = Route.Reader("ghost")) }
        rule.onNodeWithText("Message not found.").assertExists()
    }

    @Test fun `navigation composer route seeds draft recipients from the route`() {
        val viewModel = viewModel(FakeMailStore())
        rule.setContent {
            Navigation(
                viewModel,
                initialRoute = Route.Composer(to = "ada@example.org, bob@example.org", subject = "Seeded", body = "Seed body"),
            )
        }
        rule.onNodeWithText("ada@example.org").assertExists()
        rule.onNodeWithText("bob@example.org").assertExists()
        rule.onNodeWithText("Seeded").assertExists()
        rule.waitUntil(timeoutMillis = 5_000) { viewModel.draftTo.size == 2 }
        assertEquals(listOf("ada@example.org", "bob@example.org"), viewModel.draftTo)
    }

    @Test fun `navigation settings route lists identities from the store`() {
        val store = FakeMailStore()
        runBlocking {
            store.identities.upsert(
                MailIdentity("me@maemail.org", "", "pub", "AA", "sec", isPrimary = true, createdMillis = 1),
            )
        }
        rule.setContent { Navigation(viewModel(store), initialRoute = Route.Settings) }
        assertTrue(rule.onAllNodesWithText("Settings").fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithText("me@maemail.org", substring = true).assertExists()
        rule.onNodeWithText("Primary", substring = true).assertExists()
    }
}
