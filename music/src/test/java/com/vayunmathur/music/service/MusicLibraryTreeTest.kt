package com.vayunmathur.music.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.music.FakeAndroidKeyStore
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MusicLibraryTree over an empty library. A populated tree cannot be built on a
 * host JVM: the tree's snapshot combines the Room playlist/matching flows, and
 * SQLCipher cannot load here, so its init deliberately catches that and leaves
 * the snapshot empty (see the tree's own comment). What remains testable — and
 * what Android Auto depends on even with no music — is the browse skeleton:
 * root, the five tabs, id resolution, search guards, and recents bookkeeping.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MusicLibraryTreeTest {

    @Before
    fun setUp() {
        FakeAndroidKeyStore.install()
    }

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun tree(): MusicLibraryTree = MusicLibraryTree(context)

    @Test
    fun `root exposes the five browse tabs in order`() {
        val tree = tree()
        try {
            val root = tree.rootItem()
            assertEquals(MusicLibraryTree.ROOT, root.mediaId)
            assertEquals("Music", root.mediaMetadata.title?.toString())
            assertEquals(true, root.mediaMetadata.isBrowsable)
            assertEquals(false, root.mediaMetadata.isPlayable)

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
            assertTrue(tabs.all { it.mediaMetadata.isBrowsable == true && it.mediaMetadata.isPlayable == false })
            assertEquals(5, tree.childCount(MusicLibraryTree.ROOT))
        } finally {
            tree.release()
        }
    }

    @Test
    fun `empty library tabs and unknown nodes have no children`() {
        val tree = tree()
        try {
            for (tab in listOf(
                MusicLibraryTree.TAB_PLAYLISTS, MusicLibraryTree.TAB_ALBUMS,
                MusicLibraryTree.TAB_ARTISTS, MusicLibraryTree.TAB_SONGS,
                MusicLibraryTree.TAB_RECENT,
            )) {
                assertEquals(emptyList(), tree.children(tab), tab)
                assertEquals(0, tree.childCount(tab), tab)
            }
            assertEquals(emptyList(), tree.children("playlist_42"))
            assertEquals(emptyList(), tree.children("album_42"))
            assertEquals(emptyList(), tree.children("artist_42"))
            assertEquals(emptyList(), tree.children("playlist_not_a_number"))
            assertEquals(emptyList(), tree.children("totally_unknown"))
            assertEquals(emptyList(), tree.children(""))
        } finally {
            tree.release()
        }
    }

    @Test
    fun `item resolves tabs and root but nothing unknown or absent`() {
        val tree = tree()
        try {
            assertEquals(MusicLibraryTree.ROOT, tree.item(MusicLibraryTree.ROOT)?.mediaId)
            assertEquals("Playlists", tree.item(MusicLibraryTree.TAB_PLAYLISTS)?.mediaMetadata?.title?.toString())
            assertEquals("Albums", tree.item(MusicLibraryTree.TAB_ALBUMS)?.mediaMetadata?.title?.toString())
            assertEquals("Artists", tree.item(MusicLibraryTree.TAB_ARTISTS)?.mediaMetadata?.title?.toString())
            assertEquals("Songs", tree.item(MusicLibraryTree.TAB_SONGS)?.mediaMetadata?.title?.toString())
            assertEquals("Recently played", tree.item(MusicLibraryTree.TAB_RECENT)?.mediaMetadata?.title?.toString())

            assertNull(tree.item("playlist_42"))
            assertNull(tree.item("album_42"))
            assertNull(tree.item("artist_42"))
            assertNull(tree.item("42"))
            assertNull(tree.item("not_a_song"))
            assertNull(tree.resolveForPlayback("42"))
            assertNull(tree.resolveForPlayback("playlist_42"))
        } finally {
            tree.release()
        }
    }

    @Test
    fun `refreshable parents are the root plus every tab`() {
        val tree = tree()
        try {
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
        } finally {
            tree.release()
        }
    }

    @Test
    fun `search guards blank queries and finds nothing in an empty library`() {
        val tree = tree()
        try {
            assertEquals(emptyList(), tree.searchPlayableSongs(""))
            assertEquals(emptyList(), tree.searchPlayableSongs("   "))
            assertEquals(emptyList(), tree.searchPlayableSongs("anything"))
            assertEquals(emptyList(), tree.searchResults(""))
            assertEquals(emptyList(), tree.searchResults("   "))
            assertEquals(emptyList(), tree.searchResults("anything"))
        } finally {
            tree.release()
        }
    }

    @Test
    fun `mark played records ids, ignores junk, and notifies`() {
        val tree = tree()
        try {
            var notifications = 0
            tree.onLibraryChanged = { notifications++ }
            tree.markPlayed(listOf("3", "not_a_number", "1", "3"))
            assertEquals(1, notifications)
            // Recents still resolve to nothing: the library itself is empty.
            assertEquals(emptyList(), tree.children(MusicLibraryTree.TAB_RECENT))

            tree.markPlayed(emptyList())
            tree.markPlayed(listOf("junk"))
            assertEquals(1, notifications, "no playable ids means no change to announce")
            assertNotNull(tree.onLibraryChanged)
        } finally {
            tree.release()
        }
    }
}
