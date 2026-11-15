package com.vayunmathur.music.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.music.FakeAndroidKeyStore
import com.vayunmathur.music.Route
import com.vayunmathur.music.data.Music
import com.vayunmathur.music.data.MusicRepository
import com.vayunmathur.music.platform.MusicActions
import com.vayunmathur.music.platform.MusicViewModel
import com.vayunmathur.music.platform.PlaybackManager
import com.vayunmathur.music.platform.SongsUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SongsScreen over a longer list (rows below the fold are scrolled to, the
 * same lazy-composition constraint as the detail screens), plus the
 * NowPlayingQueue guard: with nothing loaded the queue panel renders nothing
 * instead of crashing on a missing now-playing state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SongsScreenExpandedTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun setUp() {
        FakeAndroidKeyStore.install()
        val app = ApplicationProvider.getApplicationContext<Application>()
        org.robolectric.Shadows.shadowOf(app).setComponentNameAndServiceForBindService(
            android.content.ComponentName(app, com.vayunmathur.music.service.PlaybackService::class.java),
            android.os.Binder(),
        )
    }

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun song(id: Long) = Music(
        id = id, title = "Song $id", artist = "Artist $id", artistId = id, album = "Album",
        albumId = 7, uri = "content://media/$id", duration = 100_000, trackNumber = id.toInt(), year = 2001,
    )

    private fun stack() = NavBackStack<Route>(arrayOf(Route.Home))

    private class RecordingActions : MusicActions {
        var played: Triple<List<Music>, Int, String?>? = null
        override fun playSong(songs: List<Music>, startWithIndex: Int, sourceId: String?, sourceName: String?) {
            played = Triple(songs, startWithIndex, sourceId)
        }
    }

    @Test
    fun longSongListPlaysTheScrolledToRowByIndex() {
        val actions = RecordingActions()
        val songs = (1L..12L).map { song(it) }
        var tapped = 0
        composeRule.setContent {
            SongsScreen(
                SongsUiState(songs = songs, playingSongId = 3L, rowOwnsSongKeys = true),
                actions, stack(),
                onSongTapped = { tapped++ },
            )
        }
        composeRule.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText("Song 12"))
        composeRule.onNodeWithText("Song 12").performClick()
        assertEquals(Triple(songs, 11, "all_songs"), actions.played)
        assertEquals(1, tapped)
    }

    @Test
    fun loadingSongListRendersNoRows() {
        composeRule.setContent {
            SongsScreen(
                SongsUiState(songs = emptyList(), loading = true),
                RecordingActions(), stack(),
            )
        }
        composeRule.onRoot().assertIsDisplayed()
        composeRule.onNodeWithText("Song 1").assertDoesNotExist()
    }

    @Test
    fun nowPlayingQueueRendersNothingWhileNothingIsLoaded() {
        val vm = MusicViewModel(app, MusicRepository.get(app), PlaybackManager.getInstance(app))
        composeRule.setContent { NowPlayingQueue(vm) }
        composeRule.onRoot().assertExists()
        composeRule.onNodeWithText("Song 1").assertDoesNotExist()
    }
}
