package com.vayunmathur.backup.ui

import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.vayunmathur.backup.data.BackendType
import com.vayunmathur.backup.data.BackupSettings
import com.vayunmathur.backup.platform.BackupUiState
import com.vayunmathur.backup.ui.components.BackendSection
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ComposeScreensTest {
    @get:Rule val rule = createComposeRule()

    @Test fun dashboardShowsStatusAndActions() {
        var backup = 0; var restore = 0; var picked = 0
        var appToggle: Boolean? = null; var fileToggle: Boolean? = null
        rule.setContent {
            DashboardScreen(
                state = BackupUiState(
                    settings = BackupSettings(BackendType.WEBDAV, webdavUrl = "https://dav.example.com", lastRun = 0L),
                    message = "All good", busy = false,
                ),
                onPickFolder = { picked++ },
                onSetWebDav = { _, _, _ -> },
                onAppBackupToggle = { appToggle = it },
                onFileBackupToggle = { fileToggle = it },
                onBackupNow = { backup++ },
                onRestoreNow = { restore++ },
            )
        }
        rule.onNodeWithText("Backup").assertIsDisplayed()
        rule.onNodeWithText("All good").assertIsDisplayed()
        rule.onNodeWithText("Status").assertIsDisplayed()
        rule.onAllNodesWithText("WebDAV: https://dav.example.com").assertCountEquals(2)
        rule.onNodeWithText("No backup has run yet.").assertIsDisplayed()
        rule.onNodeWithText("What to back up").assertIsDisplayed()
        rule.onNodeWithText("Back up files now").performScrollTo().assertIsDisplayed().performClick()
        rule.onNodeWithText("Restore files").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, backup); assertEquals(1, restore)
        // Switches are present via their list item labels
        rule.onNodeWithText("App data (system transport)").assertIsDisplayed()
        rule.onNodeWithText("Photos, videos & audio").assertIsDisplayed()
    }

    @Test fun dashboardBusyDisablesActionsAndShowsWorking() {
        rule.setContent {
            DashboardScreen(
                state = BackupUiState(settings = BackupSettings(lastRun = 1700000000000L), error = "oops", busy = true),
                onPickFolder = {}, onSetWebDav = { _, _, _ -> },
                onAppBackupToggle = {}, onFileBackupToggle = {}, onBackupNow = {}, onRestoreNow = {},
            )
        }
        rule.onNodeWithText("oops").assertIsDisplayed()
        rule.onNodeWithText("Working…").assertIsDisplayed()
        rule.onNodeWithText("Back up files now").assertIsNotEnabled()
        rule.onNodeWithText("Restore files").assertIsNotEnabled()
    }

    @Test fun onboardingGenerateAndConfirm() {
        var generated = 0; var confirmed: List<String>? = null; var dismissed = 0
        rule.setContent {
            OnboardingScreen(
                state = BackupUiState(generatedCode = emptyList()),
                onPickFolder = {}, onSetWebDav = { _, _, _ -> },
                onGenerate = { generated++ },
                onConfirmNew = { confirmed = it },
                onRestoreWithCode = {},
                onDismissMessages = { dismissed++ },
            )
        }
        rule.onNodeWithText("Set up Backup").assertIsDisplayed()
        rule.onNodeWithText("Recovery code").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Generate recovery code").performScrollTo().performClick()
        assertEquals(1, generated); assertTrue(dismissed >= 1)
        assertEquals(null, confirmed)
    }

    @Test fun onboardingShowsGeneratedCodeAndConfirms() {
        val code = listOf("abandon", "ability", "able")
        var confirmed: List<String>? = null
        rule.setContent {
            OnboardingScreen(
                state = BackupUiState(generatedCode = code, error = "bad code"),
                onPickFolder = {}, onSetWebDav = { _, _, _ -> },
                onGenerate = {}, onConfirmNew = { confirmed = it },
                onRestoreWithCode = {}, onDismissMessages = {},
            )
        }
        rule.onNodeWithText("bad code").assertIsDisplayed()
        rule.onNodeWithText("1. abandon\n2. ability\n3. able").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("I saved it — enable encrypted backups").performScrollTo().performClick()
        assertEquals(code, confirmed)
    }

    @Test fun onboardingRestoreFlow() {
        var restored: List<String>? = null
        rule.setContent {
            OnboardingScreen(
                state = BackupUiState(message = "welcome"),
                onPickFolder = {}, onSetWebDav = { _, _, _ -> },
                onGenerate = {}, onConfirmNew = {},
                onRestoreWithCode = { restored = it }, onDismissMessages = {},
            )
        }
        rule.onNodeWithText("welcome").assertIsDisplayed()
        rule.onNodeWithText("Restore existing").performScrollTo().performClick()
        rule.onNodeWithText("Enter your 12-word recovery code").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Restore with recovery code").assertIsNotEnabled()
        rule.onNodeWithText("Enter your 12-word recovery code").performScrollTo().performTextInput("abandon ability able")
        rule.onNodeWithText("Restore with recovery code").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf("abandon", "ability", "able"), restored)
    }

    @Test fun backendSectionWebDavForm() {
        var picked = 0; var saved: Triple<String, String, String>? = null
        rule.setContent {
            androidx.compose.foundation.layout.Column(
                modifier = androidx.compose.ui.Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),
            ) {
                BackendSection(
                    settings = BackupSettings(BackendType.NONE),
                    onPickFolder = { picked++ },
                    onSetWebDav = { u, user, p -> saved = Triple(u, user, p) },
                )
            }
        }
        rule.onNodeWithText("Backup destination").assertIsDisplayed()
        rule.onNodeWithText("No destination selected").assertIsDisplayed()
        rule.onNodeWithText("Choose folder (USB / SD / internal)").performClick()
        assertEquals(1, picked)
        rule.onNodeWithText("Save WebDAV destination").assertIsNotEnabled()
        rule.onNodeWithText("WebDAV URL").performScrollTo().performTextInput("https://dav.example.com")
        rule.onNodeWithText("Username").performScrollTo().performTextInput("alice")
        rule.onNodeWithText("Password").performScrollTo().performTextInput("secret")
        rule.onNodeWithText("Save WebDAV destination").performScrollTo().assertIsEnabled().performClick()
        assertEquals(Triple("https://dav.example.com", "alice", "secret"), saved)
    }

    @Test fun backendSectionPrefillsSettings() {
        rule.setContent {
            BackendSection(
                settings = BackupSettings(BackendType.WEBDAV, webdavUrl = "https://h", webdavUser = "bob"),
                onPickFolder = {}, onSetWebDav = { _, _, _ -> },
            )
        }
        rule.onNodeWithText("WebDAV: https://h").assertIsDisplayed()
        rule.onNodeWithText("https://h").assertIsDisplayed()
        rule.onNodeWithText("bob").assertIsDisplayed()
    }
}
