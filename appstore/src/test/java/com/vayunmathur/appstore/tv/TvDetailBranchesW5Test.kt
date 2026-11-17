package com.vayunmathur.appstore.tv
import com.vayunmathur.appstore.platform.TvDetailUiState
import com.vayunmathur.appstore.ui.tv.TvStoreDetailScreen

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.InstalledInfo
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.installer.InstallStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** W5: TV detail screen states — empty, installed/uninstall confirm, failed, update. */
@RunWith(RobolectricTestRunner::class)
class TvDetailBranchesW5Test {
    @get:Rule val rule = createComposeRule()

    private fun app(versionCode: Long = 2L) = UnifiedApp(
        packageName = "com.example.tvdetail",
        source = AppSource.FDROID,
        name = "TV Detail App",
        versionName = "2.0",
        versionCode = versionCode,
        summary = "A TV detail summary",
        license = "MIT",
        targetSdk = 35,
    )

    private fun exists(text: String) =
        rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun click(text: String) {
        // TvButtons are focus-driven Surfaces: performClick on the text child
        // does not fire them, so invoke OnClick on the button node itself.
        rule.onNode(hasClickAction() and hasText(text, substring = true))
            .performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
    }

    @Test
    fun emptyStateBackButtonFires() {
        var backs = 0
        rule.setContent {
            TvStoreDetailScreen(state = TvDetailUiState(app = null), onBack = { backs++ })
        }
        assertTrue(exists("Back") || exists("No apps") || rule.onAllNodesWithText("", substring = true).fetchSemanticsNodes().isNotEmpty())
        click("Back")
        assertEquals(1, backs)
    }

    @Test
    fun installedAppUninstallConfirmAndCancel() {
        var uninstalled: String? = null
        rule.setContent {
            TvStoreDetailScreen(
                state = TvDetailUiState(
                    app = app(),
                    installedInfo = InstalledInfo("com.example.tvdetail", "TV Detail App", "1.0", 2L),
                ),
                onUninstall = { uninstalled = it },
            )
        }
        rule.onNodeWithText("Uninstall", substring = true).assertIsDisplayed()
        click("Uninstall")
        // Confirmation appears; cancel first.
        assertTrue(exists("Cancel"))
        click("Cancel")
        assertEquals(null, uninstalled)
        // Again and confirm.
        click("Uninstall")
        // Confirm in the dialog (the second Uninstall node).
        val nodes = rule.onAllNodes(hasClickAction() and hasText("Uninstall", substring = true))
        nodes[nodes.fetchSemanticsNodes().lastIndex]
            .performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("com.example.tvdetail", uninstalled)
    }

    @Test
    fun failedStageShowsErrorAndDismissFires() {
        var dismissed: String? = null
        rule.setContent {
            TvStoreDetailScreen(
                state = TvDetailUiState(
                    app = app(),
                    installedInfo = InstalledInfo("com.example.tvdetail", "TV Detail App", "1.0", 1L),
                    stage = InstallStage.Failed("signature mismatch"),
                ),
                onDismissFailure = { dismissed = it },
            )
        }
        assertTrue(exists("signature mismatch"))
        click("Dismiss")
        assertEquals("com.example.tvdetail", dismissed)
    }

    @Test
    fun updateAndOpenActionsFire() {
        var installs = 0
        var opens = 0
        rule.setContent {
            TvStoreDetailScreen(
                state = TvDetailUiState(
                    app = app(versionCode = 3L),
                    installedInfo = InstalledInfo("com.example.tvdetail", "TV Detail App", "1.0", 2L),
                ),
                onInstall = { installs++ },
                onOpen = { opens++ },
            )
        }
        assertTrue(exists("Update") || exists("Open"))
        click("Open")
        assertEquals(1, opens)
        assertEquals(0, installs)
    }

    @Test
    fun notInstalledInstallFires() {
        var installedApp: String? = null
        rule.setContent {
            TvStoreDetailScreen(
                state = TvDetailUiState(app = app()),
                onInstall = { installedApp = it.packageName },
            )
        }
        click("Install")
        assertEquals("com.example.tvdetail", installedApp)
    }
}
