package com.vayunmathur.auto.platform

import com.vayunmathur.auto.protocol.GalConnection
import com.vayunmathur.auto.protocol.GalMessage
import com.vayunmathur.auto.protocol.GalService
import com.vayunmathur.auto.protocol.GalTransport
import com.vayunmathur.auto.protocol.SensorCodec
import com.vayunmathur.auto.protocol.gal.SensorResponse
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import javax.net.ssl.SSLContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Auto module: [SensorChannel] — the ch7 sensor source. The grant subscribes
 * to the advertised set and seeds night from the phone; answers attribute
 * FIFO; batches fold into the snapshot; wrong-channel traffic is ignored;
 * release unsubscribes. Sends ride a closed real connection — see
 * [InputChannelTest] for the seam.
 */
@RunWith(RobolectricTestRunner::class)
class SensorChannelTest {

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

    private fun channel(
        events: MutableList<SensorEvent> = mutableListOf(),
        night: Boolean = false,
    ): SensorChannel = SensorChannel(
        connection(),
        night = NightSource { night },
        onEvent = { events += it },
    )

    private fun answer(status: Int = 0): ByteArray =
        SensorResponse.newBuilder().setStatus(status).build().toByteArray()

    @Test
    fun `channel id is the sensor source`() {
        assertEquals(GalService.SENSOR_SOURCE.id, channel().channelId)
    }

    @Test
    fun `the grant subscribes to every advertised type and seeds night`() {
        val events = mutableListOf<SensorEvent>()
        val seen = mutableListOf<com.vayunmathur.auto.protocol.SensorSnapshot>()
        val channel = SensorChannel(
            connection(),
            night = NightSource { true },
            onEvent = { events += it },
            onValues = { seen += it },
        )
        channel.onChannelOpen()
        assertEquals(SensorCodec.STUB_SUBSCRIPTIONS.size, events.count { it is SensorEvent.Subscribed })
        assertTrue(channel.snapshot.value.isNight == true)
        // The phone seed is pre-grant: no live values posted yet.
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `answers attribute FIFO to the oldest subscribe`() {
        val events = mutableListOf<SensorEvent>()
        val channel = channel(events)
        channel.onChannelOpen()
        val first = SensorCodec.STUB_SUBSCRIPTIONS.first()
        channel.onMessage(7, GalMessage.Sensor.SENSOR_RESPONSE, answer(0))
        val answered = events.filterIsInstance<SensorEvent.SubscriptionAnswered>()
        assertEquals(1, answered.size)
        assertEquals(first.name, answered.single().type)
        assertEquals(0, answered.single().status)
    }

    @Test
    fun `an unsolicited answer is observed never fatal`() {
        val events = mutableListOf<SensorEvent>()
        val channel = channel(events)
        // No grant: no pending subscribes, so the answer is unsolicited.
        // Pre-grant traffic still sweeps timeouts only; the answer decodes
        // through the no-subscribe path as UNSOLICITED.
        channel.onChannelOpen()
        repeat(SensorCodec.STUB_SUBSCRIPTIONS.size) {
            channel.onMessage(7, GalMessage.Sensor.SENSOR_RESPONSE, answer(0))
        }
        channel.onMessage(7, GalMessage.Sensor.SENSOR_RESPONSE, answer(0))
        val answered = events.filterIsInstance<SensorEvent.SubscriptionAnswered>()
        assertEquals("UNSOLICITED", answered.last().type)
    }

    @Test
    fun `a compass batch folds into the snapshot`() {
        val events = mutableListOf<SensorEvent>()
        val channel = channel(events)
        channel.onChannelOpen()
        channel.onMessage(
            7, GalMessage.Sensor.SENSOR_BATCH,
            SensorCodec.buildCompassBatch(headingDeg = 270),
        )
        assertEquals(270, channel.snapshot.value.compassHeadingDeg)
        assertEquals(1, events.count { it is SensorEvent.BatchReceived })
    }

    @Test
    fun `live posts fold like head-unit batches and drop pre-grant`() {
        val events = mutableListOf<SensorEvent>()
        val channel = channel(events)
        val batch = SensorCodec.decodeInbound(
            GalMessage.Sensor.SENSOR_BATCH,
            SensorCodec.buildCompassBatch(headingDeg = 90),
        ) as com.vayunmathur.auto.protocol.InboundSensor.Readings
        // Pre-grant: dropped, never queued.
        channel.postLiveEvents(batch.events)
        assertTrue(events.isEmpty())
        channel.onChannelOpen()
        channel.postLiveEvents(batch.events)
        assertEquals(90, channel.snapshot.value.compassHeadingDeg)
        assertEquals(1, events.count { it is SensorEvent.BatchReceived })
    }

    @Test
    fun `wrong-channel traffic is ignored`() {
        val events = mutableListOf<SensorEvent>()
        val channel = channel(events)
        channel.onChannelOpen()
        val subscribes = events.count { it is SensorEvent.Subscribed }
        channel.onMessage(8, GalMessage.Sensor.SENSOR_BATCH, SensorCodec.buildCompassBatch(90))
        assertEquals(subscribes, events.count { it is SensorEvent.Subscribed })
        assertTrue(events.filterIsInstance<SensorEvent.BatchReceived>().isEmpty())
    }

    @Test
    fun `release unsubscribes and parks live posts`() {
        val events = mutableListOf<SensorEvent>()
        val channel = channel(events)
        channel.onChannelOpen()
        channel.release()
        // Double release is safe (idempotent park).
        channel.release()
        val batch = SensorCodec.decodeInbound(
            GalMessage.Sensor.SENSOR_BATCH,
            SensorCodec.buildCompassBatch(headingDeg = 90),
        ) as com.vayunmathur.auto.protocol.InboundSensor.Readings
        channel.postLiveEvents(batch.events)
        assertFalse(channel.snapshot.value.compassHeadingDeg == 90)
    }
}
