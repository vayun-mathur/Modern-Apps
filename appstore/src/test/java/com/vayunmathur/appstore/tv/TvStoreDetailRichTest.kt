package com.vayunmathur.appstore.tv

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.InstalledInfo
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.installer.InstallStage
import com.vayunmathur.appstore.data.security.VerificationResult
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Rich TV detail states the basic screen test does not reach: installed
 * and updatable apps, busy/failed install stages, every verification
 * verdict, and a fully populated listing body.
 */
@RunWith(RobolectricTestRunner::class)
class TvStoreDetailRichTest {

    @get:Rule
    val rule = createComposeRule()

    private fun fullApp() = UnifiedApp(
        packageName = "com.example.full",
        source = AppSource.FDROID,
        name = "Full App",
        summary = "A short summary",
        description = "A much longer description of the full app and what it does.",
        author = "Example Author",
        categories = listOf("Tools", "Productivity"),
        versionName = "2.0",
        versionCode = 5L,
        sizeBytes = 12_000_000L,
        targetSdk = 35,
        reproducible = true,
        license = "GPL-3.0",
        rating = 4.5f,
        ratingCount = 1200L,
        contentRating = "Everyone",
        containsAds = true,
        antiFeatures = listOf("Tracking"),
        whatsNew = "New in this version",
    )

    private fun installed(versionCode: Long = 5L) =
        InstalledInfo("com.example.full", "Full App", "2.0", versionCode)

    private fun assertTextExists(text: String) {
        kotlin.test.assertTrue(
            rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty(),
            "no node containing '$text'",
        )
    }

    @Test
    fun `fully populated detail renders identity stats and body`() {
        rule.setContent { TvStoreDetailScreen(state = TvDetailUiState(app = fullApp()), onInstall = {}) }
        rule.onNodeWithText("Full App").assertIsDisplayed()
        assertTextExists("Example Author")
        assertTextExists("A short summary")
        assertTextExists("2.0")
        assertTextExists("Everyone")
    }

    @Test
    fun `installed app shows the installed version line`() {
        val state = TvDetailUiState(app = fullApp(), installedInfo = installed())
        rule.setContent { TvStoreDetailScreen(state = state, onInstall = {}) }
        assertTextExists("2.0")
        rule.onNodeWithText("Full App").assertIsDisplayed()
    }

    @Test
    fun `busy stage disables the primary action`() {
        val state = TvDetailUiState(app = fullApp(), stage = InstallStage.Downloading(0.5f))
        rule.setContent { TvStoreDetailScreen(state = state, onInstall = {}) }
        rule.onNodeWithText("Full App").assertIsDisplayed()
        rule.waitForIdle()
    }

    @Test
    fun `failed stage shows the reason`() {
        val state = TvDetailUiState(
            app = fullApp(),
            stage = InstallStage.Failed("hash mismatch"),
        )
        rule.setContent { TvStoreDetailScreen(state = state, onInstall = {}) }
        assertTextExists("hash mismatch")
    }

    @Test
    fun `each verification verdict renders its line`() {
        rule.setContent {
            TvStoreDetailScreen(
                state = TvDetailUiState(
                    app = fullApp(),
                    verification = VerificationResult.Verified("hash matches"),
                ),
                onInstall = {},
            )
        }
        assertTextExists("hash matches")
    }

    @Test
    fun `rejected verification renders the block line`() {
        rule.setContent {
            TvStoreDetailScreen(
                state = TvDetailUiState(
                    app = fullApp(),
                    verification = VerificationResult.Rejected("wrong signer"),
                ),
                onInstall = {},
            )
        }
        assertTextExists("wrong signer")
    }

    @Test
    fun `loading details still renders the identity`() {
        val state = TvDetailUiState(app = fullApp(), isLoadingDetails = true)
        rule.setContent { TvStoreDetailScreen(state = state, onInstall = {}) }
        rule.onNodeWithText("Full App").assertIsDisplayed()
    }
}
