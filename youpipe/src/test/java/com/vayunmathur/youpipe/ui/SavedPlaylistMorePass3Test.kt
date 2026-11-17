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
import com.vayunmathur.youpipe.platform.SubscriptionFeedActions
import com.vayunmathur.youpipe.platform.SubscriptionFeedUiState
import com.vayunmathur.youpipe.platform.VideoRowState
import com.vayunmathur.youpipe.platform.YouPipeViewModel
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
    private val vmStore = androidx.lifecycle.ViewModelStore()
    private lateinit var backStack: NavBackStack<Route>
    private var playlistId: Long = -1

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @BeforeTest
    fun setUp() {
        runCatching { WorkManager.initialize(app, Configuration.Builder().build()) }
        db = Room.inMemoryDatabaseBuilder(app, SubscriptionDatabase::class.java)
            .allowMainThreadQueries().build()
        vm = YouPipeViewModel(app, SubscriptionRepository(app, db))
        vmStore.put("vm", vm)
        backStack = NavBackStack(arrayOf<Route>(Route.Saved))
        runBlocking { playlistId = db.playlistDao().upsert(Playlist(name = "Road Trip")) }
    }

    @AfterTest
    fun tearDown() {
        // Cancel viewModelScope before db.close(): background Room
        // queries against the closed DB ("Database is closed" /
        // connection-pool-closed) otherwise leak into later tests.
        vmStore.clear()
        db.close()
    }

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
        val stack = NavBackStack(arrayOf<Route>(Route.Saved, Route.PlaylistDetail(playlistId)))
        rule.setContent { PlaylistDetailPage(stack, vm, playlistId) }
        rule.waitUntilExactlyOneExists(hasText("Road Trip"), timeoutMillis = 10_000)
        rule.onNodeWithContentDescription("Delete").performClick()
        val remaining = runBlocking {
            withTimeout(20_000) { vm.playlists.first { list -> list.none { it.id == playlistId } } }
        }
        assertTrue(remaining.none { it.id == playlistId })
        // The pop follows the delete on the main thread; under load it can
        // land after the flow emission above, so await it (pumping
        // Robolectric's paused main looper) instead of asserting at once.
        rule.waitUntil(15_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            stack.backStack.lastOrNull() == Route.Saved
        }
        assertEquals(Route.Saved, stack.backStack.last())
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
