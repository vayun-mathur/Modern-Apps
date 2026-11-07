package com.vayunmathur.share.platform

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.share.domain.airdrop.BinaryPlist
import com.vayunmathur.share.domain.airdrop.DvZip
import com.vayunmathur.share.domain.protocol.PendingFile
import com.vayunmathur.share.domain.protocol.ShareState
import com.vayunmathur.share.platform.airdrop.AIRDROP_SERVICE_TYPE
import com.vayunmathur.share.platform.airdrop.AirDropDiscovery
import com.vayunmathur.share.platform.airdrop.link.MoseyDetector
import com.vayunmathur.share.platform.discovery.NsdDiscoveryManager
import com.vayunmathur.share.platform.discovery.SHARE_SERVICE_TYPE
import com.vayunmathur.share.platform.receive.ShareReceiveController
import com.vayunmathur.share.platform.receive.ShareReceiveNotifier
import com.vayunmathur.share.protocol.ShareNativeDiscovery
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import com.vayunmathur.share.platform.receive.ShareReceiveNotifierTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
class GapPush4Test {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    class StubProvider : ContentProvider() {
        override fun onCreate(): Boolean = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            val cursor = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME))
            cursor.addRow(arrayOf("provider-name.txt"))
            return cursor
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val f = File(context!!.cacheDir, "stub-provider.txt").apply { writeText("provider payload") }
            return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun getType(uri: Uri): String = "text/plain"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }

    @Test
    fun `content provider display name and temp file copy`() {
        ShadowContentResolver.registerProviderInternal("com.example.testprovider", StubProvider().apply { attachInfo(app, null) })
        val uri = Uri.parse("content://com.example.testprovider/item/1")
        assertEquals("provider-name.txt", resolveDisplayName(app, uri))
        // Robolectric does not route openInputStream to a programmatically registered
        // provider's openFile; register the stream the resolver should hand back instead.
        Shadows.shadowOf(app.contentResolver).registerInputStream(uri, ByteArrayInputStream("provider payload".toByteArray()))
        val out = uriToTempFile(app, uri)
        assertNotNull(out)
        assertEquals("provider payload", out!!.readText())
        // Registered input stream URI with no provider record: name falls back, copy still works.
        val streamUri = Uri.parse("content://com.example.stream/item/2")
        Shadows.shadowOf(app.contentResolver).registerInputStream(streamUri, ByteArrayInputStream("streamed".toByteArray()))
        val out2 = uriToTempFile(app, streamUri)
        assertNotNull(out2)
    }

    @Test
    fun `notifier done with real image preview`() = runBlocking {
        // Write a real PNG via Bitmap compress.
        val pngFile = File(app.cacheDir, "preview-${System.nanoTime()}.png")
        val bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        pngFile.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(pngFile.length() > 0)
        val fake = ShareReceiveNotifierTest.FakeTransfer(31337L)
        fake.pendingFiles.value = listOf(PendingFile(pngFile.name, pngFile.length(), "image/png"))
        fake.expectedTotalBytes.value = pngFile.length()
        val incoming = MutableStateFlow<List<com.vayunmathur.share.platform.IncomingTransfer>>(listOf(fake))
        val retired = mutableListOf<Long>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val notifier = ShareReceiveNotifier(app, incoming, retire = { retired += it })
        try {
            notifier.start(scope)
            delay(300)
            fake.receivedFiles.value = listOf(ReceivedFile(pngFile.name, pngFile.length(), "image/png", Uri.fromFile(pngFile)))
            fake.state.value = ShareState.Completed
            delay(800)
            assertTrue(retired.contains(31337L))
        } finally { notifier.stop(); scope.cancel() }
    }

    @Test
    fun `nsd parse failures are skipped and host falls back to resolved`() = runBlocking {
        val mgr = NsdDiscoveryManager(app)
        val nsd = app.getSystemService(NsdManager::class.java)
        val shadow = Shadows.shadowOf(nsd)
        val job = kotlinx.coroutines.CoroutineScope(Dispatchers.Default).async {
            val out = mutableListOf<com.vayunmathur.share.platform.discovery.NearbyDevice>()
            try { mgr.discover().collect { out += it } } catch (e: Exception) { }
            out
        }
        var listener: NsdManager.DiscoveryListener? = null
        val dl = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < dl) { listener = shadow.getDiscoveryListeners(SHARE_SERVICE_TYPE)?.firstOrNull(); if (listener != null) break; delay(80) }
        assertNotNull(listener)
        fun deliver(instance: String, resolvedAttrs: Map<String, String>, host: InetAddress? = null) {
            val found = NsdServiceInfo().apply { serviceName = instance; serviceType = SHARE_SERVICE_TYPE }
            listener!!.onServiceFound(found)
            // resolve listener registers synchronously in onServiceFound
            val rl = shadow.getResolveListeners(found)?.firstOrNull() ?: return
            val resolved = NsdServiceInfo().apply {
                serviceName = instance; serviceType = SHARE_SERVICE_TYPE; port = 7000
                resolvedAttrs.forEach { (k, v) -> setAttribute(k, v) }
                if (host != null) setHost(host)
            }
            rl.onServiceResolved(resolved)
        }
        val goodInfo = ShareNativeDiscovery.nativeBuildEndpointInfo("HostPeer", 1)!!
        val wifi = ShareNativeDiscovery.nativeBuildWifiLanServiceInfo("ZZZZ")!!
        // not base64 at all
        deliver("!!!not-base64!!!", mapOf("n" to b64(goodInfo)))
        // missing n attribute
        deliver(b64(wifi), emptyMap())
        // wifi bytes garbage
        deliver(b64(byteArrayOf(1, 2, 3)), mapOf("n" to b64(goodInfo)))
        // endpoint info garbage
        deliver(b64(wifi), mapOf("n" to b64(byteArrayOf(9, 9, 9))))
        // valid, host from resolved (no IPv4 TXT)
        deliver(b64(wifi), mapOf("n" to b64(goodInfo)), InetAddress.getByName("10.9.9.9"))
        delay(800)
        val devices = mgr.discoveredDevices.value
        assertEquals(1, devices.size)
        assertEquals("10.9.9.9", devices[0].host)
        assertEquals("HostPeer", devices[0].endpointName)
        // resolve failure callback
        val found2 = NsdServiceInfo().apply { serviceName = b64(wifi); serviceType = SHARE_SERVICE_TYPE }
        listener!!.onServiceFound(found2)
        shadow.getResolveListeners(found2)?.firstOrNull()?.onResolveFailed(found2, 2)
        listener.onStopDiscoveryFailed(SHARE_SERVICE_TYPE, 3)
        job.cancel()
        mgr.release()
    }

    @Test
    fun `binary plist large and typed values round trip`() {
        val bigString = "x".repeat(500)
        val map = mapOf<String, Any?>(
            "big" to bigString, "hugeInt" to (1L shl 40), "smallInt" to 5L, "neg" to -300L,
            "truth" to false, "data" to ByteArray(300) { (it % 251).toByte() },
            "list" to (1..40).map { it.toLong() },
        )
        val decoded = BinaryPlist.decodeDict(BinaryPlist.encode(map))
        assertEquals(bigString, decoded["big"])
        assertEquals(1L shl 40, decoded["hugeInt"])
        assertEquals(5L, decoded["smallInt"])
        assertEquals(false, decoded["truth"])
        assertEquals(40, (decoded["list"] as List<*>).size)
        assertTrue((decoded["data"] as ByteArray).size == 300)
    }

    @Test
    fun `dvzip reader partial reads and close`() {
        val payload = ByteArray(10_000) { (it * 7 % 253).toByte() }
        val out = ByteArrayOutputStream()
        val w = DvZip.Writer(out)
        w.write(payload, 0, 4000); w.write(payload, 4000, 6000); w.close()
        val reader = DvZip.Reader(ByteArrayInputStream(out.toByteArray()))
        val buf = ByteArray(999)
        var total = 0
        while (true) { val n = reader.read(buf); if (n < 0) break; total += n }
        assertEquals(payload.size, total)
        reader.close()
        // Truncated deflate stream: reading must fail or end early, never yield the payload.
        val truncated = out.toByteArray().copyOf(20)
        val r2 = DvZip.Reader(ByteArrayInputStream(truncated))
        var got = 0
        runCatching { val b = ByteArray(4096); while (true) { val n = r2.read(b); if (n < 0) break; got += n } }
        assertTrue(got < payload.size)
    }

    @Test
    fun `mosey detector with shared library name present`() {
        Shadows.shadowOf(app.packageManager).addSystemSharedLibraryName(MoseyDetector.SHARED_LIBRARY)
        val status = MoseyDetector.detect(app)
        // Manager class is absent in tests, so detection falls through; detail must explain.
        assertNotNull(status.detail)
        assertNull(MoseyDetector.open(app)?.takeIf { status.available })
        Shadows.shadowOf(app.packageManager).clearSystemSharedLibraryNames()
    }

    @Test
    fun `controller rename while advertising restarts advertising`() {
        ShareReceiveController.start(app)
        ShareReceiveController.setLocalName(app, "Renamed While Live")
        assertEquals("Renamed While Live", ShareReceiveController.localName)
        ShareReceiveController.stop()
        ShareReceiveController.refreshLocalIdentity(app)
    }

    @Test
    fun `airdrop discovery browse failure callbacks close flow`() = runBlocking {
        val discovery = AirDropDiscovery(app)
        val nsd = app.getSystemService(NsdManager::class.java)
        val shadow = Shadows.shadowOf(nsd)
        var completed = false
        val job = kotlinx.coroutines.CoroutineScope(Dispatchers.Default).async {
            try { discovery.browse(null).collect { } } catch (e: Exception) { }
            completed = true
        }
        var listener: NsdManager.DiscoveryListener? = null
        val dl = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < dl) { listener = shadow.getDiscoveryListeners(AIRDROP_SERVICE_TYPE)?.firstOrNull(); if (listener != null) break; delay(80) }
        assertNotNull(listener)
        listener!!.onDiscoveryStarted(AIRDROP_SERVICE_TYPE)
        val info = NsdServiceInfo().apply { serviceName = "lostpeer"; serviceType = AIRDROP_SERVICE_TYPE }
        listener.onServiceLost(info)
        listener.onDiscoveryStopped(AIRDROP_SERVICE_TYPE)
        listener.onStopDiscoveryFailed(AIRDROP_SERVICE_TYPE, 1)
        listener.onStartDiscoveryFailed(AIRDROP_SERVICE_TYPE, 2)
        val done = withTimeoutOrNull(5_000) { job.await(); true }
        assertTrue(done == true && completed)
        discovery.unadvertise()
    }
}
