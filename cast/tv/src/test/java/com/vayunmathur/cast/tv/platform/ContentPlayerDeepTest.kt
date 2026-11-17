package com.vayunmathur.cast.tv.platform

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.cast.protocol.ContentSession
import com.vayunmathur.cast.protocol.PlayMedia
import com.vayunmathur.cast.protocol.PlaybackAction
import com.vayunmathur.cast.protocol.PlaybackCommand
import com.vayunmathur.cast.protocol.ProtocolBase64
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ContentPlayer built directly: every transport action applied to an idle
 * player, the unusable-fingerprint refusal, and snapshots of nothing playing.
 *
 * Main-thread work runs on a background coroutine while the test thread pumps
 * the main looper - under Robolectric Dispatchers.Main is that paused looper.
 */
@RunWith(RobolectricTestRunner::class)
class ContentPlayerDeepTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun session(fingerprint: ByteArray = ByteArray(32) { 7 }) = ContentSession(
        host = "127.0.0.1", port = 9,
        certificateFingerprint = ProtocolBase64.encode(fingerprint),
        token = "tok", video = true, appLabel = "YouPipe",
    )

    private suspend fun <T> onMainPumped(block: suspend () -> T): T {
        val deferred: Deferred<T> = kotlinx.coroutines.CoroutineScope(Dispatchers.Default).async {
            withContext(Dispatchers.Main) { block() }
        }
        while (!deferred.isCompleted) {
            shadowOf(Looper.getMainLooper()).idle()
            delay(25)
        }
        return deferred.await()
    }

    @Test fun applyEveryActionOnIdlePlayer() { runBlocking {
        val errors = mutableListOf<String>()
        onMainPumped {
            val player = ContentPlayer(session())
            assertTrue(player.start(ctx) { detail -> errors += detail })
            assertTrue(player.apply(PlaybackCommand(PlaybackAction.Play)))
            assertTrue(player.apply(PlaybackCommand(PlaybackAction.Pause)))
            assertTrue(player.apply(PlaybackCommand(PlaybackAction.Toggle)))
            assertTrue(player.apply(PlaybackCommand(PlaybackAction.Toggle)))
            assertTrue(player.apply(PlaybackCommand(PlaybackAction.SeekTo, value = 12_000.0)))
            assertTrue(player.apply(PlaybackCommand(PlaybackAction.SkipForward)))
            assertTrue(player.apply(PlaybackCommand(PlaybackAction.SkipBack)))
            assertTrue(player.apply(PlaybackCommand(PlaybackAction.SetSpeed, value = 1.5)))
            assertTrue(player.apply(PlaybackCommand(PlaybackAction.SetVolume, value = 0.4)))
            // The queue belongs to the phone: these two are refused, by contract.
            assertFalse(player.apply(PlaybackCommand(PlaybackAction.Next)))
            assertFalse(player.apply(PlaybackCommand(PlaybackAction.Previous)))
            // No value: accepted, nothing to apply.
            assertTrue(player.apply(PlaybackCommand(PlaybackAction.SeekTo)))
            // Nothing loaded: the snapshot says so.
            val idle = player.snapshot()
            assertEquals(0L, idle.durationMs)
            assertFalse(idle.playing)
            player.play(PlayMedia(resourceId = "clip", mimeType = "video/mp4", durationMs = 60_000))
            player.setSurface(null)
            player.release()
            player.release() // idempotent teardown
        }
        Unit
    } }

    @Test fun startRefusesUnusableFingerprint() { runBlocking {
        onMainPumped {
            val player = ContentPlayer(session(fingerprint = ByteArray(8)))
            assertFalse(player.start(ctx) { })
            player.release()
        }
        Unit
    } }
}
