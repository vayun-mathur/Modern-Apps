package com.vayunmathur.share.platform

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiInfo
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.share.domain.airdrop.AirDropMessages
import com.vayunmathur.share.domain.airdrop.BinaryPlist
import com.vayunmathur.share.domain.airdrop.Cpio
import com.vayunmathur.share.domain.protocol.PendingFile
import com.vayunmathur.share.domain.protocol.ShareState
import com.vayunmathur.share.network.airdrop.AirDropHttp
import com.vayunmathur.share.network.transport.TcpTransport
import com.vayunmathur.share.platform.airdrop.AIRDROP_SERVICE_TYPE
import com.vayunmathur.share.platform.airdrop.AirDropDiscovery
import com.vayunmathur.share.platform.airdrop.AirDropReceiver
import com.vayunmathur.share.platform.airdrop.link.MoseyBackend
import com.vayunmathur.share.platform.airdrop.link.MoseyHal
import com.vayunmathur.share.platform.airdrop.link.MoseyLink
import com.vayunmathur.share.platform.discovery.BleDiscoveryManager
import com.vayunmathur.share.platform.receive.ShareReceiveController
import com.vayunmathur.share.platform.receive.ShareReceiveNotifierTest
import com.vayunmathur.share.platform.receive.ShareReceiveNotifier
import com.vayunmathur.share.protocol.ShareNativeDiscovery
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowWifiInfo

@RunWith(RobolectricTestRunner::class)
class GapPush6Test {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val ctx: Context get() = app
    private fun grant(vararg p: String) = Shadows.shadowOf(app).grantPermissions(*p)

    private class FakeHal : MoseyHal {
        override val backend = MoseyBackend.SharedLibrary
        override val version = "fake"
        var started = false
        val updates = mutableListOf<ByteArray>()
        override fun hardwareCapability(): ByteArray? = byteArrayOf(0x08, 0x01)
        override fun start(channels: IntArray, token: IBinder, config: ByteArray) { started = true }
        override fun update(countryCode: String, config: ByteArray) { updates += config }
        override fun stop() {}
    }

    @Test
    fun `mosey sta channel from wifi transport info`() = runBlocking {
        val tm = app.getSystemService(android.telephony.TelephonyManager::class.java)
        Shadows.shadowOf(tm).setNetworkCountryIso("nl")
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val network = cm.activeNetwork
        if (network != null) {
            val wifiInfo = ShadowWifiInfo.newInstance()
            Shadows.shadowOf(wifiInfo).setFrequency(5180) // channel 36
            // NetworkCapabilities.Builder is @hide in the stubs; build reflectively.
            val caps = runCatching {
                val builderClass = Class.forName("android.net.NetworkCapabilities\$Builder")
                val builder = builderClass.getDeclaredConstructor().newInstance()
                builderClass.getMethod("addTransportType", Int::class.javaPrimitiveType)
                    .invoke(builder, NetworkCapabilities.TRANSPORT_WIFI)
                builderClass.getMethod("setTransportInfo", android.net.TransportInfo::class.java)
                    .invoke(builder, wifiInfo)
                builderClass.getMethod("build").invoke(builder) as NetworkCapabilities
            }.getOrNull()
            if (caps != null) runCatching { Shadows.shadowOf(cm).setNetworkCapabilities(network, caps) }
        }
        val hal = FakeHal()
        val link = MoseyLink(app, hal)
        val ok = link.activate()
        assertFalse(ok) // mosey0 still absent
        assertTrue(hal.started)
        assertTrue(hal.updates.isNotEmpty())
        link.deactivate()
    }

    private fun scanRecordFor(serviceData: ByteArray): ScanRecord {
        val ad = ByteArray(1 + 1 + 2 + serviceData.size)
        ad[0] = (1 + 2 + serviceData.size).toByte(); ad[1] = 0x16.toByte(); ad[2] = 0xF3.toByte(); ad[3] = 0xFE.toByte()
        serviceData.copyInto(ad, 4)
        val parse = ScanRecord::class.java.getMethod("parseFromBytes", ByteArray::class.java)
        return parse.invoke(null, ad) as ScanRecord
    }

    @Test
    fun `ble scan ignores payload with unparseable endpoint info`() = runBlocking {
        grant(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        val bt = app.getSystemService(BluetoothManager::class.java)
        Shadows.shadowOf(bt.adapter).setEnabled(true)
        val mgr = BleDiscoveryManager(app)
        // Valid envelope carrying garbage endpoint-info bytes.
        val payload = ShareNativeDiscovery.nativeBuildBleEndpointPayload("QRST", byteArrayOf(7, 7, 7, 7))!!
        val serviceData = ShareNativeDiscovery.nativeBuildBleAdvertisement(payload, ByteArray(0), false)!!
        val device = bt.adapter.getRemoteDevice("AA:BB:CC:DD:EE:04")
        val result = ScanResult(device, scanRecordFor(serviceData), -50, 1L)
        val job = CoroutineScope(Dispatchers.Default).async {
            val out = mutableListOf<com.vayunmathur.share.platform.discovery.NearbyDevice>()
            try { mgr.scan().collect { out += it } } catch (e: Exception) { }
            out
        }
        delay(800)
        Shadows.shadowOf(bt.adapter.bluetoothLeScanner).scanCallbacks.forEach { it.onScanResult(1, result) }
        delay(800)
        assertTrue(mgr.bleDevices.value.isEmpty())
        Shadows.shadowOf(bt.adapter.bluetoothLeScanner).scanCallbacks.forEach { it.onScanFailed(3) }
        job.await()
        mgr.release()
    }

    @Test
    fun `notifier done variants empty and mixed mime and corrupt image`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            // Empty received: no Share/Save actions.
            val f1 = ShareReceiveNotifierTest.FakeTransfer(41001L)
            val in1 = MutableStateFlow<List<IncomingTransfer>>(listOf(f1))
            val retired = mutableListOf<Long>()
            val n1 = ShareReceiveNotifier(app, in1, retire = { retired += it })
            n1.start(scope)
            delay(250)
            f1.state.value = ShareState.Completed
            delay(600)
            assertTrue(retired.contains(41001L))
            n1.stop()
            // Mixed mime + corrupt image (decode fails -> plain done notification).
            val f2 = ShareReceiveNotifierTest.FakeTransfer(41002L)
            val corrupt = java.io.File(app.cacheDir, "corrupt.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            f2.receivedFiles.value = listOf(
                ReceivedFile("corrupt.png", 3, "image/png", Uri.fromFile(corrupt)),
                ReceivedFile("notes.txt", 5, "text/plain", Uri.parse("content://x/notes")),
                ReceivedFile("clip.mp4", 9, "video/mp4", Uri.parse("content://x/clip")),
            )
            val in2 = MutableStateFlow<List<IncomingTransfer>>(listOf(f2))
            val n2 = ShareReceiveNotifier(app, in2, retire = { retired += it })
            n2.start(scope)
            delay(250)
            f2.state.value = ShareState.Completed
            delay(800)
            assertTrue(retired.contains(41002L))
            n2.stop()
        } finally { scope.cancel() }
    }

    @Test
    fun `airdrop receiver plain cpio upload and directory ask entries`() = runBlocking {
        val store = ReceivedFileStore(app)
        val receiver = AirDropReceiver(store, { "Recv" }, { 1_000_000_000L }, { _, _ -> })
        val remote = InetAddress.getByName("127.0.0.1")
        fun req(path: String, body: ByteArray, headers: Map<String, String> = emptyMap()) =
            AirDropHttp.Request("POST", path, AirDropHttp.Headers(headers.mapKeys { it.key.lowercase() }), ByteArrayInputStream(body))
        val ask = AirDropMessages.AskRequest(
            "DirPeer", "Phone", "idd",
            listOf(
                AirDropMessages.AskFile("folder", "public.folder", 0, "./folder", isDirectory = true),
                AirDropMessages.AskFile.forFile("plain.txt", 9L),
            ),
            transferId = "t-plain",
        )
        val job = async { receiver.handle(req("/Ask", BinaryPlist.encode(ask.toPlist())), remote) }
        var inc: com.vayunmathur.share.platform.airdrop.AirDropIncoming? = null
        val dl = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < dl) { inc = receiver.incoming.value.firstOrNull(); if (inc != null) break; delay(50) }
        assertNotNull(inc)
        // Directory entries are not pending files.
        assertEquals(1, inc!!.pendingFiles.value.size)
        assertEquals("plain.txt", inc.pendingFiles.value[0].name)
        receiver.accept(inc.sessionHandle, true)
        assertEquals(200, job.await().status)
        val cpioOut = ByteArrayOutputStream()
        Cpio.Writer(cpioOut).use { w -> w.file("./plain.txt", 9, ByteArrayInputStream("plainbody".toByteArray())) }
        val resp = receiver.handle(req("/Upload", cpioOut.toByteArray(), mapOf("Content-Type" to AirDropMessages.CONTENT_TYPE_CPIO, "TransferID" to "t-plain")), remote)
        assertEquals(200, resp.status)
        assertEquals(1, inc.receivedFiles.value.size)
        assertEquals("plain.txt", inc.receivedFiles.value[0].name)
    }

    @Test
    fun `controller hasActiveTransfers true with live incoming`() = runBlocking {
        val transport = ShareReceiveController.transport(app)
        val port = transport.listen()
        val client = TcpTransport("ActiveCli", ReceivedFileStore(app))
        try {
            client.connect("127.0.0.1", port)
            var active = false
            val dl = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < dl) {
                if (ShareReceiveController.hasActiveTransfers(app)) { active = true; break }
                delay(60)
            }
            assertTrue(active)
        } finally {
            client.release()
            transport.stopListening()
        }
    }

    @Test
    fun `binary plist mutation sweep never crashes caller`() {
        val encoded = BinaryPlist.encode(mapOf("a" to listOf(1L, 2L, mapOf("b" to "c")), "s" to "hello world, plist"))
        val rnd = java.util.Random(42)
        var ok = 0; var failed = 0
        repeat(150) {
            val mutated = encoded.copyOf()
            val pos = rnd.nextInt(mutated.size)
            mutated[pos] = rnd.nextInt(256).toByte()
            runCatching { BinaryPlist.decode(mutated); ok++ }.onFailure { failed++ }
        }
        assertTrue(ok + failed == 150)
        // Every truncation length
        for (len in 0 until encoded.size step 7) {
            runCatching { BinaryPlist.decode(encoded.copyOf(len)) }
        }
        assertNotNull(BinaryPlist.decode(encoded))
    }

    @Test
    fun `airdrop discovery advertise listener callbacks`() {
        val discovery = AirDropDiscovery(app)
        discovery.advertise("cbtest", 4321, null)
        assertEquals("cbtest", discovery.ownServiceName)
        val nsd = app.getSystemService(NsdManager::class.java)
        val shadow = Shadows.shadowOf(nsd)
        val key = NsdServiceInfo().apply { serviceName = "cbtest"; serviceType = AIRDROP_SERVICE_TYPE }
        val reg = shadow.getRegistrationListener(key)
        assertNotNull(reg)
        reg!!.onServiceRegistered(NsdServiceInfo().apply { serviceName = "cbtest-renamed" })
        assertEquals("cbtest-renamed", discovery.ownServiceName)
        reg.onServiceUnregistered(key)
        reg.onUnregistrationFailed(key, 2)
        reg.onRegistrationFailed(key, 4)
        discovery.unadvertise()
        assertEquals(null, discovery.ownServiceName)
    }
}
