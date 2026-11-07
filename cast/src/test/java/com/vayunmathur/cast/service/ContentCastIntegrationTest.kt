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
import com.vayunmathur.cast.platform.submitPairCode
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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows

/**
 * A served SDK session end to end on the JVM: pair with the fake TV, open through the
 * real Messenger handler, exercise every message kind, then close.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContentCastIntegrationTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before fun reset() = runBlocking { CastController.teardown() }
    @After fun cleanup() = runBlocking { CastController.teardown() }

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
            if (System.currentTimeMillis() > deadline) throw AssertionError("timeout; condition not met")
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `served session opens forwards and closes through messenger`() = runBlocking {
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
                    putBoolean(CastContract.KEY_SERVE_RESOURCES, true)
                }
            })
            pumpUntil { received.any { it.what == CastContract.MSG_SESSION_READY } }
            val ready = received.first { it.what == CastContract.MSG_SESSION_READY }
            assertEquals("Fake TV", ready.data.getString(CastContract.KEY_RECEIVER_NAME))
            assertTrue(ready.data.getBoolean(CastContract.KEY_WANT_VIDEO))

            // Every gated message kind, now that a session is open.
            incoming.send(Message.obtain(null, CastContract.MSG_PLAYBACK_STATE).apply {
                data = Bundle().apply {
                    putLong(CastContract.KEY_POSITION_MS, 5_000)
                    putLong(CastContract.KEY_DURATION_MS, 60_000)
                    putBoolean(CastContract.KEY_PLAYING, true)
                    putFloat(CastContract.KEY_SPEED, 1.25f)
                }
            })
            incoming.send(Message.obtain(null, CastContract.MSG_PLAY_MEDIA).apply {
                data = Bundle().apply {
                    putString(CastContract.KEY_RESOURCE_ID, "song-1")
                    putString(CastContract.KEY_RESOURCE_TYPE, "audio/mpeg")
                    putLong(CastContract.KEY_MEDIA_DURATION_MS, 60_000)
                }
            })
            incoming.send(Message.obtain(null, CastContract.MSG_SEND_PLAYBACK_COMMAND).apply {
                data = Bundle().apply {
                    putInt(CastContract.KEY_ACTION, CastContract.ACTION_SEEK_TO)
                    putDouble(CastContract.KEY_ACTION_VALUE, 12_000.0)
                }
            })
            incoming.send(Message.obtain(null, CastContract.MSG_SEND_PLAYBACK_COMMAND).apply {
                data = Bundle().apply { putInt(CastContract.KEY_ACTION, 999) } // unknown: dropped
            })
            incoming.send(Message.obtain(null, CastContract.MSG_SET_NOW_PLAYING).apply {
                data = Bundle().apply {
                    putString(CastContract.KEY_RESOURCE_ID, "song-1")
                    putString(CastContract.KEY_TITLE, "Blue Monday")
                    putString(CastContract.KEY_AUTHOR, "New Order")
                }
            })
            incoming.send(Message.obtain(null, CastContract.MSG_SET_NOW_PLAYING).apply {
                data = Bundle() // nothing displayable: dropped inside nowPlayingOf
            })
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            await { tv.received.any { it is com.vayunmathur.cast.protocol.PlayMedia } }
            await { tv.received.any { it is com.vayunmathur.cast.protocol.NowPlaying } }

            // TV-originated playback state and remote presses flow back to the
            // client through the watch dispatch.
            tv.push(
                com.vayunmathur.cast.protocol.PlaybackState(
                    42_000, 90_000, playing = false, buffering = false,
                    speed = 1f, volume = 0.25f, hasNext = true, hasPrevious = true, ended = true,
                ),
            )
            tv.push(
                com.vayunmathur.cast.protocol.PlaybackCommand(
                    com.vayunmathur.cast.protocol.PlaybackAction.SetVolume, 0.5,
                ),
            )
            pumpUntil {
                received.any { it.what == CastContract.MSG_TV_PLAYBACK_STATE } &&
                    received.any { it.what == CastContract.MSG_PLAYBACK_COMMAND }
            }
            val tvState = received.first { it.what == CastContract.MSG_TV_PLAYBACK_STATE }
            kotlin.test.assertEquals(42_000L, tvState.data.getLong(CastContract.KEY_POSITION_MS))
            kotlin.test.assertEquals(true, tvState.data.getBoolean(CastContract.KEY_ENDED))
            val tvCommand = received.first { it.what == CastContract.MSG_PLAYBACK_COMMAND }
            kotlin.test.assertEquals(
                CastContract.ACTION_SET_VOLUME, tvCommand.data.getInt(CastContract.KEY_ACTION),
            )
            kotlin.test.assertEquals(0.5, tvCommand.data.getDouble(CastContract.KEY_ACTION_VALUE))

            // Close from the client: SESSION_ENDED is answered with the client reason.
            incoming.send(Message.obtain(null, CastContract.MSG_CLOSE_SESSION))
            pumpUntil {
                received.any {
                    it.what == CastContract.MSG_SESSION_ENDED &&
                        it.data.getInt(CastContract.KEY_END_REASON) == CastContract.REASON_CLIENT_CLOSED
                }
            }
            // A second close is a no-op (session already closed).
            incoming.send(Message.obtain(null, CastContract.MSG_CLOSE_SESSION))
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(false, service.onUnbind(null))
        } finally {
            controller.destroy()
            tv.close()
        }
    }
}
