package com.vayunmathur.music

import android.content.ContentValues
import android.media.MediaScannerConnection
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vayunmathur.library.room.buildDatabase
import com.vayunmathur.music.data.MusicDatabase
import com.vayunmathur.music.util.syncMusic
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Screenshot generator driven by `:music:metadata`. Seeds a few short, TAGGED MP3s (ID3v2
 * title/artist/album) into MediaStore so the scanner surfaces real metadata, indexes them,
 * then captures the Songs / Albums / Artists tabs.
 */
@RunWith(AndroidJUnit4::class)
class MetadataScreenshots {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private val outDir: File by lazy {
        File(ctx.getExternalFilesDir(null), "metadata_screenshots").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    private fun snap(index: Int) {
        val image = composeRule.onRoot().captureToImage()
        File(outDir, "$index.png").outputStream().use { out ->
            image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    // --- Tagged MP3 synthesis -------------------------------------------------

    private fun syncsafe(n: Int) = byteArrayOf(
        ((n ushr 21) and 0x7F).toByte(),
        ((n ushr 14) and 0x7F).toByte(),
        ((n ushr 7) and 0x7F).toByte(),
        (n and 0x7F).toByte(),
    )

    private fun be32(n: Int) = byteArrayOf(
        (n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte(),
    )

    /** An ID3v2.3 text frame (ISO-8859-1 encoding byte + ASCII text). */
    private fun textFrame(id: String, text: String): ByteArray {
        val data = byteArrayOf(0x00) + text.toByteArray(Charsets.ISO_8859_1)
        return id.toByteArray(Charsets.US_ASCII) + be32(data.size) + byteArrayOf(0, 0) + data
    }

    /**
     * A minimal valid MP3: an ID3v2.3 tag (so MediaStore's scanner reads real
     * title/artist/album) followed by ~1s of silent MPEG-1 Layer III frames
     * (128 kbps, 44.1 kHz, stereo). All-zero frame bodies decode to silence.
     */
    private fun taggedMp3(title: String, artist: String, album: String): ByteArray {
        val tagBody = textFrame("TIT2", title) + textFrame("TPE1", artist) + textFrame("TALB", album)
        val id3 = "ID3".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0x03, 0x00, 0x00) + syncsafe(tagBody.size) + tagBody

        val frameHeader = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00)
        val frame = frameHeader + ByteArray(FRAME_SIZE - frameHeader.size)
        val out = ByteArrayOutputStream()
        out.write(id3)
        repeat(NUM_FRAMES) { out.write(frame) }
        return out.toByteArray()
    }

    // --- MediaStore seeding ---------------------------------------------------

    /** Remove tracks seeded by a previous run so reruns don't accumulate duplicates. */
    private fun deleteSeeded() {
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        ctx.contentResolver.delete(
            collection,
            "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("$REL_PATH%"),
        )
    }

    private fun seedMusic() {
        val tracks = listOf(
            Track("Midnight Drive", "The Neon Owls", "After Hours"),
            Track("Golden Hour", "The Neon Owls", "After Hours"),
            Track("Paper Planes", "Marina Vale", "Coastlines"),
            Track("Coastlines", "Marina Vale", "Coastlines"),
            Track("Slow Mornings", "Kite & Ember", "Homebound"),
            Track("City Lights", "Kite & Ember", "Homebound"),
            Track("Wildflower", "June Sparrow", "Meadow"),
            Track("Riptide Blue", "June Sparrow", "Meadow"),
        )
        val resolver = ctx.contentResolver
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val scanPaths = mutableListOf<String>()
        tracks.forEachIndexed { i, t ->
            val fileName = "metadata_${i + 1}.mp3"
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
                put(MediaStore.Audio.Media.RELATIVE_PATH, REL_PATH)
                put(MediaStore.Audio.Media.IS_MUSIC, 1)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: return@forEachIndexed
            resolver.openOutputStream(uri)!!.use { it.write(taggedMp3(t.title, t.artist, t.album)) }
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            scanPaths += File(
                Environment.getExternalStorageDirectory(),
                "$REL_PATH$fileName",
            ).absolutePath
        }

        // Force the media scanner to parse the ID3 tags before we index, so
        // TITLE/ARTIST/ALBUM are populated (not "<unknown>") when syncMusic runs.
        val latch = CountDownLatch(scanPaths.size)
        MediaScannerConnection.scanFile(ctx, scanPaths.toTypedArray(), null) { _, _ ->
            latch.countDown()
        }
        latch.await(20, TimeUnit.SECONDS)
    }

    private data class Track(val title: String, val artist: String, val album: String)

    @Test
    fun generateStoreScreenshots() {
        deleteSeeded()
        seedMusic()
        // Index MediaStore into the app's Room DB directly (shares the cached instance
        // the app uses), so the library populates deterministically.
        val db = ctx.buildDatabase<MusicDatabase>()
        runBlocking { syncMusic(ctx, db) }

        ActivityScenario.launch(MainActivity::class.java).use {
            Thread.sleep(4000)
            snap(1) // Songs

            composeRule.onNodeWithText("Albums").performClick()
            composeRule.waitForIdle()
            Thread.sleep(1500)
            snap(2) // Albums

            composeRule.onNodeWithText("Artists").performClick()
            composeRule.waitForIdle()
            Thread.sleep(1500)
            snap(3) // Artists
        }
    }

    companion object {
        private const val REL_PATH = "Music/Metadata/"
        // MPEG-1 Layer III, 128 kbps, 44.1 kHz => floor(144*128000/44100) = 417 bytes/frame.
        private const val FRAME_SIZE = 417
        // ~1s of audio (1152 samples/frame at 44.1 kHz => ~26 ms/frame).
        private const val NUM_FRAMES = 40
    }
}
