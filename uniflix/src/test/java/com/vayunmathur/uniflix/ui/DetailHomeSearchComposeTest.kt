package com.vayunmathur.uniflix.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.uniflix.domain.MediaItem
import com.vayunmathur.uniflix.domain.MediaType
import com.vayunmathur.uniflix.platform.DetailUiState
import com.vayunmathur.uniflix.platform.HomeUiState
import com.vayunmathur.uniflix.platform.SearchUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Stateless Home / Search / Detail / PlayerSettings screens under Robolectric. */
@RunWith(RobolectricTestRunner::class)
class DetailHomeSearchComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun item(id: Int, title: String, type: MediaType = MediaType.MOVIE) =
        MediaItem(tmdbId = id, mediaType = type, title = title, year = 2022)

    @Test fun homeShowsRowsAndHubActions() {
        var movies = 0
        var opened: MediaItem? = null
        composeRule.setContent {
            DynamicTheme {
                HomeScreen(
                    state = HomeUiState(
                        trendingMovies = listOf(item(1, "Trending Film")),
                        trendingTv = listOf(item(2, "Trending Show", MediaType.TV))),
                    onRefresh = {},
                    onOpenDetail = { opened = it },
                    onOpenSearch = {}, onOpenMovies = { movies++ },
                    onOpenSeries = {}, onOpenLists = {}, onOpenContinue = {},
                    onOpenAddons = {})
            }
        }
        composeRule.onNodeWithText("Uniflix").assertIsDisplayed()
        composeRule.onNodeWithText("Trending movies").assertIsDisplayed()
        composeRule.onNodeWithText("Trending Film").assertIsDisplayed()
        composeRule.onNodeWithText("Movies").performClick()
        assertEquals(1, movies)
        composeRule.onNodeWithText("Trending Film").performClick()
        assertEquals(1, opened?.tmdbId)
    }

    @Test fun homeLoadingShowsSpinnerOnlyState() {
        composeRule.setContent {
            DynamicTheme {
                HomeScreen(
                    state = HomeUiState(loading = true),
                    onRefresh = {}, onOpenDetail = {}, onOpenSearch = {},
                    onOpenMovies = {}, onOpenSeries = {}, onOpenLists = {},
                    onOpenContinue = {}, onOpenAddons = {})
            }
        }
        composeRule.onNodeWithText("Uniflix").assertIsDisplayed()
        composeRule.onNodeWithText("Series").assertIsDisplayed()
    }

    @Test fun searchTypesQueryAndOpensResult() {
        var query: String? = null
        var opened: MediaItem? = null
        val results = listOf(item(5, "Found Film"))
        composeRule.setContent {
            DynamicTheme {
                SearchScreen(
                    state = SearchUiState(query = "", results = results),
                    onQueryChange = { query = it },
                    onBack = {},
                    onOpenDetail = { opened = it })
            }
        }
        composeRule.onNodeWithText("Search").assertIsDisplayed()
        composeRule.onNodeWithText("Title").performTextInput("found")
        assertEquals("found", query)
        composeRule.onNodeWithText("Found Film").assertIsDisplayed()
        composeRule.onNodeWithText("Found Film").performClick()
        assertEquals(results[0], opened)
    }

    @Test fun detailLoadingAndFailureStates() {
        composeRule.setContent {
            DynamicTheme {
                DetailScreen(
                    state = DetailUiState(loading = true),
                    onBack = {}, onSeasonChange = {}, onToggleWatchlist = {},
                    onPlay = { _, _ -> }, onResolve = { _, _ -> },
                    onAddNote = { _, _ -> }, onOpenDetail = {}, onOpenAddons = {})
            }
        }
        composeRule.onNodeWithText("Detail").assertIsDisplayed()
    }

    @Test fun detailFailureShowsMessage() {
        composeRule.setContent {
            DynamicTheme {
                DetailScreen(
                    state = DetailUiState(loading = false, detail = null),
                    onBack = {}, onSeasonChange = {}, onToggleWatchlist = {},
                    onPlay = { _, _ -> }, onResolve = { _, _ -> },
                    onAddNote = { _, _ -> }, onOpenDetail = {}, onOpenAddons = {})
            }
        }
        composeRule.onNodeWithText("Detail failed to load.").assertIsDisplayed()
    }

    @Test fun playerSettingsShowsPlaybackInfo() {
        composeRule.setContent {
            DynamicTheme { PlayerSettingsScreen(onBack = {}) }
        }
        composeRule.onNodeWithText("Player").assertIsDisplayed()
        composeRule.onNodeWithText("Playback").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Streams play through the configured provider. Subtitle tracks come with the resolved source.")
            .assertIsDisplayed()
    }
}
