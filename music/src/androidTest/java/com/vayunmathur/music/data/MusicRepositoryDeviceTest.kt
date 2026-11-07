package com.vayunmathur.music.data

import androidx.test.platform.app.InstrumentationRegistry
import com.vayunmathur.library.util.ManyManyMatching
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Repository Room side on a real device: SQLCipher's native library cannot
 * load under Robolectric, so playlist/matching CRUD runs here.
 */
class MusicRepositoryDeviceTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun repo(): MusicRepository = MusicRepository.get(context)

    @Test
    fun playlistAndMatchingCrudRoundTrip() = runBlocking {
        val repo = repo()
        val playlistId = repo.upsertPlaylist(Playlist(name = "Gym Device"))
        assertTrue(playlistId > 0)
        assertTrue(repo.getAllPlaylists().any { it.id == playlistId && it.name == "Gym Device" })
        assertTrue(repo.playlists.first().any { it.id == playlistId })

        repo.upsertMatching(ManyManyMatching(11L, playlistId, TYPE_MUSIC_PLAYLIST))
        repo.upsertMatchings(
            listOf(
                ManyManyMatching(12L, playlistId, TYPE_MUSIC_PLAYLIST),
                ManyManyMatching(13L, playlistId, TYPE_ALBUM_ARTIST),
            ),
        )
        assertEquals(
            listOf(11L, 12L),
            repo.getFromRight(playlistId, TYPE_MUSIC_PLAYLIST).sorted(),
        )
        assertEquals(listOf(playlistId), repo.getFromLeft(11L, TYPE_MUSIC_PLAYLIST))
        // Only playlist-type matchings surface in the combined flow's Room side.
        assertTrue(
            repo.matchings.first().all { it.type == TYPE_MUSIC_PLAYLIST },
        )

        repo.deleteMatch(11L, playlistId, TYPE_MUSIC_PLAYLIST)
        assertEquals(listOf(12L), repo.getFromRight(playlistId, TYPE_MUSIC_PLAYLIST))
        repo.deleteFromLeft(12L, TYPE_MUSIC_PLAYLIST)
        assertEquals(emptyList<Long>(), repo.getFromRight(playlistId, TYPE_MUSIC_PLAYLIST))
        repo.deleteFromRight(playlistId, TYPE_ALBUM_ARTIST)
        assertEquals(emptyList<Long>(), repo.getFromRight(playlistId, TYPE_ALBUM_ARTIST))

        repo.upsertMatching(ManyManyMatching(21L, playlistId, TYPE_MUSIC_PLAYLIST))
        repo.deleteByType(TYPE_MUSIC_PLAYLIST)
        assertEquals(emptyList<Long>(), repo.getFromRight(playlistId, TYPE_MUSIC_PLAYLIST))

        repo.upsertMatching(ManyManyMatching(22L, playlistId, TYPE_MUSIC_PLAYLIST))
        repo.clearMatchings()
        assertEquals(emptyList<Long>(), repo.getFromRight(playlistId, TYPE_MUSIC_PLAYLIST))

        repo.deletePlaylistById(playlistId)
        assertTrue(repo.getAllPlaylists().none { it.id == playlistId })
    }

    @Test
    fun backfillWithEmptyLibraryIsNoOp() = runBlocking {
        val repo = repo()
        repo.refresh()
        repo.backfillTags()
        assertEquals(emptyList<Any>(), repo.getAllMusic())
    }

    @Test
    fun renameViaUpsertKeepsOnePlaylist() = runBlocking {
        val repo = repo()
        val id = repo.upsertPlaylist(Playlist(name = "Before Device"))
        repo.upsertPlaylist(Playlist(id = id, name = "After Device"))
        val mine = repo.getAllPlaylists().filter { it.id == id }
        assertEquals(1, mine.size)
        assertEquals("After Device", mine.single().name)
        repo.deletePlaylistById(id)
    }
}
