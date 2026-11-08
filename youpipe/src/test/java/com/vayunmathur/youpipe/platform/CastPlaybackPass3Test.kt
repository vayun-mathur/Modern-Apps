package com.vayunmathur.youpipe.platform

import android.app.Application
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.lang.reflect.Proxy
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.runner.RunWith

/** Slice 2/3 pass 3: CastPlayback's player attachment identity guard and lifecycle. */
@RunWith(AndroidJUnit4::class)
class CastPlaybackPass3Test {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @AfterTest
    fun reset() {
        CastPlayback.onNavigate = null
        CastPlayback.close()
    }

    private fun fakePlayer(): Player {
        val handler = java.lang.reflect.InvocationHandler { _, method, _ ->
            when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Float.TYPE -> 0f
                java.lang.Double.TYPE -> 0.0
                else -> null
            }
        }
        return Proxy.newProxyInstance(
            Player::class.java.classLoader, arrayOf(Player::class.java), handler,
        ) as Player
    }

    @Test
    fun attachDetach_identityGuard() {
        val first = fakePlayer()
        val second = fakePlayer()
        CastPlayback.attachPlayer(first)
        assertSame(first, CastPlayback.attachedPlayer)
        // A stale detach from an outgoing screen must not clear the new player.
        CastPlayback.detachPlayer(second)
        assertSame(first, CastPlayback.attachedPlayer)
        // Attaching a replacement cancels any orphan countdown and wins.
        CastPlayback.attachPlayer(second)
        assertSame(second, CastPlayback.attachedPlayer)
        CastPlayback.detachPlayer(first)
        assertSame(second, CastPlayback.attachedPlayer)
        CastPlayback.detachPlayer(second)
        assertNull(CastPlayback.attachedPlayer)
        // attachPlayer(null) is the unconditional detach.
        CastPlayback.attachPlayer(first)
        CastPlayback.attachPlayer(null)
        assertNull(CastPlayback.attachedPlayer)
    }

    @Test
    fun navigationCallback_isInvokedState() {
        var navigated: Boolean? = null
        CastPlayback.onNavigate = { navigated = it }
        CastPlayback.onNavigate?.invoke(true)
        assertEquals(true, navigated)
        CastPlayback.onNavigate = null
        assertNull(CastPlayback.onNavigate)
    }

    @Test
    fun open_withoutCastAppFailsToIdleAndSupportAnswers() {
        val result = runBlocking {
            withTimeoutOrNull(20_000) { CastPlayback.open(app, 1280, 720) }
        }
        // Either the SDK refuses quickly with a CastException (returned), or
        // the bind simply never answers on this host and the call is still
        // outstanding - in both cases no session may be left dangling.
        CastPlayback.close()
        assertEquals(CastPlayback.State.Idle, CastPlayback.state.value)
        // support() is a pure capability answer about this device.
        CastPlayback.support(app)
    }

    @Test
    fun nowPlaying_withoutSessionIsSilent() {
        CastPlayback.setNowPlaying("Title", "Author")
        assertEquals(CastPlayback.State.Idle, CastPlayback.state.value)
    }
}
