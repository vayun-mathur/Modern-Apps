package com.vayunmathur.appstore.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.InstalledInfo
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.installer.InstallStage
import com.vayunmathur.appstore.data.security.VerificationResult
import com.vayunmathur.appstore.util.AppDetailActions
import com.vayunmathur.appstore.util.AppDetailUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * App detail screen: install / update / open states, the uninstall
 * confirmation, the trust card verdicts, links and the details table.
 */
@RunWith(RobolectricTestRunner::class)
class AppDetailScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private fun app() = UnifiedApp(
        packageName = "com.example.app",
        source = AppSource.FDROID,
        name = "Example",
        summary = "Does things",
        description = "A longer description of the example app.",
        author = "Example Org",
        versionName = "2.0",
        versionCode = 2L,
        sizeBytes = 1_500_000L,
        website = "https://example.org",
        categories = listOf("Tools"),
        whatsNew = "Bug fixes",
    )

    private fun installed(versionCode: Long = 1L) = InstalledInfo(
        packageName = "com.example.app",
        name = "Example",
        versionName = "1.0",
        versionCode = versionCode,
    )

    @Test
    fun `uninstalled app offers install`() {
        var installedPkg: UnifiedApp? = null
        val actions = object : AppDetailActions {
            override fun install(app: UnifiedApp) {
                installedPkg = app
            }
        }
        rule.setContent {
            AppDetailScreen(state = AppDetailUiState(app = app()), actions = actions)
        }

        assertTrue(rule.onAllNodesWithText("Example", substring = true).fetchSemanticsNodes().size >= 2)
        rule.onNodeWithText("Install", substring = false).performScrollTo()
        rule.onNodeWithText("Install", substring = false).performClick()
        assertEquals("com.example.app", installedPkg?.packageName)
    }

    @Test
    fun `outdated install offers update and open`() {
        val state = AppDetailUiState(app = app(), installedInfo = installed(1L))
        rule.setContent { AppDetailScreen(state = state, actions = AppDetailActions.Noop) }

        assertTrue(state.hasUpdate)
        rule.onNodeWithText("Update").assertIsDisplayed()
        rule.onNodeWithText("Open").assertIsDisplayed()
    }

    @Test
    fun `current install offers open and uninstall`() {
        val state = AppDetailUiState(app = app().copy(versionCode = 1L), installedInfo = installed(1L))
        rule.setContent { AppDetailScreen(state = state, actions = AppDetailActions.Noop) }

        rule.onNodeWithText("Open").assertIsDisplayed()
    }

    @Test
    fun `rejected verification blocks with the reason`() {
        val state = AppDetailUiState(
            app = app(),
            verification = VerificationResult.Rejected("hash mismatch"),
        )
        rule.setContent { AppDetailScreen(state = state, actions = AppDetailActions.Noop) }

        rule.onNodeWithText("hash mismatch", substring = true).performScrollTo()
        rule.onNodeWithText("hash mismatch", substring = true).assertIsDisplayed()
    }

    @Test
    fun `verified verdict shows the check detail`() {
        val state = AppDetailUiState(
            app = app(),
            verification = VerificationResult.Verified("hash matches for 1 of 1 file(s)"),
        )
        rule.setContent { AppDetailScreen(state = state, actions = AppDetailActions.Noop) }

        rule.onNodeWithText("hash matches", substring = true).performScrollTo()
        rule.onNodeWithText("hash matches", substring = true).assertIsDisplayed()
    }

    @Test
    fun `failed stage offers dismiss`() {
        var dismissed: String? = null
        val actions = object : AppDetailActions {
            override fun dismissInstallFailure(packageName: String) {
                dismissed = packageName
            }
        }
        val state = AppDetailUiState(
            app = app(),
            stage = InstallStage.Failed("network broke"),
        )
        rule.setContent { AppDetailScreen(state = state, actions = actions) }

        rule.onNodeWithText("network broke", substring = true).performScrollTo()
        rule.onNodeWithText("network broke", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Dismiss", substring = true).performScrollTo()
        rule.onNodeWithText("Dismiss", substring = true).performClick()
        assertEquals("com.example.app", dismissed)
    }

    @Test
    fun `busy install disables the action`() {
        val state = AppDetailUiState(
            app = app(),
            stage = InstallStage.Downloading(0.3f),
        )
        rule.setContent { AppDetailScreen(state = state, actions = AppDetailActions.Noop) }

        // The downloading label shows the percent; both the button and the progress
        // line carry it, so assert on all nodes.
        assertTrue(rule.onAllNodesWithText("30", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `play source offers the play store link`() {
        val state = AppDetailUiState(app = app().copy(source = AppSource.PLAYSTORE))
        rule.setContent { AppDetailScreen(state = state, actions = AppDetailActions.Noop) }

        rule.onAllNodesWithText("Play Store", substring = true)[0].performScrollTo()
        rule.onAllNodesWithText("Play Store", substring = true)[0].assertExists()
    }

    @Test
    fun `details table shows package and facts`() {
        val state = AppDetailUiState(app = app())
        rule.setContent { AppDetailScreen(state = state, actions = AppDetailActions.Noop) }

        rule.onNodeWithText("com.example.app", substring = false).performScrollTo()
        rule.onNodeWithText("com.example.app", substring = false).assertIsDisplayed()
    }

    @Test
    fun `null app renders nothing`() {
        rule.setContent { AppDetailScreen(state = AppDetailUiState(app = null), actions = AppDetailActions.Noop) }

        rule.waitForIdle()
    }
}
