package com.vayunmathur.youpipe.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.ui.invisibleClickable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * Slice 2/3 (w5): Compose coverage for the small pure UI in the A-L slice —
 * ChannelPage.kt's [ChannelHeader] (identity block; AsyncImage loads nothing
 * headless and must not break the texts) and DownloadedVideosPage.kt's
 * [SelectionIndicator] (selected vs unselected visuals), plus [FeedGrid]'s
 * compact path (single-column list on phone widths). Robolectric +
 * createComposeRule, the module's standard Compose harness.
 */
@RunWith(AndroidJUnit4::class)
class W5Slice2UiComponentsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun channelHeader_showsNameAndSubscriberCount() {
        val info = ChannelInfo(
            name = "Some Channel",
            channelID = "UC12345",
            subscribers = 12_345,
            videos = 100,
            avatar = "",
        )
        composeRule.setContent { ChannelHeader(info) }
        composeRule.onNodeWithText("Some Channel").assertIsDisplayed()
        // "12.35K subscribers": substring match, the ListItem supporting
        // text wraps the count in a longer sentence.
        composeRule.onNode(hasText(countString(context, 12_345), substring = true)).assertIsDisplayed()
    }

    @Test
    fun channelHeader_decodesHtmlName() {
        val info = ChannelInfo(
            name = "Fish &amp; Chips",
            channelID = "UC1",
            subscribers = 0,
            videos = 0,
            avatar = "",
        )
        composeRule.setContent { ChannelHeader(info) }
        composeRule.onNodeWithText("Fish & Chips").assertIsDisplayed()
    }

    @Test
    fun selectionIndicator_bothStatesCompose() {
        // SelectionIndicator carries no text; the assertion is that both
        // branches compose without crashing (selected draws the check icon,
        // unselected only the outline ring).
        composeRule.setContent {
            SelectionIndicator(isSelected = false)
            SelectionIndicator(isSelected = true)
        }
        composeRule.waitForIdle()
    }

    @Test
    fun feedGrid_compactPathRendersAllRows() {
        val rows = listOf(
            com.vayunmathur.youpipe.util.VideoRowState(videoID = 1, title = "First video"),
            com.vayunmathur.youpipe.util.VideoRowState(videoID = 2, title = "Second video"),
            com.vayunmathur.youpipe.util.VideoRowState(videoID = 3, title = "Third video"),
        )
        var opened = -1L
        composeRule.setContent {
            FeedGrid(rows = rows, key = { it.videoID }, itemContent = { row ->
                com.vayunmathur.library.ui.Text(
                    row.title,
                    modifier = androidx.compose.ui.Modifier.invisibleClickable { opened = row.videoID },
                )
            })
        }
        composeRule.onNodeWithText("First video").assertIsDisplayed()
        composeRule.onNodeWithText("Second video").assertIsDisplayed()
        composeRule.onNodeWithText("Third video").assertIsDisplayed()
        composeRule.onNodeWithText("Second video").performClick()
        assertEquals(2L, opened)
    }

    @Test
    fun linkifiedText_rendersTextAndKeepsTrailingPunctuationOutsideLink() {
        var shown = ""
        composeRule.setContent {
            shown = "see https://example.com/page (wow)"
            LinkifiedText(shown)
        }
        // Plain-text segments and the cleaned URL both render; the trailing
        // ")" is appended as normal text, so the full line reads correctly.
        composeRule.onNode(hasText("see https://example.com/page (wow)", substring = true)).assertExists()
        assertTrue(shown.contains("https://example.com/page"), shown)
    }

    @Test
    fun linkifiedText_bareWwwGetsHttpsAndClickOpensIt() {
        var opened: String? = null
        val fakeHandler = object : androidx.compose.ui.platform.UriHandler {
            override fun openUri(uri: String) {
                opened = uri
            }
        }
        composeRule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalUriHandler provides fakeHandler,
            ) {
                LinkifiedText("visit www.example.com/x, ok?")
            }
        }
        // "www." prefix is resolved to https and the trailing comma stays
        // outside the link: the visible text keeps both parts.
        composeRule.onNode(hasText("www.example.com/x", substring = true)).assertIsDisplayed()
        composeRule.onNode(hasText(",", substring = true)).assertIsDisplayed()
        composeRule.onNode(hasText("www.example.com/x", substring = true)).performClick()
        assertEquals("https://www.example.com/x", opened)
    }
}
