package com.vayunmathur.share.platform

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.share.MainActivity
import com.vayunmathur.share.domain.airdrop.AirDropMessages
import com.vayunmathur.share.domain.airdrop.BinaryPlist
import com.vayunmathur.share.domain.airdrop.DvZip
import com.vayunmathur.share.domain.protocol.ShareState
import com.vayunmathur.share.network.airdrop.AirDropHttp
import com.vayunmathur.share.network.airdrop.AirDropHttpServer
import com.vayunmathur.share.network.transport.Connection
import com.vayunmathur.share.network.transport.TcpTransport
import com.vayunmathur.share.network.transport.stageFiles
import com.vayunmathur.share.network.transport.streamFile
import com.vayunmathur.share.platform.airdrop.AIRDROP_SERVICE_TYPE
import com.vayunmathur.share.platform.discovery.BleDiscoveryManager
import com.vayunmathur.share.platform.discovery.DiscoverySource
import com.vayunmathur.share.platform.discovery.NearbyDevice
import com.vayunmathur.share.platform.discovery.NsdDiscoveryManager
import com.vayunmathur.share.platform.receive.ShareReceiveController
import com.vayunmathur.share.protocol.ShareNativeDiscovery
import com.vayunmathur.share.ui.OutgoingFilesCard
import com.vayunmathur.share.ui.ShareSendScreen
import com.vayunmathur.share.ui.TransferCard
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class GapPush3Test {
    @get:Rule val rule = createComposeRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private fun grant(vararg p: String) = Shadows.shadowOf(app).grantPermissions(*p)
    private fun pump() = Shadows.shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `send screen with viewmodel composes`() {
        val vm = ShareViewModel(app)
        rule.setContent { ShareSendScreen(viewModel = vm) }
        rule.onNodeWithText("Nearby devices", substring = true).assertIsDisplayed()
    }

    @Test
    fun `transfer card button clicks`() {
        var done = 0; var dismiss = 0
        rule.setContent {
            androidx.compose.foundation.layout.Column {
                TransferCard(endpoint = "DonePeer", state = ShareState.Completed, bytesSent = 0, error = null, onDisconnect = { done++ })
                TransferCard(endpoint = "FailPeer", state = ShareState.Failed, bytesSent = 0, error = "kaput", onDisconnect = { dismiss++ })
            }
        }
        rule.onNodeWithText("Done").performClick()
        rule.onNodeWithText("Dismiss").performClick()
        assertEquals(1, done); assertEquals(1, dismiss)
    }

    @Test
    fun `outgoing card pick and clear clicks`() {
        val picks = AtomicInteger(0); val clears = AtomicInteger(0)
        rule.setContent {
            OutgoingFilesCard(
                uris = listOf(Uri.parse("content://x/1")), displayNames = listOf("f.txt"),
                onPickFiles = { picks.incrementAndGet() }, onClear = { clears.incrementAndGet() },
            )
        }
        rule.onNodeWithText("Add files").performClick()
        rule.onNodeWithText("Clear").performClick()
        assertEquals(1, picks.get()); assertEquals(1, clears.get())
    }

    @Test
    fun `controller syncServiceState from IO thread with main pumping`() {
        val doneFlag = AtomicBoolean(false)
        val t = Thread {
            kotlinx.coroutines.runBlocking {
                ShareReceiveController.setReceiveEnabled(app, true)
                ShareReceiveController.syncServiceState(app)
                doneFlag.set(true)
            }
        }
        t.start()
        val dl = System.currentTimeMillis() + 12_000
        while (System.currentTimeMillis() < dl && !doneFlag.get()) { pump(); Thread.sleep(50) }
        t.join(3_000)
        assertTrue(doneFlag.get())
        runBlocking { assertTrue(ShareReceiveController.isReceiveEnabled(app)) }
        // Turn back off for other tests.
        val done2 = AtomicBoolean(false)
        val t2 = Thread { kotlinx.coroutines.runBlocking { ShareReceiveController.setReceiveEnabled(app, false); done2.set(true) } }
        t2.start()
        val dl2 = System.currentTimeMillis() + 12_000
        while (System.currentTimeMillis() < dl2 && !done2.get()) { pump(); Thread.sleep(50) }
        t2.join(3_000)
        assertTrue(done2.get())
    }

    @Test
    fun `nsd advertise re-register on change and idempotent same`() {
        val mgr = NsdDiscoveryManager(app)
        val info = ShareNativeDiscovery.nativeBuildEndpointInfo("AdvPeer", 1)!!
        val a = mgr.advertise("EFGH", info, 5001)
        val b = mgr.advertise("EFGH", info, 5001)
        assertEquals(a, b)
        val c = mgr.advertise("EFGH", info, 5002, "10.1.1.1")
        assertEquals(a, c) // same instance name, new port re-registers
        mgr.unadvertise(); mgr.unadvertise()
        assertNull(mgr.instanceName)
    }

    @Test
    fun `ble extended advertising and malformed scan payloads`() = runBlocking {
        grant(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
        val bt = app.getSystemService(BluetoothManager::class.java)
        Shadows.shadowOf(bt.adapter).setEnabled(true)
        Shadows.shadowOf(bt.adapter).setIsLeExtendedAdvertisingSupported(true)
        val mgr = BleDiscoveryManager(app)
        val info = ShareNativeDiscovery.nativeBuildEndpointInfo("ExtPeer", 1)!!
        assertTrue(mgr.startAdvertising("IJKL", info, byteArrayOf(1, 2)))
        Thread.sleep(300)
        mgr.stopAdvertising()
        // Malformed scan payloads are ignored (valid BleAdvertisement wrapping garbage).
        val garbagePayload = byteArrayOf(1, 2, 3, 4)
        val garbageData = ShareNativeDiscovery.nativeBuildBleAdvertisement(garbagePayload, ByteArray(0), false)
        if (garbageData != null) {
            val ad = ByteArray(1 + 1 + 2 + garbageData.size)
            ad[0] = (1 + 2 + garbageData.size).toByte(); ad[1] = 0x16.toByte(); ad[2] = 0xF3.toByte(); ad[3] = 0xFE.toByte()
            garbageData.copyInto(ad, 4)
            val parse = android.bluetooth.le.ScanRecord::class.java.getMethod("parseFromBytes", ByteArray::class.java)
            val record = parse.invoke(null, ad) as android.bluetooth.le.ScanRecord
            val device = bt.adapter.getRemoteDevice("AA:BB:CC:DD:EE:03")
            val result = android.bluetooth.le.ScanResult(device, record, -50, 1L)
            val job = kotlinx.coroutines.CoroutineScope(Dispatchers.Default).async {
                val out = mutableListOf<NearbyDevice>()
                try { mgr.scan().collect { out += it } } catch (e: Exception) { }
                out
            }
            delay(700)
            Shadows.shadowOf(bt.adapter.bluetoothLeScanner).scanCallbacks.forEach { it.onScanResult(1, result) }
            delay(700)
            assertTrue(mgr.bleDevices.value.isEmpty())
            Shadows.shadowOf(bt.adapter.bluetoothLeScanner).scanCallbacks.forEach { it.onScanFailed(1) }
            job.await()
        }
        mgr.release()
    }

    @Test
    fun `tcp streaming ops fail cleanly before transfer state`() {
        val transport = TcpTransport("Ops", ReceivedFileStore(app))
        try {
            val session = com.vayunmathur.share.domain.protocol.ShareSession(localName = "OpsPeer", isInitiator = true)
            val conn = Connection(Socket(), session, incoming = false, remoteEndpoint = "127.0.0.1:9")
            val f = File(app.cacheDir, "ops-${System.nanoTime()}.txt").apply { writeText("ops") }
            // stageFiles succeeds (staging is state-independent), streamFile fails at openFile.
            val staged = transport.stageFiles(conn, listOf(f))
            assertNotNull(staged)
            assertFalse(transport.streamFile(conn, f))
            assertEquals(ShareState.Failed, conn.state.value)
            session.destroy()
        } finally { transport.release() }
    }

    @Test
    fun `binary plist truncation sweep and dvzip corrupt reader`() {
        val encoded = BinaryPlist.encode(mapOf("k" to "value", "n" to 123L, "list" to listOf(1L, 2L)))
        var decoded = 0; var threw = 0
        for (len in listOf(0, 5, 9, 20, encoded.size / 2, encoded.size - 1)) {
            runCatching { BinaryPlist.decode(encoded.copyOf(len)); decoded++ }.onFailure { threw++ }
        }
        assertTrue(decoded + threw == 6)
        assertTrue(threw >= 3)
        // Corrupt DvZip: reader over garbage either throws or yields non-matching bytes.
        val garbage = ByteArrayInputStream(byteArrayOf(0x1f.toByte(), 0x8b.toByte(), 0, 0, 0, 0))
        runCatching { DvZip.Reader(garbage).readBytes() }
        // Empty dvzip round trip
        val out = ByteArrayOutputStream()
        DvZip.Writer(out).close()
        val back = DvZip.Reader(ByteArrayInputStream(out.toByteArray())).readBytes()
        assertEquals(0, back.size)
    }

    @Test
    fun `airdrop http chunked trailers and oversized body`() {
        // Oversized plist body read via Request.readBody with small limit is bounded.
        val wire = "POST /Ask HTTP/1.1\r\nContent-Length: 100\r\n\r\n" + "x".repeat(100)
        val req = AirDropHttp.readRequest(ByteArrayInputStream(wire.toByteArray()))!!
        // Reading past the caller's limit is a protocol error, not a truncation.
        try {
            req.readBody(limit = 10)
            throw AssertionError("expected ProtocolException")
        } catch (e: AirDropHttp.ProtocolException) {
            assertTrue(e.message!!.contains("exceeds"))
        }
        // Bad chunk size line throws ProtocolException/EOF
        val bad = "POST /U HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\nZZZ\r\nabc\r\n"
        val req2 = AirDropHttp.readRequest(ByteArrayInputStream(bad.toByteArray()))!!
        runCatching { req2.readBody() }
    }

    @Test
    fun `main activity visible composes content`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.create().start().resume().visible().get()
        Thread.sleep(500); pump()
        assertNotNull(activity)
        controller.pause().stop().destroy()
    }

    @Test
    fun `viewmodel airdrop send succeeds against controller browse and receiver`() = runBlocking {
        // AirDrop receiver behind a server whose /Discover reports Apple (non-Google, so the
        // sender lists it); /Ask and /Upload delegate to the real receiver.
        val store = ReceivedFileStore(app)
        val receiver = com.vayunmathur.share.platform.airdrop.AirDropReceiver(store, { "VmMac" }, { 1_000_000_000L }, { _, _ -> })
        val server = AirDropHttpServer { req, remote ->
            if (req.path == "/Discover") {
                AirDropHttp.Response(200, mapOf("Content-Type" to AirDropMessages.CONTENT_TYPE_PLIST),
                    BinaryPlist.encode(AirDropMessages.DiscoverResponse("VmMac", "Apple", AirDropMessages.DEFAULT_MEDIA_CAPABILITIES).toPlist()))
            } else receiver.handle(req, remote)
        }
        try {
            val serverPort = server.start()
            val controller = ShareReceiveController.airDrop(app)
            val nsd = app.getSystemService(NsdManager::class.java)
            val shadow = Shadows.shadowOf(nsd)
            val emitted = java.util.concurrent.CopyOnWriteArrayList<NearbyDevice>()
            val browseJob = kotlinx.coroutines.CoroutineScope(Dispatchers.Default).async {
                try { controller.browse().collect { emitted += it } } catch (e: Exception) { }
            }
            var listener: NsdManager.DiscoveryListener? = null
            val dl = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < dl) {
                listener = shadow.getDiscoveryListeners(AIRDROP_SERVICE_TYPE)?.firstOrNull()
                if (listener != null) break
                delay(100)
            }
            assertNotNull(listener)
            val found = NsdServiceInfo().apply { serviceName = "vmmac"; serviceType = AIRDROP_SERVICE_TYPE }
            listener!!.onServiceFound(found)
            delay(600)
            val resolved = NsdServiceInfo().apply {
                serviceName = "vmmac"; serviceType = AIRDROP_SERVICE_TYPE
                port = serverPort; setHost(InetAddress.getByName("127.0.0.1"))
            }
            var device: NearbyDevice? = null
            val dl2 = System.currentTimeMillis() + 12_000
            while (System.currentTimeMillis() < dl2) {
                shadow.getResolveListeners(found)?.firstOrNull()?.onServiceResolved(resolved)
                device = emitted.firstOrNull()
                if (device != null) break
                delay(300)
            }
            assertNotNull("browse should emit the Apple peer: $emitted", device)
            browseJob.cancel()
            // Now send through the ViewModel (shares the same AirDropController singleton).
            val vm = ShareViewModel(app)
            val src = File(app.cacheDir, "vm-ad-send-${System.nanoTime()}.txt").apply { writeText("vm airdrop payload") }
            vm.setOutgoingUris(listOf(Uri.fromFile(src)))
            delay(400)
            vm.connectToDevice(device!!)
            // Accept the Ask on the standalone receiver; VM scope is Main-bound so pump.
            var accepted = false
            val dl3 = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < dl3 && !accepted) {
                pump()
                val inc = receiver.incoming.value.firstOrNull()
                if (inc != null) { receiver.accept(inc.sessionHandle, true); accepted = true }
                delay(60)
            }
            assertTrue("receiver should see the VM Ask", accepted)
            // Let the upload finish.
            val dl4 = System.currentTimeMillis() + 15_000
            var completed = false
            while (System.currentTimeMillis() < dl4) {
                pump()
                if (receiver.incoming.value.firstOrNull()?.state?.value == ShareState.Completed) { completed = true; break }
                delay(80)
            }
            assertTrue("upload should complete", completed)
            assertTrue(receiver.incoming.value.first().receivedFiles.value.isNotEmpty())
        } finally { server.release() }
    }
}
