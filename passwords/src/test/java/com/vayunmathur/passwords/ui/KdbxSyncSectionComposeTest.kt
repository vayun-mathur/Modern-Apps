package com.vayunmathur.passwords.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performTextInput
import com.vayunmathur.passwords.FakeAndroidKeyStore
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KdbxSyncSectionComposeTest {

    @get:Rule val rule = createComposeRule()

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        // DataStore files survive between tests in this sandbox; reset the
        // quarantine state the quarantine test sets.
        val ctx = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()
        kotlinx.coroutines.runBlocking {
            com.vayunmathur.passwords.sync.KdbxSyncSettings.clearQuarantine(ctx)
        }
    }

    @Test fun sectionShowsSyncRows() {
        rule.setContent { KdbxSyncSection() }
        rule.onNodeWithText("KDBX Sync").assertIsDisplayed()
        rule.onNodeWithText("Sync with a KDBX file").assertIsDisplayed()
        rule.onNodeWithText("KDBX file").assertIsDisplayed()
        rule.onNodeWithText("Vault password").assertIsDisplayed()
        rule.onNodeWithText("Last sync").assertIsDisplayed()
        rule.onNodeWithText("Sync now").assertIsDisplayed()
    }

    @Test fun vaultPasswordDialogOpensAndCancels() {
        rule.setContent { KdbxSyncSection() }
        rule.onNodeWithText("Vault password").performClick()
        rule.onNodeWithText("Save").assertIsDisplayed()
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithText("Sync now").assertIsDisplayed()
    }

    @Test fun vaultPasswordDialogSavesEnteredPassword() {
        rule.setContent { KdbxSyncSection() }
        rule.onNodeWithText("Vault password").performClick()
                // The dialog's text field is the editable node in the dialog.
        rule.onAllNodes(hasSetTextAction())[0].performTextInput("vault-pw-123")
        rule.onNodeWithText("Save").performClick()
        rule.onNodeWithText("Sync now").assertIsDisplayed()
    }

    @Test fun quarantinedStateShowsReviewRowAndResolveDialog() {
        val ctx = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()
        kotlinx.coroutines.runBlocking {
            com.vayunmathur.passwords.sync.KdbxSyncSettings
                .recordQuarantine(ctx, com.vayunmathur.passwords.sync.KdbxSyncSettings.QUARANTINE_FOREIGN_DELETE)
        }
        rule.setContent { KdbxSyncSection() }
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithText("Review paused sync").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Review paused sync").assertIsDisplayed()
        rule.onNodeWithText("Review paused sync").performClick()
        rule.onNodeWithText("Re-check and resume").assertIsDisplayed()
        rule.onNodeWithText("Cancel").performClick()
    }

    @Test fun fileMenuShowsChooseAndCreate() {
        rule.setContent { KdbxSyncSection() }
        rule.onNodeWithText("KDBX file").performClick()
        rule.onNodeWithText("Choose existing file").assertIsDisplayed()
        rule.onNodeWithText("Create new file").assertIsDisplayed()
    }
}
