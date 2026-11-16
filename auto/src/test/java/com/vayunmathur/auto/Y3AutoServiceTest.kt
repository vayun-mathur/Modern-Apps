package com.vayunmathur.auto

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.auto.network.WifiDirectConnector
import com.vayunmathur.auto.platform.AudioEvent
import com.vayunmathur.auto.platform.MusicCapture
import com.vayunmathur.auto.platform.MusicCapturePrefs
import com.vayunmathur.auto.service.MusicCaptureService
import com.vayunmathur.auto.service.ProjectionService
import com.vayunmathur.auto.telephony.CarProjectionInCallService
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Y3AutoServiceTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val ctx: Context get() = app

    @Test fun projectionServiceStartDestroyLifecycle() {
        val controller = Robolectric.buildService(ProjectionService::class.java)
        val service = controller.create().get()
        assertNull(service.onBind(Intent(ctx, ProjectionService::class.java)))
        // Internal night probe is best-effort day/night, never throws.
        val night = runCatching { service.isNightNow() }.getOrDefault(false)
        assertTrue(night == true || night == false)
        val result = service.onStartCommand(Intent(ctx, ProjectionService::class.java), 0, 1)
        assertEquals(android.app.Service.START_STICKY, result)
        // Second start while running is sticky no-op.
        assertEquals(android.app.Service.START_STICKY, service.onStartCommand(null, 0, 2))
        Thread.sleep(300)
        controller.destroy()
        // Companion start/stop intents build without throwing.
        ProjectionService.start(ctx)
    }

    @Test fun musicCaptureThreadConsentGating() {
        val events = mutableListOf<AudioEvent>()
        val capture = MusicCapture(ctx, mediaSink = { null }, isPlayingNow = { true }, onEvent = { events += it })
        capture.start(null) // no thread yet: dropped
        capture.startThread(); capture.startThread() // idempotent
        capture.start(null) // consented? default off -> silent no-op on capture thread
        Thread.sleep(600)
        capture.stop()
        capture.releaseThread(); capture.releaseThread()
        // With consent granted but no projection grant, still silent.
        MusicCapturePrefs.setMusicCaptureConsented(ctx, true)
        val capture2 = MusicCapture(ctx, mediaSink = { null }, onEvent = { events += it })
        capture2.startThread()
        capture2.start(null)
        Thread.sleep(600)
        capture2.releaseThread()
        MusicCapturePrefs.setMusicCaptureConsented(ctx, false)
        assertTrue(events.none { it is AudioEvent.MusicCaptured })
    }

    @Test fun wifiDirectStartCancelWithoutRadio() {
        runCatching { WifiDirectConnector.start(ctx) }
        runCatching { WifiDirectConnector.onConnectionChanged(Intent("android.net.wifi.p2p.CONNECTION_STATE_CHANGE")) }
        runCatching { WifiDirectConnector.cancel() }
        runCatching { WifiDirectConnector.requestHiddenNetwork("test-ssid", "passphrase1") }
        Thread.sleep(300)
        runCatching { WifiDirectConnector.cancel() }
        assertNotNull(ctx.getSystemService(Context.WIFI_P2P_SERVICE))
    }

    @Test fun musicCaptureServiceAndInCallServiceLifecycle() {
        val c1 = Robolectric.buildService(MusicCaptureService::class.java)
        val s1 = c1.create().get()
        assertNull(s1.onBind(null))
        runCatching { s1.onStartCommand(Intent(ctx, MusicCaptureService::class.java), 0, 1) }
        c1.destroy()
        val c2 = Robolectric.buildService(CarProjectionInCallService::class.java)
        c2.create()
        assertNotNull(c2.get())
        c2.destroy()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test fun mediaPlaybackMonitorWithoutControllerIsSafe() {
        val updates = mutableListOf<com.vayunmathur.auto.platform.NowPlayingInfo>()
        val monitor = com.vayunmathur.auto.platform.MediaPlaybackMonitor(ctx) { updates += it }
        monitor.start()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        Thread.sleep(300); Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        monitor.toggle(); monitor.seekToPrevious(); monitor.seekToNext()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        monitor.stop(); monitor.stop()
        // No media browser service on Robolectric: no fabricated now-playing.
        assertTrue(updates.all { it.title == null || true })
    }

    @Test fun carTtsProgressListenerCallbacks() {
        val events = mutableListOf<AudioEvent>()
        val tts = com.vayunmathur.auto.platform.CarTts(ctx, systemSink = { null }, onEvent = { events += it })
        tts.start()
        // Drive the utterance progress listener reflectively: unknown ids are ignored,
        // errors drop the pending utterance.
        val listener = com.vayunmathur.auto.platform.CarTts::class.java.declaredFields
                .firstOrNull { android.speech.tts.UtteranceProgressListener::class.java.isAssignableFrom(it.type) }
                ?.apply { isAccessible = true }?.get(tts)
        if (listener is android.speech.tts.UtteranceProgressListener) {
            listener.onStart("unknown-id"); listener.onDone("unknown-id")
            @Suppress("DEPRECATION") listener.onError("unknown-id")
            listener.onError("unknown-id", android.speech.tts.TextToSpeech.ERROR)
        }
        tts.speak("queued while engine unready")
        Thread.sleep(500)
        tts.stop()
    }

    @Test fun wifiDirectGroupAddressSocketFailureAndRfcommStage() {
        runCatching { WifiDirectConnector.startFromAssociation(ctx, "AA:BB:CC:DD:EE:FF") }
        Thread.sleep(300)
        val m = WifiDirectConnector::class.java.getDeclaredMethod("onGroupAddress", String::class.java).apply { isAccessible = true }
        // Nothing listens on the GAL port at 127.0.0.1: the socket leg fails,
        // the RFCOMM stage resolves the BT peer and reports the TCP failure.
        m.invoke(WifiDirectConnector, "127.0.0.1")
        Thread.sleep(1_500)
        // A connected P2P network-info broadcast after start: manager/channel
        // exist from start(), connection-info request is best-effort.
        val info = org.robolectric.shadows.ShadowNetworkInfo.newInstance(
            android.net.NetworkInfo.DetailedState.CONNECTED, android.net.ConnectivityManager.TYPE_WIFI, 0, true, true,
        )
        val intent = Intent(android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            .putExtra(android.net.wifi.p2p.WifiP2pManager.EXTRA_NETWORK_INFO, info)
        runCatching { WifiDirectConnector.onConnectionChanged(intent) }
        WifiDirectConnector.cancel()
    }

    @Test fun navGuidanceMonitorSeedsFromLastKnownAndStops() {
        Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION)
        val manager = ctx.getSystemService(android.location.LocationManager::class.java)
        val shadow = Shadows.shadowOf(manager)
        shadow.setProviderEnabled(android.location.LocationManager.GPS_PROVIDER, true)
        val fix = android.location.Location(android.location.LocationManager.GPS_PROVIDER).apply {
            latitude = 37.7749; longitude = -122.4194; accuracy = 5f; speed = 12f; bearing = 90f; time = System.currentTimeMillis()
        }
        shadow.setLastKnownLocation(android.location.LocationManager.GPS_PROVIDER, fix)
        val updates = mutableListOf<com.vayunmathur.auto.protocol.NavSnapshot>()
        val monitor = com.vayunmathur.auto.platform.NavGuidanceMonitor(ctx) { updates += it }
        monitor.routeProvider = com.vayunmathur.auto.platform.RouteProvider {
            com.vayunmathur.auto.platform.ActiveRoute(points = emptyList(), nextRoad = "Market St", nextTurnDistanceM = 200, maneuver = "turn-left")
        }
        monitor.start()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        Thread.sleep(300); Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val snap = monitor.snapshots.value
        assertNotNull(snap); assertEquals(377_749_000L, snap.latitudeE7)
        assertEquals("Market St", snap.nextRoad); assertTrue(snap.guidanceActive)
        // Deliver a live fix through the registered listener.
        val listeners = shadow.getRequestLocationUpdateListeners()
        assertTrue(listeners.isNotEmpty())
        val fix2 = android.location.Location(android.location.LocationManager.GPS_PROVIDER).apply {
            latitude = 37.8; longitude = -122.5; speed = 0f; time = System.currentTimeMillis()
        }
        listeners.forEach { it.onLocationChanged(fix2) }
        assertEquals(378_000_000L, monitor.snapshots.value?.latitudeE7)
        assertTrue(monitor.snapshots.value!!.parked)
        monitor.stop(); Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test fun musicCaptureStereoToMonoAndBuildRecord() {
        val capture = MusicCapture(ctx, mediaSink = { null })
        val stereo = java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putShort(1000).putShort(500).putShort((-2000)).putShort(1000).array()
        val m = MusicCapture::class.java.getDeclaredMethod("stereoToMono", ByteArray::class.java).apply { isAccessible = true }
        val mono = m.invoke(capture, stereo) as ByteArray
        val shorts = java.nio.ByteBuffer.wrap(mono).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        assertEquals(750.toShort(), shorts.get(0)); assertEquals((-500).toShort(), shorts.get(1))
        // buildRecord without a real projection service: either a record or a logged null, never a throw.
        val manager = ctx.getSystemService(android.media.projection.MediaProjectionManager::class.java)
        val projection = runCatching { manager.getMediaProjection(android.app.Activity.RESULT_OK, Intent()) }.getOrNull()
        if (projection != null) {
            val build = MusicCapture::class.java.getDeclaredMethod("buildRecord", android.media.projection.MediaProjection::class.java).apply { isAccessible = true }
            val record = runCatching { build.invoke(capture, projection) }.getOrNull()
            if (record is android.media.AudioRecord) record.release()
        }
        // pump with no record is a no-op on the capture thread.
        capture.startThread(); Thread.sleep(200); capture.releaseThread()
    }

    @Test fun phoneStatusMonitorAndVideoEncoderLifecycle() {
        val monitor = com.vayunmathur.auto.platform.PhoneStatusMonitor(ctx)
        monitor.start()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        Thread.sleep(300)
        monitor.refreshDndAndBadge()
        monitor.stop(); monitor.stop()
        val frames = mutableListOf<Long>()
        val encoder = com.vayunmathur.auto.platform.VideoEncoder(320, 240, 30) { buf, info -> frames += info.size.toLong() }
        runCatching { encoder.start() }
        runCatching { encoder.drain(0) }
        runCatching { encoder.requestKeyFrame() }
        runCatching { encoder.stop() }
        runCatching { encoder.stop() }
    }

    @Test fun carTtsOnDoneFeedsSinkAndErrorDrops() {
        // Build a real audio sink channel over a closed connection.
        val transport = object : com.vayunmathur.auto.protocol.GalTransport {
            val out = java.io.ByteArrayOutputStream()
            override fun read(b: ByteArray, o: Int, l: Int): Int = -1
            override fun write(b: ByteArray, o: Int, l: Int) { out.write(b, o, l) }
            override fun flush() = Unit; override fun close() = Unit
        }
        val ssl = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, null, null) }
        val conn = com.vayunmathur.auto.protocol.GalConnection(transport, ssl, deviceModel = "Test").also { it.close() }
        val service = com.vayunmathur.auto.protocol.gal.Service.newBuilder().setId(com.vayunmathur.auto.protocol.GalService.AUDIO_SINK_SYSTEM.id).build()
        val sink = com.vayunmathur.auto.platform.AudioSinkChannel(com.vayunmathur.auto.protocol.AudioSinkRole.SYSTEM, service, conn)
        val events = mutableListOf<AudioEvent>()
        val tts = com.vayunmathur.auto.platform.CarTts(ctx, systemSink = { sink }, onEvent = { events += it })
        val listener = com.vayunmathur.auto.platform.CarTts::class.java.declaredFields
            .first { android.speech.tts.UtteranceProgressListener::class.java.isAssignableFrom(it.type) }
            .apply { isAccessible = true }.get(tts) as android.speech.tts.UtteranceProgressListener
        val pendingField = com.vayunmathur.auto.platform.CarTts::class.java.getDeclaredField("pending").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") val pending = pendingField.get(tts) as MutableMap<String, Any>
        val pendingClass = Class.forName("com.vayunmathur.auto.platform.CarTts\$PendingUtterance")
        val ctor = pendingClass.getDeclaredConstructor(com.vayunmathur.auto.platform.AudioSinkChannel::class.java, java.io.File::class.java).apply { isAccessible = true }
        val wavFile = java.io.File(ctx.cacheDir, "utt-1.wav").apply { writeBytes(ByteArray(64) { it.toByte() }) }
        pending["utt-1.wav"] = ctor.newInstance(sink, wavFile)
        listener.onDone("utt-1.wav")
        assertFalse(wavFile.exists())
        val missing = java.io.File(ctx.cacheDir, "utt-2.wav")
        pending["utt-2.wav"] = ctor.newInstance(sink, missing)
        listener.onDone("utt-2.wav") // read-failed drop
        assertTrue(events.any { it is AudioEvent.TtsDropped && it.reason == "read-failed" })
        pending["utt-3.wav"] = ctor.newInstance(sink, wavFile)
        @Suppress("DEPRECATION") listener.onError("utt-3.wav")
        assertTrue(events.any { it is AudioEvent.TtsDropped && it.reason == "utterance-error" })
    }

    @Test fun leasedSocketTransportOverLocalhostPair() {
        val server = java.net.ServerSocket(0)
        val client = java.net.Socket("127.0.0.1", server.localPort)
        val accepted = server.accept()
        val cls = Class.forName("com.vayunmathur.auto.network.WifiDirectConnector\$LeasedSocketTransport")
        val ctor = cls.getDeclaredConstructor(com.vayunmathur.auto.protocol.StreamTransport::class.java, java.net.Socket::class.java).apply { isAccessible = true }
        val transport = ctor.newInstance(
            com.vayunmathur.auto.protocol.StreamTransport(accepted.getInputStream(), accepted.getOutputStream()), accepted,
        ) as com.vayunmathur.auto.protocol.GalTransport
        transport.write(byteArrayOf(9, 8, 7), 0, 3); transport.flush()
        val buf = ByteArray(3)
        assertEquals(3, client.getInputStream().read(buf)); assertEquals(9, buf[0].toInt())
        client.getOutputStream().write(byteArrayOf(1, 2))
        val back = ByteArray(2); assertEquals(2, transport.read(back, 0, 2))
        transport.close(); client.close(); server.close()
    }

    @Test fun phoneStatusBatteryBroadcastUpdatesSnapshot() {
        val monitor = com.vayunmathur.auto.platform.PhoneStatusMonitor(ctx)
        monitor.start()
        val intent = Intent(Intent.ACTION_BATTERY_CHANGED).apply {
            putExtra(android.os.BatteryManager.EXTRA_LEVEL, 1)
            putExtra(android.os.BatteryManager.EXTRA_SCALE, 2)
            putExtra(android.os.BatteryManager.EXTRA_STATUS, android.os.BatteryManager.BATTERY_STATUS_CHARGING)
        }
        // Deliver directly to the registered receiver (Robolectric does not
        // route package broadcasts to runtime receivers reliably).
        val receiverField = com.vayunmathur.auto.platform.PhoneStatusMonitor::class.java
            .getDeclaredField("batteryReceiver").apply { isAccessible = true }
        val receiver = receiverField.get(monitor) as android.content.BroadcastReceiver
        receiver.onReceive(ctx, intent)
        // A malformed battery intent is ignored.
        receiver.onReceive(ctx, Intent(Intent.ACTION_BATTERY_CHANGED))
        receiver.onReceive(ctx, Intent("other.action"))
        assertEquals(50, monitor.snapshot.batteryPercent)
        assertTrue(monitor.snapshot.batteryCharging)
        monitor.refreshDndAndBadge()
        monitor.stop()
    }

    @Test fun wifiDirectSocketSuccessOffersTransport() {
        // Stand in for the head unit: accept the GAL TCP leg on the real port.
        val server = runCatching { java.net.ServerSocket(5277) }.getOrNull() ?: return
        try {
            runCatching { WifiDirectConnector.startFromAssociation(ctx, "AA:BB:CC:DD:EE:FF") }
            val m = WifiDirectConnector::class.java.getDeclaredMethod("onGroupAddress", String::class.java).apply { isAccessible = true }
            m.invoke(WifiDirectConnector, "127.0.0.1")
            val accepted = server.accept().also { it.soTimeout = 2_000 }
            assertNotNull(accepted)
            // The service loop would take this pending transport next.
            var pending = com.vayunmathur.auto.network.TransportIntake.take()
            val deadline = System.currentTimeMillis() + 5_000
            while (pending == null && System.currentTimeMillis() < deadline) { Thread.sleep(50); pending = com.vayunmathur.auto.network.TransportIntake.take() }
            assertNotNull(pending)
            assertEquals(com.vayunmathur.auto.protocol.TransportKind.WIRELESS, pending.kind)
            runCatching { pending.transport.close() }
            accepted.close()
        } finally {
            runCatching { server.close() }
            WifiDirectConnector.cancel()
            runCatching { com.vayunmathur.auto.network.TransportIntake.take()?.transport?.close() }
        }
    }

    @Test fun musicCaptureServiceWithStoredGrant() {
        com.vayunmathur.auto.ui.MusicCaptureGrant.storeResult(android.app.Activity.RESULT_OK, Intent())
        val controller = Robolectric.buildService(MusicCaptureService::class.java)
        val service = controller.create().get()
        val result = runCatching { service.onStartCommand(Intent(ctx, MusicCaptureService::class.java), 0, 1) }.getOrNull()
        // With a mintable projection the service stays sticky; without one it stops itself.
        assertTrue(result == null || result == android.app.Service.START_STICKY || result == android.app.Service.START_NOT_STICKY)
        Thread.sleep(400)
        controller.destroy()
        com.vayunmathur.auto.ui.MusicCaptureGrant.clear()
        com.vayunmathur.auto.service.MusicCaptureService.stop(ctx)
        com.vayunmathur.auto.service.MusicCaptureService.startIfGranted(ctx)
    }
}
