package com.vayunmathur.music.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.vayunmathur.music.service.MusicLibraryTree
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * The library with actual media in it: an MP3 carrying ID3 title/artist/
 * album frames is inserted into MediaStore (the same row the shipped seed
 * script would make), the repository scans it, and the browse tree/search
 * resolve it. This is the populated half of MusicRepository, Util's
 * MediaStore readers and MusicLibraryTree that an empty library cannot
 * reach. An MP3 because the scanner takes artist/album from ID3; a WAV
 * has nowhere to carry them and scans in as <unknown>.
 */
class SeededLibraryDeviceTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private var insertedUri: Uri? = null
    private var songId: Long = -1

    /** ID3v2.3 (UTF-8 text frames) over silent 128 kbps MP3 frames. */
    private fun mp3Bytes(): ByteArray {
        val out = ByteArrayOutputStream()
        val tagOut = ByteArrayOutputStream()
        fun collect(id: String, text: String) {
            val payload = byteArrayOf(3) + text.toByteArray(Charsets.UTF_8)
            tagOut.write(id.toByteArray(Charsets.US_ASCII))
            repeat(4) { tagOut.write(payload.size ushr (24 - 8 * it)) }
            tagOut.write(0); tagOut.write(0)
            tagOut.write(payload)
        }
        collect("TIT2", "Seeded Tone")
        collect("TPE1", "Seed Artist")
        collect("TALB", "Seed Album")
        val tag = tagOut.toByteArray()
        out.write("ID3".toByteArray(Charsets.US_ASCII))
        out.write(3); out.write(0); out.write(0)
        // Syncsafe size: 7 bits per byte.
        repeat(4) { out.write((tag.size ushr (21 - 7 * it)) and 0x7F) }
        out.write(tag)
        // ~100 silent frames (header + zeroed side info/main data reads as silence).
        val frame = ByteArray(417)
        frame[0] = 0xFF.toByte(); frame[1] = 0xFB.toByte(); frame[2] = 0x90.toByte()
        repeat(100) { out.write(frame) }
        return out.toByteArray()
    }

    @Before
    fun seedSong() {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "Seeded Tone.mp3")
            put(MediaStore.Audio.Media.TITLE, "Seeded Tone")
            put(MediaStore.Audio.Media.ARTIST, "Seed Artist")
            put(MediaStore.Audio.Media.ALBUM, "Seed Album")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/CoverageSeed")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
        assertNotNull("MediaStore insert must succeed", uri)
        insertedUri = uri
        resolver.openOutputStream(uri!!)!!.use { it.write(mp3Bytes()) }
        val publish = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
        resolver.update(uri, publish, null, null)

        // The scanner fills the derived columns asynchronously; refresh until
        // the row is visible as music (bounded wait for an external system).
        val repo = MusicRepository.get(context)
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            runBlocking { repo.refresh() }
            val song = runBlocking { repo.getAllMusic() }
                .firstOrNull { it.title == "Seeded Tone" }
            if (song != null) {
                songId = song.id
                return
            }
            Thread.sleep(500)
        }
        throw AssertionError("seeded MP3 never appeared in the music library")
    }

    @After
    fun removeSong() {
        insertedUri?.let { context.contentResolver.delete(it, null, null) }
        insertedUri = null
    }

    @Test
    fun repositoryReadsSeededSongAlbumAndArtist() = runBlocking {
        val repo = MusicRepository.get(context)
        val song = repo.getAllMusic().first { it.id == songId }
        assertEquals("Seeded Tone", song.title)
        assertEquals("Seed Artist", song.artist)
        assertEquals("Seed Album", song.album)
        assertTrue(repo.getAllAlbums().any { it.name == "Seed Album" })
        assertTrue(repo.getAllArtists().any { it.name == "Seed Artist" })
    }

    @Test
    fun browseTreeResolvesSeededSong() {
        val tree = MusicLibraryTree(context)
        try {
            val deadline = System.currentTimeMillis() + 30_000
            var songs = tree.children(MusicLibraryTree.TAB_SONGS)
            while (songs.none { it.mediaId == songId.toString() } &&
                System.currentTimeMillis() < deadline
            ) {
                Thread.sleep(250)
                songs = tree.children(MusicLibraryTree.TAB_SONGS)
            }
            assertTrue(
                "songs tab must list the seeded song",
                songs.any { it.mediaId == songId.toString() },
            )
            val item = tree.item(songId.toString())
            assertNotNull(item)
            assertEquals("Seeded Tone", item!!.mediaMetadata.title?.toString())
            val playable = tree.resolveForPlayback(songId.toString())
            assertNotNull(playable)
            assertNotNull(playable!!.localConfiguration)
            assertTrue(tree.searchResults("Seeded").any { it.mediaId == songId.toString() })
            assertTrue(
                tree.searchPlayableSongs("Seeded Tone")
                    .any { it.mediaId == songId.toString() },
            )
            assertTrue(tree.searchPlayableSongs("Seed Artist").isNotEmpty())
            assertTrue(tree.searchPlayableSongs("Seed Album").isNotEmpty())
            tree.markPlayed(listOf(songId.toString()))
            assertTrue(
                tree.children(MusicLibraryTree.TAB_RECENT)
                    .any { it.mediaId == songId.toString() },
            )
        } finally {
            tree.release()
        }
    }
}
