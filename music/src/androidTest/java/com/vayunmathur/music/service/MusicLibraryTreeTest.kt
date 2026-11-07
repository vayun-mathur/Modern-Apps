package com.vayunmathur.music.service

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Auto browse tree over an empty library: the tab skeleton must be
 * complete and stable, node lookups for unknown ids must resolve to
 * nothing, and the recently-played bookkeeping must behave. Song-level
 * content needs a populated snapshot, which arrives from MediaStore.
 * On device, because the tree's snapshot collector reads the SQLCipher
 * playlist tables whose native library cannot load under Robolectric.
 */
class MusicLibraryTreeTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun tree(): MusicLibraryTree = MusicLibraryTree(context)

    @Test
    fun `root item and tabs form the browse skeleton`() {
        val tree = tree()
        val root = tree.rootItem()
        assertEquals(MusicLibraryTree.ROOT, root.mediaId)
        assertEquals("Music", root.mediaMetadata.title?.toString())

        val tabs = tree.children(MusicLibraryTree.ROOT)
        assertEquals(
            listOf(
                MusicLibraryTree.TAB_PLAYLISTS,
                MusicLibraryTree.TAB_ALBUMS,
                MusicLibraryTree.TAB_ARTISTS,
                MusicLibraryTree.TAB_SONGS,
                MusicLibraryTree.TAB_RECENT,
            ),
            tabs.map { it.mediaId },
        )
        assertEquals(
            listOf("Playlists", "Albums", "Artists", "Songs", "Recently played"),
            tabs.map { it.mediaMetadata.title?.toString() },
        )
        tree.release()
    }

    @Test
    fun `empty tabs have no children and unknown parents neither`() {
        val tree = tree()
        for (tab in listOf(
            MusicLibraryTree.TAB_PLAYLISTS,
            MusicLibraryTree.TAB_ALBUMS,
            MusicLibraryTree.TAB_ARTISTS,
            MusicLibraryTree.TAB_SONGS,
            MusicLibraryTree.TAB_RECENT,
        )) {
            assertTrue(tab, tree.children(tab).isEmpty())
            assertEquals(tab, 0, tree.childCount(tab))
        }
        assertEquals(5, tree.childCount(MusicLibraryTree.ROOT))
        assertTrue(tree.children("no-such-parent").isEmpty())
        assertTrue(tree.children("playlist_99").isEmpty())
        assertTrue(tree.children("album_99").isEmpty())
        assertTrue(tree.children("artist_99").isEmpty())
        assertTrue(tree.children("playlist_abc").isEmpty())
        tree.release()
    }

    @Test
    fun `item lookup resolves tabs and rejects unknown ids`() {
        val tree = tree()
        val rootItem = tree.item(MusicLibraryTree.ROOT)
        assertNotNull(rootItem)
        assertEquals(MusicLibraryTree.ROOT, rootItem!!.mediaId)
        val albumsItem = tree.item(MusicLibraryTree.TAB_ALBUMS)
        assertNotNull(albumsItem)
        assertEquals(MusicLibraryTree.TAB_ALBUMS, albumsItem!!.mediaId)
        assertNull(tree.item("tab_nope"))
        assertNull(tree.item("playlist_99"))
        assertNull(tree.item("album_99"))
        assertNull(tree.item("artist_99"))
        assertNull(tree.item("12345"))
        assertNull(tree.item("song"))
        tree.release()
    }

    @Test
    fun `refreshable parents are the root plus every tab`() {
        val tree = tree()
        assertEquals(
            listOf(
                MusicLibraryTree.ROOT,
                MusicLibraryTree.TAB_PLAYLISTS,
                MusicLibraryTree.TAB_ALBUMS,
                MusicLibraryTree.TAB_ARTISTS,
                MusicLibraryTree.TAB_SONGS,
                MusicLibraryTree.TAB_RECENT,
            ),
            tree.refreshableParents(),
        )
        tree.release()
    }

    @Test
    fun `playback resolution and search find nothing in an empty library`() {
        val tree = tree()
        assertNull(tree.resolveForPlayback("1"))
        assertNull(tree.resolveForPlayback("abc"))
        assertTrue(tree.searchPlayableSongs("anything").isEmpty())
        assertTrue(tree.searchPlayableSongs("   ").isEmpty())
        assertTrue(tree.searchResults("anything").isEmpty())
        assertTrue(tree.searchResults("").isEmpty())
        tree.release()
    }

    @Test
    fun `markPlayed ignores non numeric ids and notifies`() {
        val tree = tree()
        var notified = 0
        tree.onLibraryChanged = { notified++ }
        tree.markPlayed(listOf("abc", ""))
        assertTrue("no valid ids means no change to report", notified == 0)
        tree.markPlayed(listOf("7", "7", "9"))
        assertEquals(1, notified)
        // Recent tab still lists nothing: those ids are not in the library.
        assertTrue(tree.children(MusicLibraryTree.TAB_RECENT).isEmpty())
        tree.release()
    }
}
