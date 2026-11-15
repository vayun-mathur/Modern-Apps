package com.vayunmathur.music.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.music.data.Music
import com.vayunmathur.sdk.cast.PlaybackAction
import com.vayunmathur.sdk.cast.PlaybackCommand
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CastPlayback session-less behaviour beyond CastPlaybackTest: every transport
 * command is a silent no-op without a TV, close/clearCache are idempotent, and
 * support probing returns a real verdict instead of throwing on a host JVM.
 * Actually opening a session needs a Cast receiver, so open()/play() success
 * paths are device-only and not faked here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CastPlaybackExpandedTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun song(id: Long) = Music(
        id = id, title = "Song $id", artist = "Artist", artistId = 1, album = "Album",
        albumId = 7, uri = "content://media/$id", duration = 180_000, trackNumber = 1, year = 2020,
    )

    @Test
    fun `support probing returns a verdict and growing starts empty`() {
        assertNotNull(CastPlayback.support(context))
        assertNull(CastPlayback.growing.value)
        assertNull(CastPlayback.tv.value)
        assertFalse(CastPlayback.isCasting)
    }

    @Test
    fun `every playback command is a no op without a session`() {
        for (action in PlaybackAction.entries) {
            CastPlayback.send(PlaybackCommand(action, 1.5))
        }
        CastPlayback.send(PlaybackCommand(PlaybackAction.SeekTo, 30_000.0))
        CastPlayback.send(PlaybackCommand(PlaybackAction.SetSpeed, 2.0))
        assertEquals(CastPlayback.State.Idle, CastPlayback.state.value)
    }

    @Test
    fun `playing several songs without a session changes nothing`() {
        runBlocking {
            CastPlayback.play(context, song(1))
            CastPlayback.play(context, song(2), startPositionMs = 45_000)
            CastPlayback.play(context, song(3), startPositionMs = -1)
        }
        assertEquals(CastPlayback.State.Idle, CastPlayback.state.value)
        assertNull(CastPlayback.growing.value)
        assertNull(CastPlayback.tv.value)
    }

    @Test
    fun `close and clear cache are idempotent when nothing exists`() {
        CastPlayback.close()
        CastPlayback.close()
        CastPlayback.clearCache(context)
        CastPlayback.clearCache(context)
        assertEquals(CastPlayback.State.Idle, CastPlayback.state.value)

        val opusDir = File(context.cacheDir, "cast-opus").apply { mkdirs() }
        File(opusDir, "nested").mkdirs()
        File(File(opusDir, "nested"), "9.opus").writeBytes(byteArrayOf(9))
        CastPlayback.clearCache(context)
        assertTrue(!opusDir.exists())
        CastPlayback.clearCache(context)
        assertTrue(!File(context.cacheDir, "cast-art").exists() || File(context.cacheDir, "cast-art").list().isNullOrEmpty())
    }

    @Test
    fun `open failure leaves no growing resource and no tv snapshot`() {
        val opened = runBlocking { CastPlayback.open(context) }
        assertFalse(opened)
        assertEquals(CastPlayback.State.Idle, CastPlayback.state.value)
        assertNull(CastPlayback.growing.value)
        assertNull(CastPlayback.tv.value)
    }
}
