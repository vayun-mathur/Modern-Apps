package com.vayunmathur.appstore.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.installer.InstallStage
import com.vayunmathur.appstore.util.BrowseUiState
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Browse states beyond the basic rows test: loading with no rows yet,
 * and rows that carry installed/stage chrome from the shared state.
 */
@RunWith(RobolectricTestRunner::class)
class BrowseScreenRichTest {

    @get:Rule
    val rule = createComposeRule()

    private fun app(pkg: String, name: String) = UnifiedApp(
        packageName = pkg,
        source = AppSource.FDROID,
        name = name,
        summary = "Summary of $name",
    )

    private fun exists(text: String) =
        rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `loading with no rows shows progress not the empty state`() {
        rule.setContent {
            BrowseScreen(state = BrowseUiState(title = "Modern", isLoading = true))
        }
        rule.waitForIdle()
        assertTrue(!exists("Nothing here"))
    }

    @Test
    fun `rows render with installed packages in the list`() {
        val state = BrowseUiState(
            title = "Modern",
            apps = listOf(app("com.example.a", "Alpha"), app("com.example.b", "Beta")),
            installedPackages = setOf("com.example.a"),
            stages = mapOf("com.example.b" to InstallStage.Failed("no space")),
        )
        rule.setContent { BrowseScreen(state = state) }
        rule.onNodeWithText("Alpha").assertIsDisplayed()
        rule.onNodeWithText("Beta").assertIsDisplayed()
    }

    @Test
    fun `loading with rows already present keeps them on screen`() {
        val state = BrowseUiState(
            title = "Modern",
            apps = listOf(app("com.example.a", "Alpha")),
            isLoading = true,
        )
        rule.setContent { BrowseScreen(state = state) }
        rule.onNodeWithText("Alpha").assertIsDisplayed()
    }
}
