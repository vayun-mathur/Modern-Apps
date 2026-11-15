package com.vayunmathur.auto.service

import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.auto.platform.MusicCaptureSinkHolder
import com.vayunmathur.auto.protocol.AudioSinkRole
import com.vayunmathur.auto.protocol.ChannelMessage
import com.vayunmathur.auto.protocol.GalConnection
import com.vayunmathur.auto.protocol.GalService
import com.vayunmathur.auto.protocol.GalTransport
import com.vayunmathur.auto.protocol.gal.InputSourceService
import com.vayunmathur.auto.protocol.gal.MediaSinkService
import com.vayunmathur.auto.protocol.gal.MediaSourceService
import com.vayunmathur.auto.protocol.gal.SensorSourceService
import com.vayunmathur.auto.protocol.gal.Service
import com.vayunmathur.auto.protocol.gal.VideoConfiguration
import com.vayunmathur.auto.protocol.gal.VideoResolution
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import javax.net.ssl.SSLContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [ProjectionService] channel bring-up (`openNext` factories in
 * ProjectionServiceChannels.kt) and inbound routing. Each case drives one
 * advertised service through a fresh closed connection whose session lists
 * are seeded by reflection, then asserts which owner the service created.
 */
@RunWith(RobolectricTestRunner::class)
class ProjectionChannelsTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private class FakeTransport : GalTransport {
        val written = ByteArrayOutputStream()
        override fun read(b: ByteArray, o: Int, l: Int): Int = -1
        override fun write(b: ByteArray, o: Int, l: Int) { written.write(b, o, l) }
        override fun flush() = Unit
        override fun close() = Unit
    }

    private fun connection(): GalConnection {
        val ssl = SSLContext.getInstance("TLS").apply { init(null, null, null) }
        return GalConnection(FakeTransport(), ssl, deviceModel = "Test").also { it.close() }
    }

    private fun setSession(connection: GalConnection, name: String, value: Any?) {
        val f = connection.session.javaClass.getDeclaredField(name).apply { isAccessible = true }
        f.set(connection.session, value)
    }

    private fun svcProto(block: Service.Builder.() -> Unit): Service =
        Service.newBuilder().apply(block).build()

    private fun mediaSink(): MediaSinkService = MediaSinkService.newBuilder()
        .addVideoConfigs(
            VideoConfiguration.newBuilder()
                .setCodecResolution(VideoResolution.VIDEO_1280x720).setFrameRate(30).setDensity(160)
                .build(),
        ).build()

    private fun newService(): ProjectionService =
        Robolectric.buildService(ProjectionService::class.java).create().get()

    private fun openOne(svc: Service): Pair<ProjectionService, GalConnection> {
        val service = newService()
        val connection = connection()
        setSession(connection, "services", listOf(svc))
        service.openNext(connection)
        return service to connection
    }

    @Test fun `pending open blocks any new channel`() {
        val service = newService()
        val connection = connection()
        setSession(connection, "services", listOf(svcProto { setId(GalService.VIDEO_SINK.id); setMediaSink(mediaSink()) }))
        setSession(connection, "pendingChannels", listOf(GalService.VIDEO_SINK.id))
        service.openNext(connection)
        assertNull(service.video)
    }

    @Test fun `already open and refused services are skipped`() {
        val videoSvc = svcProto { setId(GalService.VIDEO_SINK.id); setMediaSink(mediaSink()) }
        val s1 = newService(); val c1 = connection()
        setSession(c1, "services", listOf(videoSvc))
        setSession(c1, "openChannels", setOf(GalService.VIDEO_SINK.id))
        s1.openNext(c1)
        assertNull(s1.video)

        val s2 = newService(); val c2 = connection()
        setSession(c2, "services", listOf(videoSvc))
        setSession(c2, "refusedChannels", setOf(GalService.VIDEO_SINK.id))
        s2.openNext(c2)
        assertNull(s2.video)
    }

    @Test fun `video sink service creates the video channel`() {
        val (service, _) = openOne(svcProto { setId(GalService.VIDEO_SINK.id); setMediaSink(mediaSink()) })
        assertNotNull(service.video)
        assertEquals(GalService.VIDEO_SINK.id, service.video!!.channelId)
        service.video!!.release()
    }

    @Test fun `notification service creates the messaging owner`() {
        val (service, _) = openOne(svcProto { setId(GalService.NOTIFICATION.id) })
        assertNotNull(service.messaging)
        assertEquals(GalService.NOTIFICATION.id, service.messaging!!.channelId)
        service.messaging!!.release()
    }

    @Test fun `input payload service creates the input owner on its own id`() {
        val (service, _) = openOne(svcProto { setId(3); setInputSource(InputSourceService.newBuilder().build()) })
        assertNotNull(service.input)
        assertEquals(3, service.inputChannelId)
    }

    @Test fun `sensor payload service creates the sensor owner on its own id`() {
        val (service, _) = openOne(svcProto { setId(1); setSensorSource(SensorSourceService.newBuilder().build()) })
        assertNotNull(service.sensors)
        assertEquals(1, service.sensorChannelId)
        service.sensors!!.release()
    }

    @Test fun `guidance and nav status services create their owners`() {
        val (s1, _) = openOne(svcProto { setId(GalService.AUDIO_SINK_GUIDANCE.id); setMediaSink(mediaSink()) })
        assertNotNull(s1.guidance)
        s1.guidance!!.release()

        val (s2, _) = openOne(svcProto { setId(GalService.NAVIGATION_STATUS.id) })
        assertNotNull(s2.navStatus)
        s2.navStatus!!.release()
    }

    @Test fun `audio sink services create both sink owners and publish the media sink`() {
        val (s1, _) = openOne(svcProto { setId(AudioSinkRole.SYSTEM.serviceId); setMediaSink(mediaSink()) })
        assertNotNull(s1.audioSys)
        s1.audioSys!!.release()

        val (s2, _) = openOne(svcProto { setId(AudioSinkRole.MEDIA.serviceId); setMediaSink(mediaSink()) })
        assertNotNull(s2.audioMedia)
        assertEquals(s2.audioMedia, MusicCaptureSinkHolder.sink)
        MusicCaptureSinkHolder.sink = null
        s2.audioMedia!!.release()
    }

    @Test fun `media source payload creates the mic owner on its own id`() {
        val (service, _) = openOne(svcProto { setId(7); setMediaSource(MediaSourceService.newBuilder().build()) })
        assertNotNull(service.mic)
        assertEquals(7, service.micChannelId)
        service.mic!!.release()
    }

    @Test fun `unowned service creates no owner`() {
        val (service, _) = openOne(svcProto { setId(GalService.BLUETOOTH.id) })
        assertNull(service.video); assertNull(service.messaging); assertNull(service.input)
        assertNull(service.sensors); assertNull(service.audioSys); assertNull(service.mic)
    }

    @Test fun `sink-shaped services without a media sink create nothing`() {
        val (s1, _) = openOne(svcProto { setId(GalService.VIDEO_SINK.id) }) // no media_sink payload
        assertNull(s1.video)
        val (s2, _) = openOne(svcProto { setId(GalService.AUDIO_SINK_GUIDANCE.id) })
        assertNull(s2.guidance)
    }

    // -- routing ---------------------------------------------------------------
    private fun ProjectionService.route(message: ChannelMessage) {
        val m = ProjectionService::class.java
            .getDeclaredMethod("routeChannelMessage", ChannelMessage::class.java)
            .apply { isAccessible = true }
        m.invoke(this, message)
    }

    @Test fun `routing tolerates every channel family with no owners bound`() {
        val service = newService()
        // Media-browser channels are observed and ignored.
        service.route(ChannelMessage(GalService.MEDIA_PLAYBACK_STATUS.id, 1, byteArrayOf(1)))
        service.route(ChannelMessage(GalService.MEDIA_BROWSER.id, 1, byteArrayOf(1)))
        // Owned families with null owners: every branch must drop safely.
        service.route(ChannelMessage(GalService.NOTIFICATION.id, 1, byteArrayOf(1)))
        service.route(ChannelMessage(GalService.AUDIO_SINK_GUIDANCE.id, 1, byteArrayOf(1)))
        service.route(ChannelMessage(GalService.NAVIGATION_STATUS.id, 1, byteArrayOf(1)))
        service.route(ChannelMessage(AudioSinkRole.SYSTEM.serviceId, 1, byteArrayOf(1)))
        service.route(ChannelMessage(AudioSinkRole.MEDIA.serviceId, 1, byteArrayOf(1)))
        service.route(ChannelMessage(GalService.VIDEO_SINK.id, 1, byteArrayOf(1)))
        service.route(ChannelMessage(999, 1, byteArrayOf(1))) // unowned
        // Input/sensor/mic route by discovery-bound id; set them, still ownerless.
        val f = ProjectionService::class.java.getDeclaredField("inputChannelId").apply { isAccessible = true }
        f.set(service, 3)
        service.route(ChannelMessage(3, 1, byteArrayOf(1))) // input traffic, no owner: dropped with a count
        val fs = ProjectionService::class.java.getDeclaredField("sensorChannelId").apply { isAccessible = true }
        fs.set(service, 1)
        service.route(ChannelMessage(1, 1, byteArrayOf(1)))
        val fm = ProjectionService::class.java.getDeclaredField("micChannelId").apply { isAccessible = true }
        fm.set(service, 7)
        service.route(ChannelMessage(7, 1, byteArrayOf(1)))
        assertTrue(true) // reaching here: every branch dropped without crashing
    }

    @Test fun `focus mirror and active report run with no channels bound`() {
        val service = newService()
        val connection = connection()
        val focus = ProjectionService::class.java
            .getDeclaredMethod("onSessionFocusChanged", GalConnection::class.java)
            .apply { isAccessible = true }
        focus.invoke(service, connection) // all sinks null: mirror still publishes

        val report = ProjectionService::class.java.getDeclaredMethod(
            "reportActiveOnce", GalConnection::class.java, String::class.java, Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        assertEquals(true, report.invoke(service, connection, "Test Car", false))
        assertEquals(true, report.invoke(service, connection, "Test Car", true))

        val expiry = ProjectionService::class.java
            .getDeclaredMethod("publishCredentialExpiry").apply { isAccessible = true }
        expiry.invoke(service) // asset present or not: never fails the service
        assertNull(service.onBind(null))
    }
}
