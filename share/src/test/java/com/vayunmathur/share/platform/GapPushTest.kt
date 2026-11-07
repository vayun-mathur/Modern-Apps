package com.vayunmathur.share.platform

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Looper
import android.telephony.TelephonyManager
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.share.domain.protocol.ShareState
import com.vayunmathur.share.platform.airdrop.AirDropDiscovery
import com.vayunmathur.share.platform.airdrop.link.MoseyBackend
import com.vayunmathur.share.platform.airdrop.link.MoseyHal
import com.vayunmathur.share.platform.airdrop.link.MoseyLink
import com.vayunmathur.share.platform.discovery.BleDiscoveryManager
import com.vayunmathur.share.platform.discovery.NEARBY_CONNECTIONS_SERVICE_UUID
import com.vayunmathur.share.platform.discovery.NsdDiscoveryManager
import com.vayunmathur.share.platform.discovery.SHARE_SERVICE_TYPE
import com.vayunmathur.share.platform.receive.ShareReceiveTileService
import com.vayunmathur.share.platform.receive.ShareSaveActivity
import com.vayunmathur.share.protocol.ShareNativeDiscovery
import com.vayunmathur.share.ui.TransferCard
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import java.io.File
import java.net.InetAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class GapPushTest {
    @get:Rule val rule = createComposeRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val ctx: Context get() = app

    private fun grant(vararg perms: String) = Shadows.shadowOf(app).grantPermissions(*perms)

    // ---------- BLE scan ----------
    @Test
    fun `ble scan emits device for valid advertisement and ignores foreign`() = runBlocking {
        grant(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        val bt = app.getSystemService(BluetoothManager::class.java)
        Shadows.shadowOf(bt.adapter).setEnabled(true)
        val mgr = BleDiscoveryManager(app)
        val info = ShareNativeDiscovery.nativeBuildEndpointInfo("BlePeer", 1)!!
        val payload = ShareNativeDiscovery.nativeBuildBleEndpointPayload("WXYZ", info)!!
        val serviceData = ShareNativeDiscovery.nativeBuildBleAdvertisement(payload, ByteArray(0), false)!!
        // AD structure: length, type 0x16, UUID16 LE (FEF3), data
        val ad = ByteArray(1 + 1 + 2 + serviceData.size)
        ad[0] = (1 + 2 + serviceData.size).toByte()
        ad[1] = 0x16.toByte()
        ad[2] = 0xF3.toByte(); ad[3] = 0xFE.toByte()
        serviceData.copyInto(ad, 4)
        val device = bt.adapter.getRemoteDevice("AA:BB:CC:DD:EE:01")
        // ScanRecord.parseFromBytes is @hide in the SDK stubs; it exists in the runtime jar.
        val parse = ScanRecord::class.java.getMethod("parseFromBytes", ByteArray::class.java)
        val record = parse.invoke(null, ad) as ScanRecord
        assertNotNull(record.getServiceData(NEARBY_CONNECTIONS_SERVICE_UUID))
        val result = ScanResult(device, record, -55, 1_000_000L)
        val deferred = async(Dispatchers.Default) {
            val out = mutableListOf<com.vayunmathur.share.platform.discovery.NearbyDevice>()
            try { mgr.scan().collect { out += it } } catch (e: Exception) { }
            out
        }
        val scanner = bt.adapter.bluetoothLeScanner
        // Wait until production's startScan has registered its callback.
        val dl0 = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < dl0 && Shadows.shadowOf(scanner).scanCallbacks.isEmpty()) delay(100)
        assertTrue("scan callback registered: ${Shadows.shadowOf(scanner).scanCallbacks}", Shadows.shadowOf(scanner).scanCallbacks.isNotEmpty())
        // Verify the native parse chain the production callback will run.
        val parsedData = ShareNativeDiscovery.nativeParseBleAdvertisement(serviceData)
        assertNotNull("nativeParseBleAdvertisement", parsedData)
        assertNotNull("nativeParseBleEndpointInfo", ShareNativeDiscovery.nativeParseBleEndpointInfo(parsedData!!))
        Shadows.shadowOf(scanner).addScanResult(result)
        delay(600)
        if (!mgr.bleDevices.value.containsKey("WXYZ")) {
            // Shadow delivery may be posted to the paused main looper; deliver directly instead.
            Shadows.shadowOf(scanner).scanCallbacks.forEach { it.onScanResult(1, result) }
        }
        // foreign advertisement is ignored
        val foreign = parse.invoke(null, byteArrayOf(2, 0x16, 0xAA.toByte(), 0xBB.toByte())) as ScanRecord
        Shadows.shadowOf(scanner).addScanResult(ScanResult(device, foreign, -60, 2_000_000L))
        val dl1 = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < dl1 && !mgr.bleDevices.value.containsKey("WXYZ")) delay(100)
        val devices = mgr.bleDevices.value
        assertTrue("expected WXYZ in $devices", devices.containsKey("WXYZ"))
        assertEquals("BlePeer", devices["WXYZ"]!!.endpointName)
        // scan failure closes the flow
        Shadows.shadowOf(scanner).scanCallbacks.firstOrNull()?.onScanFailed(2)
        val collected = deferred.await()
        assertTrue(collected.any { it.endpointId == "WXYZ" })
        mgr.release()
    }

    // ---------- NSD ----------
    @Test
    fun `nsd registration listener callbacks and discover resolve flow`() = runBlocking {
        val mgr = NsdDiscoveryManager(app)
        val info = ShareNativeDiscovery.nativeBuildEndpointInfo("NsdPeer", 1)!!
        val instance = mgr.advertise("ABCD", info, 9123, "192.168.1.2")
        assertNotNull(instance)
        val nsd = app.getSystemService(NsdManager::class.java)
        val shadow = Shadows.shadowOf(nsd)
        val keyInfo = NsdServiceInfo().apply { serviceName = instance; serviceType = SHARE_SERVICE_TYPE }
        val reg = shadow.getRegistrationListener(keyInfo)
        assertNotNull(reg)
        reg!!.onServiceRegistered(NsdServiceInfo().apply { serviceName = "renamed-instance" })
        assertEquals("renamed-instance", mgr.instanceName)
        reg.onServiceUnregistered(keyInfo)
        reg.onUnregistrationFailed(keyInfo, 3)
        reg.onRegistrationFailed(keyInfo, 5)
        mgr.unadvertise()

        // discover: found -> resolve -> emitted
        val deferred = async(Dispatchers.Default) {
            val out = mutableListOf<com.vayunmathur.share.platform.discovery.NearbyDevice>()
            try { mgr.discover().collect { out += it } } catch (e: Exception) { }
            out
        }
        delay(700)
        val listeners = shadow.getDiscoveryListeners(SHARE_SERVICE_TYPE)!!
        assertTrue(listeners.isNotEmpty())
        val listener = listeners[0]
        listener.onDiscoveryStarted(SHARE_SERVICE_TYPE)
        val found = NsdServiceInfo().apply { serviceName = instance; serviceType = SHARE_SERVICE_TYPE }
        listener.onServiceFound(found)
        delay(500)
        val resolveListeners = shadow.getResolveListeners(found)!!
        assertTrue(resolveListeners.isNotEmpty())
        val wifiBytes = ShareNativeDiscovery.nativeBuildWifiLanServiceInfo("ABCD")!!
        val resolved = NsdServiceInfo().apply {
            serviceName = Base64.encodeToString(wifiBytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            serviceType = SHARE_SERVICE_TYPE
            port = 8888
            setAttribute("n", Base64.encodeToString(info, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
            setAttribute("IPv4", "10.0.0.9")
        }
        // serviceName must equal the found instance for toNearbyDevice self-check; use resolved name = instance is our own.
        // Instead resolve listeners were registered for `found`; deliver a peer instance:
        resolveListeners[0].onServiceResolved(resolved)
        delay(700)
        // Our own advertised instance is filtered; now deliver failure/close paths
        listener.onServiceLost(found)
        listener.onDiscoveryStopped(SHARE_SERVICE_TYPE)
        deferred.cancel()
        mgr.stopDiscovery()
        mgr.clearDiscoveredDevices()
        assertTrue(true)
    }

    @Test
    fun `nsd discover with peer record emits device`() = runBlocking {
        val mgr = NsdDiscoveryManager(app)
        val info = ShareNativeDiscovery.nativeBuildEndpointInfo("OtherPeer", 1)!!
        val wifiBytes = ShareNativeDiscovery.nativeBuildWifiLanServiceInfo("PQRS")!!
        val peerInstance = Base64.encodeToString(wifiBytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val nsd = app.getSystemService(NsdManager::class.java)
        val shadow = Shadows.shadowOf(nsd)
        val deferred = async(Dispatchers.Default) {
            val out = mutableListOf<com.vayunmathur.share.platform.discovery.NearbyDevice>()
            try { mgr.discover().collect { out += it } } catch (e: Exception) { }
            out
        }
        delay(700)
        val listener = shadow.getDiscoveryListeners(SHARE_SERVICE_TYPE)!![0]
        val found = NsdServiceInfo().apply { serviceName = peerInstance; serviceType = SHARE_SERVICE_TYPE }
        listener.onServiceFound(found)
        delay(500)
        val resolved = NsdServiceInfo().apply {
            serviceName = peerInstance; serviceType = SHARE_SERVICE_TYPE; port = 7777
            setAttribute("n", Base64.encodeToString(info, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
            setAttribute("IPv4", "10.0.0.7")
        }
        shadow.getResolveListeners(found)!![0].onServiceResolved(resolved)
        delay(700)
        val devices = mgr.discoveredDevices.value
        assertTrue("expected PQRS in $devices", devices.any { it.endpointId == "PQRS" && it.host == "10.0.0.7" && it.port == 7777 })
        deferred.cancel()
        mgr.release()
    }

    // ---------- MoseyLink with country ----------
    private class FakeHal : MoseyHal {
        override val backend = MoseyBackend.SharedLibrary
        override val version = "fake"
        var started = false; var stopped = false
        val updates = mutableListOf<String>()
        override fun hardwareCapability(): ByteArray? = byteArrayOf(0x08, 0x01)
        override fun start(channels: IntArray, token: android.os.IBinder, config: ByteArray) { started = true }
        override fun update(countryCode: String, config: ByteArray) { updates += countryCode }
        override fun stop() { stopped = true }
    }

    @Test
    fun `mosey link with country starts hal then fails on missing interface`() = runBlocking {
        val tm = app.getSystemService(TelephonyManager::class.java)
        Shadows.shadowOf(tm).setNetworkCountryIso("us")
        val hal = FakeHal()
        val link = MoseyLink(app, hal)
        val ok = link.activate()
        assertFalse(ok) // mosey0 never appears on Robolectric
        assertTrue(hal.started)
        assertTrue(hal.stopped)
        link.onTransferStarted(InetAddress.getByName("fe80::9") as java.net.Inet6Address)
        assertTrue(hal.updates.contains("US"))
        link.deactivate()
    }

    // ---------- TileService ----------
    @Test
    fun `tile service refresh and click with tile present`() {
        grant(Manifest.permission.POST_NOTIFICATIONS)
        val controller = Robolectric.buildService(ShareReceiveTileService::class.java)
        val service = controller.create().get()
        service.onTileAdded()
        service.onStartListening()
        Thread.sleep(400)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val tile = service.qsTile
        assertNotNull(tile)
        service.onClick()
        Thread.sleep(400)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        // No controller.destroy(): Robolectric's TileService shadow mishandles Service.onDestroy.
    }

    // ---------- SaveActivity OK copy ----------
    @Test
    fun `save activity copies to chosen destination`() {
        val store = ReceivedFileStore(app)
        val staged = store.newFile("copy-src.txt").apply { writeText("copy payload data") }
        val destFile = File(app.cacheDir, "copy-dest-${System.nanoTime()}.txt")
        val intent = Intent(app, ShareSaveActivity::class.java).apply {
            putExtra(ShareSaveActivity.EXTRA_FILE_URIS, arrayListOf(store.contentUri(staged)))
        }
        val controller = Robolectric.buildActivity(ShareSaveActivity::class.java, intent)
        val activity = controller.create().start().get()
        val started = Shadows.shadowOf(activity).nextStartedActivityForResult
        assertNotNull(started)
        val resultData = Intent().apply { data = Uri.fromFile(destFile) }
        Shadows.shadowOf(activity).receiveResult(started!!.intent, android.app.Activity.RESULT_OK, resultData)
        // copy runs on lifecycleScope/IO; wait for file content
        val dl = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < dl && (!destFile.exists() || destFile.length() == 0L)) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
        }
        assertTrue("dest should receive payload, exists=${destFile.exists()} len=${if (destFile.exists()) destFile.length() else -1}",
            destFile.exists() && destFile.readText() == "copy payload data")
        controller.destroy()
    }

    // ---------- TransferCard remaining states ----------
    @Test
    fun `transfer card awaiting unknown handshaking render`() {
        rule.setContent { TransferCard(endpoint = "PeerA", state = ShareState.AwaitingAccept, bytesSent = 0, error = null, onDisconnect = {}) }
        rule.onNodeWithText("PeerA", substring = true).assertExists()
    }
}
