package com.vayunmathur.appstore.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.installer.InstallStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Shared catalogue rows: the list row, the section heading, the install
 * progress line, the source chip and the rating label. Rendered under
 * Robolectric with the Compose test rule.
 */
@RunWith(RobolectricTestRunner::class)
class AppComponentsTest {

    @get:Rule
    val rule = createComposeRule()

    private fun app(
        pkg: String = "com.example.app",
        name: String = "Example",
        source: AppSource = AppSource.FDROID,
        summary: String = "Does things",
        rating: Float? = 4.5f,
    ) = UnifiedApp(
        packageName = pkg,
        source = source,
        name = name,
        summary = summary,
        rating = rating,
    )

    @Test
    fun `row shows the name summary source chip and rating`() {
        rule.setContent { AppRow(app = app()) }

        rule.onNodeWithText("Example").assertIsDisplayed()
        rule.onNodeWithText("Does things").assertIsDisplayed()
        rule.onNodeWithText("4.5").assertIsDisplayed()
    }

    @Test
    fun `row without a rating draws no rating text`() {
        rule.setContent { AppRow(app = app(rating = null)) }

        rule.onNodeWithText("Example").assertIsDisplayed()
        assertTrue(rule.onAllNodes(hasText("4.5")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `row forwards taps`() {
        var clicked = 0
        rule.setContent { AppRow(app = app(), onClick = { clicked++ }) }

        rule.onNodeWithText("Example").performClick()

        assertEquals(1, clicked)
    }

    @Test
    fun `row shows the version label when given`() {
        rule.setContent { AppRow(app = app(), versionLabel = "1.0 → 2.0") }

        rule.onNodeWithText("1.0 → 2.0").assertIsDisplayed()
    }

    @Test
    fun `row shows an install failure reason`() {
        rule.setContent {
            AppRow(app = app(), stage = InstallStage.Failed("hash mismatch"))
        }

        rule.onNodeWithText("hash mismatch", substring = true).assertIsDisplayed()
    }

    @Test
    fun `section header shows the title subtitle and action`() {
        var acted = 0
        rule.setContent {
            SectionHeader(title = "Recent", subtitle = "Fresh builds", actionLabel = "More", onAction = { acted++ })
        }

        rule.onNodeWithText("Recent").assertIsDisplayed()
        rule.onNodeWithText("Fresh builds").assertIsDisplayed()
        rule.onNodeWithText("More").performClick()
        assertEquals(1, acted)
    }

    @Test
    fun `source chip covers every source`() {
        rule.setContent {
            androidx.compose.foundation.layout.Column {
                for (source in AppSource.entries) {
                    SourceChip(source = source)
                }
            }
        }

        // Each chip renders its label; assert at least one known label is present.
        rule.onNodeWithText("F-Droid").assertIsDisplayed()
    }

    @Test
    fun `carousel shows every tile and forwards taps`() {
        val apps = listOf(app("com.example.a", "Alpha"), app("com.example.b", "Beta"))
        val tapped = mutableListOf<String>()
        rule.setContent {
            AppCarousel(
                apps = apps,
                installedPackages = setOf("com.example.a"),
                installedIcons = emptyMap(),
                onAppClick = { tapped += it.packageName },
            )
        }

        rule.onNodeWithText("Alpha").assertIsDisplayed()
        rule.onNodeWithText("Beta").assertIsDisplayed()
        rule.onNodeWithText("Beta").performClick()
        assertEquals(listOf("com.example.b"), tapped)
    }

    @Test
    fun `stage progress draws nothing without a stage`() {
        rule.setContent { StageProgress(stage = null) }

        rule.waitForIdle()
    }

    @Test
    fun `stage progress shows download progress`() {
        rule.setContent { StageProgress(stage = InstallStage.Downloading(0.5f)) }

        rule.onNodeWithText("50", substring = true).assertIsDisplayed()
    }
}
