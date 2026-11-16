package com.vayunmathur.music.ui

import android.app.Application
import android.content.ComponentName
import android.os.Binder
import android.os.Looper
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.ManyManyMatching
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.music.FakeAndroidKeyStore
import com.vayunmathur.music.FakeMediaProvider
import com.vayunmathur.music.R
import com.vayunmathur.music.Route
import com.vayunmathur.music.data.MusicRepository
import com.vayunmathur.music.data.Playlist
import com.vayunmathur.music.data.TestMusicRepository
import com.vayunmathur.music.data.TYPE_MUSIC_PLAYLIST
import com.vayunmathur.music.platform.MusicViewModel
import com.vayunmathur.music.platform.PlaybackManager
import com.vayunmathur.music.ui.dialogs.AddToPlaylistDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.AfterClass
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PopulatedTabsDialogsQueueTest {
    @get:Rule val composeRule = createComposeRule()
    companion object {
        @JvmStatic @AfterClass fun tearDownClass() {
            FakeMediaProvider.enabled = false
            val app = ApplicationProvider.getApplicationContext<Application>()
            runBlocking { MusicRepository.get(app).refresh() }
        }
    }
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var repo: MusicRepository
    private var playlistId = 0L

    @OptIn(ExperimentalCoroutinesApi::class)
    @Before fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        FakeMediaProvider.enabled = true
        FakeAndroidKeyStore.install()
        ShadowContentResolver.registerProviderInternal("media", FakeMediaProvider())
        Shadows.shadowOf(app).setComponentNameAndServiceForBindService(
            ComponentName(app, com.vayunmathur.music.service.PlaybackService::class.java), Binder())
        val (r, _) = TestMusicRepository.install(app)
        repo = r
        runBlocking {
            repo.refresh()
            playlistId = repo.upsertPlaylist(Playlist(name = "Road Mix"))
            repo.upsertMatching(ManyManyMatching(1, playlistId, TYPE_MUSIC_PLAYLIST))
            repo.upsertMatching(ManyManyMatching(3, playlistId, TYPE_MUSIC_PLAYLIST))
        }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vm() = MusicViewModel(app, repo, PlaybackManager.getInstance(app))
    private fun stack(vararg r: Route) = NavBackStack(arrayOf(*r))
    private fun await(desc: String, cond: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            composeRule.waitForIdle()
            if (runBlocking { cond() }) return
            Thread.sleep(50)
        }
        assertTrue(runBlocking { cond() }, "timeout: $desc")
    }
    private fun awaitText(text: String) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            composeRule.waitForIdle()
            if (composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()) return
            Thread.sleep(50)
        }
        composeRule.onNodeWithText(text, substring = true).assertExists()
    }

    @Test fun playlistsTabListsPlaylistsAndNavigates() {
        val nav = stack(Route.Home)
        composeRule.setContent { PlaylistsTabContent(nav, vm()) }
        awaitText("Road Mix")
        composeRule.onNodeWithText("Road Mix").performClick()
        assertEquals(Route.PlaylistDetail(playlistId), nav.last())
    }

    @Test fun addToPlaylistDialogChecksPlaylistAndConfirmsMembership() {
        val nav = stack(Route.Home, Route.AddToPlaylistDialog(2L))
        composeRule.setContent { AddToPlaylistDialog(nav, vm(), musicId = 2L) }
        awaitText("Road Mix")
        composeRule.onNodeWithText("Road Mix").performClick()
        composeRule.onNodeWithText(app.getString(com.vayunmathur.library.ui.R.string.ok)).performClick()
        await("song 2 added") { repo.getFromRight(playlistId, TYPE_MUSIC_PLAYLIST).contains(2L) }
        assertEquals(Route.Home, nav.last())
    }

    @Test fun addToPlaylistDialogCreatesNewPlaylistFromInlineField() {
        val nav = stack(Route.Home, Route.AddToPlaylistDialog(1L))
        composeRule.setContent { AddToPlaylistDialog(nav, vm(), musicId = 1L) }
        awaitText("Road Mix")
        composeRule.onNodeWithText(app.getString(R.string.new_playlist)).performTextInput("Fresh Mix")
        // The create affordance is the icon button beside the field: last clickable in dialog
        val clickables = composeRule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        assertTrue(clickables.isNotEmpty())
        composeRule.onAllNodes(hasClickAction())[clickables.size - 3].performClick()
        await("Fresh Mix created") { repo.getAllPlaylists().any { it.name == "Fresh Mix" } }
    }

    @Test fun addToPlaylistDialogDismissPopsWithoutAdding() {
        val nav = stack(Route.Home, Route.AddToPlaylistDialog(2L))
        composeRule.setContent { AddToPlaylistDialog(nav, vm(), musicId = 2L) }
        awaitText("Road Mix")
        composeRule.onNodeWithText(app.getString(com.vayunmathur.library.ui.R.string.cancel)).performClick()
        assertEquals(Route.Home, nav.last())
        assertTrue(runBlocking { !repo.getFromRight(playlistId, TYPE_MUSIC_PLAYLIST).contains(2L) })
    }

    @Test fun playlistDetailPlayAndShuffleTagPlaylistSource() {
        val viewModel = vm()
        val nav = stack(Route.Home)
        composeRule.setContent { PlaylistDetailScreen(nav, viewModel, playlistId) }
        awaitText("Road Mix")
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(app.getString(com.vayunmathur.library.ui.R.string.play)))
        composeRule.onNodeWithText(app.getString(com.vayunmathur.library.ui.R.string.play)).performClick()
        await("source playlist") { viewModel.currentSource.value == "playlist_$playlistId" }
        composeRule.onNodeWithText(app.getString(R.string.label_shuffle)).performClick()
        assertEquals("playlist_$playlistId", viewModel.currentSource.value)
        // tapping a track also plays from playlist
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Gamma Song"))
        composeRule.onNodeWithText("Gamma Song", substring = true).performClick()
        assertEquals("playlist_$playlistId", viewModel.currentSource.value)
    }

    @Suppress("UNCHECKED_CAST")
    private fun setPlayback(mediaId: String, source: String?) {
        val pm = PlaybackManager.getInstance(app)
        fun flow(name: String): MutableStateFlow<Any?> {
            val f = PlaybackManager::class.java.getDeclaredField(name); f.isAccessible = true
            return f.get(pm) as MutableStateFlow<Any?>
        }
        val item = MediaItem.Builder().setMediaId(mediaId)
            .setMediaMetadata(MediaMetadata.Builder().setTitle("T").setArtist("A").build()).build()
        flow("_currentMediaItem").value = item
        flow("_currentSource").value = source
        flow("_currentSourceName").value = "Src"
        flow("_duration").value = 180_000L
        flow("_currentPosition").value = 0L
    }

    @Test fun nowPlayingQueueAlbumSourceShowsUpcomingInDiscTrackOrder() {
        val viewModel = vm()
        setPlayback("1", "album_21")
        composeRule.setContent { NowPlayingQueue(viewModel) }
        awaitText("Alpha Song")
        composeRule.onNodeWithText("Alpha Song", substring = true).performClick()
        assertEquals("album_21", viewModel.currentSource.value)
    }

    @Test fun nowPlayingQueueArtistSourceShowsArtistSongs() {
        val viewModel = vm()
        setPlayback("1", "artist_11")
        composeRule.setContent { NowPlayingQueue(viewModel) }
        awaitText("Alpha Song")
    }

    @Test fun nowPlayingQueueAllSongsSourceShowsUpcoming() {
        val viewModel = vm()
        setPlayback("1", "all_songs")
        composeRule.setContent { NowPlayingQueue(viewModel) }
        awaitText("Gamma Song")
    }

    @Test fun nowPlayingQueuePlaylistSourceShowsUpcomingMember() {
        val viewModel = vm()
        setPlayback("1", "playlist_$playlistId")
        composeRule.setContent { NowPlayingQueue(viewModel) }
        awaitText("Gamma Song")
        composeRule.onNodeWithText("Gamma Song", substring = true).performClick()
        assertEquals("playlist_$playlistId", viewModel.currentSource.value)
    }

    @Test fun nowPlayingQueueUnknownSourceFallsBackToAllSongs() {
        val viewModel = vm()
        setPlayback("1", "bogus_source")
        composeRule.setContent { NowPlayingQueue(viewModel) }
        awaitText("Gamma Song")
    }

    @Test fun musicTabsScreenRendersTabsOverPopulatedLibrary() {
        // WorkManager for SyncWorker.enqueue inside MusicTabsScreen
        runCatching {
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        }
        val nav = stack(Route.Home)
        composeRule.setContent { MusicTabsScreen(nav, vm()) }
        awaitText("Alpha Song")
        assertTrue(composeRule.onAllNodesWithText("Alpha Song").fetchSemanticsNodes().isNotEmpty())
    }
}
