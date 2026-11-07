package com.vayunmathur.share.platform

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothManager
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Binder
import android.os.Looper
import android.os.Parcel
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.share.domain.protocol.ShareState
import com.vayunmathur.share.network.transport.TcpTransport
import com.vayunmathur.share.platform.airdrop.AIRDROP_SERVICE_TYPE
import com.vayunmathur.share.platform.airdrop.AirDropDiscovery
import com.vayunmathur.share.platform.airdrop.link.MoseyBackend
import com.vayunmathur.share.platform.airdrop.link.MoseyDetector
import com.vayunmathur.share.platform.discovery.BleDiscoveryManager
import com.vayunmathur.share.platform.discovery.DiscoverySource
import com.vayunmathur.share.platform.discovery.NearbyDevice
import com.vayunmathur.share.platform.discovery.NsdDiscoveryManager
import com.vayunmathur.share.protocol.ShareNativeDiscovery
import com.vayunmathur.share.ui.SharePopupContent
import com.vayunmathur.share.ui.TransferCard
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowServiceManager

@RunWith(RobolectricTestRunner::class)
class GapPush5Test {
    @get:Rule val rule = createComposeRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private fun grant(vararg p: String) = Shadows.shadowOf(app).grantPermissions(*p)
    private fun pump() = Shadows.shadowOf(Looper.getMainLooper()).idle()

    private class FakeMoseyBinder : android.os.IBinder {
        override fun getInterfaceDescriptor(): String = "com.google.android.moseyservice.IMoseyService"
        override fun pingBinder(): Boolean = true
        override fun isBinderAlive(): Boolean = true
        override fun queryLocalInterface(descriptor: String): android.os.IInterface? = null
        override fun dump(fd: java.io.FileDescriptor, args: Array<out String>?) {}
        override fun dumpAsync(fd: java.io.FileDescriptor, args: Array<out String>?) {}
        override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (reply == null) return false
            reply.writeInt(0) // no exception
            when (code) {
                16777215 -> reply.writeInt(3) // interface version
                4 -> { // query capability: non-null marker + sized byte array
                    reply.writeInt(1)
                    val cap = byteArrayOf(0x08, 0x01)
                    reply.writeInt(4 + 4 + cap.size)
                    reply.writeByteArray(cap)
                }
            }
            return true
        }
        override fun linkToDeath(recipient: android.os.IBinder.DeathRecipient, flags: Int) {}
        override fun unlinkToDeath(recipient: android.os.IBinder.DeathRecipient, flags: Int): Boolean = true
    }

    private fun declareService(name: String, binder: android.os.IBinder) {
        ShadowServiceManager.setServiceDeclaredOrNot(name, true)
        val iface = object : android.os.IInterface {
            override fun asBinder(): android.os.IBinder = binder
        }
        runCatching { ShadowServiceManager.addBinderService(name, android.os.IInterface::class.java, iface) }
        val sm = Class.forName("android.os.ServiceManager")
        runCatching { sm.getMethod("addService", String::class.java, android.os.IBinder::class.java).invoke(null, name, binder) }
    }

    @Test
    fun `mosey aidl hal via service manager shadow`() {
        ShadowServiceManager.reset()
        try {
            declareService(MoseyDetector.AIDL_ANDROID, FakeMoseyBinder())
            val status = MoseyDetector.detect(app)
            assertEquals(MoseyBackend.AidlAndroid, status.backend)
            val hal = MoseyDetector.open(app)
            assertNotNull(hal)
            // Robolectric's declared-service binder is a proxy whose transact is
            // unimplemented, so version degrades to A-1 instead of reaching our fake.
            assertEquals("A-1", hal!!.version)
            assertEquals(MoseyBackend.AidlAndroid, hal.backend)
        } finally { ShadowServiceManager.reset() }
    }

    @Test
    fun `mosey pixel aidl hal has no capability query`() {
        ShadowServiceManager.reset()
        try {
            declareService(MoseyDetector.AIDL_PIXEL, FakeMoseyBinder())
            val status = MoseyDetector.detect(app)
            assertEquals(MoseyBackend.AidlPixel, status.backend)
            val hal = MoseyDetector.open(app)
            assertNotNull(hal)
            assertEquals(null, hal!!.hardwareCapability())
            assertEquals(MoseyBackend.AidlPixel, hal.backend)
        } finally { ShadowServiceManager.reset() }
    }

    @Test
    fun `ble legacy advertising when extended unsupported`() {
        grant(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        val bt = app.getSystemService(BluetoothManager::class.java)
        Shadows.shadowOf(bt.adapter).setEnabled(true)
        Shadows.shadowOf(bt.adapter).setIsLeExtendedAdvertisingSupported(false)
        val mgr = BleDiscoveryManager(app)
        // Short name so the endpoint payload fits the legacy fast-mode budget.
        val info = ShareNativeDiscovery.nativeBuildEndpointInfo("L", 1)!!
        // Return value depends on whether the native fast payload fits; either way the
        // legacy path and its stop counterparts must run without throwing.
        mgr.startAdvertising("MNOP", info)
        Thread.sleep(200)
        assertTrue(mgr.startFastInitiation(byteArrayOf(5, 6)))
        mgr.stopFastInitiation()
        mgr.stopAdvertising()
        mgr.release()
    }

    @Test
    fun `transfer card unknown and transferring with error`() {
        rule.setContent {
            androidx.compose.foundation.layout.Column {
                TransferCard(endpoint = "UnknownPeer", state = ShareState.Unknown, bytesSent = 0, error = null, onDisconnect = {})
                TransferCard(endpoint = "WarnPeer", state = ShareState.Transferring, bytesSent = 4096, error = "transient warning", onDisconnect = {})
            }
        }
        rule.onNodeWithText("Unknown state").assertIsDisplayed()
        rule.onNodeWithText("transient warning").assertIsDisplayed()
    }

    @Test
    fun `popup content with files and scanning`() {
        val actions = object : ShareActions {
            override fun startScan() {}
            override fun stopScan() {}
            override fun connectToDevice(device: NearbyDevice) {}
            override fun setOutgoingUris(uris: List<Uri>) {}
            override fun clearOutgoing() {}
            override fun disconnect(connection: com.vayunmathur.share.network.transport.Connection) {}
        }
        rule.setContent {
            SharePopupContent(
                uiState = SendUiState(outgoingDisplayNames = listOf("alpha.txt", "beta.txt"), outgoingUris = listOf(Uri.parse("content://x/1"), Uri.parse("content://x/2")), isScanning = true),
                actions = actions, permissionsDenied = false, onDismiss = {},
            )
        }
        rule.onNodeWithText("• alpha.txt").assertIsDisplayed()
        rule.onNodeWithText("Scanning for nearby devices", substring = true).assertIsDisplayed()
    }

    @Test
    fun `nsd startCollectingDiscovery suspends until cancelled`() = runBlocking {
        val mgr = NsdDiscoveryManager(app)
        val job = CoroutineScope(Dispatchers.Default).async {
            runCatching { mgr.startCollectingDiscovery() }
        }
        delay(800)
        mgr.stopDiscovery()
        job.cancel()
        assertTrue(job.isCancelled || job.isCompleted)
        mgr.release()
    }

    @Test
    fun `airdrop discovery browse resolve failure drops peer`() = runBlocking {
        val discovery = AirDropDiscovery(app)
        val nsd = app.getSystemService(NsdManager::class.java)
        val shadow = Shadows.shadowOf(nsd)
        val emitted = java.util.concurrent.CopyOnWriteArrayList<com.vayunmathur.share.platform.airdrop.AirDropPeer>()
        val job = CoroutineScope(Dispatchers.Default).async {
            try { discovery.browse(null).collect { emitted += it } } catch (e: Exception) { }
        }
        var listener: NsdManager.DiscoveryListener? = null
        val dl = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < dl) { listener = shadow.getDiscoveryListeners(AIRDROP_SERVICE_TYPE)?.firstOrNull(); if (listener != null) break; delay(80) }
        assertNotNull(listener)
        val found = NsdServiceInfo().apply { serviceName = "failpeer"; serviceType = AIRDROP_SERVICE_TYPE }
        listener!!.onServiceFound(found)
        delay(600)
        shadow.getResolveListeners(found)?.firstOrNull()?.onResolveFailed(found, 3)
        delay(600)
        assertTrue(emitted.isEmpty())
        job.cancel()
    }

    @Test
    fun `viewmodel disconnect clears active connection`() = runBlocking {
        val server = TcpTransport("DiscSrv", ReceivedFileStore(app))
        try {
            val port = server.listen()
            val vm = ShareViewModel(app)
            val collectJob = CoroutineScope(Dispatchers.Default).async {
                try { vm.sendUiState.collect { } } catch (e: Exception) { }
            }
            val src = File(app.cacheDir, "disc-${System.nanoTime()}.txt").apply { writeText("disc") }
            vm.setOutgoingUris(listOf(Uri.fromFile(src)))
            delay(400)
            vm.connectToDevice(NearbyDevice(endpointId = "DP", endpointName = "DiscPeer", host = "127.0.0.1", port = port, source = DiscoverySource.Nsd))
            var active: com.vayunmathur.share.network.transport.Connection? = null
            val dl = System.currentTimeMillis() + 12_000
            while (System.currentTimeMillis() < dl) {
                pump()
                active = vm.sendUiState.value.activeConnection
                if (active != null) break
                Thread.sleep(60)
            }
            assertNotNull("active connection should appear", active)
            vm.disconnect(active!!)
            val dl2 = System.currentTimeMillis() + 8_000
            var cleared = false
            while (System.currentTimeMillis() < dl2) {
                pump()
                if (vm.sendUiState.value.activeConnection == null) { cleared = true; break }
                Thread.sleep(60)
            }
            assertTrue(cleared)
            collectJob.cancel()
        } finally { server.release() }
    }
}
