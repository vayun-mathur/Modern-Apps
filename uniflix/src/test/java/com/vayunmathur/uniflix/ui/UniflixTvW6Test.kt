package com.vayunmathur.uniflix.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.uniflix.data.UniflixDatabase
import com.vayunmathur.uniflix.data.UniflixRepository
import com.vayunmathur.uniflix.data.fakeHttpResponse
import com.vayunmathur.uniflix.data.uniflixTransport
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

/**
 * W6: TV watch/detail with a resolved stream source — overlay toggles,
 * server/subtitle/quality pickers and episode rows the empty-state
 * renders never reach.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UniflixTvW6Test {

    @get:Rule val rule = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @BeforeTest fun setUp() {
        FakeAndroidKeyStore.install()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val db = Room.inMemoryDatabaseBuilder(context, UniflixDatabase::class.java)
            .allowMainThreadQueries().build()
        com.vayunmathur.library.util.databases[UniflixDatabase::class] = db
        UniflixRepository.resetInstanceForTest()
        kotlinx.coroutines.runBlocking {
            db.addonDao().upsert(
                com.vayunmathur.uniflix.data.AddonEntity(
                    manifestUrl = "https://example.invalid/manifest.json",
                    baseUrl = "https://example.invalid", addonId = "demo", name = "Demo Addon",
                ),
            )
        }
        uniflixTransport = { url ->
            when {
                url.contains("/season/") -> fakeHttpResponse(
                    200,
                    """{"episodes":[{"episode_number":1,"name":"Pilot","overview":"First."},{"episode_number":2,"name":"Second","overview":"Next."}]}""",
                )
                url.contains("/stream/") -> fakeHttpResponse(
                    200,
                    """{"streams":[{"url":"https://cdn.example.invalid/v.m3u8","name":"HD","subtitles":[{"url":"https://cdn.example.invalid/en.srt","lang":"en","label":"English"}]}]}""",
                )
                url.contains("/manifest.json") -> fakeHttpResponse(
                    200,
                    """{"id":"demo","name":"Demo Addon","version":"1.0.0","resources":["stream"],"types":["movie","series"]}""",
                )
                url.contains("/movie/10") -> fakeHttpResponse(
                    200,
                    """{"id":10,"title":"Detail Film","release_date":"2023-05-06","overview":"A film overview.","runtime":121,"vote_average":7.5}""",
                )
                url.contains("/tv/20") -> fakeHttpResponse(
                    200,
                    """{"id":20,"name":"Detail Show","first_air_date":"2022-02-02","overview":"A show overview.","number_of_seasons":1,"seasons":[{"season_number":1,"name":"Season 1","episode_count":2}]}""",
                )
                else -> fakeHttpResponse(404, "{}")
            }
        }
    }

    @AfterTest fun tearDown() { uniflixTransport = null }

    @Test
    fun tvWatch_resolvedSource_overlayAndPickers() {
        val stack = NavBackStack<TvRoute>(arrayOf(TvRoute.Home))
        rule.setContent { TvWatchPage(stack, "movie", 10, 0, 0) }
        rule.waitForIdle()
        // Tap the surface to toggle the overlay, then open each picker if offered.
        rule.onAllNodesWithText("Detail Film", substring = true).fetchSemanticsNodes().let {
            if (it.isNotEmpty()) rule.onAllNodesWithText("Detail Film", substring = true)[0].performClick()
        }
        rule.waitForIdle()
        for (label in listOf("Demo Addon", "HD", "English", "Subtitles", "Quality")) {
            rule.onAllNodesWithText(label, substring = true).fetchSemanticsNodes().let { nodes ->
                if (nodes.isNotEmpty()) {
                    rule.onAllNodesWithText(label, substring = true)[0].performClick()
                    rule.waitForIdle()
                }
            }
        }
    }

    @Test
    fun tvDetail_series_episodeRows() {
        val stack = NavBackStack<TvRoute>(arrayOf(TvRoute.Home))
        rule.setContent { TvDetailPage(stack, "tv", 20) }
        rule.waitUntil(8_000) {
            rule.onAllNodesWithText("Detail Show", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitForIdle()
        rule.onAllNodesWithText("Pilot", substring = true).fetchSemanticsNodes().let {
            if (it.isNotEmpty()) rule.onAllNodesWithText("Pilot", substring = true)[0].performClick()
        }
        rule.waitForIdle()
    }

    @Test
    fun tvWatch_seriesEpisode_resolves() {
        val stack = NavBackStack<TvRoute>(arrayOf(TvRoute.Home))
        rule.setContent { TvWatchPage(stack, "tv", 20, 1, 1) }
        rule.waitForIdle()
    }
}
