package com.vayunmathur.cast.service

import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.cast.domain.CastDevice
import com.vayunmathur.cast.domain.ClientPhase
import com.vayunmathur.cast.platform.CastController
import com.vayunmathur.cast.platform.FakeTv
import com.vayunmathur.cast.platform.connect
import com.vayunmathur.cast.platform.startContentSession
import com.vayunmathur.cast.platform.submitPairCode
import com.vayunmathur.cast.protocol.PlaybackState
import com.vayunmathur.cast.protocol.StreamConfig
import com.vayunmathur.sdk.cast.CastContract
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * An encoded (not served) SDK session through the real service: with a
 * surface-producing shadow codec the engine starts, so the client is answered
 * with a surface and the granted geometry, and playback state flows to the TV.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(shadows = [com.vayunmathur.cast.platform.mirror.ShadowMediaCodecSynthesizing::class])
class ContentStartedTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        com.vayunmathur.cast.platform.mirror.FakeEncoders.install()
        runBlocking { CastController.teardown() }
    }

    @After fun tearDown() {
        runBlocking { CastController.teardown() }
        com.vayunmathur.cast.platform.mirror.FakeEncoders.reset()
    }

    private suspend fun await(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timeout")
            delay(15)
        }
    }

    private fun pumpUntil(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("pump timeout")
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `encoded session starts answers with a surface and forwards state`() = runBlocking {
        val tv = FakeTv()
        val controller = Robolectric.buildService(ContentCastService::class.java)
        try {
            val target = CastDevice(id = "tv-fake-1", friendlyName = "Fake TV", host = "127.0.0.1", port = tv.port)
            CastController.connect(app, target, thenMirror = false)
            await { CastController.sessionState.value.phase == ClientPhase.AwaitingCode }
            CastController.submitPairCode(app, FakeTv.CODE)
            await { CastController.sessionState.value.phase == ClientPhase.Paired }

            val service = controller.create().get()
            val incoming = Messenger(assertNotNull(service.onBind(null)))
            val received = mutableListOf<Message>()
            val replyTo = Messenger(Handler(Looper.getMainLooper()) { msg ->
                received += Message.obtain(msg)
                true
            })

            incoming.send(Message.obtain(null, CastContract.MSG_OPEN_SESSION).apply {
                this.replyTo = replyTo
                data = Bundle().apply {
                    putInt(CastContract.KEY_WIDTH, 1280)
                    putInt(CastContract.KEY_HEIGHT, 720)
                    putBoolean(CastContract.KEY_WANT_AUDIO, true)
                }
            })
            pumpUntil { received.any { it.what == CastContract.MSG_SESSION_READY } }
            val ready = received.first { it.what == CastContract.MSG_SESSION_READY }
            assertEquals("Fake TV", ready.data.getString(CastContract.KEY_RECEIVER_NAME))
            assertTrue(ready.data.getInt(CastContract.KEY_GRANTED_WIDTH) > 0)
            assertTrue(ready.data.getInt(CastContract.KEY_GRANTED_HEIGHT) > 0)
            assertTrue(tv.received.any { it is StreamConfig })

            // Playback state from the client reaches the television.
            incoming.send(Message.obtain(null, CastContract.MSG_PLAYBACK_STATE).apply {
                data = Bundle().apply {
                    putLong(CastContract.KEY_POSITION_MS, 7_000)
                    putLong(CastContract.KEY_DURATION_MS, 90_000)
                    putBoolean(CastContract.KEY_PLAYING, true)
                }
            })
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            await { tv.received.any { it is PlaybackState } }

            incoming.send(Message.obtain(null, CastContract.MSG_CLOSE_SESSION))
            pumpUntil {
                received.any {
                    it.what == CastContract.MSG_SESSION_ENDED &&
                        it.data.getInt(CastContract.KEY_END_REASON) == CastContract.REASON_CLIENT_CLOSED
                }
            }
            Unit
        } finally {
            controller.destroy()
            tv.close()
        }
    }

    @Test
    fun `controller starts a video only encoded session`() = runBlocking {
        val tv = FakeTv()
        try {
            val target = CastDevice(id = "tv-fake-1", friendlyName = "Fake TV", host = "127.0.0.1", port = tv.port)
            CastController.connect(app, target, thenMirror = false)
            await { CastController.sessionState.value.phase == ClientPhase.AwaitingCode }
            CastController.submitPairCode(app, FakeTv.CODE)
            await { CastController.sessionState.value.phase == ClientPhase.Paired }
            val result = CastController.startContentSession(
                app, 1280, 720, wantAudio = false, appLabel = "Music",
            )
            assertTrue(result is com.vayunmathur.cast.platform.ContentSessionResult.Started, "got $result")
            val started = result as com.vayunmathur.cast.platform.ContentSessionResult.Started
            assertTrue(started.audioWriteEnd == null, "no audio was asked for, so no pipe")
            assertEquals("Fake TV", started.receiverName)
            CastController.stopMirroring(app)
            await { CastController.mirrorPhase.value == com.vayunmathur.cast.platform.MirrorPhase.Idle }
        } finally {
            tv.close()
        }
        Unit
    }
}
