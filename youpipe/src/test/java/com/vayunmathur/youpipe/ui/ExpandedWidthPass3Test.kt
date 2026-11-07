package com.vayunmathur.youpipe.ui

import android.app.Application
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkManager
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.Route
import com.vayunmathur.youpipe.data.DownloadedVideo
import com.vayunmathur.youpipe.data.HistoryVideo
import com.vayunmathur.youpipe.data.Playlist
import com.vayunmathur.youpipe.data.PlaylistItem
import com.vayunmathur.youpipe.data.SubscriptionDatabase
import com.vayunmathur.youpipe.data.SubscriptionRepository
import com.vayunmathur.youpipe.util.SearchActions
import com.vayunmathur.youpipe.util.SearchUiState
import com.vayunmathur.youpipe.util.SubscriptionFeedActions
import com.vayunmathur.youpipe.util.SubscriptionFeedUiState
import com.vayunmathur.youpipe.util.VideoDetailActions
import com.vayunmathur.youpipe.util.VideoDetailUiState
import com.vayunmathur.youpipe.util.VideoRowState
import com.vayunmathur.youpipe.util.YouPipeViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Slice 2/3 pass 3: expanded-width (tablet/desktop) branches of the slice's screens. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w1200dp-h800dp")
class ExpandedWidthPass3Test {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var db: SubscriptionDatabase
    private lateinit var vm: YouPipeViewModel
    private lateinit var backStack: NavBackStack<Route>
    private var playlistId: Long = -1

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @BeforeTest
    fun setUp() {
        runCatching { WorkManager.initialize(app, Configuration.Builder().build()) }
        db = Room.inMemoryDatabaseBuilder(app, SubscriptionDatabase::class.java)
            .allowMainThreadQueries().build()
        vm = YouPipeViewModel(app, SubscriptionRepository(app, db))
        backStack = NavBackStack(arrayOf<Route>(Route.Main(2)))
        runBlocking {
            db.historyVideoDao().upsert(
                HistoryVideo(progress = 1_000, videoItem = VideoInfo("Grid Watched", 55, 100, 9, Instant.fromEpochSeconds(1_600_000_000), "", "Chan"), timestamp = Instant.fromEpochSeconds(1_700_100_000)),
            )
            db.historyVideoDao().upsert(
                HistoryVideo(progress = 2_000, videoItem = VideoInfo("Grid Second", 56, 100, 9, Instant.fromEpochSeconds(1_600_000_000), "", "Chan"), timestamp = Instant.fromEpochSeconds(1_700_200_000)),
            )
            playlistId = db.playlistDao().upsert(Playlist(name = "Grid Mix"))
            db.playlistItemDao().upsert(
                PlaylistItem(playlistId = playlistId, videoItem = VideoInfo("Grid Song", 71, 100, 9, Instant.fromEpochSeconds(1_600_000_000), "", "Chan"), position = 1.0, timestamp = Instant.fromEpochSeconds(1_700_100_000)),
            )
            db.downloadedVideoDao().upsert(
                DownloadedVideo(id = 77, videoItem = VideoInfo("Grid Offline", 77, 100, 9, Instant.fromEpochSeconds(1_600_000_000), "", "Chan"), filePath = "/dl/77.mp4", timestamp = Instant.fromEpochSeconds(1_700_300_000)),
            )
        }
    }

    @AfterTest
    fun tearDown() = db.close()

    private fun row(id: Long, title: String) = VideoRowState(id, title, "", "Chan", "1 view", null, "chan")

    @Test
    fun historyGrid_clickNavigates() {
        rule.setContent { HistoryPage(backStack, vm) }
        rule.waitUntilExactlyOneExists(hasText("Grid Watched"), timeoutMillis = 10_000)
        rule.onNodeWithText("Grid Watched").performClick()
        assertEquals(Route.VideoPage(55), backStack.backStack.last())
    }

    @Test
    fun historyGrid_longPressSelectsAndFabDeletes() {
        rule.setContent { HistoryPage(backStack, vm) }
        rule.waitUntilExactlyOneExists(hasText("Grid Watched"), timeoutMillis = 10_000)
        rule.onNodeWithText("Grid Watched").performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Delete").performClick()
        rule.waitUntilDoesNotExist(hasText("Grid Watched"), timeoutMillis = 10_000)
        rule.onNodeWithText("Grid Second").assertExists()
    }

    @Test
    fun playlistGrid_clickNavigates() {
        rule.setContent { PlaylistDetailPage(backStack, vm, playlistId) }
        rule.waitUntilExactlyOneExists(hasText("Grid Song"), timeoutMillis = 10_000)
        rule.onNodeWithText("Grid Song").performClick()
        assertEquals(Route.VideoPage(71), backStack.backStack.last())
    }

    @Test
    fun downloadsGrid_clickNavigates() {
        rule.setContent { DownloadedVideosPage(backStack, vm) }
        rule.waitUntilExactlyOneExists(hasText("Grid Offline"), timeoutMillis = 10_000)
        rule.onNodeWithText("Grid Offline").performClick()
        assertEquals(Route.VideoPage(77), backStack.backStack.last())
    }

    @Test
    fun subscriptionFeedGrid_clickOpensVideo() {
        var opened: Long? = null
        val actions = object : SubscriptionFeedActions {
            override fun openVideo(videoID: Long) { opened = videoID }
        }
        rule.setContent {
            SubscriptionVideosScreen(SubscriptionFeedUiState(videos = listOf(row(31, "Grid Feed Video"))), actions)
        }
        rule.onNodeWithText("Grid Feed Video").assertIsDisplayed()
        rule.onNodeWithText("Grid Feed Video").performClick()
        assertEquals(31L, opened)
    }

    @Test
    fun searchFeedGrid_clickOpensVideo() {
        var opened: Long? = null
        val actions = object : SearchActions {
            override fun openVideo(videoID: Long) { opened = videoID }
        }
        rule.setContent {
            SearchScreen(SearchUiState(recommendations = listOf(row(41, "Grid Recommended"))), actions)
        }
        rule.onNodeWithText("Grid Recommended").assertIsDisplayed()
        rule.onNodeWithText("Grid Recommended").performClick()
        assertEquals(41L, opened)
    }

    @Test
    fun feedGrid_expandedRendersItems() {
        rule.setContent {
            FeedGrid(rows = listOf("Alpha", "Beta"), key = { it }) { Text("cell-$it") }
        }
        rule.onNodeWithText("cell-Alpha").assertIsDisplayed()
        rule.onNodeWithText("cell-Beta").assertIsDisplayed()
    }

    @Test
    fun videoDetail_expandedTwoPaneShowsDescriptionAndRelatedTab() {
        var opened: Long? = null
        val actions = object : VideoDetailActions {
            override fun openVideo(videoID: Long) { opened = videoID }
        }
        val state = VideoDetailUiState(
            loaded = true, title = "Wide Video", byline = "Chan | 1 view",
            description = "A wide description",
            comments = listOf(Comment("Nice", "Fan", 3, 0)),
            relatedVideos = listOf(row(51, "Wide Related")),
        )
        rule.setContent {
            VideoDetailScreen(state = state, actions = actions) { Text("PLAYER-SLOT") }
        }
        rule.onNodeWithText("PLAYER-SLOT").assertIsDisplayed()
        rule.onNodeWithText("Wide Video").assertIsDisplayed()
        rule.onNodeWithText("A wide description", substring = true).assertIsDisplayed()
        // Right pane starts on comments; switch to related and open the row.
        rule.onNodeWithText("Related Videos", substring = true).performClick()
        rule.waitUntilExactlyOneExists(hasText("Wide Related"), timeoutMillis = 10_000)
        rule.onNodeWithText("Wide Related").performClick()
        assertEquals(51L, opened)
    }
}
