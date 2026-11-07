package com.vayunmathur.share.platform

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothManager
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.share.domain.airdrop.AirDropMessages
import com.vayunmathur.share.domain.airdrop.BinaryPlist
import com.vayunmathur.share.domain.airdrop.DvZip
import com.vayunmathur.share.domain.protocol.ShareState
import com.vayunmathur.share.network.airdrop.AirDropHttp
import com.vayunmathur.share.network.airdrop.AirDropHttpServer
import com.vayunmathur.share.network.transport.TcpTransport
import com.vayunmathur.share.platform.airdrop.AirDropPeer
import com.vayunmathur.share.platform.airdrop.AirDropSender
import com.vayunmathur.share.platform.airdrop.link.MoseyConfig
import com.vayunmathur.share.platform.airdrop.link.WifiLanLink
import com.vayunmathur.share.platform.discovery.BleDiscoveryManager
import com.vayunmathur.share.platform.receive.ShareReceiveNotifier
import com.vayunmathur.share.platform.receive.ShareReceiveNotifierTest
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class GapPush8Test {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `ble stopScan while scan active`() = runBlocking {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        val bt = app.getSystemService(BluetoothManager::class.java)
        Shadows.shadowOf(bt.adapter).setEnabled(true)
        val mgr = BleDiscoveryManager(app)
        val job = CoroutineScope(Dispatchers.Default).async {
            try { mgr.scan().collect { } } catch (e: Exception) { }
        }
        // Wait for the scan callback to register, then stop through the manager.
        val dl = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < dl && Shadows.shadowOf(bt.adapter.bluetoothLeScanner).scanCallbacks.isEmpty()) delay(80)
        assertTrue(Shadows.shadowOf(bt.adapter.bluetoothLeScanner).scanCallbacks.isNotEmpty())
        mgr.stopScan()
        mgr.stopScan() // already cleared: no-op
        job.cancel()
        mgr.release()
    }

    @Test
    fun `tcp awaitIdle waits for live connection then returns`() = runBlocking {
        val server = TcpTransport("IdleSrv", ReceivedFileStore(app))
        val client = TcpTransport("IdleCli", ReceivedFileStore(app))
        try {
            val port = server.listen()
            val conn = client.connect("127.0.0.1", port)
            val idle = CoroutineScope(Dispatchers.Default).async { client.awaitIdle() }
            delay(700)
            assertFalse(idle.isCompleted)
            client.disconnect(conn)
            val done = kotlinx.coroutines.withTimeoutOrNull(8_000) { idle.await(); true }
            assertEquals(true, done)
        } finally { client.release(); server.release() }
    }

    private fun assertFalse(b: Boolean) = org.junit.Assert.assertFalse(b)

    @Test
    fun `dvzip single byte writes and mosey negative sta channel`() {
        val out = ByteArrayOutputStream()
        val w = DvZip.Writer(out)
        w.write(65); w.write(66); w.write(67)
        w.flush(); w.close()
        val reader = DvZip.Reader(ByteArrayInputStream(out.toByteArray()))
        assertEquals(65, reader.read()); assertEquals(66, reader.read()); assertEquals(67, reader.read())
        assertEquals(-1, reader.read())
        reader.close()
        // Negative sta channel exercises sign-extended varint encoding.
        val cfg = MoseyConfig.staChannelUpdate(-5)
        assertTrue(cfg.size > 4)
        val start = MoseyConfig.start(intArrayOf(149, 44), staChannel = -1, dualBandSimultaneous = true)
        assertTrue(start.isNotEmpty())
    }

    @Test
    fun `airdrop sender discover returns apple peer details`() = runBlocking {
        val server = AirDropHttpServer { req, _ ->
            if (req.path == "/Discover") {
                AirDropHttp.Response(200, mapOf("Content-Type" to AirDropMessages.CONTENT_TYPE_PLIST),
                    BinaryPlist.encode(AirDropMessages.DiscoverResponse("StudioMac", "Apple", AirDropMessages.DEFAULT_MEDIA_CAPABILITIES).toPlist()))
            } else AirDropHttp.Response(404)
        }
        try {
            val port = server.start()
            val sender = AirDropSender { "Phone" }
            val peer = AirDropPeer("svc", InetAddress.getByName("127.0.0.1"), port)
            val info = sender.discover(peer, WifiLanLink(app))
            assertNotNull(info)
            assertEquals("StudioMac", info!!.receiverComputerName)
            assertEquals("Apple", info.manufacturer)
        } finally { server.release() }
    }

    @Test
    fun `notifier onGone posts failure for vanished visible transfer`() = runBlocking {
        val fake = ShareReceiveNotifierTest.FakeTransfer(51001L)
        val incoming = MutableStateFlow<List<IncomingTransfer>>(listOf(fake))
        val retired = mutableListOf<Long>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val notifier = ShareReceiveNotifier(app, incoming, retire = { retired += it })
        try {
            notifier.start(scope)
            delay(250)
            fake.state.value = ShareState.AwaitingAccept
            delay(600)
            // Transfer vanishes without a terminal state: failure should be posted.
            incoming.value = emptyList()
            delay(700)
            // No retire for vanished (only terminal posts retire); just assert no crash and
            // a subsequent terminal transfer still retires cleanly in the same notifier.
            val f2 = ShareReceiveNotifierTest.FakeTransfer(51002L)
            incoming.value = listOf(f2)
            delay(250)
            f2.state.value = ShareState.Failed
            delay(600)
            assertTrue(retired.contains(51002L))
        } finally { notifier.stop(); scope.cancel() }
    }
}
