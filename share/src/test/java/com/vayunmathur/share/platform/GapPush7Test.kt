package com.vayunmathur.share.platform

import android.Manifest
import android.app.Activity
import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.share.network.airdrop.AirDropHttpServer
import com.vayunmathur.share.platform.airdrop.AirDropPeer
import com.vayunmathur.share.platform.airdrop.AirDropReceiver
import com.vayunmathur.share.platform.airdrop.AirDropSender
import com.vayunmathur.share.platform.airdrop.link.WifiLanLink
import com.vayunmathur.share.platform.discovery.BleDiscoveryManager
import com.vayunmathur.share.platform.discovery.DiscoverySource
import com.vayunmathur.share.platform.discovery.NearbyDevice
import com.vayunmathur.share.platform.receive.ShareSaveActivity
import com.vayunmathur.share.protocol.ShareNativeDiscovery
import java.io.File
import java.net.InetAddress
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class GapPush7Test {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `viewmodel connect to refused port logs and resets`() = runBlocking {
        val vm = ShareViewModel(app)
        val src = File(app.cacheDir, "refused-${System.nanoTime()}.txt").apply { writeText("x") }
        vm.setOutgoingUris(listOf(Uri.fromFile(src)))
        delay(300)
        // Nothing listens on port 9 (discard): connect throws inside the VM coroutine.
        vm.connectToDevice(NearbyDevice(endpointId = "DEAD", endpointName = "Dead", host = "127.0.0.1", port = 9, source = DiscoverySource.Nsd))
        // Pump Main (viewModelScope) and give the IO connect time to fail.
        val dl = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < dl) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(60)
            // connecting flag must reset: a second attempt must also be accepted (no hang).
            if (vm.sendUiState.value.activeConnection == null) break
        }
        vm.connectToDevice(NearbyDevice(endpointId = "DEAD", endpointName = "Dead", host = "127.0.0.1", port = 9, source = DiscoverySource.Nsd))
        delay(500)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(vm.sendUiState.value.activeConnection == null)
    }

    @Test
    fun `airdrop sender declined when receiver rejects ask`() = runBlocking {
        val store = ReceivedFileStore(app)
        val receiver = AirDropReceiver(store, { "DeclineMac" }, { 1_000_000_000L }, { _, _ -> })
        val server = AirDropHttpServer(receiver::handle)
        try {
            val port = server.start()
            val peer = AirDropPeer(serviceName = "decline", address = InetAddress.getByName("127.0.0.1"), port = port)
            val sender = AirDropSender { "SendPhone" }
            val file = File(app.cacheDir, "decline-${System.nanoTime()}.txt").apply { writeText("no thanks") }
            val resultJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).async {
                sender.send(peer, WifiLanLink(app), listOf(file)) { _, _ -> }
            }
            var handle: Long? = null
            val dl = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < dl) {
                handle = receiver.incoming.value.firstOrNull()?.sessionHandle
                if (handle != null) break
                delay(50)
            }
            assertNotNull(handle)
            assertEquals(0, receiver.accept(handle!!, false))
            assertEquals(AirDropSender.Result.Declined, resultJob.await())
        } finally { server.release() }
    }

    @Test
    fun `save activity two files first saved second cancelled`() {
        val store = ReceivedFileStore(app)
        val f1 = store.newFile("multi-one.txt").apply { writeText("one") }
        val f2 = store.newFile("multi-two.txt").apply { writeText("two") }
        val dest = File(app.cacheDir, "multi-dest-${System.nanoTime()}.txt")
        val intent = Intent(app, ShareSaveActivity::class.java).apply {
            putExtra(ShareSaveActivity.EXTRA_FILE_URIS, arrayListOf(store.contentUri(f1), store.contentUri(f2)))
        }
        val controller = Robolectric.buildActivity(ShareSaveActivity::class.java, intent)
        val activity = controller.create().start().get()
        val first = Shadows.shadowOf(activity).nextStartedActivityForResult
        assertNotNull(first)
        Shadows.shadowOf(activity).receiveResult(first!!.intent, Activity.RESULT_OK, Intent().apply { data = Uri.fromFile(dest) })
        // Wait for copy + advance to second picker.
        var second: org.robolectric.shadows.ShadowActivity.IntentForResult? = null
        val dl = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < dl) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            second = Shadows.shadowOf(activity).nextStartedActivityForResult
            if (second != null) break
            Thread.sleep(60)
        }
        assertTrue(dest.exists() && dest.readText() == "one")
        assertNotNull("second picker should launch", second)
        Shadows.shadowOf(activity).receiveResult(second!!.intent, Activity.RESULT_CANCELED, null)
        Thread.sleep(400)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(activity.isFinishing)
        controller.destroy()
    }

    @Test
    fun `ble advertiser null branch`() {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
        val bt = app.getSystemService(BluetoothManager::class.java)
        Shadows.shadowOf(bt.adapter).setEnabled(true)
        Shadows.shadowOf(bt.adapter).setBluetoothLeAdvertiser(null)
        val mgr = BleDiscoveryManager(app)
        val info = ShareNativeDiscovery.nativeBuildEndpointInfo("NullAdv", 1)!!
        // Shadow may ignore the null advertiser; either outcome must not throw.
        mgr.startAdvertising("OPQR", info)
        mgr.release()
    }
}
