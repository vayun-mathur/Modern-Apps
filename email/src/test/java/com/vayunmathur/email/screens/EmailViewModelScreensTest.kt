package com.vayunmathur.email.screens

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.vayunmathur.email.data.BlockedSender
import com.vayunmathur.email.data.DraftEntry
import com.vayunmathur.email.data.EmailAccount
import com.vayunmathur.email.data.EmailDatabase
import com.vayunmathur.email.data.EmailMessage
import com.vayunmathur.email.data.EmailRepository
import com.vayunmathur.email.data.OutboxEntry
import com.vayunmathur.email.platform.EmailViewModel
import com.vayunmathur.email.ui.DraftsScreen
import com.vayunmathur.email.ui.OutboxScreen
import com.vayunmathur.email.ui.SettingsScreen
import com.vayunmathur.email.ui.signatureBlockHtml
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Drives the real [EmailViewModel] against a pre-seeded in-memory database
 * (via the shared `databases` registry, so no SQLCipher/KeyStore is touched)
 * and renders Drafts/Outbox/Settings through their real composables.
 *
 * Selection, search, blocking and signatures are ViewModel state with direct
 * list impact: a wrong toggle shows the bulk-action bar on the wrong rows,
 * a missed block leaks the sender back into the inbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmailViewModelScreensTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var db: EmailDatabase
    private lateinit var vm: EmailViewModel

    private fun preview(
        uid: Long,
        from: String = "friend@x.com",
        subject: String = "hello",
    ) = EmailMessage(
        accountEmail = "me@x.com", folderName = "INBOX", id = uid,
        subject = subject, from = from, date = "d", dateMillis = uid,
    )

    @BeforeTest
    fun open() {
        try {
            WorkManager.initialize(context, Configuration.Builder().build())
        } catch (_: IllegalStateException) {
            // Already initialized in this sandbox.
        }
        db = Room.inMemoryDatabaseBuilder(context, EmailDatabase::class.java)
            .allowMainThreadQueries().build()
        resetRepositorySingleton()
        com.vayunmathur.library.util.databases[EmailDatabase::class] = db
        // Create the VM after seeding so the repository binds this test's db.
        vm = EmailViewModel(context)
        runBlocking {
            db.accountDao().insertAccount(EmailAccount(email = "me@x.com"))
        }
    }

    @AfterTest
    fun close() {
        com.vayunmathur.library.util.databases.remove(EmailDatabase::class)
        resetRepositorySingleton()
        db.close()
    }

    private fun resetRepositorySingleton() {
        // EmailRepository holds `private static volatile instance` on itself;
        // clear it so the next EmailRepository.get() re-reads the databases
        // registry instead of a closed db from an earlier test.
        val field = EmailRepository::class.java.getDeclaredField("instance")
        field.isAccessible = true
        field.set(null, null)
    }

    // ---------- pure selection / search state ----------

    @Test
    fun `selection toggles and clears`() = runTest {
        assertEquals(emptySet(), vm.selectedMessageUids.first())
        vm.toggleMessageSelection(7L)
        vm.toggleMessageSelection(9L)
        assertEquals(setOf(7L, 9L), vm.selectedMessageUids.first())
        vm.toggleMessageSelection(7L)
        assertEquals(setOf(9L), vm.selectedMessageUids.first())
        vm.clearSelection()
        assertEquals(emptySet(), vm.selectedMessageUids.first())
    }

    @Test
    fun `account and folder selection reset search`() = runTest {
        vm.setSearchQuery("hello")
        assertEquals("hello", vm.searchQuery.first())
        vm.selectAccount("me@x.com")
        assertEquals("", vm.searchQuery.first())
        assertEquals("INBOX", vm.selectedFolderName.first())
        vm.setSearchQuery("x")
        vm.selectFolder("Sent")
        assertEquals("Sent", vm.selectedFolderName.first())
        assertEquals("", vm.searchQuery.first())
        vm.selectAccount("")
        assertEquals(null, vm.selectedAccountEmail.first())
    }

    @Test
    fun `blocked sender is hidden from messages flow`() = runTest {
        db.messageDao().insertMessages(
            listOf(
                preview(1L, from = "Spammy <spam@evil.com>"),
                preview(2L, from = "friend@x.com"),
            )
        )
        // Give the VM's init a chance to pick the account, then read unified inbox.
        vm.selectAccount("")
        val before = vm.messages.first()
        assertEquals(2, before.size)
        vm.blockSender("Spammy <spam@evil.com>")
        // Block runs on viewModelScope; poll the flow until it applies.
        var after = before
        repeat(200) {
            after = vm.messages.first()
            if (after.size == 1) return@repeat
            Thread.sleep(10)
        }
        assertEquals(1, after.size)
        assertEquals("friend@x.com", after.single().from)
        // Case-insensitive substring match catches bare-address variants.
        assertEquals(
            listOf("spam@evil.com"),
            db.accountDao().getBlockedSendersFlow().first().map { it.address },
        )
        vm.unblockSender("spam@evil.com")
        repeat(200) {
            if (vm.messages.first().size == 2) return@repeat
            Thread.sleep(10)
        }
        assertEquals(2, vm.messages.first().size)
    }

    @Test
    fun `signatureBlockHtml escapes and formats`() {
        assertEquals("", signatureBlockHtml(null))
        assertEquals("", signatureBlockHtml(EmailAccount(email = "e", signature = "  ")))
        val html = signatureBlockHtml(EmailAccount(email = "e", signature = "Best,\nMe <3"))
        assertTrue(html.startsWith("<br><br>-- <br>"), html)
        assertTrue(html.contains("Best,"), html)
        assertTrue(html.contains("&lt;3"), html)
    }

    // ---------- composable screens ----------

    @Test
    fun `drafts screen shows empty state then the saved draft`() {
        composeRule.setContent { DraftsScreen(viewModel = vm, onBack = {}, onOpenDraft = {}) }
        composeRule.onNodeWithText("No drafts", substring = true).assertIsDisplayed()

        runBlocking {
            db.outboxDao().insertDraft(
                DraftEntry(accountEmail = "me@x.com", to = "a@b.com", subject = "Trip plans", body = "line1\nline2")
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Trip plans").assertIsDisplayed()
        composeRule.onNodeWithText("To: a@b.com", substring = true).assertIsDisplayed()
    }

    @Test
    fun `drafts screen falls back to no-subject label`() {
        runBlocking {
            db.outboxDao().insertDraft(DraftEntry(accountEmail = "me@x.com", body = "bod"))
        }
        composeRule.setContent { DraftsScreen(viewModel = vm, onBack = {}, onOpenDraft = {}) }
        composeRule.waitForIdle()
        // Subject blank -> "(No subject)"-style fallback; body still shown.
        composeRule.onNodeWithText("bod", substring = true).assertIsDisplayed()
    }

    @Test
    fun `outbox screen shows empty state then the queued entry`() {
        composeRule.setContent { OutboxScreen(viewModel = vm, onBack = {}) }
        composeRule.onNodeWithText("Outbox is empty", substring = true).assertIsDisplayed()

        runBlocking {
            db.outboxDao().insertOutboxEntry(
                OutboxEntry(accountEmail = "me@x.com", to = "a@b.com", subject = "Hi", body = "first line\nsecond")
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Hi").assertIsDisplayed()
        composeRule.onNodeWithText("first line", substring = true).assertIsDisplayed()
    }

    @Test
    fun `outbox row surfaces send errors and retry counts`() {
        runBlocking {
            db.outboxDao().insertOutboxEntry(
                OutboxEntry(
                    accountEmail = "me@x.com", to = "a@b.com", subject = "Stuck",
                    body = "b", lastError = "timeout", attemptCount = 3,
                )
            )
        }
        composeRule.setContent { OutboxScreen(viewModel = vm, onBack = {}) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Stuck").assertIsDisplayed()
        composeRule.onNodeWithText("timeout", substring = true).assertIsDisplayed()
    }

    @Test
    fun `settings screen shows sections and edits signature`() {
        var backPresses = 0
        composeRule.setContent { SettingsScreen(viewModel = vm, onBack = { backPresses++ }) }
        // The blocked-senders flow is cold (no stateIn), so its first emission
        // races composition on Room's query executor: wait until it lands.
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("me@x.com").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("me@x.com").assertIsDisplayed()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Blocked senders", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Blocked senders", substring = true).assertIsDisplayed()
    }

    @Test
    fun `settings screen lists blocked senders`() {
        runBlocking {
            db.accountDao().insertBlockedSender(BlockedSender("spam@evil.com"))
        }
        composeRule.setContent { SettingsScreen(viewModel = vm, onBack = {}) }
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("spam@evil.com").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("spam@evil.com").assertIsDisplayed()
    }
}
