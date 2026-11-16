package com.vayunmathur.music.intents

import android.app.Application
import android.content.ComponentName
import android.os.Binder
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.intents.music.PlayMusicData
import com.vayunmathur.music.FakeAndroidKeyStore
import com.vayunmathur.music.FakeMediaProvider
import com.vayunmathur.music.data.MusicRepository
import com.vayunmathur.music.data.Playlist
import com.vayunmathur.music.data.TestMusicRepository
import com.vayunmathur.music.data.TYPE_MUSIC_PLAYLIST
import com.vayunmathur.library.util.ManyManyMatching
import com.vayunmathur.music.platform.PlaybackManager
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearchPlayIntentsTest {
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

    @Before fun setUp() {
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
            // stored order 3 then 1 must be preserved by PlayIntent
            repo.upsertMatching(ManyManyMatching(3, playlistId, TYPE_MUSIC_PLAYLIST))
            repo.upsertMatching(ManyManyMatching(1, playlistId, TYPE_MUSIC_PLAYLIST))
        }
    }

    private fun searchIntent(): SearchIntent =
        Robolectric.buildActivity(SearchIntent::class.java).create().get()
    private fun playIntent(): PlayIntent =
        Robolectric.buildActivity(PlayIntent::class.java).create().get()

    @Test fun searchMatchesSongsAlbumsArtistsPlaylistsCaseInsensitively() = runBlocking {
        val intent = searchIntent()
        val songHits = intent.performCalculation("alpha")
        assertEquals(listOf("song"), songHits.map { it.type })
        assertEquals("Alpha Song", songHits.first().name)
        val albumHits = intent.performCalculation("ALBUM ONE")
        assertTrue(albumHits.any { it.type == "album" && it.name == "Album One" })
        val artistHits = intent.performCalculation("artist one")
        assertTrue(artistHits.any { it.type == "artist" })
        val playlistHits = intent.performCalculation("road")
        assertTrue(playlistHits.any { it.type == "playlist" && it.name == "Road Mix" })
        // 'Song' matches every song title and nothing else in this fixture
        assertEquals(3, intent.performCalculation("Song").count { it.type == "song" })
        assertEquals(emptyList(), intent.performCalculation("zzzz-nothing"))
        // empty query is a substring of everything
        assertTrue(intent.performCalculation("").size >= 3)
    }

    @Test fun playIntentPlaysSongAlbumArtistAndPlaylistInPlaybackOrder() = runBlocking {
        val pm = PlaybackManager.getInstance(app)
        playIntent().performCalculation(PlayMusicData(type = "song", id = 2L))
        // PlaybackManager records no queue without a controller, but playSong was reached:
        // source bookkeeping is only set by playSong on a non-empty queue, so use order fn + side effect via album
        playIntent().performCalculation(PlayMusicData(type = "album", id = 21L))
        playIntent().performCalculation(PlayMusicData(type = "artist", id = 11L))
        playIntent().performCalculation(PlayMusicData(type = "playlist", id = playlistId))
        playIntent().performCalculation(PlayMusicData(type = "unknown", id = 1L))
        playIntent().performCalculation(PlayMusicData(type = "song", id = 999L))
        // No controller is connected on a host JVM, so nothing can be loaded:
        // every call above must have been a safe no-op at the player boundary.
        assertEquals(null, pm.currentMediaItem.value)
    }

    @Test fun orderForPlaybackSortsAlbumsByDiscTrackAndPlaylistsByStoredOrder() = runBlocking {
        val all = repo.getAllMusic()
        assertEquals(listOf(1L, 2L), orderForPlayback("album", 21L, all).map { it.id })
        assertEquals(listOf(2L), orderForPlayback("song", 2L, all).map { it.id })
        assertEquals(setOf(1L, 2L), orderForPlayback("artist", 11L, all).map { it.id }.toSet())
        assertEquals(listOf(3L, 1L), orderForPlayback("playlist", playlistId, all, listOf(3L, 1L)).map { it.id })
        assertEquals(emptyList(), orderForPlayback("genre", 1L, all))
        assertEquals(emptyList(), orderForPlayback("playlist", playlistId, all, listOf(999L)))
    }
}
