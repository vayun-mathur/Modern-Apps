package com.vayunmathur.music.platform

import android.app.Application
import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.music.FakeAndroidKeyStore
import com.vayunmathur.music.FakeMediaProvider
import com.vayunmathur.music.data.MusicRepository
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MusicViewModel against the populated fake library (see [FakeMediaProvider]):
 * per-entity lookup over the in-memory flows, and delegation of every transport
 * call to PlaybackManager. Anything Room-backed is deliberately not exercised:
 * playlistState/playlists and the matched*For* accessors collect the Room
 * playlist/matching flows, whose SQLCipher nativeOpen throws UnsatisfiedLinkError
 * on a host JVM (verified in the verification run), so those paths — and
 * playlist CRUD — run on device only. This mirrors PopulatedLibraryTest, which
 * only ever touches music/album/artist state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MusicViewModelExpandedTest {

    @get:Rule
    val composeRule = createComposeRule()

    companion object {
        @JvmStatic
        @AfterClass
        fun tearDownClass() {
            FakeMediaProvider.enabled = false
            val app = ApplicationProvider.getApplicationContext<Context>()
            runBlocking { MusicRepository.get(app).refresh() }
        }
    }

    @Before
    fun setUp() {
        FakeMediaProvider.enabled = true
        FakeAndroidKeyStore.install()
        ShadowContentResolver.registerProviderInternal("media", FakeMediaProvider())
        val app = ApplicationProvider.getApplicationContext<Application>()
        org.robolectric.Shadows.shadowOf(app).setComponentNameAndServiceForBindService(
            android.content.ComponentName(app, com.vayunmathur.music.service.PlaybackService::class.java),
            android.os.Binder(),
        )
        runBlocking { MusicRepository.get(app).refresh() }
    }

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun viewModel(): MusicViewModel =
        MusicViewModel(app, MusicRepository.get(app), PlaybackManager.getInstance(app))

    @Test
    fun `entity states resolve populated songs albums and artists`() {
        val vm = viewModel()
        var songTitle: String? = null
        var albumName: String? = null
        var unknownArtistName: String? = "sentinel"
        var missingSong: Any? = "sentinel"
        composeRule.setContent {
            val song by vm.musicState(1L)
            val album by vm.albumState(21L)
            val artist by vm.artistState(12L)
            val missing by vm.musicState(999L)
            songTitle = song?.title
            albumName = album?.name
            unknownArtistName = artist?.name
            missingSong = missing
        }
        assertEquals("Beta Song", songTitle)
        assertEquals("Album One", albumName)
        // Artist 12 is MediaStore's <unknown> sentinel, normalised to blank.
        assertEquals("", unknownArtistName)
        assertNull(missingSong)
    }

    @Test
    fun `entity flows publish the populated library to subscribers`() {
        val vm = viewModel()
        var songTitles: List<String> = emptyList()
        var albumNames: List<String> = emptyList()
        var artistNames: List<String> = emptyList()
        var loaded = false
        composeRule.setContent {
            val songs by vm.music.collectAsState()
            val albums by vm.albums.collectAsState()
            val artists by vm.artists.collectAsState()
            val isLoaded by vm.loaded.collectAsState()
            songTitles = songs.map { it.title }
            albumNames = albums.map { it.name }
            artistNames = artists.map { it.name }
            loaded = isLoaded
        }
        assertEquals(listOf("Alpha Song", "Beta Song", "Gamma Song"), songTitles.sorted())
        assertEquals(listOf("Album One", "Album Two"), albumNames.sorted())
        assertEquals(listOf("", "Artist One"), artistNames.sorted())
        assertTrue(loaded)
    }

    @Test
    fun `playback calls delegate to the playback manager bookkeeping`() {
        val vm = viewModel()
        val pm = PlaybackManager.getInstance(app)
        val songs = runBlocking { MusicRepository.get(app).getAllMusic() }
        assertEquals(3, songs.size)

        vm.playSong(songs, 2, sourceId = "artist_11", sourceName = "Artist One")
        assertEquals("artist_11", pm.currentSource.value)
        assertEquals("Artist One", pm.currentSourceName.value)
        assertEquals("artist_11", vm.currentSource.value)

        vm.playShuffled(songs, sourceId = "all_songs", sourceName = "All Songs")
        assertEquals("all_songs", pm.currentSource.value)

        // Invalid queues must not clobber the recorded source (PlaybackManager guard).
        vm.playSong(emptyList(), 0, sourceId = "album_99", sourceName = "Nope")
        assertEquals("all_songs", pm.currentSource.value)
        vm.playSong(songs, 99, sourceId = "album_99", sourceName = "Nope")
        assertEquals("all_songs", pm.currentSource.value)

        // Transport without a connected controller is a safe no-op, except seek,
        // which still records the requested position for the UI.
        vm.togglePlayPause()
        vm.skipNext()
        vm.skipPrevious()
        vm.toggleShuffle()
        vm.toggleRepeat()
        vm.seekTo(12_345L)
        assertEquals(12_345L, vm.currentPosition.value)
        vm.seekTo(0L)
        assertEquals(0L, vm.currentPosition.value)
    }

    @Test
    fun `now playing and playing song stay empty while nothing is loaded`() {
        val vm = viewModel()
        var nowPlaying: NowPlayingUiState? = NowPlayingUiState(title = "sentinel")
        var playingId: Long? = 7L
        composeRule.setContent {
            nowPlaying = vm.nowPlayingState()
            playingId = vm.playingSongIdFrom("album_21")
        }
        assertNull(nowPlaying)
        assertNull(playingId)
        assertTrue(vm.music.value.isEmpty() || vm.music.value.size == 3)
    }
}
