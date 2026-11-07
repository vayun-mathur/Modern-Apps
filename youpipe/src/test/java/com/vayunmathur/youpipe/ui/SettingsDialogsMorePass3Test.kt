package com.vayunmathur.youpipe.ui

import android.app.Application
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
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
import com.vayunmathur.youpipe.ui.dialogs.AddToPlaylist
import com.vayunmathur.youpipe.util.YouPipeViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Shadows

/** Slice 2/3 pass 3: language selector and the loaded add-to-playlist dialog. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SettingsDialogsMorePass3Test {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var db: SubscriptionDatabase
    private lateinit var vm: YouPipeViewModel
    private var playlistId: Long = -1

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @BeforeTest
    fun setUp() {
        runCatching { WorkManager.initialize(app, Configuration.Builder().build()) }
        db = Room.inMemoryDatabaseBuilder(app, SubscriptionDatabase::class.java)
            .allowMainThreadQueries().build()
        vm = YouPipeViewModel(app, SubscriptionRepository(app, db))
        runBlocking { playlistId = db.playlistDao().upsert(Playlist(name = "Music Mix")) }
        vm.videoStateMutable.value = YouPipeViewModel.VideoState(
            data = VideoData(
                title = "Dialog Video", views = 10, duration = 90,
                uploadDate = Instant.fromEpochSeconds(1_700_000_000),
                thumbnailURL = "", author = "Chan", authorURL = "UC1", authorThumbnail = "",
                description = "desc",
            ),
        )
    }

    @AfterTest
    fun tearDown() {
        // Not closed: ViewModel flow collectors outlive the test on the paused
        // main looper and crash the next test if they land on a closed DB.
    }

    private fun <T> awaitFlow(flow: Flow<T>, ready: (T) -> Boolean): T {
        var result: T? = null
        val job = CoroutineScope(Dispatchers.IO).launch {
            flow.collect { value -> if (ready(value)) result = value }
        }
        val deadline = System.currentTimeMillis() + 10_000
        while (result == null && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        job.cancel()
        return result ?: throw AssertionError("flow never became ready")
    }

    @Test
    fun languageSelector_writesSelectedLanguage() {
        val stack = NavBackStack(arrayOf<Route>(Route.Main(4)))
        rule.setContent { GeneralSettingsPage(stack, vm) }
        val label = app.getString(R.string.label_youtube_language)
        rule.waitUntilExactlyOneExists(hasText(label), timeoutMillis = 10_000)
        runCatching { rule.onNode(hasScrollAction()).performScrollToNode(hasText(label)) }
        rule.onNodeWithText(label).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Deutsch").performClick()
        assertEquals("de", awaitFlow(vm.youtubeLanguage) { it == "de" })
    }

    @Test
    fun addToPlaylist_checkAndConfirmAddsVideo() {
        val stack = NavBackStack(arrayOf<Route>(Route.Main(0), Route.AddToPlaylist(99)))
        rule.setContent { AddToPlaylist(stack, vm, videoID = 99) }
        rule.waitUntilExactlyOneExists(hasText("Music Mix"), timeoutMillis = 10_000)
        rule.onNodeWithText("Music Mix").performClick()
        rule.onNodeWithText(app.getString(android.R.string.ok)).performClick()
        val items = runBlocking {
            withTimeout(5_000) { vm.playlistItemsFor(playlistId).first { it.isNotEmpty() } }
        }
        assertEquals(listOf(99L), items.map { it.videoItem.videoID })
        assertEquals(Route.Main(0), stack.backStack.last())
    }

    @Test
    fun addToPlaylist_createFieldMakesPlaylistWithVideo() {
        val stack = NavBackStack(arrayOf<Route>(Route.Main(0), Route.AddToPlaylist(99)))
        rule.setContent { AddToPlaylist(stack, vm, videoID = 99) }
        rule.waitUntilExactlyOneExists(hasText("Music Mix"), timeoutMillis = 10_000)
        rule.onAllNodes(hasSetTextAction())[0].performTextInput("Fresh Mix")
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Add").performClick()
        val playlists = awaitFlow(vm.playlists) { list -> list.any { it.name == "Fresh Mix" } }
        val fresh = playlists.first { it.name == "Fresh Mix" }
        val items = runBlocking {
            withTimeout(5_000) { vm.playlistItemsFor(fresh.id).first { it.isNotEmpty() } }
        }
        assertTrue(items.any { it.videoItem.videoID == 99L })
    }
}
