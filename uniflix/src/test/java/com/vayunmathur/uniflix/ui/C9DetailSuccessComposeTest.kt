package com.vayunmathur.uniflix.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.uniflix.data.UniflixDatabase
import com.vayunmathur.uniflix.data.UniflixRepository
import com.vayunmathur.uniflix.data.fakeHttpResponse
import com.vayunmathur.uniflix.data.uniflixTransport
import com.vayunmathur.uniflix.domain.Episode
import com.vayunmathur.uniflix.domain.MediaDetail
import com.vayunmathur.uniflix.domain.MediaType
import com.vayunmathur.uniflix.domain.SeasonSummary
import com.vayunmathur.uniflix.platform.DetailUiState
import com.vayunmathur.uniflix.platform.FakeAndroidKeyStore
import com.vayunmathur.uniflix.ui.tv.TvDetailPage
import com.vayunmathur.uniflix.ui.tv.TvRoute
import com.vayunmathur.uniflix.ui.tv.TvWatchPage
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Detail success bodies: phone DetailScreen from state, TV detail/watch via fake TMDB. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class C9DetailSuccessComposeTest {

    @get:Rule val rule = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @BeforeTest fun setUp() {
        FakeAndroidKeyStore.install()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val db = Room.inMemoryDatabaseBuilder(context, UniflixDatabase::class.java)
            .allowMainThreadQueries().build()
        com.vayunmathur.library.util.databases[UniflixDatabase::class] = db
        UniflixRepository.resetInstanceForTest()
        uniflixTransport = { url ->
            when {
                url.contains("/season/") -> fakeHttpResponse(200, """{"episodes":[{"episode_number":1,"name":"Pilot","overview":"First."}]}""")
                url.contains("/movie/10") -> fakeHttpResponse(200, """{"id":10,"title":"Detail Film","release_date":"2023-05-06","overview":"A film overview.","runtime":121,"vote_average":7.5}""")
                url.contains("/tv/20") -> fakeHttpResponse(200, """{"id":20,"name":"Detail Show","first_air_date":"2022-02-02","overview":"A show overview.","seasons":[{"season_number":1,"name":"Season 1","episode_count":1}]}""")
                else -> fakeHttpResponse(404, "{}")
            }
        }
    }

    @AfterTest fun tearDown() {
        uniflixTransport = null
    }

    @Test fun detailScreen_movieSuccess() {
        val state = DetailUiState(
            detail = MediaDetail(
                tmdbId = 10, mediaType = MediaType.MOVIE, title = "Detail Film", year = 2023,
                overview = "A film overview.", voteAverage = 7.5, runtimeMinutes = 121,
            ),
            watchlisted = true,
        )
        rule.setContent {
            DetailScreen(
                state = state, onBack = {}, onSeasonChange = {}, onToggleWatchlist = {},
                onPlay = { _, _ -> }, onResolve = { _, _ -> }, onAddNote = { _, _ -> },
                onOpenDetail = {}, onOpenAddons = {},
            )
        }
        rule.onNodeWithText("Detail Film").assertIsDisplayed()
        rule.onNodeWithText("A film overview.").assertExists()
    }

    @Test fun detailScreen_tvSuccessWithEpisodes() {
        val state = DetailUiState(
            detail = MediaDetail(
                tmdbId = 20, mediaType = MediaType.TV, title = "Detail Show", year = 2022,
                overview = "A show overview.",
                seasons = listOf(SeasonSummary(1, "Season 1", 1, null)),
            ),
            episodes = listOf(Episode(1, 1, "Pilot", "First.", null)),
            selectedSeason = 1,
        )
        rule.setContent {
            DetailScreen(
                state = state, onBack = {}, onSeasonChange = {}, onToggleWatchlist = {},
                onPlay = { _, _ -> }, onResolve = { _, _ -> }, onAddNote = { _, _ -> },
                onOpenDetail = {}, onOpenAddons = {},
            )
        }
        rule.onNodeWithText("Detail Show").assertIsDisplayed()
        rule.onAllNodesWithText("Pilot", substring = true)[0].assertExists()
    }

    @Test fun detailScreen_loadingAndMissingProvider() {
        rule.setContent {
            DetailScreen(
                state = DetailUiState(loading = true), onBack = {}, onSeasonChange = {},
                onToggleWatchlist = {}, onPlay = { _, _ -> }, onResolve = { _, _ -> },
                onAddNote = { _, _ -> }, onOpenDetail = {}, onOpenAddons = {},
            )
        }
        rule.waitForIdle()
    }

    @Test fun tvDetail_successViaFakeTmdb() {
        val stack = NavBackStack<TvRoute>(arrayOf(TvRoute.Main()))
        rule.setContent { TvDetailPage(stack, "movie", 10) }
        rule.waitUntil(5000) {
            rule.onAllNodesWithText("Detail Film").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Detail Film").assertExists()
    }

    @Test fun tvWatch_rendersWithFakeTmdb() {
        val stack = NavBackStack<TvRoute>(arrayOf(TvRoute.Main()))
        rule.setContent { TvWatchPage(stack, "movie", 10, 0, 0) }
        rule.waitForIdle()
    }
}
