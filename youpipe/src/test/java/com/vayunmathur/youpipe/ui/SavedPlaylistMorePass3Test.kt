package com.vayunmathur.youpipe.ui

import android.app.Application
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkManager
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.Route
import com.vayunmathur.youpipe.data.Playlist
import com.vayunmathur.youpipe.data.SubscriptionDatabase
import com.vayunmathur.youpipe.data.SubscriptionRepository
import com.vayunmathur.youpipe.util.SubscriptionFeedActions
import com.vayunmathur.youpipe.util.SubscriptionFeedUiState
import com.vayunmathur.youpipe.util.VideoRowState
import com.vayunmathur.youpipe.util.YouPipeViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.runner.RunWith

/** Slice 2/3 pass 3: Saved hub navigation, playlist deletion/empty state, subscription feed states. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SavedPlaylistMorePass3Test {

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
        backStack = NavBackStack(arrayOf<Route>(Route.Main(3)))
        runBlocking { playlistId = db.playlistDao().upsert(Playlist(name = "Road Trip")) }
    }

    @AfterTest
    fun tearDown() = db.close()

    @Test
    fun savedPage_downloadsRowNavigatesAndUserPlaylistOpens() {
        rule.setContent { SavedPage(backStack, vm) }
        rule.waitUntilExactlyOneExists(hasText("Road Trip"), timeoutMillis = 10_000)
        rule.onNodeWithText(app.getString(R.string.title_downloads)).performClick()
        assertEquals(Route.Downloads, backStack.backStack.last())
        rule.onNodeWithText("Road Trip").performClick()
        assertEquals(Route.PlaylistDetail(playlistId), backStack.backStack.last())
    }

    @Test
    fun savedPage_createFabNavigates() {
        rule.setContent { SavedPage(backStack, vm) }
        rule.waitUntilExactlyOneExists(hasText("Road Trip"), timeoutMillis = 10_000)
        rule.onNodeWithContentDescription("Add").performClick()
        assertEquals(Route.CreatePlaylist, backStack.backStack.last())
    }

    @Test
    fun playlistDetail_emptyPlaylistShowsEmptyText() {
        rule.setContent { PlaylistDetailPage(backStack, vm, playlistId) }
        rule.waitUntilExactlyOneExists(hasText(app.getString(R.string.playlist_empty)), timeoutMillis = 10_000)
        rule.onNodeWithText(app.getString(R.string.playlist_empty)).assertIsDisplayed()
    }

    @Test
    fun playlistDetail_deletePlaylistRemovesItAndPops() {
        val stack = NavBackStack(arrayOf<Route>(Route.Main(3), Route.PlaylistDetail(playlistId)))
        rule.setContent { PlaylistDetailPage(stack, vm, playlistId) }
        rule.waitUntilExactlyOneExists(hasText("Road Trip"), timeoutMillis = 10_000)
        rule.onNodeWithContentDescription("Delete").performClick()
        val remaining = runBlocking {
            withTimeout(5_000) { vm.playlists.first { list -> list.none { it.id == playlistId } } }
        }
        assertTrue(remaining.none { it.id == playlistId })
        assertEquals(Route.Main(3), stack.backStack.last())
    }

    @Test
    fun subscriptionFeedScreen_loadingShowsSkeletonsNotVideos() {
        rule.setContent {
            SubscriptionVideosScreen(
                SubscriptionFeedUiState(isLoading = true),
                SubscriptionFeedActions.Noop,
            )
        }
        rule.onNodeWithText("Feed Video").assertDoesNotExist()
    }

    @Test
    fun subscriptionFeedScreen_videoClickOpensAndProgressStateRenders() {
        var opened: Long? = null
        val actions = object : SubscriptionFeedActions {
            override fun openVideo(videoID: Long) { opened = videoID }
        }
        val row = VideoRowState(31, "Feed Video", "", "Chan", "2 views", null, "chan")
        rule.setContent {
            SubscriptionVideosScreen(
                SubscriptionFeedUiState(videos = listOf(row), fetchProgress = 0.5f),
                actions,
            )
        }
        rule.onNodeWithText("Feed Video").assertIsDisplayed()
        rule.onNodeWithText("Feed Video").performClick()
        assertEquals(31L, opened)
    }
}
