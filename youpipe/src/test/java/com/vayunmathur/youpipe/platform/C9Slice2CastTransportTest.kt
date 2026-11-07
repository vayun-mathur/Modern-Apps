package com.vayunmathur.youpipe.platform

import androidx.media3.common.Player
import java.lang.reflect.Proxy
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** CastPlayback transport + player attach/detach identity guard (no TV needed). */
class C9Slice2CastTransportTest {

    @BeforeTest fun reset() {
        CastPlayback.close()
        CastPlayback.onNavigate = null
    }

    private fun fakePlayer(): Player {
        val handler = java.lang.reflect.InvocationHandler { _, method, _ ->
            when (method.name) {
                "getPlaybackState" -> Player.STATE_IDLE
                "getDuration", "getCurrentPosition", "getBufferedPosition" -> 0L
                "getVolume" -> 1f
                "getPlaybackSpeed" -> 1f
                "isPlaying", "isLoading" -> false
                "getAvailableCommands" -> null
                "getDeviceInfo" -> null
                "getAudioAttributes" -> null
                "getSeekBackIncrement" -> 5000L
                "getSeekForwardIncrement" -> 15000L
                "getMaxSeekToPreviousPosition" -> 3000L
                "getCurrentPosition" -> 0L
                "getTotalBufferedDuration" -> 0L
                "getCurrentLiveOffset" -> 0L
                "getTargetLiveOffset" -> 0L
                "getCurrentMediaItemIndex" -> 0
                "getMediaItemCount" -> 0
                "getRepeatMode" -> Player.REPEAT_MODE_OFF
                "getShuffleModeEnabled", "getPlayWhenReady" -> false
                "getPlayWhenReady" -> false
                "getCurrentWindowIndex" -> 0
                "getCurrentPeriodIndex" -> 0
                "hashCode" -> System.identityHashCode(method)
                else -> {
                    val type = method.returnType
                    when {
                        type == Boolean::class.javaPrimitiveType -> false
                        type == Int::class.javaPrimitiveType -> 0
                        type == Long::class.javaPrimitiveType -> 0L
                        type == Float::class.javaPrimitiveType -> 0f
                        type == Double::class.javaPrimitiveType -> 0.0
                        else -> null
                    }
                }
            }
        }
        return Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java), handler) as Player
    }

    @Test fun transport_updateMerges() {
        CastPlayback.update { it.copy(positionMs = 5000, playing = true) }
        assertEquals(5000L, CastPlayback.transport.value.positionMs)
        assertTrue(CastPlayback.transport.value.playing)
        CastPlayback.update { it.copy(positionMs = 6000) }
        assertEquals(6000L, CastPlayback.transport.value.positionMs)
        assertTrue(CastPlayback.transport.value.playing, "read-modify-write keeps other fields")
    }

    @Test fun attachDetach_identityGuard() {
        val first = fakePlayer()
        val second = fakePlayer()
        CastPlayback.attachPlayer(first)
        assertSame(first, CastPlayback.attachedPlayer)
        CastPlayback.attachPlayer(second)
        // Detaching the outgoing (old) player must not clear the new one.
        CastPlayback.detachPlayer(first)
        assertSame(second, CastPlayback.attachedPlayer)
        CastPlayback.detachPlayer(second)
        assertNull(CastPlayback.attachedPlayer)
    }

    @Test fun attachNull_detachesCurrent() {
        val player = fakePlayer()
        CastPlayback.attachPlayer(player)
        CastPlayback.attachPlayer(null)
        assertNull(CastPlayback.attachedPlayer)
    }

    @Test fun connectingThenClose_returnsToIdle() {
        CastPlayback.markConnecting()
        assertEquals(CastPlayback.State.Connecting, CastPlayback.state.value)
        CastPlayback.setNowPlaying("Video", "Author") // silent with no session
        CastPlayback.close()
        assertEquals(CastPlayback.State.Idle, CastPlayback.state.value)
        assertEquals(false, CastPlayback.transport.value.hasNext)
        CastPlayback.close() // idempotent
        assertEquals(CastPlayback.State.Idle, CastPlayback.state.value)
    }
}
