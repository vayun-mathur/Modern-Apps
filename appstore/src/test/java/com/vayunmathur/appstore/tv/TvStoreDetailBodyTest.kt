package com.vayunmathur.appstore.tv

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.UnifiedApp
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The right-hand detail body: summary/description, What's New with
 * its date, category/anti-feature chips, the trust card, links and
 * the facts block (author, licence, target SDK, package).
 */
@RunWith(RobolectricTestRunner::class)
class TvStoreDetailBodyTest {

    @get:Rule
    val rule = createComposeRule()

    private fun fullApp(source: AppSource = AppSource.FDROID) = UnifiedApp(
        packageName = "com.example.body",
        source = source,
        name = "Body App",
        summary = "Body summary",
        description = "Body description that is longer than the summary.",
        author = "Body Author",
        categories = listOf("Tools"),
        versionName = "2.0",
        versionCode = 5L,
        targetSdk = 35,
        reproducible = true,
        license = "GPL-3.0",
        website = "https://example.org",
        sourceCode = "https://example.org/src",
        whatsNew = "Body fixes",
        updatedOn = "Nov 1, 2026",
        containsAds = true,
        antiFeatures = listOf("Tracking"),
        ratingCount = 2500L,
        permissions = listOf("android.permission.CAMERA", "android.permission.INTERNET"),
        screenshots = listOf("https://example.org/shot1.png"),
    )

    private fun exists(text: String) =
        rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `full body renders summary whats new chips and facts`() {
        rule.setContent {
            TvStoreDetailBody(
                state = TvDetailUiState(app = fullApp()),
                onOpenInBrowser = {},
                onOpenInPlayStore = {},
            )
        }
        assertTrue(exists("Body summary"))
        assertTrue(exists("Body description"))
        assertTrue(exists("Body fixes"))
        assertTrue(exists("Nov 1, 2026"))
        assertTrue(exists("Tools"))
        assertTrue(exists("Body Author"))
        assertTrue(exists("GPL-3.0"))
        assertTrue(exists("com.example.body"))
    }

    @Test
    fun `play source body offers the play store link`() {
        rule.setContent {
            TvStoreDetailBody(
                state = TvDetailUiState(app = fullApp(AppSource.PLAYSTORE)),
                onOpenInBrowser = {},
                onOpenInPlayStore = {},
            )
        }
        assertTrue(exists("Body summary"))
        assertTrue(exists("Play Store") || exists("play store"))
    }

    @Test
    fun `minimal body renders only what was published`() {
        val bare = UnifiedApp(
            packageName = "com.example.bare",
            source = AppSource.FDROID,
            name = "Bare",
        )
        rule.setContent {
            TvStoreDetailBody(
                state = TvDetailUiState(app = bare),
                onOpenInBrowser = {},
                onOpenInPlayStore = {},
            )
        }
        rule.waitForIdle()
        assertTrue(!exists("Body fixes"))
        assertTrue(exists("com.example.bare"))
    }

    @Test
    fun `no app renders no body`() {
        rule.setContent {
            TvStoreDetailBody(
                state = TvDetailUiState(app = null),
                onOpenInBrowser = {},
                onOpenInPlayStore = {},
            )
        }
        rule.waitForIdle()
        assertTrue(!exists("Body summary"))
    }
}
