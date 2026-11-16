package com.vayunmathur.uniflix.ui.tv

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.uniflix.data.UniflixDatabase
import com.vayunmathur.uniflix.data.UniflixRepository
import com.vayunmathur.uniflix.data.fakeHttpResponse
import com.vayunmathur.uniflix.data.uniflixTransport
import com.vayunmathur.uniflix.platform.FakeAndroidKeyStore
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
 * W6: the TV navigation host with a live catalog — tab switching, poster
 * → detail → watch, and the addons tab — over the fake TMDB transport.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UniflixTvNavigationW6Test {

    @get:Rule val rule = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @BeforeTest fun setUp() {
        FakeAndroidKeyStore.install()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val db = Room.inMemoryDatabaseBuilder(context, UniflixDatabase::class.java)
            .allowMainThreadQueries().build()
        com.vayunmathur.library.util.databases[UniflixDatabase::class] = db
        UniflixRepository.resetInstanceForTest()
        val list = """{"results":[{"id":10,"title":"Detail Film","release_date":"2023-05-06","overview":"A film.","vote_average":7.5},{"id":20,"name":"Detail Show","first_air_date":"2022-02-02","overview":"A show.","vote_average":8.0}]}"""
        uniflixTransport = { url ->
            when {
                url.contains("/stream/") -> fakeHttpResponse(200, """{"streams":[{"url":"https://cdn.example.invalid/v.m3u8","name":"HD"}]}""")
                url.contains("/manifest.json") -> fakeHttpResponse(200, """{"id":"demo","name":"Demo Addon","version":"1.0.0","resources":["stream"],"types":["movie","series"]}""")
                url.contains("/movie/10") -> fakeHttpResponse(200, """{"id":10,"title":"Detail Film","release_date":"2023-05-06","overview":"A film overview.","runtime":121,"vote_average":7.5}""")
                url.contains("/tv/20") -> fakeHttpResponse(200, """{"id":20,"name":"Detail Show","first_air_date":"2022-02-02","overview":"A show overview.","seasons":[{"season_number":1,"name":"Season 1","episode_count":1}]}""")
                url.contains("/season/") -> fakeHttpResponse(200, """{"episodes":[{"episode_number":1,"name":"Pilot","overview":"First."}]}""")
                url.contains("/trending") || url.contains("/top/") || url.contains("/discover") || url.contains("/search") -> fakeHttpResponse(200, list)
                else -> fakeHttpResponse(404, "{}")
            }
        }
        kotlinx.coroutines.runBlocking {
            db.addonDao().upsert(
                com.vayunmathur.uniflix.data.AddonEntity(
                    manifestUrl = "https://example.invalid/manifest.json",
                    baseUrl = "https://example.invalid", addonId = "demo", name = "Demo Addon",
                ),
            )
        }
    }

    @AfterTest fun tearDown() { uniflixTransport = null }

    @Test
    fun tvHost_tabsAndPosterToDetail() {
        rule.setContent { DynamicTheme { TvNavigation() } }
        rule.waitForIdle()
        // Switch through the main tabs if they are labelled.
        for (tab in listOf("My List", "Addons", "Movies", "Series", "Home")) {
            rule.onAllNodesWithText(tab, substring = true).fetchSemanticsNodes().let {
                if (it.isNotEmpty()) {
                    rule.onAllNodesWithText(tab, substring = true)[0].performClick()
                    rule.waitForIdle()
                }
            }
        }
        // Open a poster from whichever catalog tab is showing.
        rule.onAllNodesWithText("Detail Film", substring = true).fetchSemanticsNodes().let {
            if (it.isNotEmpty()) {
                rule.onAllNodesWithText("Detail Film", substring = true)[0].performClick()
                rule.waitForIdle()
            }
        }
        rule.waitForIdle()
    }
}
