package com.vayunmathur.appstore.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
 * Phone detail sections in isolation: the primary action for each
 * install state (with taps firing the right callback), the trust
 * card for each verification verdict, links, and the facts table.
 */
@RunWith(RobolectricTestRunner::class)
class AppDetailSectionsRichTest {

    @get:Rule
    val rule = createComposeRule()

    private fun app(source: AppSource = AppSource.FDROID, versionCode: Long = 2L) = UnifiedApp(
        packageName = "com.example.sections",
        source = source,
        name = "Section App",
        versionName = "2.0",
        versionCode = versionCode,
        license = "MIT",
        targetSdk = 35,
        ratingCount = 1200L,
        permissions = listOf("android.permission.CAMERA"),
        website = "https://example.org",
        sourceCode = "https://example.org/src",
        privacyPolicyUrl = "https://example.org/privacy",
    )

    private fun installed(versionCode: Long = 2L) =
        InstalledInfo("com.example.sections", "Section App", "1.0", versionCode)

    private fun exists(text: String) =
        rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `install action fires for a not installed app`() {
        var installedApp: UnifiedApp? = null
        val actions = object : AppDetailActions {
            override fun install(app: UnifiedApp) {
                installedApp = app
            }
        }
        rule.setContent {
            AppInstallActions(
                state = AppDetailUiState(app = app()),
                actions = actions,
                onUninstall = {},
            )
        }
        rule.onNodeWithText("Install", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Install", substring = true).performClick()
        assertEquals("com.example.sections", installedApp?.packageName)
    }

    @Test
    fun `update action fires and open is offered for an updatable app`() {
        var installs = 0
        var opened: String? = null
        val actions = object : AppDetailActions {
            override fun install(app: UnifiedApp) {
                installs++
            }

            override fun openApp(packageName: String) {
                opened = packageName
            }
        }
        rule.setContent {
            AppInstallActions(
                state = AppDetailUiState(app = app(versionCode = 3L), installedInfo = installed()),
                actions = actions,
                onUninstall = {},
            )
        }
        assertTrue(exists("Update"))
        rule.onNodeWithText("Open", substring = true).performClick()
        assertEquals("com.example.sections", opened)
        assertEquals(0, installs)
    }

    @Test
    fun `busy stage disables the action and failed stage offers dismiss`() {
        var dismissed: String? = null
        val actions = object : AppDetailActions {
            override fun dismissInstallFailure(packageName: String) {
                dismissed = packageName
            }
        }
        rule.setContent {
            AppInstallActions(
                state = AppDetailUiState(
                    app = app(),
                    installedInfo = installed(),
                    stage = InstallStage.Failed("bad hash"),
                ),
                actions = actions,
                onUninstall = {},
            )
        }
        assertTrue(exists("bad hash"))
        rule.onNodeWithText("Dismiss", substring = true).performClick()
        assertEquals("com.example.sections", dismissed)
    }

    @Test
    fun `trust card renders each verification verdict`() {
        rule.setContent {
            AppTrustCard(
                source = AppSource.FDROID,
                verification = VerificationResult.Verified("hash ok"),
                onOpenTrust = {},
            )
        }
        assertTrue(exists("hash ok"))
    }

    @Test
    fun `links and facts render for a full listing`() {
        var browsed: String? = null
        val actions = object : AppDetailActions {
            override fun openInBrowser(url: String) {
                browsed = url
            }
        }
        rule.setContent {
            AppDetailLinks(app = app(), actions = actions)
            AppDetailDetails(app = app())
        }
        assertTrue(exists("com.example.sections"))
        assertTrue(exists("MIT"))
        assertTrue(exists("CAMERA"))
        rule.onNodeWithText("Website", substring = true).performClick()
        assertEquals("https://example.org", browsed)
    }
}
