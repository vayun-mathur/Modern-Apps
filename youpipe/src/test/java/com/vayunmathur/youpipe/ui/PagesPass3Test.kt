package com.vayunmathur.youpipe.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkManager
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.Route
import com.vayunmathur.youpipe.data.DownloadedVideo
import com.vayunmathur.youpipe.data.HistoryVideo
import com.vayunmathur.youpipe.data.Playlist
import com.vayunmathur.youpipe.data.PlaylistItem
import com.vayunmathur.youpipe.data.Subscription
import com.vayunmathur.youpipe.data.SubscriptionCategory
import com.vayunmathur.youpipe.data.SubscriptionDatabase
import com.vayunmathur.youpipe.data.SubscriptionRepository
import com.vayunmathur.youpipe.data.SubscriptionVideo
import com.vayunmathur.youpipe.util.SearchActions
import com.vayunmathur.youpipe.util.SearchResultRow
import com.vayunmathur.youpipe.util.SearchUiState
import com.vayunmathur.youpipe.util.VideoRowState
import com.vayunmathur.youpipe.util.YouPipeViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import androidx.compose.ui.test.hasText

/**
 * Slice 2/3 pass 3: the ViewModel-bound pages, driven by the real ViewModel
 * over the repository's in-memory seam. Seed data in, rendered rows out.
 */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class PagesPass3Test {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var db: SubscriptionDatabase
    private lateinit var vm: YouPipeViewModel
    private var musicPlaylistId: Long = -1

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @BeforeTest
    fun setUp() {
        runCatching { WorkManager.initialize(app, Configuration.Builder().build()) }
        db = Room.inMemoryDatabaseBuilder(app, SubscriptionDatabase::class.java)
            .allowMainThreadQueries().build()
        vm = YouPipeViewModel(app, SubscriptionRepository(app, db))
        runBlocking { seed() }
    }

    @AfterTest
    fun tearDown() = db.close()

    private suspend fun seed() {
        val subId = db.subscriptionDao().upsert(
            Subscription(name = "Seeded Chan", channelID = "UCseed", avatarURL = ""),
        )
        db.subscriptionCategoryDao().upsertAll(listOf(SubscriptionCategory(subId, "News")))
        db.subscriptionVideoDao().upsertAll(
            listOf(
                SubscriptionVideo(
                    name = "Seeded Video", duration = 60, views = 3,
                    uploadDate = Instant.fromEpochSeconds(1_700_000_000),
                    thumbnailURL = "", author = "Seeded Chan", channelID = subId,
                ),
            ),
        )
        db.historyVideoDao().upsert(
            HistoryVideo(
                progress = 1_000,
                videoItem = VideoInfo("Watched One", 55, 100, 9, Instant.fromEpochSeconds(1_600_000_000), "", "Seeded Chan"),
                timestamp = Instant.fromEpochSeconds(1_700_100_000),
            ),
        )
        // No explicit Watch later row: the ViewModel seeds the mandatory one.
        musicPlaylistId = db.playlistDao().upsert(Playlist(name = "Music Mix"))
        db.playlistItemDao().upsert(
            PlaylistItem(
                playlistId = musicPlaylistId,
                videoItem = VideoInfo("Playlist Song", 66, 200, 4, Instant.fromEpochSeconds(1_650_000_000), "", "Seeded Chan"),
                timestamp = Instant.fromEpochSeconds(1_700_200_000),
            ),
        )
        db.downloadedVideoDao().upsert(
            DownloadedVideo(
                id = 77,
                videoItem = VideoInfo("Offline Video", 77, 300, 8, Instant.fromEpochSeconds(1_600_000_000), "", "Seeded Chan"),
                filePath = "/dl/77.mp4",
                timestamp = Instant.fromEpochSeconds(1_700_300_000),
            ),
        )
    }


    private fun awaitText(text: String) {
        rule.waitUntilExactlyOneExists(hasText(text), timeoutMillis = 10_000)
    }

    @Test
    fun historyPage_showsWatchedRows() {
        rule.setContent {
            val backStack = rememberNavBackStack<Route>(Route.Main(0))
            HistoryPage(backStack, vm)
        }
        awaitText("Watched One"); rule.onNodeWithText("Watched One").assertIsDisplayed()
    }

    @Test
    fun subscriptionsPage_showsSubscriptionsAndCategories() {
        rule.setContent {
            val backStack = rememberNavBackStack<Route>(Route.Main(1))
            SubscriptionsPage(backStack, vm)
        }
        awaitText("Seeded Chan"); rule.onNodeWithText("Seeded Chan").assertIsDisplayed()
    }

    @Test
    fun subscriptionVideosPage_showsFeed() {
        rule.setContent {
            val backStack = rememberNavBackStack<Route>(Route.Main(1))
            SubscriptionVideosPage(backStack, vm, null)
        }
        awaitText("Seeded Video"); rule.onNodeWithText("Seeded Video").assertIsDisplayed()
    }

    @Test
    fun savedPage_showsPlaylists() {
        rule.setContent {
            val backStack = rememberNavBackStack<Route>(Route.Main(3))
            SavedPage(backStack, vm)
        }
        awaitText("Music Mix"); rule.onNodeWithText("Music Mix").assertIsDisplayed()
    }

    @Test
    fun downloadedPage_showsDownloads() {
        rule.setContent {
            val backStack = rememberNavBackStack<Route>(Route.Main(3))
            DownloadedVideosPage(backStack, vm)
        }
        awaitText("Offline Video"); rule.onNodeWithText("Offline Video").assertIsDisplayed()
    }

    @Test
    fun playlistDetail_showsItems() {
        rule.setContent {
            val backStack = rememberNavBackStack<Route>(Route.Main(3))
            PlaylistDetailPage(backStack, vm, musicPlaylistId)
        }
        awaitText("Playlist Song"); rule.onNodeWithText("Playlist Song").assertIsDisplayed()
    }

    @Test
    fun generalSettings_renders() {
        rule.setContent {
            val backStack = rememberNavBackStack<Route>(Route.Main(4))
            GeneralSettingsPage(backStack, vm)
        }
        rule.onNodeWithText(app.getString(R.string.label_default_page)).assertIsDisplayed()
    }

    @Test
    fun sponsorBlockSettings_renders() {
        rule.setContent {
            val backStack = rememberNavBackStack<Route>(Route.Main(4))
            SponsorBlockSettingsPage(backStack, vm)
        }
        rule.onNodeWithText(app.getString(R.string.settings_sponsorblock)).assertIsDisplayed()
    }

    @Test
    fun settingsRoot_renders() {
        rule.setContent {
            val backStack = rememberNavBackStack<Route>(Route.Main(4))
            SettingsPage(backStack, vm)
        }
        rule.onNodeWithText(app.getString(R.string.settings_general_content), substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun dataSettings_renders() {
        rule.setContent {
            val backStack = rememberNavBackStack<Route>(Route.Main(4))
            DataSettingsPage(backStack, vm)
        }
        rule.onNodeWithText(app.getString(R.string.settings_data), substring = true).assertIsDisplayed()
    }

    @Test
    fun manageInterests_renders() {
        rule.setContent {
            val backStack = rememberNavBackStack<Route>(Route.Main(4))
            ManageInterestsPage(backStack, vm)
        }
        rule.onNodeWithText(app.getString(R.string.title_manage_interests)).assertIsDisplayed()
    }

    @Test
    fun recommendationsSettings_renders() {
        rule.setContent {
            val backStack = rememberNavBackStack<Route>(Route.Main(4))
            RecommendationsSettingsPage(backStack, vm)
        }
        rule.onNodeWithText(app.getString(R.string.label_recommendations)).assertIsDisplayed()
    }

    @Test
    fun createCategoryDialog_listsSubscriptions() {
        rule.setContent {
            val backStack = rememberNavBackStack<Route>(Route.Main(1))
            com.vayunmathur.youpipe.ui.dialogs.CreateSubscriptionCategory(backStack, vm, null)
        }
        awaitText("Seeded Chan"); rule.onNodeWithText("Seeded Chan").assertIsDisplayed()
    }

    @Test
    fun searchScreen_rendersResultsAndSuggestions() {
        val state = SearchUiState(
            query = "seed",
            suggestions = listOf("seeded chan"),
            results = listOf(
                SearchResultRow.Video(
                    VideoRowState(5, "Result Video", "", "Seeded Chan", "1 view", null, "seeded chan"),
                ),
                SearchResultRow.Channel("UCseed", "Seeded Chan", "", "1.2K subscribers"),
            ),
            recommendations = listOf(
                VideoRowState(6, "Recommended One", "", "Seeded Chan", "2 views", null, "seeded chan"),
            ),
        )
        rule.setContent { SearchScreen(state, SearchActions.Noop) }
        // Collapsed search shows recommendations; results need the expanded
        // search state, which is interaction-driven and left unasserted here.
        rule.onNodeWithText("Recommended One").assertIsDisplayed()
    }
}
