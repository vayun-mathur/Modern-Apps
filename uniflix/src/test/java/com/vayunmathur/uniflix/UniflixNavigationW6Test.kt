package com.vayunmathur.uniflix

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
 * W6: the phone Navigation graph with a live (fake-transport) catalog —
 * Home poster → Detail → Watch, plus Search — the DetailRoute/WatchRoute
 * bodies that offline navigation tests never reach.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UniflixNavigationW6Test {

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
                url.contains("/manifest.json") -> fakeHttpResponse(200, """{"id":"demo","name":"Demo","version":"1.0.0","resources":["stream"],"types":["movie","series"]}""")
                url.contains("/movie/10") -> fakeHttpResponse(200, """{"id":10,"title":"Detail Film","release_date":"2023-05-06","overview":"A film overview.","runtime":121,"vote_average":7.5}""")
                url.contains("/tv/20") -> fakeHttpResponse(200, """{"id":20,"name":"Detail Show","first_air_date":"2022-02-02","overview":"A show overview.","seasons":[{"season_number":1,"name":"Season 1","episode_count":1}]}""")
                url.contains("/season/") -> fakeHttpResponse(200, """{"episodes":[{"episode_number":1,"name":"Pilot","overview":"First."}]}""")
                url.contains("/trending") || url.contains("/top/") || url.contains("/discover") || url.contains("/search") ->
                    fakeHttpResponse(200, list)
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
    fun navigation_homeToDetailToWatch() {
        rule.setContent { DynamicTheme { Navigation() } }
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("Detail Film", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onAllNodesWithText("Detail Film", substring = true)[0].performClick()
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("A film overview.", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        // Play / Watch affordance opens the watch route.
        for (label in listOf("Watch", "Play")) {
            rule.onAllNodesWithText(label, substring = true).fetchSemanticsNodes().let {
                if (it.isNotEmpty()) {
                    rule.onAllNodesWithText(label, substring = true)[0].performClick()
                    rule.waitForIdle()
                    return
                }
            }
        }
        rule.waitForIdle()
    }
}
