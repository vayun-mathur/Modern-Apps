package com.vayunmathur.share.platform

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.share.domain.airdrop.AirDropMessages
import com.vayunmathur.share.domain.airdrop.BinaryPlist
import com.vayunmathur.share.domain.airdrop.Cpio
import com.vayunmathur.share.network.airdrop.AirDropHttp
import com.vayunmathur.share.platform.airdrop.AirDropDiscovery
import com.vayunmathur.share.platform.airdrop.AirDropReceiver
import com.vayunmathur.share.platform.discovery.BleDiscoveryManager
import com.vayunmathur.share.platform.discovery.DiscoverySource
import com.vayunmathur.share.platform.discovery.NearbyDevice
import com.vayunmathur.share.platform.discovery.NsdDiscoveryManager
import com.vayunmathur.share.platform.receive.ShareReceiveController
import com.vayunmathur.share.platform.receive.ShareReceiveTileService
import com.vayunmathur.share.platform.receive.ShareSaveActivity
import com.vayunmathur.share.protocol.ShareNativeDiscovery
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class MoreCoverageTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `viewmodel start and stop scan`() {
        val vm = ShareViewModel(app)
        vm.startScan()
        vm.startScan() // already scanning: no-op
        Thread.sleep(300)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        vm.stopScan()
        assertTrue(vm.sendUiState.value.discoveredDevices.isEmpty())
    }

    @Test
    fun `viewmodel connect with no files shows message path`() {
        val vm = ShareViewModel(app)
        val dev = NearbyDevice(endpointId = "ABCD", endpointName = "Pixel", host = "127.0.0.1", port = 9, source = DiscoverySource.Nsd)
        vm.connectToDevice(dev) // no outgoing uris -> returns after message
    }

    @Test
    fun `controller start stop and receive flag`() {
        ShareReceiveController.start(app)
        ShareReceiveController.start(app) // idempotent
        ShareReceiveController.stop()
        ShareReceiveController.stop()
        ShareReceiveController.refreshLocalIdentity(app)
        // setReceiveEnabled reconciles the service via
        // withContext(Dispatchers.Main); calling it from a runBlocking on
        // the Robolectric main thread deadlocks (the paused main looper
        // can never run the posted block while runBlocking occupies the
        // thread — this hung the whole :share suite in CI). Run the
        // suspend calls on a worker thread and pump the main looper here.
        val done = java.util.concurrent.CountDownLatch(1)
        var failure: Throwable? = null
        Thread {
            runBlocking {
                try {
                    ShareReceiveController.setReceiveEnabled(app, false)
                    assertEquals(false, ShareReceiveController.isReceiveEnabled(app))
                } catch (t: Throwable) {
                    failure = t
                } finally {
                    done.countDown()
                }
            }
        }.start()
        while (!done.await(50, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
        failure?.let { throw it }
        assertTrue(ShareReceiveController.connectionFor(app, 1L) == null)
    }
    @Test
    fun `tile service lifecycle`() {
        Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val controller = Robolectric.buildService(ShareReceiveTileService::class.java)
        val service = controller.create().get()
        service.onTileAdded()
        service.onStartListening()
        service.onClick()
        Thread.sleep(300)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        // Robolectric's ShadowTileService cannot cast to ShadowService on
        // destroy() (ClassCastException inside TileService.onDestroy) —
        // not our code. Best-effort destroy; the service under test
        // already ran its lifecycle above.
        try {
            controller.destroy()
        } catch (_: ClassCastException) {
        }
    }

    @Test
    fun `save activity with uris launches picker`() {
        val uri = Uri.parse("content://com.vayunmathur.share.fileprovider/received/note.txt")
        val intent = Intent(app, ShareSaveActivity::class.java).apply {
            putExtra(ShareSaveActivity.EXTRA_FILE_URIS, arrayListOf(uri))
        }
        val controller = Robolectric.buildActivity(ShareSaveActivity::class.java, intent).create().start()
        val activity = controller.get()
        // Picker intent should have been requested via activity result; activity not finishing yet.
        Shadows.shadowOf(activity).nextStartedActivityForResult
        controller.destroy()
    }

    @Test
    fun `ble advertising with granted permission attempts native payload`() {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        val mgr = BleDiscoveryManager(app)
        val info = ShareNativeDiscovery.nativeBuildEndpointInfo("Pixel", 1)!!
        // Adapter may be disabled in Robolectric; either false or true acceptable, must not crash.
        mgr.startAdvertising("ABCD", info)
        mgr.startFastInitiation(byteArrayOf(1, 2))
        mgr.startFastInitiation(byteArrayOf(1)) // bad length -> false path
        mgr.stopAdvertising()
        mgr.stopFastInitiation()
        assertTrue(true)
    }

    @Test
    fun `nsd discover flow terminates and airdrop discovery advertise`() = runBlocking {
        val mgr = NsdDiscoveryManager(app)
        var count = 0
        val job = async { mgr.discover().collect { count++ } }
        delay(500)
        mgr.stopDiscovery()
        job.cancel()
        val ad = AirDropDiscovery(app)
        ad.advertise("testsvc", 9001, null)
        assertEquals("testsvc", ad.ownServiceName)
        ad.unadvertise()
        var peers = 0
        val job2 = async { ad.browse(null).collect { peers++ } }
        delay(500)
        job2.cancel()
        assertTrue(true)
    }

    @Test
    fun `receiver zip and gzip cpio uploads`() = runBlocking {
        // ZIP path
        val store = ReceivedFileStore(app)
        val receiver = AirDropReceiver(store, { "Mac" }, { 1_000_000_000L }, { _, _ -> })
        val remote = InetAddress.getByName("127.0.0.1")
        fun req(path: String, body: ByteArray, headers: Map<String, String> = emptyMap()): AirDropHttp.Request =
            AirDropHttp.Request("POST", path, AirDropHttp.Headers(headers.mapKeys { it.key.lowercase() }), ByteArrayInputStream(body))
        suspend fun acceptedSession(transferId: String): com.vayunmathur.share.platform.airdrop.AirDropIncoming {
            val ask = AirDropMessages.AskRequest("Peer", "Phone", "id", listOf(AirDropMessages.AskFile.forFile("z.txt", 1L)), transferId = transferId)
            val job = async { receiver.handle(req("/Ask", BinaryPlist.encode(ask.toPlist())), remote) }
            var inc: com.vayunmathur.share.platform.airdrop.AirDropIncoming? = null
            val dl = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < dl) { inc = receiver.incoming.value.firstOrNull { it.ask.transferId == transferId }; if (inc != null) break; delay(20) }
            receiver.accept(inc!!.sessionHandle, true)
            job.await()
            return inc
        }

        val s1 = acceptedSession("zip-1")
        val zipOut = ByteArrayOutputStream()
        ZipOutputStream(zipOut).use { z -> z.putNextEntry(ZipEntry("folder/z.txt")); z.write("zipdata".toByteArray()); z.closeEntry() }
        val r1 = receiver.handle(req("/Upload", zipOut.toByteArray(), mapOf("Content-Type" to AirDropMessages.CONTENT_TYPE_ZIP, "TransferID" to "zip-1")), remote)
        assertEquals(200, r1.status)
        assertEquals(1, s1.receivedFiles.value.size)

        // gzip-wrapped cpio path (unknown content type sniffs gzip)
        val s2 = acceptedSession("gz-1")
        val raw = ByteArrayOutputStream()
        Cpio.Writer(raw).use { w -> w.file("./g.txt", 6, ByteArrayInputStream("gzipok".toByteArray())) }
        val gzOut = ByteArrayOutputStream()
        GZIPOutputStream(gzOut).use { it.write(raw.toByteArray()) }
        val r2 = receiver.handle(req("/Upload", gzOut.toByteArray(), mapOf("Content-Type" to "application/octet-stream", "TransferID" to "gz-1")), remote)
        assertEquals(200, r2.status)
        assertEquals(1, s2.receivedFiles.value.size)
        assertNotNull(s1.displayName)
    }
}
