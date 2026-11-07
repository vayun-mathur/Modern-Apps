package com.vayunmathur.share.platform

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.share.domain.protocol.ShareState
import com.vayunmathur.share.network.transport.TcpTransport
import com.vayunmathur.share.platform.discovery.DiscoverySource
import com.vayunmathur.share.platform.discovery.NearbyDevice
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ShareViewModelConnectTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `connect sends staged file to loopback peer then disconnects`() = runBlocking {
        val server = TcpTransport("PeerSrv", ReceivedFileStore(app))
        try {
            val port = server.listen()
            val vm = ShareViewModel(app)
            val src = File(app.cacheDir, "vm-send-${System.nanoTime()}.txt").apply { writeText("viewmodel payload") }
            vm.setOutgoingUris(listOf(Uri.fromFile(src)))
            // display names resolve async on IO; give it a moment
            delay(500)
            val device = NearbyDevice(endpointId = "PEER", endpointName = "Peer", host = "127.0.0.1", port = port, source = DiscoverySource.Nsd)
            vm.connectToDevice(device)
            // viewModelScope is Main-bound; pump the Robolectric main looper while waiting.
            fun pump() { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle() }
            var incoming: com.vayunmathur.share.network.transport.Connection? = null
            val dl0 = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < dl0) {
                pump()
                incoming = server.incomingConnections.value.firstOrNull()
                if (incoming != null) break
                delay(50)
            }
            assertNotNull("peer should see incoming", incoming)
            var accepted = false
            val dl = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < dl && !accepted) {
                pump()
                if (incoming!!.state.value == ShareState.AwaitingAccept) {
                    server.acceptIncoming(incoming.sessionHandle, true)
                    accepted = true
                }
                delay(50)
            }
            assertTrue(accepted)
            var got: com.vayunmathur.share.platform.ReceivedFile? = null
            val dl2 = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < dl2) {
                pump()
                got = incoming!!.receivedFiles.value.firstOrNull()
                if (got != null) break
                delay(50)
            }
            assertNotNull(got)
            assertTrue(got!!.name.endsWith(".txt"))
            // Active connection should be visible in UI state; disconnect it.
            val active = vm.sendUiState.value.activeConnection
            if (active != null) vm.disconnect(active)
            // Double-connect guard: second tap while connecting is ignored (no crash).
            vm.connectToDevice(device)
        } finally {
            server.release()
        }
    }

    @Test
    fun `airdrop device with staged files reports failure without peer listing`() = runBlocking {
        val vm = ShareViewModel(app)
        val src = File(app.cacheDir, "vm-ad-${System.nanoTime()}.txt").apply { writeText("ad") }
        vm.setOutgoingUris(listOf(Uri.fromFile(src)))
        delay(300)
        val device = NearbyDevice(endpointId = "airdrop:gone", endpointName = "GoneMac", source = DiscoverySource.AirDrop)
        vm.connectToDevice(device)
        delay(1_000)
        // No crash; connecting flag released so a second call also works.
        vm.connectToDevice(device)
        delay(500)
        assertTrue(vm.outgoingUris.value.isNotEmpty())
    }

    @Test
    fun `handle share intent with stream uri stages it`() {
        val vm = ShareViewModel(app)
        val src = File(app.cacheDir, "vm-intent-${System.nanoTime()}.txt").apply { writeText("intent") }
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_STREAM, Uri.fromFile(src))
        }
        vm.handleShareIntent(intent)
        assertTrue(vm.outgoingUris.value.isNotEmpty())
        // SEND_MULTIPLE
        val multi = android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/plain"
            putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, arrayListOf(Uri.fromFile(src)))
        }
        vm.handleShareIntent(multi)
        assertTrue(vm.outgoingUris.value.isNotEmpty())
    }
}
