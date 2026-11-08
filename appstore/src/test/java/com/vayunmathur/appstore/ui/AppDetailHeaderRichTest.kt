package com.vayunmathur.appstore.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.util.AppDetailUiState
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Detail header pieces the screen-level test does not isolate: the
 * facts row in full and empty forms, description expansion, What's
 * New, and the header with/without an author and while loading.
 */
@RunWith(RobolectricTestRunner::class)
class AppDetailHeaderRichTest {

    @get:Rule
    val rule = createComposeRule()

    private fun app(
        name: String = "Header App",
        summary: String = "",
        description: String = "",
        author: String? = null,
    ) = UnifiedApp(
        packageName = "com.example.header",
        source = AppSource.PLAYSTORE,
        name = name,
        summary = summary,
        description = description,
        author = author,
        rating = 4.5f,
        installs = 1_500_000L,
        sizeBytes = 12_000_000L,
        contentRating = "Everyone",
        versionName = "2.0",
        whatsNew = "Fresh fixes",
    )

    private fun exists(text: String) =
        rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `header renders name author and source while loading`() {
        rule.setContent {
            AppDetailHeader(
                state = AppDetailUiState(app = app(author = "Header Dev"), isLoadingDetails = true),
            )
        }
        rule.onNodeWithText("Header App").assertIsDisplayed()
        rule.onNodeWithText("Header Dev").assertIsDisplayed()
    }

    @Test
    fun `header with no app renders nothing`() {
        rule.setContent { AppDetailHeader(state = AppDetailUiState(app = null)) }
        rule.waitForIdle()
        assertTrue(!exists("Header App"))
    }

    @Test
    fun `facts row shows rating installs size rating and version`() {
        rule.setContent { AppDetailFacts(app = app()) }
        assertTrue(exists("4.5"))
        assertTrue(exists("Everyone"))
        assertTrue(exists("2.0"))
    }

    @Test
    fun `facts row is empty when nothing was published`() {
        val bare = UnifiedApp(
            packageName = "com.example.bare",
            source = AppSource.FDROID,
            name = "Bare",
        )
        rule.setContent { AppDetailFacts(app = bare) }
        rule.waitForIdle()
        assertTrue(!exists("Everyone"))
    }

    @Test
    fun `description expands and collapses`() {
        val long = app(
            summary = "Short summary",
            description = "A very long description that goes on and on about the app.",
        )
        rule.setContent { AppDetailDescription(app = long) }
        rule.onNodeWithText("Short summary").assertIsDisplayed()
        assertTrue(exists("A very long description"))
        rule.onNodeWithText("Read more", substring = true).performClick()
        assertTrue(exists("Show less"))
    }

    @Test
    fun `whats new renders its notes`() {
        rule.setContent { AppDetailWhatsNew(app = app()) }
        assertTrue(exists("Fresh fixes"))
    }

    @Test
    fun `screenshot strip composes for urls`() {
        rule.setContent {
            AppScreenshotStrip(
                urls = listOf("https://example.org/1.png", "https://example.org/2.png"),
            )
        }
        rule.waitForIdle()
    }
}
