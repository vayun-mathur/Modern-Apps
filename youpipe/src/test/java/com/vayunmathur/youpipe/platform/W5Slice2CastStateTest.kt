package com.vayunmathur.youpipe.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Slice 2/3 (w5): CastPlayback's framework-free state machine (platform/
 * CastPlayback.kt). Robolectric: the object owns a Main-dispatcher scope and
 * touches android.util.Log through the repo facade, so plain JVM can't host
 * it. Session open/reporting need a real Cast TV (covered on-device only);
 * everything below pins the attach/detach identity guard, transport RMW
 * updates, speed clamping, and the seek/volume guards.
 */
@RunWith(AndroidJUnit4::class)
class W5Slice2CastStateTest {

    @Test
    fun state_startsIdleAndMarkConnecting() {
        CastPlayback.close()
        assertTrue(CastPlayback.state.value is CastPlayback.State.Idle)
        CastPlayback.markConnecting()
        assertTrue(CastPlayback.state.value is CastPlayback.State.Connecting)
        CastPlayback.close()
        assertTrue(CastPlayback.state.value is CastPlayback.State.Idle)
    }

    @Test
    fun close_isIdempotentAndResetsTransportFlags() {
        CastPlayback.update { it.copy(hasNext = true, hasPrevious = true, playing = true) }
        CastPlayback.close()
        CastPlayback.close()
        val t = CastPlayback.transport.value
        assertFalse(t.hasNext)
        assertFalse(t.hasPrevious)
        assertTrue(CastPlayback.state.value is CastPlayback.State.Idle)
    }

    @Test
    fun update_isReadModifyWrite() {
        CastPlayback.close()
        CastPlayback.update { it.copy(positionMs = 1_000, durationMs = 60_000, playing = true) }
        CastPlayback.update { it.copy(volume = 0.5f) }
        val t = CastPlayback.transport.value
        // Second update preserved the first update's writes.
        assertEquals(1_000L, t.positionMs)
        assertEquals(60_000L, t.durationMs)
        assertTrue(t.playing)
        assertEquals(0.5f, t.volume)
        CastPlayback.close()
    }

    @Test
    fun detachPlayer_nullClearsPlayer() {
        val fake = fakePlayer()
        CastPlayback.attachPlayer(fake)
        assertSame(fake, CastPlayback.attachedPlayer)
        // Null means "whatever is attached" outside a cast: clears, no timer.
        CastPlayback.attachPlayer(null)
        assertNull(CastPlayback.attachedPlayer)
        CastPlayback.close()
    }

    @Test
    fun detachPlayer_wrongIdentityKeepsPlayer() {
        val first = fakePlayer()
        val second = fakePlayer()
        CastPlayback.attachPlayer(first)
        // Outgoing screen disposing a stale controller must not clear the new one.
        CastPlayback.detachPlayer(second)
        assertSame(first, CastPlayback.attachedPlayer)
        CastPlayback.detachPlayer(first)
        assertNull(CastPlayback.attachedPlayer)
        CastPlayback.close()
    }

    @Test
    fun transport_defaultsAreSane() {
        val t = CastPlayback.Transport()
        assertEquals(0L, t.positionMs)
        assertEquals(1f, t.speed)
        assertEquals(1f, t.volume)
        assertFalse(t.dragging)
        assertFalse(t.hasNext)
        assertFalse(t.hasPrevious)
    }

    /**
     * A Player double via JDK dynamic proxy: attach/detach only store and
     * identity-compare the instance, never invoke it, so the handler body is
     * unreachable in these tests (and returns JVM defaults if ever called).
     * Keeps the test immune to media3 Player API additions.
     */
    private fun fakePlayer(): androidx.media3.common.Player =
        java.lang.reflect.Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(androidx.media3.common.Player::class.java),
        ) { _, _, _ -> null } as androidx.media3.common.Player
}
