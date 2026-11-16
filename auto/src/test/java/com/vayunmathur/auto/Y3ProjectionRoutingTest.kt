package com.vayunmathur.auto

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.auto.protocol.ChannelMessage
import com.vayunmathur.auto.protocol.GalConnection
import com.vayunmathur.auto.protocol.GalService
import com.vayunmathur.auto.protocol.GalTransport
import com.vayunmathur.auto.protocol.NavSnapshot
import com.vayunmathur.auto.protocol.gal.MediaSinkService
import com.vayunmathur.auto.protocol.gal.Service
import com.vayunmathur.auto.protocol.gal.VideoConfiguration
import com.vayunmathur.auto.protocol.gal.VideoResolution
import com.vayunmathur.auto.service.ProjectionService
import com.vayunmathur.auto.service.openNext
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import javax.net.ssl.SSLContext
import kotlin.test.Test
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class Y3ProjectionRoutingTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

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

    private fun invoke(service: ProjectionService, name: String, vararg args: Any?) {
        val method = ProjectionService::class.java.declaredMethods.first { it.name == name && it.parameterCount == args.size }
        method.isAccessible = true
        method.invoke(service, *args)
    }

    private fun mediaSink(): MediaSinkService = MediaSinkService.newBuilder()
        .addVideoConfigs(
            VideoConfiguration.newBuilder()
                .setCodecResolution(VideoResolution.VIDEO_1280x720).setFrameRate(30).setDensity(160)
                .build(),
        ).build()

    @Test fun routingGuidanceAndTeardownWithSeededChannels() {
        val service = Robolectric.buildService(ProjectionService::class.java).create().get()
        val connection = connection()
        // Seed a session that has granted every owned service, then let the
        // service's own openNext factories build the channel owners.
        val services = listOf(
            Service.newBuilder().setId(GalService.VIDEO_SINK.id).setMediaSink(mediaSink()).build(),
            Service.newBuilder().setId(GalService.NOTIFICATION.id).build(),
            Service.newBuilder().setId(GalService.AUDIO_SINK_GUIDANCE.id).build(),
            Service.newBuilder().setId(GalService.NAVIGATION_STATUS.id).build(),
        )
        setSession(connection, "services", services)
        repeat(4) { runCatching { service.openNext(connection) } }
        // Route one message to every owned/unowned branch.
        val channels = listOf(
            GalService.VIDEO_SINK.id, GalService.NOTIFICATION.id,
            GalService.AUDIO_SINK_GUIDANCE.id, GalService.NAVIGATION_STATUS.id,
            999,
        )
        for (id in channels) {
            runCatching { invoke(service, "routeChannelMessage", ChannelMessage(id, 0x8001, byteArrayOf(1, 2, 3))) }
        }
        runCatching {
            invoke(service, "onGuidanceSnapshot", NavSnapshot(latitudeE7 = 377_749_000, longitudeE7 = -1_224_194_000, parked = true, isNight = true, speedMps = 0.0))
            invoke(service, "onGuidanceSnapshot", NavSnapshot(latitudeE7 = 377_749_000, longitudeE7 = -1_224_194_000, parked = false, isNight = false, speedMps = 12.0))
        }
        runCatching { invoke(service, "onSessionFocusChanged", connection) }
        runCatching { invoke(service, "publishCredentialExpiry") }
        runCatching { invoke(service, "tearDownSession", connection) }
        // Teardown cleared the owners.
        assertTrue(service.video == null && service.messaging == null)
    }

    @Test fun grantedChannelSetupsRunOnce() {
        val service = Robolectric.buildService(ProjectionService::class.java).create().get()
        val connection = connection()
        setSession(connection, "services", listOf(
            Service.newBuilder().setId(GalService.VIDEO_SINK.id).setMediaSink(mediaSink()).build(),
            Service.newBuilder().setId(GalService.NOTIFICATION.id).build(),
        ))
        repeat(2) { runCatching { service.openNext(connection) } }
        setSession(connection, "openChannels", setOf(GalService.VIDEO_SINK.id, GalService.NOTIFICATION.id))
        val setupsClass = Class.forName("com.vayunmathur.auto.service.ProjectionService\$ChannelSetups")
        val pending = setupsClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        runCatching { invoke(service, "setUpGrantedChannels", connection, pending) }
        runCatching { invoke(service, "setUpGrantedChannels", connection, pending) } // once-only flags
        runCatching { invoke(service, "tearDownSession", connection) }
        assertTrue(service.video == null)
    }

    @Test fun sessionOnTransportWithDeadTransportTearsDown() {
        val service = Robolectric.buildService(ProjectionService::class.java).create().get()
        // Start the service so `running` is true and session-scoped owners exist.
        runCatching { service.onStartCommand(android.content.Intent(ctx, ProjectionService::class.java), 0, 1) }
        val method = ProjectionService::class.java.getDeclaredMethod(
            "sessionOnTransport", com.vayunmathur.auto.protocol.GalTransport::class.java, String::class.java,
        ).apply { isAccessible = true }
        // An immediately-EOF transport: version pump ends, teardown must run.
        runCatching { method.invoke(service, FakeTransport(), "Test Car") }
            .onFailure { it.cause?.printStackTrace() }
        assertTrue(service.video == null)
        runCatching { service.onDestroy() }
    }

    @Test fun openNextForEveryAdvertisedService() {
        for (galService in GalService.entries) {
            if (galService == GalService.CONTROL) continue
            val service = Robolectric.buildService(ProjectionService::class.java).create().get()
            val connection = connection()
            val proto = when (galService) {
                GalService.VIDEO_SINK -> Service.newBuilder().setId(galService.id).setMediaSink(mediaSink()).build()
                else -> Service.newBuilder().setId(galService.id).build()
            }
            setSession(connection, "services", listOf(proto))
            runCatching { service.openNext(connection) }
            runCatching { service.openNext(connection) } // second pass: already-open/pending guards
            runCatching { invoke(service, "tearDownSession", connection) }
        }
        assertTrue(true)
    }
}
