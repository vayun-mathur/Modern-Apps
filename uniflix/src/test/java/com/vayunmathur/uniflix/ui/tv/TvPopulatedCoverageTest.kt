package com.vayunmathur.uniflix.ui.tv

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.uniflix.data.ContinueWatchingEntry
import com.vayunmathur.uniflix.data.UniflixDatabase
import com.vayunmathur.uniflix.data.UniflixRepository
import com.vayunmathur.uniflix.data.UserList
import com.vayunmathur.uniflix.data.UserListItem
import com.vayunmathur.uniflix.data.WatchlistEntry
import com.vayunmathur.uniflix.data.fakeHttpResponse
import com.vayunmathur.uniflix.data.uniflixTransport
import com.vayunmathur.uniflix.data.watchKey
import com.vayunmathur.uniflix.domain.MediaItem
import com.vayunmathur.uniflix.platform.FakeAndroidKeyStore
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** TV grid/list pages with a populated catalog and library (not just empty states). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TvPopulatedCoverageTest {

    @get:Rule val rule = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val repo get() = UniflixRepository.get(context)

    @BeforeTest fun setUp() {
        FakeAndroidKeyStore.install()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val db = Room.inMemoryDatabaseBuilder(context, UniflixDatabase::class.java)
            .allowMainThreadQueries().build()
        com.vayunmathur.library.util.databases[UniflixDatabase::class] = db
        UniflixRepository.resetInstanceForTest()
        uniflixTransport = { url ->
            when {
                url.contains("/trending/movie") -> fakeHttpResponse(200, """{"results":[{"id":11,"title":"Pop Trend Film","poster_path":"/a.jpg","vote_average":8.1}]}""")
                url.contains("/trending/tv") -> fakeHttpResponse(200, """{"results":[{"id":12,"name":"Pop Trend Show","media_type":"tv","poster_path":"/b.jpg"}]}""")
                url.contains("/top/movie") -> fakeHttpResponse(200, """{"results":[{"id":13,"title":"Pop Top Film","poster_path":"/c.jpg"}]}""")
                url.contains("/top/tv") -> fakeHttpResponse(200, """{"results":[{"id":14,"name":"Pop Top Show","media_type":"tv","poster_path":"/d.jpg"}]}""")
                else -> fakeHttpResponse(404, "{}")
            }
        }
    }

    @AfterTest fun tearDown() { uniflixTransport = null }

    private fun seedLibrary() = runBlocking {
        repo.upsertWatchlist(
            WatchlistEntry(watchKey(21, "movie"), 21, "movie", "Watchlist Film", "/w.jpg", 1000L),
        )
        repo.savePosition(
            ContinueWatchingEntry(
                watchKey(22, "tv", 1, 2), 22, "tv", "Resume Show", "/r.jpg",
                season = 1, episode = 2, positionMs = 60_000, durationMs = 3_600_000, updatedAt = 2000L,
            ),
        )
        val listId = repo.upsertList(UserList(name = "Weekend Picks", createdAt = 3000L))
        repo.upsertListItem(UserListItem(listId = listId, tmdbId = 23, mediaType = "movie", title = "Listed Film", posterPath = "/l.jpg"))
    }

    @Test fun topMoviesGridShowsPosterAndOpensDetail() {
        val stack = NavBackStack<TvRoute>(arrayOf(TvRoute.Home))
        rule.setContent { DynamicTheme { TvGridPage(stack, TV_GRID_TOP_MOVIES) } }
        rule.waitUntil(timeoutMillis = 8_000) {
            runCatching { rule.onNodeWithText("Pop Top Film").assertIsDisplayed(); true }.getOrDefault(false)
        }
        // TV cards open on CENTER; in semantics the click lives on the
        // androidx.tv Card, not the title text or poster image.
        rule.onAllNodes(androidx.compose.ui.test.hasClickAction())[0]
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        rule.waitForIdle()
        assertEquals(TvRoute.Detail("movie", 13), stack.last())
    }

    @Test fun continueGridShowsResumeAndOpensWatch() {
        seedLibrary()
        val stack = NavBackStack<TvRoute>(arrayOf(TvRoute.Home))
        rule.setContent { DynamicTheme { TvGridPage(stack, TV_GRID_CONTINUE) } }
        rule.waitUntil(timeoutMillis = 8_000) {
            runCatching { rule.onNodeWithText("Resume Show").assertIsDisplayed(); true }.getOrDefault(false)
        }
        rule.onAllNodes(androidx.compose.ui.test.hasClickAction())[0]
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        rule.waitForIdle()
        assertEquals(TvRoute.Watch("tv", 22, 1, 2), stack.last())
    }

    @Test fun myListShowsAllSectionsAndOpensDetail() {
        seedLibrary()
        var opened: MediaItem? = null
        var resumed: ContinueWatchingEntry? = null
        rule.setContent {
            DynamicTheme {
                TvMyListTab(onOpenDetail = { opened = it }, onResume = { resumed = it })
            }
        }
        rule.waitUntil(timeoutMillis = 8_000) {
            runCatching { rule.onNodeWithText("Watchlist Film").assertIsDisplayed(); true }.getOrDefault(false)
        }
        rule.onNodeWithText("Watchlist Film").assertIsDisplayed()
        rule.onNodeWithText("Weekend Picks").assertIsDisplayed()
        // First clickable card is the first continue-watching entry; the
        // watchlist film opens detail, the resume card resumes playback.
        rule.onNodeWithText("Watchlist Film").assertIsDisplayed()
        val clickables = rule.onAllNodes(androidx.compose.ui.test.hasClickAction()).fetchSemanticsNodes()
        assertTrue(clickables.size >= 2, "expected section cards, got ${clickables.size}")
        rule.onAllNodes(androidx.compose.ui.test.hasClickAction())[1]
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        rule.waitForIdle()
        assertEquals(21, opened?.tmdbId)
        assertEquals(null, resumed)
        rule.onAllNodes(androidx.compose.ui.test.hasClickAction())[0]
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        rule.waitForIdle()
        assertEquals(22, resumed?.tmdbId)
        assertTrue(resumed!!.positionMs == 60_000L)
    }
}
