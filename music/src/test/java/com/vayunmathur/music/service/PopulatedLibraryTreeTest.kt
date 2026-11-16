package com.vayunmathur.music.service

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.ManyManyMatching
import com.vayunmathur.music.FakeAndroidKeyStore
import com.vayunmathur.music.FakeMediaProvider
import com.vayunmathur.music.data.MusicRepository
import com.vayunmathur.music.data.Playlist
import com.vayunmathur.music.data.TestMusicRepository
import com.vayunmathur.music.data.TYPE_MUSIC_PLAYLIST
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PopulatedLibraryTreeTest {
    companion object {
        @JvmStatic @AfterClass fun tearDownClass() {
            FakeMediaProvider.enabled = false
            val app = ApplicationProvider.getApplicationContext<Context>()
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
        val (r, _) = TestMusicRepository.install(app)
        repo = r
        runBlocking {
            repo.refresh()
            playlistId = repo.upsertPlaylist(Playlist(name = "Road Mix"))
            repo.upsertMatching(ManyManyMatching(3, playlistId, TYPE_MUSIC_PLAYLIST))
            repo.upsertMatching(ManyManyMatching(1, playlistId, TYPE_MUSIC_PLAYLIST))
        }
    }

    private fun populatedTree(): MusicLibraryTree {
        val tree = MusicLibraryTree(app)
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            if (tree.children(MusicLibraryTree.TAB_SONGS).size == 3 &&
                tree.children(MusicLibraryTree.TAB_PLAYLISTS).size == 1) return tree
            Thread.sleep(50)
        }
        return tree
    }

    @Test fun populatedTabsNodesAndItems() {
        val tree = populatedTree()
        try {
            assertEquals(listOf("Alpha Song", "Beta Song", "Gamma Song"),
                tree.children(MusicLibraryTree.TAB_SONGS).map { it.mediaMetadata.title.toString() })
            assertEquals(listOf("Album One", "Album Two"),
                tree.children(MusicLibraryTree.TAB_ALBUMS).map { it.mediaMetadata.title.toString() })
            assertEquals(2, tree.children(MusicLibraryTree.TAB_ARTISTS).size)
            assertEquals(listOf("Road Mix"),
                tree.children(MusicLibraryTree.TAB_PLAYLISTS).map { it.mediaMetadata.title.toString() })
            // Playlist songs follow library order filtered by membership (ids 1 and 3)
            assertEquals(listOf("1", "3"),
                tree.children("playlist_$playlistId").map { it.mediaId })
            // Album songs sorted disc/track: Beta disc1 track2, Alpha disc2 track5
            assertEquals(listOf("1", "2"), tree.children("album_21").map { it.mediaId })
            assertEquals(listOf("2", "1"), tree.children("artist_11").map { it.mediaId }) // title order
            assertEquals(listOf("3"), tree.children("album_22").map { it.mediaId })
            assertEquals(emptyList(), tree.children("album_999"))
            assertEquals(emptyList(), tree.children("artist_x"))
            assertEquals(3, tree.childCount(MusicLibraryTree.TAB_SONGS))

            val songItem = tree.item("1")
            assertNotNull(songItem); assertEquals("Beta Song", songItem.mediaMetadata.title.toString())
            assertEquals(true, songItem.mediaMetadata.isPlayable)
            assertEquals("Album One", tree.item("album_21")?.mediaMetadata?.title.toString())
            assertEquals("Artist One", tree.item("artist_11")?.mediaMetadata?.title.toString())
            assertEquals("Road Mix", tree.item("playlist_$playlistId")?.mediaMetadata?.title.toString())
            assertNull(tree.item("album_999")); assertNull(tree.item("999"))

            val playable = tree.resolveForPlayback("2")
            assertNotNull(playable)
            assertEquals("content://media/external/audio/media/2", playable.localConfiguration?.uri.toString())
            assertNull(tree.resolveForPlayback("album_21"))
        } finally { tree.release() }
    }

    @Test fun populatedSearchByTitleAlbumArtistAndPlaylist() {
        val tree = populatedTree()
        try {
            assertEquals(listOf("2"), tree.searchPlayableSongs("alpha").map { it.mediaId })
            assertEquals(listOf("1", "2"), tree.searchPlayableSongs("Album One").map { it.mediaId })
            assertEquals(listOf("2", "1"), tree.searchPlayableSongs("Artist One").map { it.mediaId })
            assertEquals(listOf("1", "3"), tree.searchPlayableSongs("Road Mix").map { it.mediaId })
            assertEquals(emptyList(), tree.searchPlayableSongs("no such thing"))
            val results = tree.searchResults("song")
            assertEquals(3, results.size)
            assertTrue(results.all { it.mediaMetadata.title.toString().contains("Song") })
            val albumResults = tree.searchResults("Album One")
            assertTrue(albumResults.any { it.mediaId == "album_21" })
            assertTrue(tree.searchResults("Artist").any { it.mediaId == "artist_11" })
            assertTrue(tree.searchResults("Road").any { it.mediaId == "playlist_$playlistId" })
            assertEquals(emptyList(), tree.searchResults("zzz"))
        } finally { tree.release() }
    }

    @Test fun recentsAreMostRecentFirstDedupedAndCappedToLibrarySongs() {
        val tree = populatedTree()
        try {
            var notices = 0
            tree.onLibraryChanged = { notices++ }
            tree.markPlayed(listOf("1", "2"))
            tree.markPlayed(listOf("3", "1"))
            assertEquals(listOf("1", "3", "2"), tree.children(MusicLibraryTree.TAB_RECENT).map { it.mediaId })
            assertTrue(notices >= 2)
            // many ids: only existing songs show, newest first
            tree.markPlayed((10L..70L).map { it.toString() } + listOf("2"))
            assertEquals("2", tree.children(MusicLibraryTree.TAB_RECENT).first().mediaId)
        } finally { tree.release() }
    }
}
