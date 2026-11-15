package com.vayunmathur.music.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.ManyManyMatching
import com.vayunmathur.music.FakeAndroidKeyStore
import com.vayunmathur.music.FakeMediaProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Room-backed half of [MusicRepository] — playlists and matchings —
 * against a real in-memory Room database (see [TestMusicRepository]).
 * MediaStore reads still come from [FakeMediaProvider], so the combined
 * matchings flow can be asserted end to end: derived album/artist pairs
 * plus stored playlist rows, with persisted album/artist rows filtered out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MusicRepositoryRoomTest {

    companion object {
        @JvmStatic
        @AfterClass
        fun tearDownClass() {
            // The fake provider + repository singleton outlive this class in
            // Robolectric's shared sandbox: switch the provider off and
            // republish an empty library for later classes.
            FakeMediaProvider.enabled = false
            val app = ApplicationProvider.getApplicationContext<Application>()
            runBlocking { MusicRepository.get(app).refresh() }
        }
    }


    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var repo: MusicRepository
    private lateinit var db: MusicDatabase

    @Before
    fun setUp() {
        FakeMediaProvider.enabled = true
        FakeAndroidKeyStore.install()
        ShadowContentResolver.registerProviderInternal("media", FakeMediaProvider())
        val (repository, database) = TestMusicRepository.install(app)
        repo = repository
        db = database
    }

    @Test
    fun playlistCrudRoundTripsThroughRoom() = runBlocking {
        assertTrue(repo.getAllPlaylists().isEmpty())
        val id = repo.upsertPlaylist(Playlist(name = "Road Mix"))
        assertTrue(id > 0, "upsert must return the generated id, was $id")
        repo.upsertPlaylist(Playlist(name = "Chill"))
        val all = repo.getAllPlaylists()
        assertEquals(listOf("Road Mix", "Chill"), all.map { it.name })
        // Renaming is an upsert with the same primary key.
        repo.upsertPlaylist(Playlist(id = id, name = "Road Mix 2"))
        assertEquals("Road Mix 2", repo.getAllPlaylists().first { it.id == id }.name)
        assertEquals(2, repo.getAllPlaylists().size)
        repo.deletePlaylistById(id)
        assertEquals(listOf("Chill"), repo.getAllPlaylists().map { it.name })
    }

    @Test
    fun playlistsFlowPublishesRoomContents() = runBlocking {
        repo.upsertPlaylist(Playlist(name = "Flow Mix"))
        val first = repo.playlists.first()
        assertEquals(listOf("Flow Mix"), first.map { it.name })
    }

    @Test
    fun matchingCrudIsDirectionalAndTyped() = runBlocking {
        repo.upsertMatching(ManyManyMatching(leftID = 1, rightID = 50, type = TYPE_MUSIC_PLAYLIST))
        repo.upsertMatching(ManyManyMatching(leftID = 2, rightID = 50, type = TYPE_MUSIC_PLAYLIST))
        repo.upsertMatching(ManyManyMatching(leftID = 1, rightID = 60, type = TYPE_MUSIC_PLAYLIST))
        repo.upsertMatchings(
            listOf(ManyManyMatching(leftID = 3, rightID = 50, type = TYPE_MUSIC_PLAYLIST)),
        )
        assertEquals(setOf(1L, 2L, 3L), repo.getFromRight(50, TYPE_MUSIC_PLAYLIST).toSet())
        assertEquals(setOf(50L, 60L), repo.getFromLeft(1, TYPE_MUSIC_PLAYLIST).toSet())
        // A different type namespace does not leak into playlist lookups.
        assertTrue(repo.getFromRight(50, TYPE_ALBUM_ARTIST).isEmpty())

        repo.deleteMatch(left = 1, right = 50, type = TYPE_MUSIC_PLAYLIST)
        assertEquals(setOf(2L, 3L), repo.getFromRight(50, TYPE_MUSIC_PLAYLIST).toSet())
        repo.deleteFromLeft(2, TYPE_MUSIC_PLAYLIST)
        assertEquals(setOf(3L), repo.getFromRight(50, TYPE_MUSIC_PLAYLIST).toSet())
        repo.deleteFromRight(60, TYPE_MUSIC_PLAYLIST)
        assertTrue(repo.getFromLeft(1, TYPE_MUSIC_PLAYLIST).isEmpty())
        repo.deleteByType(TYPE_MUSIC_PLAYLIST)
        assertTrue(repo.getFromRight(50, TYPE_MUSIC_PLAYLIST).isEmpty())
    }

    @Test
    fun matchingsFlowMergesDerivedPairsWithStoredPlaylistRowsOnly() = runBlocking {
        repo.refresh()
        // Stored playlist row (kept) + a stale persisted album/artist row
        // (must be filtered out by the repository's merge, see its KDoc).
        repo.upsertMatching(ManyManyMatching(leftID = 2, rightID = 77, type = TYPE_MUSIC_PLAYLIST))
        repo.upsertMatching(ManyManyMatching(leftID = 21, rightID = 11, type = TYPE_ALBUM_ARTIST))
        val merged = repo.matchings.first { merged -> merged.any { it.type == TYPE_MUSIC_PLAYLIST } }
        val playlistRows = merged.filter { it.type == TYPE_MUSIC_PLAYLIST }
        assertEquals(listOf(2L to 77L), playlistRows.map { it.leftID to it.rightID })
        // Derived pairs are recomputed from MediaStore, not read from Room:
        // album 21 (both Artist One songs) maps to artist 11 exactly once.
        val derived = merged.filter { it.type == TYPE_ALBUM_ARTIST }
        assertEquals(1, derived.count { it.leftID == 21L && it.rightID == 11L })
    }

    @Test
    fun backfillTagsAppliesCachedDurationsAndPrunesStaleRows() = runBlocking {
        repo.refresh()
        // Alpha Song (id 2) has year 0 in MediaStore: seed a tag-cache row
        // for it, plus a stale row for a song that no longer exists.
        val alpha = repo.getAllMusic().first { it.id == 2L }
        db.musicDao().upsertAll(
            listOf(
                alpha.copy(year = 2001, duration = 181_000),
                alpha.copy(id = 99, title = "Ghost", year = 1990),
            ),
        )
        repo.backfillTags()
        val after = repo.getAllMusic().first { it.id == 2L }
        assertEquals(2001, after.year)
        assertEquals(180_000, after.duration) // cached duration only fills a zero duration
        // The stale cache row was pruned by backfill.
        assertTrue(db.musicDao().getAll().none { it.id == 99L })
    }

    @Test
    fun clearMatchingsEmptiesEveryType() = runBlocking {
        repo.upsertMatching(ManyManyMatching(leftID = 1, rightID = 2, type = TYPE_MUSIC_PLAYLIST))
        repo.upsertMatching(ManyManyMatching(leftID = 3, rightID = 4, type = TYPE_ALBUM_ARTIST))
        repo.clearMatchings()
        assertTrue(repo.getFromRight(2, TYPE_MUSIC_PLAYLIST).isEmpty())
        assertTrue(repo.getFromRight(4, TYPE_ALBUM_ARTIST).isEmpty())
    }
}
