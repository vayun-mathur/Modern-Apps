package com.vayunmathur.music.intents

import com.vayunmathur.music.data.Music
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the playback order of assistant "play X" queues: albums play in
 * disc/track order (not the library's title order) and playlists play in
 * their stored matching order.
 */
class PlayIntentOrderTest {

    private fun song(
        id: Long,
        title: String,
        albumId: Long = 7,
        disc: Int = 1,
        track: Int = 0,
    ) = Music(
        id = id, title = title, artist = "Artist", artistId = 1, album = "Album",
        albumId = albumId, uri = "content://media/$id", duration = 180_000,
        trackNumber = track, year = 2020, discNumber = disc,
    )

    // Title-sorted, as the library hands it over: B-side before the opener.
    private val library = listOf(
        song(3, "Bravo", disc = 1, track = 2),
        song(2, "Alpha", disc = 2, track = 1),
        song(1, "Zulu", disc = 1, track = 1),
        song(9, "Other album", albumId = 8),
    )

    @Test
    fun albumPlaysInDiscTrackOrderNotTitleOrder() {
        val queue = orderForPlayback("album", 7, library)
        assertEquals(listOf(1L, 3L, 2L), queue.map { it.id })
    }

    @Test
    fun playlistPlaysInStoredMatchingOrder() {
        val queue = orderForPlayback("playlist", 5, library, playlistIds = listOf(2L, 1L))
        assertEquals(listOf(2L, 1L), queue.map { it.id })
    }

    @Test
    fun playlistSkipsIdsNoLongerInTheLibrary() {
        val queue = orderForPlayback("playlist", 5, library, playlistIds = listOf(99L, 1L))
        assertEquals(listOf(1L), queue.map { it.id })
    }

    @Test
    fun songAndUnknownTypesAreUnchanged() {
        assertEquals(listOf(9L), orderForPlayback("song", 9, library).map { it.id })
        assertTrue(orderForPlayback("genre", 1, library).isEmpty())
    }
}
