package com.vayunmathur.uniflix.ui.tv

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.uniflix.data.ContinueWatchingEntry
import com.vayunmathur.uniflix.data.WatchlistEntry
import com.vayunmathur.uniflix.domain.Episode
import com.vayunmathur.uniflix.domain.MediaDetail
import com.vayunmathur.uniflix.domain.MediaItem
import com.vayunmathur.uniflix.domain.MediaType
import com.vayunmathur.uniflix.domain.StreamProvider
import com.vayunmathur.uniflix.domain.VideoSource
import com.vayunmathur.uniflix.platform.DetailUiState
import com.vayunmathur.uniflix.platform.DetailViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** TV routes, media-card mapping helpers, and TV card composables. */
@RunWith(RobolectricTestRunner::class)
class TvCardsAndRoutesTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test fun routesAndTabConstants() {
        assertEquals(TV_TAB_HOME, TvRoute.Main().initialTab)
        assertEquals(0, TvRoute.Watch("tv", 7).season)
        assertEquals(TvRoute.Detail("movie", 3), TvRoute.Detail("movie", 3))
        assertEquals(TvRoute.Grid(TV_GRID_CONTINUE), TvRoute.Grid("continue"))
        assertEquals(TvRoute.AddonDetail(4L), TvRoute.AddonDetail(4L))
        assertEquals(
            listOf(0, 1, 2, 3, 4, 5),
            listOf(TV_TAB_SEARCH, TV_TAB_HOME, TV_TAB_MOVIES, TV_TAB_SHOWS, TV_TAB_MY_LIST, TV_TAB_ADDONS))
        assertEquals(
            listOf("continue", "trending-movies", "trending-tv", "top-movies", "top-tv"),
            listOf(
                TV_GRID_CONTINUE, TV_GRID_TRENDING_MOVIES, TV_GRID_TRENDING_TV,
                TV_GRID_TOP_MOVIES, TV_GRID_TOP_TV))
    }

    @Test fun mediaTypeOfParsesRawStrings() {
        assertEquals(MediaType.TV, mediaTypeOf("tv"))
        assertEquals(MediaType.MOVIE, mediaTypeOf("movie"))
        assertEquals(MediaType.MOVIE, mediaTypeOf(""))
        assertEquals(MediaType.MOVIE, mediaTypeOf("TV"))
    }

    @Test fun entryMappingHelpers() {
        val cont = ContinueWatchingEntry(
            key = "tv:7:1:2", tmdbId = 7, mediaType = "tv", title = "Show",
            posterPath = "/p.jpg", season = 1, episode = 2, positionMs = 30_000L, durationMs = 60_000L)
        val asItem = cont.toMediaItem()
        assertEquals(MediaType.TV, asItem.mediaType)
        assertEquals("Show", asItem.title)
        assertEquals("/p.jpg", asItem.posterPath)
        val watch = WatchlistEntry(
            key = "movie:8:0:0", tmdbId = 8, mediaType = "movie", title = "Film",
            posterPath = null, addedAt = 1L)
        assertEquals(MediaType.MOVIE, watch.toMediaItem().mediaType)
        assertEquals("MOVIE:8", mediaKey(MediaItem(8, MediaType.MOVIE, "Film")))
        assertEquals("TV:7", mediaKey(asItem))
    }

    @Test fun resumeProgressClampsAndRejectsZeroDuration() {
        assertEquals(0.5f, resumeProgress(30_000L, 60_000L))
        assertEquals(1f, resumeProgress(90_000L, 60_000L))
        assertEquals(0f, resumeProgress(-5L, 60_000L))
        assertNull(resumeProgress(10L, 0L))
        assertNull(resumeProgress(10L, -1L))
    }

    @Test fun posterCardRendersAndOpens() {
        var opened = 0
        composeRule.setContent {
            DynamicTheme {
                MediaPosterCard(
                    item = MediaItem(1, MediaType.MOVIE, "Poster Film", year = 2023),
                    onOpen = { opened++ })
            }
        }
        composeRule.onNodeWithText("Poster Film").assertExists()
        composeRule.onNodeWithText("2023").assertExists()
        composeRule.onNode(hasClickAction()).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        assertEquals(1, opened)
    }

    @Test fun continueCardRendersAndResumes() {
        var resumed = 0
        composeRule.setContent {
            DynamicTheme {
                ContinueEntryCard(
                    entry = ContinueWatchingEntry(
                        key = "tv:7:1:2", tmdbId = 7, mediaType = "tv", title = "Resume Show",
                        posterPath = null, season = 1, episode = 2,
                        positionMs = 10_000L, durationMs = 20_000L),
                    onResume = { resumed++ })
            }
        }
        composeRule.onNodeWithText("Resume Show").assertExists()
        composeRule.onNode(hasClickAction()).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        assertEquals(1, resumed)
    }

    @Test fun episodeCardRendersAndPlays() {
        var plays = 0
        composeRule.setContent {
            DynamicTheme {
                TvEpisodeCard(
                    episode = Episode(1, 3, "Third", overview = "Things happen"),
                    progress = 0.25f,
                    onPlay = { plays++ })
            }
        }
        composeRule.onNodeWithText("E3 · Third").assertExists()
        composeRule.onNodeWithText("Things happen").assertExists()
        composeRule.onNode(hasClickAction()).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        assertEquals(1, plays)
    }

    private class FakeProvider : StreamProvider() {
        override val name: String = "Fake"
        override val supports4k: Boolean = false
        override suspend fun resolve(
            tmdbId: Int, mediaType: MediaType, season: Int, episode: Int, server: String?,
        ): VideoSource = VideoSource("https://x/v", "S")
    }

    @Test fun detailPlayButtonNavigatesToWatch() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = DetailViewModel(app)
        vm.streamProviders = listOf(FakeProvider())
        val stack = NavBackStack<TvRoute>(arrayOf(TvRoute.Main()))
        val detail = MediaDetail(tmdbId = 42, mediaType = MediaType.MOVIE, title = "Film")
        composeRule.setContent {
            DynamicTheme {
                TvDetailPlayButton(
                    state = DetailUiState(),
                    detail = detail,
                    type = MediaType.MOVIE,
                    tmdbId = 42,
                    mediaType = "movie",
                    backStack = stack,
                    vm = vm,
                    playFocus = FocusRequester())
            }
        }
        composeRule.onNodeWithText("Play").assertExists()
        composeRule.onNode(hasClickAction()).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        assertEquals(TvRoute.Watch("movie", 42), stack.backStack.last())
        assertEquals(2, stack.backStack.size)
    }

    @Test fun detailPlayButtonForShowUsesFirstEpisode() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = DetailViewModel(app)
        vm.streamProviders = listOf(FakeProvider())
        val stack = NavBackStack<TvRoute>(arrayOf(TvRoute.Main()))
        val detail = MediaDetail(tmdbId = 7, mediaType = MediaType.TV, title = "Show")
        composeRule.setContent {
            DynamicTheme {
                TvDetailPlayButton(
                    state = DetailUiState(
                        episodes = listOf(Episode(2, 4, "Ep")), selectedSeason = 2),
                    detail = detail,
                    type = MediaType.TV,
                    tmdbId = 7,
                    mediaType = "tv",
                    backStack = stack,
                    vm = vm,
                    playFocus = FocusRequester())
            }
        }
        composeRule.onNode(hasClickAction()).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        assertEquals(TvRoute.Watch("tv", 7, season = 2, episode = 4), stack.backStack.last())
        assertTrue(stack.backStack.size == 2)
    }
}
