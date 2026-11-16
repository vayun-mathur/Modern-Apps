package com.vayunmathur.cast.tv.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.cast.protocol.Bye
import com.vayunmathur.cast.protocol.ContentReady
import com.vayunmathur.cast.protocol.ContentSession
import com.vayunmathur.cast.protocol.NowPlaying
import com.vayunmathur.cast.protocol.Ping
import com.vayunmathur.cast.protocol.PlayMedia
import com.vayunmathur.cast.protocol.PlaybackAction
import com.vayunmathur.cast.protocol.PlaybackCommand
import com.vayunmathur.cast.protocol.ContentEnded
import com.vayunmathur.cast.protocol.PlaybackState
import java.net.ServerSocket
import java.net.Socket
import kotlin.io.encoding.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import android.os.Looper
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * serveContent called directly: the no-decoder refusal, and an audio session
 * (fake Opus decoder installed) served until the phone says goodbye.
 */
@RunWith(RobolectricTestRunner::class)
class ServeContentDeepTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @AfterTest fun tearDown() {
        ReceiverController.contentPlayer = null
        ReceiverController.detachSurface()
        ReceiverController.forgetPlayback()
        FakeDecoders.reset()
    }

    private fun channelPair(): Pair<ControlChannel, ControlChannel> {
        val server = ServerSocket(0)
        var accepted: Socket? = null
        val thread = Thread { accepted = server.accept() }
        thread.start()
        val client = Socket("127.0.0.1", server.localPort)
        thread.join(5_000)
        server.close()
        return ControlChannel(accepted!!) to ControlChannel(client)
    }

    private fun session(video: Boolean) = ContentSession(
        host = "127.0.0.1", port = 9,
        certificateFingerprint = Base64.encode(ByteArray(32) { 3 }),
        token = "tok", video = video, appLabel = "Music",
    )

    private fun pumpMainUntil(deadlineMs: Long, done: () -> Boolean) {
        while (!done() && System.currentTimeMillis() < deadlineMs) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(25)
        }
    }

    @Test fun audioOnlyRefusedWithoutOpusDecoder() { runBlocking {
        // No fakes: this TV "has no Opus decoder", so it must refuse by name.
        val (tv, phone) = channelPair()
        val result = async(Dispatchers.Default) {
            ReceiverController.serveContent(ctx, tv, session(video = false), "Pixel")
        }
        val ready = phone.receive()?.message as ContentReady
        assertFalse(ready.accepted)
        assertTrue(ready.detail.contains("Opus"))
        pumpMainUntil(System.currentTimeMillis() + 10_000) { result.isCompleted }
        assertFalse(result.await())
        assertEquals(
            ReceiverPhase.Failed(ReceiverFailure.NoAudioDecoder),
            ReceiverController.state.value.phase,
        )
        phone.close()
    } }

    @Test fun audioSessionServedUntilBye() { runBlocking {
        FakeDecoders.install()
        val (tv, phone) = channelPair()
        val result = async(Dispatchers.Default) {
            ReceiverController.serveContent(ctx, tv, session(video = false), "Pixel")
        }
        // Phone side on IO: its receives block up to the channel timeout, so the
        // test thread stays free to pump the main looper the player hops to.
        val flow = async(Dispatchers.IO) {
            val ready = phone.receive()?.message as ContentReady
            assertTrue(ready.accepted, ready.detail)
            val phase = ReceiverController.state.value.phase
            assertTrue(phase is ReceiverPhase.Mirroring && !phase.hasVideo, phase.toString())

            phone.send(PlayMedia(resourceId = "song1", mimeType = "audio/mp4", durationMs = 30_000))
            phone.send(NowPlaying(resourceId = "song1", title = "A Song"))
            phone.send(PlaybackCommand(PlaybackAction.Toggle))
            phone.send(Ping)
            // Heartbeat reports (PlaybackState) and the ping echo interleave; read
            // until the echo proves the loop is alive.
            var echoed = false
            while (!echoed) {
                val message = phone.receive()?.message
                if (message is Ping) echoed = true
            }
            assertEquals("song1", ReceiverController.state.value.playingResourceId)
            phone.send(Bye())
            "done"
        }
        pumpMainUntil(System.currentTimeMillis() + 60_000) { flow.isCompleted && result.isCompleted }
        assertEquals("done", flow.await())
        assertFalse(result.await())
        phone.close()
    } }

    @Test fun videoSessionEndsBackToConnectedOnContentEnded() { runBlocking {
        FakeDecoders.install()
        val (tv, phone) = channelPair()
        val surface = TestSurface()
        ReceiverController.attachSurface(surface.surface)
        val result = async(Dispatchers.Default) {
            ReceiverController.serveContent(ctx, tv, session(video = true), "Pixel")
        }
        val flow = async(Dispatchers.IO) {
            val ready = phone.receive()?.message as ContentReady
            assertTrue(ready.accepted, ready.detail)
            val phase = ReceiverController.state.value.phase
            assertTrue(phase is ReceiverPhase.Mirroring && phase.hasVideo, phase.toString())
            phone.send(PlayMedia(resourceId = "clip1", mimeType = "video/mp4", durationMs = 45_000))
            phone.send(PlaybackCommand(PlaybackAction.Toggle))
            phone.send(ContentEnded)
            "done"
        }
        pumpMainUntil(System.currentTimeMillis() + 60_000) { flow.isCompleted && result.isCompleted }
        assertEquals("done", flow.await())
        // ContentEnded keeps the connection: serveContent returns true.
        assertTrue(result.await())
        phone.close()
        surface.texture.release()
    } }

    @Test fun unusableFingerprintRefusesAtPlayerBuild() { runBlocking {
        // A fingerprint that decodes but is the wrong size: the player cannot be
        // built, so the session is refused with that reason.
        val (tv, phone) = channelPair()
        val bad = ContentSession(
            host = "127.0.0.1", port = 9,
            certificateFingerprint = Base64.encode(ByteArray(8) { 5 }),
            token = "tok", video = true, appLabel = "YouPipe",
        )
        val result = async(Dispatchers.Default) {
            ReceiverController.serveContent(ctx, tv, bad, "Pixel")
        }
        val flow = async(Dispatchers.IO) { phone.receive()?.message as ContentReady }
        pumpMainUntil(System.currentTimeMillis() + 30_000) { flow.isCompleted && result.isCompleted }
        val ready = flow.await()
        assertFalse(ready.accepted)
        assertTrue(ready.detail.contains("player"), ready.detail)
        assertFalse(result.await())
        assertEquals(
            ReceiverPhase.Failed(ReceiverFailure.Handshake),
            ReceiverController.state.value.phase,
        )
        phone.close()
    } }

    @Test fun publishReportsLocallyAndFailsSoftOnDeadChannel() {
        val (tv, phone) = channelPair()
        phone.close()
        tv.close()
        // The snapshot still lands in local state even when the phone is gone.
        ReceiverController.publish(
            PlaybackState(5_000, 60_000, playing = true, buffering = false, volume = 0.75f),
            tv,
        )
        assertEquals(0.75f, ReceiverController.state.value.playback?.state?.volume)
        ReceiverController.forgetPlayback()
    }
}
