package com.vayunmathur.auto.platform

import com.vayunmathur.auto.protocol.GalConnection
import com.vayunmathur.auto.protocol.GalTransport
import com.vayunmathur.auto.protocol.NavStatusUpdate
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import javax.net.ssl.SSLContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Auto module: [NavStatusChannel] — the phone-to-head-unit turn guidance
 * post on ch10. The grant posts the inactive stub; live updates dedupe
 * identical reposts; pre-grant posts drop; inbound traffic is observed and
 * ignored. Sends ride a closed real connection (dropped silently, events
 * still fire) — see [InputChannelTest] for the seam.
 */
@RunWith(RobolectricTestRunner::class)
class NavStatusChannelTest {

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

    @Test
    fun `channel id is the navigation-status service`() {
        assertEquals(10, NavStatusChannel(connection()).channelId)
    }

    @Test
    fun `the grant posts the inactive stub`() {
        val events = mutableListOf<SensorEvent>()
        NavStatusChannel(connection(), onEvent = { events += it }).onChannelOpen()
        assertEquals(listOf<SensorEvent>(SensorEvent.NavStatusPosted), events)
    }

    @Test
    fun `pre-grant posts drop without an event`() {
        val events = mutableListOf<SensorEvent>()
        val channel = NavStatusChannel(connection(), onEvent = { events += it })
        channel.postStatus(guidanceActive = true, nextRoad = "El Camino")
        channel.postUpdate(NavStatusUpdate(guidanceActive = true))
        assertTrue(events.isEmpty())
    }

    @Test
    fun `live posts go out and identical updates dedupe`() {
        val events = mutableListOf<SensorEvent>()
        val channel = NavStatusChannel(connection(), onEvent = { events += it })
        channel.onChannelOpen()
        // postStatus is a direct send: every call posts.
        channel.postStatus(guidanceActive = true, nextRoad = "El Camino")
        assertEquals(2, events.count { it is SensorEvent.NavStatusPosted })
        // postUpdate dedupes against the last update: the grant-time inactive
        // stub is the baseline, so the first live update posts once and the
        // identical repost is skipped.
        channel.postUpdate(NavStatusUpdate(guidanceActive = true, nextRoad = "El Camino"))
        channel.postUpdate(NavStatusUpdate(guidanceActive = true, nextRoad = "El Camino"))
        assertEquals(3, events.count { it is SensorEvent.NavStatusPosted })
        channel.postUpdate(NavStatusUpdate(guidanceActive = false))
        assertEquals(4, events.count { it is SensorEvent.NavStatusPosted })
    }

    @Test
    fun `inbound traffic is observed and ignored`() {
        val events = mutableListOf<SensorEvent>()
        val channel = NavStatusChannel(connection(), onEvent = { events += it })
        channel.onChannelOpen()
        val before = events.size
        channel.onMessage(10, 0x8001)
        assertEquals(before, events.size)
        // Another channel's traffic is ignored too.
        channel.onMessage(7, 0x8001)
        assertEquals(before, events.size)
    }

    @Test
    fun `release parks the channel`() {
        val events = mutableListOf<SensorEvent>()
        val channel = NavStatusChannel(connection(), onEvent = { events += it })
        channel.onChannelOpen()
        channel.release()
        channel.postStatus(guidanceActive = true)
        assertEquals(1, events.count { it is SensorEvent.NavStatusPosted })
    }
}
