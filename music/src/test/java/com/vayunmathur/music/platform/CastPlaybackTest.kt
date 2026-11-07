package com.vayunmathur.music.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.music.data.Music
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CastPlayback with no television: the state machine must sit at Idle,
 * opening must fail cleanly back to Idle, and every session-scoped call
 * must be a silent no-op — CastingPlayer leans on exactly that contract.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CastPlaybackTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun song(id: Long = 1L) = Music(
        id = id, title = "Cast Song", artist = "Artist", artistId = 1, album = "Album",
        albumId = 7, uri = "content://media/$id", duration = 180_000, trackNumber = 1, year = 2020,
    )

    @Test
    fun `state starts idle and not casting`() {
        assertEquals(CastPlayback.State.Idle, CastPlayback.state.value)
        assertFalse(CastPlayback.isCasting)
        assertEquals(null, CastPlayback.tv.value)
    }

    @Test
    fun `open without a receiver fails back to idle`() {
        val opened = runBlocking { CastPlayback.open(context) }
        assertFalse(opened, "no connected TV means no cast session")
        assertEquals(CastPlayback.State.Idle, CastPlayback.state.value)
        assertFalse(CastPlayback.isCasting)
    }

    @Test
    fun `play send and close are no ops without a session`() {
        runBlocking { CastPlayback.play(context, song()) }
        CastPlayback.send(
            com.vayunmathur.sdk.cast.PlaybackCommand(
                com.vayunmathur.sdk.cast.PlaybackAction.Play,
            ),
        )
        CastPlayback.close()
        assertEquals(CastPlayback.State.Idle, CastPlayback.state.value)
    }

    @Test
    fun `clear cache removes transcoded files`() {
        val opusDir = File(context.cacheDir, "cast-opus").apply { mkdirs() }
        File(opusDir, "1.opus").writeBytes(byteArrayOf(1, 2, 3))
        val artDir = File(context.cacheDir, "cast-art").apply { mkdirs() }
        File(artDir, "1.jpg").writeBytes(byteArrayOf(4, 5))
        CastPlayback.clearCache(context)
        assertTrue(!opusDir.exists(), "transcode cache must be deleted")
        assertTrue(!artDir.exists(), "artwork cache must be deleted")
    }

    @Test
    fun `state and tv playback are value types`() {
        val casting = CastPlayback.State.Casting("Living Room TV")
        assertEquals("Living Room TV", casting.receiverName)
        assertEquals(casting, CastPlayback.State.Casting("Living Room TV"))
        assertTrue(CastPlayback.State.Connecting != CastPlayback.State.Idle)
        val tv = CastPlayback.TvPlayback(
            com.vayunmathur.sdk.cast.PlaybackState(
                positionMs = 5_000, durationMs = 180_000, playing = true,
                buffering = false, ended = false, speed = 1f,
            ),
            receivedAtMs = 123L,
        )
        assertEquals(5_000L, tv.state.positionMs)
        assertEquals(123L, tv.receivedAtMs)
        assertEquals(tv.copy(receivedAtMs = 124L).receivedAtMs, 124L)
    }
}
