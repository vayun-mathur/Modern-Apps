package com.vayunmathur.youpipe.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.youpipe.util.SearchActions
import com.vayunmathur.youpipe.util.SearchResultRow
import com.vayunmathur.youpipe.util.SearchUiState
import com.vayunmathur.youpipe.util.VideoRowState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith

/** Slice 2/3 pass 3: SearchScreen states and interactions with a recording actions fake. */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SearchInteractionsPass3Test {

    @get:Rule
    val rule = createComposeRule()

    private class RecordingActions(var submitResult: Boolean = false) : SearchActions {
        val queries = mutableListOf<String>()
        var submits = 0
        val openedVideos = mutableListOf<Long>()
        val openedChannels = mutableListOf<String>()
        val events = mutableListOf<String>()
        override fun setSearchQuery(query: String) { queries += query }
        override fun submitSearch(): Boolean { submits++; return submitResult }
        override fun openVideo(videoID: Long) { openedVideos += videoID }
        override fun openChannel(channelID: String) { openedChannels += channelID }
        override fun notInterested(channelKey: String) { events += "not:$channelKey" }
        override fun moreLikeThis(channelKey: String) { events += "more:$channelKey" }
        override fun pinChannel(channelKey: String) { events += "pin:$channelKey" }
        override fun blockChannel(channelKey: String) { events += "block:$channelKey" }
    }

    private fun row(id: Long, title: String) =
        VideoRowState(id, title, "", "Seeded Chan", "1 view", null, "seeded chan")

    @Test
    fun emptyRecommendations_showsEmptyState() {
        rule.setContent { SearchScreen(SearchUiState(), SearchActions.Noop) }
        rule.onNodeWithText("Watch some videos", substring = true).assertExists()
    }

    @Test
    fun feedRowClick_opensVideo() {
        val actions = RecordingActions()
        rule.setContent {
            SearchScreen(SearchUiState(recommendations = listOf(row(5, "Feed Video"))), actions)
        }
        rule.onNodeWithText("Feed Video").assertIsDisplayed()
        rule.onNodeWithText("Feed Video").performClick()
        assertEquals(listOf(5L), actions.openedVideos)
    }

    @Test
    fun feedOverflowMenu_dispatchesChannelActions() {
        val actions = RecordingActions()
        rule.setContent {
            SearchScreen(SearchUiState(recommendations = listOf(row(5, "Feed Video"))), actions)
        }
        rule.onNodeWithText("Feed Video").assertIsDisplayed()
        // Find the overflow affordance among the screen's clickable nodes:
        // other clicks here are recorded-but-harmless (open video / expand search).
        val clickableCount = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size
        var menuOpen = false
        for (i in 0 until clickableCount) {
            rule.onAllNodes(hasClickAction())[i].performClick()
            rule.waitForIdle()
            if (rule.onAllNodes(hasText("Not interested")).fetchSemanticsNodes().isNotEmpty()) {
                menuOpen = true
                break
            }
        }
        assertTrue(menuOpen, "overflow menu never opened among $clickableCount clickable nodes")
        rule.onNodeWithText("Not interested").performClick()
        assertEquals(listOf("not:seeded chan"), actions.events)
    }

    @Test
    fun expandingSearch_showsResultsAndChannelRowClickOpensChannel() {
        val actions = RecordingActions()
        val state = SearchUiState(
            query = "seed",
            suggestions = listOf("seeded chan"),
            results = listOf(
                SearchResultRow.Video(row(9, "Result Video")),
                SearchResultRow.Channel("UCseed", "Result Channel", "", "1.2K subscribers"),
            ),
            recommendations = listOf(row(6, "Recommended One")),
        )
        rule.setContent { SearchScreen(state, actions) }
        rule.onNodeWithText("Recommended One").assertIsDisplayed()
        // Tap the search bar to expand the overlay holding results/suggestions.
        rule.onNode(hasSetTextAction()).performClick()
        rule.waitUntilExactlyOneExists(hasText("Result Video"), timeoutMillis = 10_000)
        rule.onNodeWithText("Result Channel").performClick()
        assertEquals(listOf("UCseed"), actions.openedChannels)
    }

    @Test
    fun expandingSearch_suggestionClickSubmitsThatSuggestion() {
        val actions = RecordingActions()
        val state = SearchUiState(
            query = "se",
            suggestions = listOf("seeded chan"),
            recommendations = listOf(row(6, "Recommended One")),
        )
        rule.setContent { SearchScreen(state, actions) }
        rule.onNode(hasSetTextAction()).performClick()
        rule.waitUntilExactlyOneExists(hasText("seeded chan"), timeoutMillis = 10_000)
        rule.onNodeWithText("seeded chan").performClick()
        rule.waitForIdle()
        assertTrue(actions.queries.contains("seeded chan"), actions.queries.toString())
        assertEquals(1, actions.submits)
    }

    @Test
    fun channelRow_directComposableReportsClick() {
        var clicked = false
        rule.setContent {
            ChannelRow(SearchResultRow.Channel("UC9", "Solo Channel", "", "42 subscribers")) { clicked = true }
        }
        rule.onNodeWithText("Solo Channel").assertIsDisplayed()
        rule.onNodeWithText("42 subscribers").assertIsDisplayed()
        rule.onNodeWithText("Solo Channel").performClick()
        assertTrue(clicked)
    }
}
