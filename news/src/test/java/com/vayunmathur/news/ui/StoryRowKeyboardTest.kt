package com.vayunmathur.news.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import com.vayunmathur.news.domain.FeedArticle
import com.vayunmathur.news.domain.Story
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Keyboard/mouse contract for a feed story card (tablet desktop mode):
 * the card itself is a focus stop, Enter activates it (same action as a
 * tap), and Tab moves on to the bookmark button, which Enter also
 * activates. Nothing on the card is swipe- or touch-only.
 */
@RunWith(RobolectricTestRunner::class)
class StoryRowKeyboardTest {
    @get:Rule val rule = createComposeRule()

    @Test fun enterActivatesCard_tabReachesBookmark_enterTogglesIt() {
        var clicks = 0
        var toggles = 0
        val article = FeedArticle(
            url = "https://example.com/a",
            sourceId = "bbc",
            title = "Keyboard story",
            publishedAtMillis = 0L,
        )
        rule.setContent {
            StoryRow(
                story = Story(id = "s1", representative = article, articles = listOf(article)),
                bookmarked = false,
                onClick = { clicks++ },
                onToggleBookmark = { toggles++ },
            )
        }
        rule.waitForIdle()
        val clickables = rule.onAllNodes(hasClickAction())
        // First focus stop is the card; Enter opens it, like a tap.
        clickables[0].performSemanticsAction(SemanticsActions.RequestFocus)
        clickables[0].assertIsFocused()
        clickables[0].performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertEquals(1, clicks)
        // Tab moves to the bookmark button; Enter toggles it.
        clickables[0].performKeyInput { pressKey(Key.Tab) }
        rule.waitForIdle()
        clickables[1].assertIsFocused()
        clickables[1].performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertEquals(1, toggles)
    }

    @Test fun cardShowsSourceCountOnly_noLeadSourceName() {
        fun art(source: String, url: String) = FeedArticle(
            url = url, sourceId = source, title = "Headline $url", publishedAtMillis = 0L,
        )
        val articles = listOf(art("bbc", "https://e/1"), art("dw", "https://e/2"), art("npr", "https://e/3"))
        rule.setContent {
            StoryRow(
                story = Story(id = "srv:s1", representative = articles.first(), articles = articles),
                bookmarked = false,
                onClick = {},
                onToggleBookmark = {},
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("3 sources").assertIsDisplayed()
        // No source is named on the card, lead or otherwise.
        assertEquals(0, rule.onAllNodesWithText("BBC", substring = true).fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithText("NPR", substring = true).fetchSemanticsNodes().size)
    }

    @Test fun singletonCardShowsOneSource() {
        val article = FeedArticle(
            url = "https://example.com/a", sourceId = "bbc", title = "Solo story", publishedAtMillis = 0L,
        )
        rule.setContent {
            StoryRow(
                story = Story(id = "s1", representative = article, articles = listOf(article)),
                bookmarked = false,
                onClick = {},
                onToggleBookmark = {},
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("1 source").assertIsDisplayed()
    }
}
