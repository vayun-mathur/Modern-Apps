package com.vayunmathur.youpipe.ui

import android.app.Application
import androidx.compose.ui.test.ExperimentalTestApi
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
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.Route
import com.vayunmathur.youpipe.data.Playlist
import com.vayunmathur.youpipe.data.PlaylistItem
import com.vayunmathur.youpipe.data.SubscriptionDatabase
import com.vayunmathur.youpipe.data.SubscriptionRepository
import com.vayunmathur.youpipe.util.YouPipeViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.runner.RunWith

/** Slice 2/3 pass 3: playlist detail - navigate, select, remove. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class PlaylistInteractionsPass3Test {

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
        runBlocking {
            playlistId = db.playlistDao().upsert(Playlist(name = "Road Trip"))
            db.playlistItemDao().upsertAll(
                listOf(
                    PlaylistItem(
                        playlistId = playlistId,
                        videoItem = VideoInfo("Song One", 71, 100, 9, Instant.fromEpochSeconds(1_600_000_000), "", "Chan"),
                        position = 1.0, timestamp = Instant.fromEpochSeconds(1_700_100_000),
                    ),
                    PlaylistItem(
                        playlistId = playlistId,
                        videoItem = VideoInfo("Song Two", 72, 100, 9, Instant.fromEpochSeconds(1_600_000_000), "", "Chan"),
                        position = 2.0, timestamp = Instant.fromEpochSeconds(1_700_200_000),
                    ),
                ),
            )
        }
    }

    @AfterTest
    fun tearDown() = db.close()

    @Test
    fun itemClick_navigatesToVideo() {
        rule.setContent { PlaylistDetailPage(backStack, vm, playlistId) }
        rule.waitUntilExactlyOneExists(hasText("Road Trip"), timeoutMillis = 10_000)
        rule.waitUntilExactlyOneExists(hasText("Song One"), timeoutMillis = 10_000)
        rule.onNodeWithText("Song One").performClick()
        rule.waitForIdle()
        assertEquals(Route.VideoPage(71), backStack.backStack.last())
    }

    @Test
    fun longPressSelectAndDelete_removesItem() {
        rule.setContent { PlaylistDetailPage(backStack, vm, playlistId) }
        rule.waitUntilExactlyOneExists(hasText("Song One"), timeoutMillis = 10_000)
        rule.onNodeWithText("Song One").performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNodeWithText("Song Two").performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Delete").performClick()
        rule.waitUntilDoesNotExist(hasText("Song One"), timeoutMillis = 10_000)
        rule.waitUntilDoesNotExist(hasText("Song Two"), timeoutMillis = 10_000)
        val remaining = runBlocking {
            withTimeout(5_000) { vm.playlistItemsFor(playlistId).first { it.isEmpty() } }
        }
        assertEquals(emptyList(), remaining)
    }
}
