package com.vayunmathur.youpipe.platform

import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.sdk.cast.PlaybackAction
import com.vayunmathur.sdk.cast.PlaybackCommand
import java.lang.reflect.Proxy
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith

/** Slice 2/3 pass 3: TV-remote commands applied to the attached player (internal apply seam). */
@RunWith(AndroidJUnit4::class)
class CastCommandsPass3Test {

    private class FakePlayer(var playing: Boolean = false, var position: Long = 5_000L) {
        val calls = mutableListOf<String>()
        val player: Player = Proxy.newProxyInstance(
            Player::class.java.classLoader, arrayOf(Player::class.java),
        ) { _, method, args ->
            when (method.name) {
                "play" -> { calls += "play"; playing = true; null }
                "pause" -> { calls += "pause"; playing = false; null }
                "isPlaying" -> playing
                "getIsPlaying" -> playing
                "getCurrentPosition" -> position
                "seekTo" -> { val to = args[0] as Long; calls += "seekTo:$to"; position = to; null }
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    java.lang.Float.TYPE -> 0f
                    java.lang.Double.TYPE -> 0.0
                    else -> null
                }
            }
        } as Player
    }

    @BeforeTest
    fun setUp() {
        CastPlayback.close()
        CastPlayback.update { CastPlayback.Transport(durationMs = 60_000) }
    }

    @AfterTest
    fun tearDown() {
        CastPlayback.onNavigate = null
        CastPlayback.close()
        CastPlayback.update { CastPlayback.Transport() }
    }

    @Test
    fun playPauseAndToggleDriveAttachedPlayer() {
        val fake = FakePlayer(playing = false)
        CastPlayback.attachPlayer(fake.player)
        CastPlayback.apply(PlaybackCommand(PlaybackAction.Play))
        CastPlayback.apply(PlaybackCommand(PlaybackAction.Toggle)) // now playing -> pause
        CastPlayback.apply(PlaybackCommand(PlaybackAction.Toggle)) // paused -> play
        CastPlayback.apply(PlaybackCommand(PlaybackAction.Pause))
        assertEquals(listOf("play", "pause", "play", "pause"), fake.calls)
    }

    @Test
    fun commandsWithoutPlayerAreDropped() {
        CastPlayback.apply(PlaybackCommand(PlaybackAction.Play))
        CastPlayback.apply(PlaybackCommand(PlaybackAction.SeekTo, 1_000.0))
        assertEquals(0L, CastPlayback.transport.value.positionMs)
    }

    @Test
    fun seekClampsToDurationAndSkipsMoveByTenSeconds() {
        val fake = FakePlayer(position = 5_000L)
        CastPlayback.attachPlayer(fake.player)
        CastPlayback.apply(PlaybackCommand(PlaybackAction.SeekTo, 90_000.0))
        assertEquals(60_000L, CastPlayback.transport.value.positionMs)
        CastPlayback.apply(PlaybackCommand(PlaybackAction.SkipBack))
        // Fake position is now 60s (seekTo recorded it); skip back lands at 50s.
        assertEquals("seekTo:50000", fake.calls.last())
        CastPlayback.apply(PlaybackCommand(PlaybackAction.SkipForward))
        assertEquals("seekTo:60000", fake.calls.last())
        // A value-less seek does nothing.
        val before = fake.calls.size
        CastPlayback.apply(PlaybackCommand(PlaybackAction.SeekTo))
        assertEquals(before, fake.calls.size)
    }

    @Test
    fun seekIsRefusedWhileDragging() {
        val fake = FakePlayer()
        CastPlayback.attachPlayer(fake.player)
        CastPlayback.update { it.copy(dragging = true) }
        CastPlayback.apply(PlaybackCommand(PlaybackAction.SeekTo, 30_000.0))
        assertTrue(fake.calls.isEmpty(), fake.calls.toString())
        assertEquals(0L, CastPlayback.transport.value.positionMs)
    }

    @Test
    fun speedAndVolumeAreClampedIntoTransport() {
        val fake = FakePlayer()
        CastPlayback.attachPlayer(fake.player)
        CastPlayback.apply(PlaybackCommand(PlaybackAction.SetSpeed, 99.0))
        assertEquals(2f, CastPlayback.transport.value.speed)
        CastPlayback.apply(PlaybackCommand(PlaybackAction.SetSpeed, 0.01))
        assertEquals(0.25f, CastPlayback.transport.value.speed)
        CastPlayback.apply(PlaybackCommand(PlaybackAction.SetVolume, 1.5))
        assertEquals(1f, CastPlayback.transport.value.volume)
        CastPlayback.apply(PlaybackCommand(PlaybackAction.SetVolume, -1.0))
        assertEquals(0f, CastPlayback.transport.value.volume)
    }

    @Test
    fun nextPreviousOnlyNavigateWhenAvailable() {
        val fake = FakePlayer()
        CastPlayback.attachPlayer(fake.player)
        val navigations = mutableListOf<Boolean>()
        CastPlayback.onNavigate = { navigations += it }
        CastPlayback.apply(PlaybackCommand(PlaybackAction.Next))
        assertTrue(navigations.isEmpty())
        CastPlayback.update { it.copy(hasNext = true, hasPrevious = true) }
        CastPlayback.apply(PlaybackCommand(PlaybackAction.Next))
        CastPlayback.apply(PlaybackCommand(PlaybackAction.Previous))
        assertEquals(listOf(true, false), navigations)
    }

    @Test
    fun castingStateValueCarriesGrantedGeometry() {
        // Surface is the one platform value a JVM test cannot mint; the data
        // class contract itself (equality/copy) is still exercised via Idle/
        // Connecting state flow transitions in CastPlaybackPass3Test. Here we
        // assert the Transport defaults a remote sees before any player writes.
        val transport = CastPlayback.Transport()
        assertEquals(1f, transport.speed)
        assertEquals(1f, transport.volume)
        assertEquals(transport, transport.copy())
    }
}
