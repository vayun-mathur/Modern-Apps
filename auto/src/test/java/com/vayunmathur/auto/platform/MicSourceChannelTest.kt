package com.vayunmathur.auto.platform

import com.vayunmathur.auto.protocol.GalConnection
import com.vayunmathur.auto.protocol.GalMessage
import com.vayunmathur.auto.protocol.GalService
import com.vayunmathur.auto.protocol.GalTransport
import org.junit.runner.RunWith
import org.junit.Rule
import org.junit.rules.Timeout
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.LooperMode
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.ssl.SSLContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Auto module: [MicSourceChannel] — the car microphone. Requests ack
 * opaquely; bulk chunks ack without retention until a permission-gated turn
 * buffers them; wrong-channel traffic is ignored. The mic thread is real
 * (HandlerThread), so turn tests drain it with a blocking round-trip.
 * Sends ride a closed real connection — see [InputChannelTest] for the seam.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.LEGACY)
class MicSourceChannelTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

    /** Tripped by the next observed event; set by [drain] as a thread barrier. */
    @Volatile
    private var barrier: java.util.concurrent.CountDownLatch? = null

    private fun observe(events: MutableList<AudioEvent>): (AudioEvent) -> Unit = { event ->
        events += event
        barrier?.countDown()
    }

    private class FakeTransport : GalTransport {
        val written = ByteArrayOutputStream()
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            written.write(bytes, offset, length)
        }
        override fun flush() = Unit
        override fun close() = Unit
    }

    private fun connection(): GalConnection {
        val context = SSLContext.getInstance("TLS").apply { init(null, null, null) }
        return GalConnection(FakeTransport(), context, deviceModel = "Test").also { it.close() }
    }

    private fun chunk(pcm: ByteArray): ByteArray {
        val header = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(123L).array()
        return header + pcm
    }

    @Test
    fun `channel id is the audio source`() {
        assertEquals(GalService.AUDIO_SOURCE.id, MicSourceChannel(connection()).channelId)
    }

    @Test
    fun `wrong-channel traffic is ignored`() {
        val events = mutableListOf<AudioEvent>()
        val channel = MicSourceChannel(connection(), onEvent = observe(events))
        channel.onChannelOpen()
        channel.onMessage(8, GalMessage.Microphone.REQUEST, byteArrayOf())
        channel.release()
        assertTrue(events.isEmpty())
    }

    @Test
    fun `mic requests ack without events`() {
        val events = mutableListOf<AudioEvent>()
        val channel = MicSourceChannel(connection(), onEvent = observe(events))
        channel.onChannelOpen()
        channel.onMessage(channel.channelId, GalMessage.Microphone.REQUEST, byteArrayOf(1, 2))
        // The ack rides the closed connection (dropped); the request itself
        // is acked opaquely and counted, never parsed.
        channel.release()
        assertEquals(listOf<AudioEvent>(AudioEvent.MicAcked), events)
    }

    @Test
    fun `chunks without a turn ack and count idle`() {
        val events = mutableListOf<AudioEvent>()
        val channel = MicSourceChannel(connection(), onEvent = observe(events))
        channel.onChannelOpen()
        channel.onMessage(channel.channelId, GalMessage.Media.DATA_WITH_TIMESTAMP, chunk(byteArrayOf(1, 2, 3)))
        drain(channel)
        channel.release()
        // The chunk and the barrier chunk each observe idle plus their ack.
        assertEquals(4, events.size)
        assertEquals(
            listOf(AudioEvent.MicIdle, AudioEvent.MicAcked, AudioEvent.MicIdle, AudioEvent.MicAcked),
            events,
        )
    }

    @Test
    fun `unparseable traffic is observed and ignored`() {
        val events = mutableListOf<AudioEvent>()
        val channel = MicSourceChannel(connection(), onEvent = observe(events))
        channel.onChannelOpen()
        channel.onMessage(channel.channelId, 0x1234, byteArrayOf(1))
        channel.onMessage(channel.channelId, GalMessage.Media.DATA, byteArrayOf())
        channel.release()
        assertTrue(events.isEmpty())
    }

    @Test
    fun `a turn without retention yields nothing`() {
        val events = mutableListOf<AudioEvent>()
        var callbackFired = false
        val channel = MicSourceChannel(
            connection(),
            retentionAllowed = { false },
            onEvent = observe(events),
        )
        channel.onChannelOpen()
        channel.beginTurn { callbackFired = true }
        channel.onMessage(channel.channelId, GalMessage.Media.DATA_WITH_TIMESTAMP, chunk(byteArrayOf(9)))
        channel.endTurn()
        drain(channel)
        channel.release()
        assertEquals(false, callbackFired)
    }

    @Test
    fun `a retained turn hands the buffered pcm to its callback`() {
        val events = mutableListOf<AudioEvent>()
        var result: ByteArray? = null
        val channel = MicSourceChannel(
            connection(),
            retentionAllowed = { true },
            onEvent = observe(events),
        )
        channel.onChannelOpen()
        channel.beginTurn { result = it }
        channel.onMessage(channel.channelId, GalMessage.Media.DATA_WITH_TIMESTAMP, chunk(byteArrayOf(1, 2)))
        channel.onMessage(channel.channelId, GalMessage.Media.DATA_WITH_TIMESTAMP, chunk(byteArrayOf(3, 4, 5)))
        channel.endTurn()
        drain(channel)
        channel.release()
        val pcm = result ?: error("expected retained pcm")
        assertEquals(listOf<Byte>(1, 2, 3, 4, 5), pcm.toList())
        assertEquals(1, events.count { it is AudioEvent.MicTurn })
        assertEquals(5L, (events.single { it is AudioEvent.MicTurn } as AudioEvent.MicTurn).bytes)
    }

    /**
     * Barrier on the mic thread: posts one chunk after every earlier post
     * (same FIFO handler). With no turn open the chunk observes MicIdle plus
     * its ack, which trips the latch — so await returns only after all
     * earlier posts ran. The barrier's own events stay in [events]; tests
     * that assert exact contents account for them.
     */
    private fun drain(channel: MicSourceChannel) {
        val latch = java.util.concurrent.CountDownLatch(1)
        barrier = latch
        try {
            channel.onMessage(
                channel.channelId,
                GalMessage.Media.DATA_WITH_TIMESTAMP,
                chunk(byteArrayOf(0)),
            )
            // The mic looper is paused under Robolectric: idle every known
            // looper (a no-op for idle ones) so the barrier chunk runs.
            for (looper in org.robolectric.shadows.ShadowLooper.getAllLoopers()) {
                runCatching { Shadows.shadowOf(looper).idle() }
            }
            assertTrue(
                latch.await(5, java.util.concurrent.TimeUnit.SECONDS),
                "mic thread did not drain",
            )
        } finally {
            barrier = null
        }
    }
}
