package com.vayunmathur.music.platform

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.music.data.Music
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * PlaybackManager without a connected controller (Robolectric binds no
 * session service): queue-source bookkeeping must still happen, transport
 * calls must be safe no-ops, and invalid queues must change nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackManagerTest {

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        org.robolectric.Shadows.shadowOf(app).setComponentNameAndServiceForBindService(
            android.content.ComponentName(
                app,
                com.vayunmathur.music.service.PlaybackService::class.java,
            ),
            android.os.Binder(),
        )
    }

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private fun manager(): PlaybackManager = PlaybackManager.getInstance(app)

    private fun song(id: Long) = Music(
        id = id, title = "Song $id", artist = "Artist", artistId = 1, album = "Album",
        albumId = 7, uri = "content://media/$id", duration = 180_000, trackNumber = 1, year = 2020,
    )

    @Test
    fun `singleton and initial observable state`() {
        val pm = manager()
        assertSame(pm, manager())
        assertFalse(pm.isPlaying.value)
        assertNull(pm.player.value)
        assertNull(pm.currentMediaItem.value)
    }

    @Test
    fun `playSong records the queue source without a controller`() {
        val pm = manager()
        pm.playSong(listOf(song(1), song(2)), 1, sourceId = "album_7", sourceName = "Seven")
        assertEquals("album_7", pm.currentSource.value)
        assertEquals("Seven", pm.currentSourceName.value)
        // Nothing can actually load: the observable item stays empty.
        assertNull(pm.currentMediaItem.value)
        assertFalse(pm.isPlaying.value)
    }

    @Test
    fun `playSong rejects empty queues and bad indices without touching the source`() {
        val pm = manager()
        pm.playSong(listOf(song(1)), 0, sourceId = "album_1", sourceName = "One")
        assertEquals("album_1", pm.currentSource.value)
        pm.playSong(emptyList(), 0, sourceId = "album_2", sourceName = "Two")
        assertEquals("album_1", pm.currentSource.value)
        pm.playSong(listOf(song(1)), 5, sourceId = "album_3", sourceName = "Three")
        assertEquals("album_1", pm.currentSource.value)
        pm.playSong(listOf(song(1)), -1, sourceId = "album_4", sourceName = "Four")
        assertEquals("album_1", pm.currentSource.value)
    }

    @Test
    fun `playShuffled records the source and rejects empty queues`() {
        val pm = manager()
        pm.playShuffled(listOf(song(1), song(2), song(3)), sourceId = "all_songs", sourceName = "All")
        assertEquals("all_songs", pm.currentSource.value)
        pm.playShuffled(emptyList(), sourceId = "playlist_9", sourceName = "Nine")
        assertEquals("all_songs", pm.currentSource.value)
    }

    @Test
    fun `transport calls are safe without a controller and seek still tracks position`() {
        val pm = manager()
        pm.togglePlayPause()
        pm.skipNext()
        pm.skipPrevious()
        pm.toggleShuffle()
        pm.toggleRepeat()
        assertFalse(pm.shuffleMode.value)
        pm.seekTo(42_000L)
        assertEquals(42_000L, pm.currentPosition.value)
        pm.seekTo(0L)
        assertEquals(0L, pm.currentPosition.value)
    }
}
