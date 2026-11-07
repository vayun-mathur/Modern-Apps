package com.vayunmathur.cast.tv.platform

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.cast.protocol.Bye
import com.vayunmathur.cast.protocol.ContentEnded
import com.vayunmathur.cast.protocol.ContentReady
import com.vayunmathur.cast.protocol.ContentSession
import com.vayunmathur.cast.protocol.Hello
import com.vayunmathur.cast.protocol.NowPlaying
import com.vayunmathur.cast.protocol.PairOk
import com.vayunmathur.cast.protocol.PairProof
import com.vayunmathur.cast.protocol.PairRequired
import com.vayunmathur.cast.protocol.Ping
import com.vayunmathur.cast.protocol.PlayMedia
import com.vayunmathur.cast.protocol.PlaybackAction
import com.vayunmathur.cast.protocol.PlaybackCommand
import com.vayunmathur.cast.protocol.ProtocolBase64
import com.vayunmathur.cast.protocol.SealedSecret
import com.vayunmathur.cast.protocol.SecretSealing
import com.vayunmathur.cast.protocol.SessionKeys
import com.vayunmathur.cast.protocol.Transcript
import com.vayunmathur.cast.protocol.TvIdentity
import java.net.Socket
import kotlin.io.encoding.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ContentSessionSuccessTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun servedContentSessionPlaysAndEndsBackToConnected() { runBlocking {
        ReceiverController.start(ctx)
        // Wait for advertising on a background-friendly poll (main thread also pumps the looper below).
        val flow = async(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + 30_000
            while (ReceiverController.state.value.phase !is ReceiverPhase.Advertising) {
                check(System.currentTimeMillis() < deadline) { "never advertised" }
                Thread.sleep(50)
            }
            val channel = ControlChannel(Socket("127.0.0.1", ReceiverController.serverSocket!!.localPort))
            val transcript = Transcript()
            transcript.add(channel.send(Hello(senderName = "Pixel", senderId = "content-ok-1", paired = false)))
            val identityReceived = channel.receive()!!
            transcript.add(identityReceived.body)
            val bundle = ProtocolBase64.decode((identityReceived.message as TvIdentity).publicBundle)!!
            val secret = ByteArray(32) { 4 }
            transcript.add(channel.send(SealedSecret(ProtocolBase64.encode(SecretSealing.seal(bundle, secret)))))
            val keys = SessionKeys.of(secret)
            channel.codec.useSessionKey(keys.control)
            assertTrue(channel.receive()?.message is PairRequired)
            // Read the on-screen code from state.
            var code: String? = null
            val codeDeadline = System.currentTimeMillis() + 10_000
            while (code == null && System.currentTimeMillis() < codeDeadline) {
                code = (ReceiverController.state.value.phase as? ReceiverPhase.Pairing)?.code
                if (code == null) Thread.sleep(50)
            }
            channel.send(PairProof(ProtocolBase64.encode(keys.pairProof(code!!, transcript.value()))))
            assertTrue(channel.receive()?.message is PairOk)
            channel.send(ContentSession(host = "127.0.0.1", port = 9, certificateFingerprint = Base64.encode(ByteArray(32) { 1 }), token = "tok", video = true, appLabel = "YouPipe"))
            val ready = channel.receive()?.message as ContentReady
            assertEquals(true, ready.accepted)
            channel.send(PlayMedia(resourceId = "r1", mimeType = "video/mp4", durationMs = 60_000))
            channel.send(NowPlaying(resourceId = "r1", title = "Clip"))
            channel.send(PlaybackCommand(PlaybackAction.Toggle))
            channel.send(PlaybackCommand(PlaybackAction.Next)) // refused by player, ignored by loop
            channel.send(Ping)
            // The reporting coroutine interleaves PlaybackState snapshots on the same channel.
            var echoed = false
            repeat(20) { if (channel.receive()?.message is Ping) { echoed = true; return@repeat } }
            assertTrue(echoed)
            channel.send(ContentEnded)
            // Back in the configuration loop: a Bye now ends the connection.
            Thread.sleep(300)
            channel.send(Bye())
            Thread.sleep(300)
            channel.close()
            "done"
        }
        val deadline = System.currentTimeMillis() + 60_000
        while (flow.isActive && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            delay(50)
        }
        assertEquals("done", flow.await())
        ReceiverController.stop()
    } }

    @Test fun servedSessionHeartbeatAndByeInsideServeLoop() { runBlocking {
        ReceiverController.start(ctx)
        val flow = async(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + 30_000
            while (ReceiverController.state.value.phase !is ReceiverPhase.Advertising) {
                check(System.currentTimeMillis() < deadline) { "never advertised" }
                Thread.sleep(50)
            }
            val channel = ControlChannel(Socket("127.0.0.1", ReceiverController.serverSocket!!.localPort))
            val transcript = Transcript()
            transcript.add(channel.send(Hello(senderName = "Pixel", senderId = "content-bye-1", paired = false)))
            val identityReceived = channel.receive()!!
            transcript.add(identityReceived.body)
            val bundle = ProtocolBase64.decode((identityReceived.message as TvIdentity).publicBundle)!!
            val secret = ByteArray(32) { 6 }
            transcript.add(channel.send(SealedSecret(ProtocolBase64.encode(SecretSealing.seal(bundle, secret)))))
            val keys = SessionKeys.of(secret)
            channel.codec.useSessionKey(keys.control)
            assertTrue(channel.receive()?.message is PairRequired)
            var code: String? = null
            val codeDeadline = System.currentTimeMillis() + 10_000
            while (code == null && System.currentTimeMillis() < codeDeadline) {
                code = (ReceiverController.state.value.phase as? ReceiverPhase.Pairing)?.code
                if (code == null) Thread.sleep(50)
            }
            channel.send(PairProof(ProtocolBase64.encode(keys.pairProof(code!!, transcript.value()))))
            assertTrue(channel.receive()?.message is PairOk)
            channel.send(ContentSession(host = "127.0.0.1", port = 9, certificateFingerprint = Base64.encode(ByteArray(32) { 2 }), token = "tok", video = false, appLabel = "Music"))
            // video=false but this test only proceeds if the player path accepts; on a JVM with an
            // Opus decoder the session is served, otherwise it is refused — both are valid answers.
            val ready = channel.receive()?.message as ContentReady
            if (ready.accepted) {
                channel.send(PlayMedia(resourceId = "song1", mimeType = "audio/mp4", durationMs = 30_000))
                // Let at least two heartbeats fire, then exercise stray-message and Bye handling.
                Thread.sleep(1_300)
                channel.send(com.vayunmathur.cast.protocol.PlaybackState(1_000, 30_000, playing = true, buffering = false))
                channel.send(Bye())
                Thread.sleep(300)
            }
            channel.close()
            "done"
        }
        val deadline = System.currentTimeMillis() + 60_000
        while (flow.isActive && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            delay(50)
        }
        assertEquals("done", flow.await())
        ReceiverController.stop()
    } }
}
