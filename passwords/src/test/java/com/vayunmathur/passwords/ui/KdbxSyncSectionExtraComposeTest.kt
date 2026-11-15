package com.vayunmathur.passwords.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.vayunmathur.passwords.FakeAndroidKeyStore
import com.vayunmathur.passwords.sync.KdbxSyncSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KdbxSyncSectionExtraComposeTest {

    @get:Rule val rule = createComposeRule()

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        runCatching { WorkManager.initialize(ctx, Configuration.Builder().build()) }
        runBlocking {
            KdbxSyncSettings.clearQuarantine(ctx)
            KdbxSyncSettings.setEnabled(ctx, false)
            KdbxSyncSettings.setDocumentUri(ctx, "")
        }
    }

    private fun awaitText(text: String) {
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun toggleEnableSchedulesSyncWork() {
        rule.setContent { KdbxSyncSection() }
        rule.onNodeWithText("Sync with a KDBX file").performClick()
        // Enabling persists through DataStore and enqueues the periodic + immediate work.
        rule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { KdbxSyncSettings.enabled(ctx) }
        }
        val wm = WorkManager.getInstance(ctx)
        rule.waitUntil(timeoutMillis = 5_000) {
            wm.getWorkInfosForUniqueWork("KdbxSyncPeriodic").get().isNotEmpty()
        }
        assertTrue(wm.getWorkInfosForUniqueWork("KdbxSyncPeriodic").get().isNotEmpty())
        // And disabling cancels the schedule again.
        rule.onNodeWithText("Sync with a KDBX file").performClick()
        rule.waitUntil(timeoutMillis = 5_000) {
            !runBlocking { KdbxSyncSettings.enabled(ctx) }
        }
        assertEquals(false, runBlocking { KdbxSyncSettings.enabled(ctx) })
    }

    @Test fun errorStatusShowsSpecificReason() {
        runBlocking { KdbxSyncSettings.recordFailure(ctx, "wrong_password") }
        rule.setContent { KdbxSyncSection() }
        awaitText("Wrong vault password. Nothing was changed locally.")
        rule.onNodeWithText("Wrong vault password. Nothing was changed locally.", substring = true)
            .assertIsDisplayed()
    }

    @Test fun genericErrorStatusIncludesReason() {
        runBlocking { KdbxSyncSettings.recordFailure(ctx, "disk exploded") }
        rule.setContent { KdbxSyncSection() }
        awaitText("Sync failed: disk exploded")
    }

    @Test fun successStatusShowsFormattedLastSyncDate() {
        runBlocking { KdbxSyncSettings.recordSuccess(ctx) }
        rule.setContent { KdbxSyncSection() }
        // "Never" must be gone once a sync has been recorded.
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithText("Never").fetchSemanticsNodes().isEmpty()
        }
        rule.onNodeWithText("Last sync").assertIsDisplayed()
    }

    @Test fun quarantineResolveConfirmClearsQuarantine() {
        runBlocking {
            KdbxSyncSettings.recordQuarantine(ctx, KdbxSyncSettings.QUARANTINE_FOREIGN_DELETE)
        }
        rule.setContent { KdbxSyncSection() }
        awaitText("Review paused sync")
        rule.onNodeWithText("Review paused sync").performClick()
        rule.onNodeWithText("Re-check and resume").performClick()
        rule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { KdbxSyncSettings.quarantineFlow(ctx).first() } == ""
        }
        assertEquals("", runBlocking { KdbxSyncSettings.quarantineFlow(ctx).first() })
    }

    @Test fun passwordDialogSaveDisabledUntilTextEntered() {
        rule.setContent { KdbxSyncSection() }
        rule.onNodeWithText("Vault password").performClick()
        rule.onNodeWithText("Save").assertIsNotEnabled()
        rule.onAllNodes(hasSetTextAction())[0].performTextInput("x")
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithText("Sync now").assertIsDisplayed()
    }

    @Test fun documentUriIsShownWhenConfigured() {
        runBlocking { KdbxSyncSettings.setDocumentUri(ctx, "content://docs/vault.kdbx") }
        rule.setContent { KdbxSyncSection() }
        awaitText("content://docs/vault.kdbx")
        rule.onNodeWithText("content://docs/vault.kdbx").assertIsDisplayed()
    }

    @Test fun fileMenuChooseAndCreateCloseTheMenu() {
        rule.setContent { KdbxSyncSection() }
        rule.onNodeWithText("KDBX file").performClick()
        rule.onNodeWithText("Choose existing file").performClick()
        // Menu closed after launching the picker.
        assertEquals(0, rule.onAllNodesWithText("Choose existing file").fetchSemanticsNodes().size)

        rule.onNodeWithText("KDBX file").performClick()
        rule.onNodeWithText("Create new file").performClick()
        assertEquals(0, rule.onAllNodesWithText("Create new file").fetchSemanticsNodes().size)
        rule.onNodeWithText("Sync now").assertIsDisplayed()
    }

    @Test fun syncNowRowEnqueuesImmediateWorkWhenEnabled() {
        runBlocking { KdbxSyncSettings.setEnabled(ctx, true) }
        rule.setContent { KdbxSyncSection() }
        rule.onNodeWithText("Sync now").performClick()
        val wm = WorkManager.getInstance(ctx)
        rule.waitUntil(timeoutMillis = 5_000) {
            wm.getWorkInfosForUniqueWork("KdbxSyncNow").get().isNotEmpty()
        }
        assertTrue(wm.getWorkInfosForUniqueWork("KdbxSyncNow").get().isNotEmpty())
    }
}
