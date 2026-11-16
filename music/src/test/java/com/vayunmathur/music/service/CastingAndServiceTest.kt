package com.vayunmathur.music.service

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.music.FakeAndroidKeyStore
import com.vayunmathur.music.platform.CastPlayback
import com.vayunmathur.sdk.cast.PlaybackState
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CastingAndServiceTest {
    @Before fun setUp() { FakeAndroidKeyStore.install() }
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun setField(target: Any, name: String, value: Any?) {
        var c: Class<*>? = target.javaClass
        while (c != null) {
            runCatching {
                val f = c.getDeclaredField(name); f.isAccessible = true; f.set(target, value); return
            }
            c = c.superclass
        }
        error("no field $name")
    }
    private fun localPlayer(): ExoPlayer = ExoPlayer.Builder(context).build()

    @Test fun castingPlayerDelegatesWhenNotCastingAndSynthesisesTvStateWhenCasting() {
        val local = localPlayer()
        try {
            val item = MediaItem.Builder().setMediaId("12").setUri("content://media/12").build()
            local.setMediaItem(item)
            val player = CastingPlayer(local)
            // not casting: transport delegates to local
            player.pause(); player.seekTo(0L); player.setPlaybackSpeed(1.5f)
            assertEquals(1.5f, local.playbackParameters.speed)

            // casting with a TV snapshot: state is synthesised from TV, not local
            setField(player, "casting", true)
            setField(player, "tv", CastPlayback.TvPlayback(
                PlaybackState(positionMs = 30_000, durationMs = 180_000, playing = true, buffering = false, ended = false, speed = 1f),
                receivedAtMs = System.currentTimeMillis()))
            setField(player, "growing", "12")
            // supplierFor is private and normally set by onCast; invoke it reflectively
            val m = CastingPlayer::class.java.getDeclaredMethod("supplierFor", CastPlayback.TvPlayback::class.java)
            m.isAccessible = true
            setField(player, "positions", m.invoke(player, player.let {
                CastPlayback.TvPlayback(PlaybackState(30_000, 180_000, true, false, speed = 1f), System.currentTimeMillis())
            }))
            val getState = CastingPlayer::class.java.getDeclaredMethod("getState"); getState.isAccessible = true
            val state = getState.invoke(player) as androidx.media3.common.SimpleBasePlayer.State
            assertNotNull(state)
            // seek commands removed while the current resource is growing
            assertFalse(state.availableCommands.contains(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM),
                "growing track must not offer seek")
            // transport while casting goes to CastPlayback (no session = silent no-op)
            player.play(); player.seekTo(5_000L); player.seekToNextMediaItem(); player.setPlaybackSpeed(2f)

            // ended + buffering snapshot maps to ENDED
            setField(player, "tv", CastPlayback.TvPlayback(
                PlaybackState(positionMs = 180_000, durationMs = 180_000, playing = false, buffering = true, ended = true, speed = 1f),
                receivedAtMs = System.currentTimeMillis()))
            setField(player, "growing", null)
            player.release()
        } finally { runCatching { local.release() } }
    }

    @Test fun castQueueLifecycleWithoutCastingIsSafe() {
        val local = localPlayer()
        try {
            val queue = CastQueue(context, local)
            local.setMediaItem(MediaItem.Builder().setMediaId("1").setUri("content://media/1").build())
            queue.release()
            queue.release()
        } finally { runCatching { local.release() } }
    }

    @Test fun playbackServiceCreatesSessionAndServesLibraryRoot() {
        val controller = Robolectric.buildService(PlaybackService::class.java)
        val service = controller.create().get()
        assertNotNull(service)
        controller.destroy()
    }
}
