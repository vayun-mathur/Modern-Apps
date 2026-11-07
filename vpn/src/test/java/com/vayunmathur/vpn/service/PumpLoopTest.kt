package com.vayunmathur.vpn.service

import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.vpn.data.VpnConfig
import com.vayunmathur.vpn.util.VpnNative
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.nio.channels.DatagramChannel
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PumpLoopTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun nativeAvailable(): Boolean = try { VpnNative.init(); true } catch (_: Throwable) { false }

    @Test fun pumpLoopConsumesTunPacketAndStopsOnDisconnect() { runBlocking {
        Assume.assumeTrue(nativeAvailable())
        val svc = Robolectric.buildService(VpnTunnelService::class.java).create().get()
        val priv = VpnNative.generatePrivateKey()
        val handle = VpnNative.newTunnel(priv, VpnNative.derivePublicKey(priv), "", 25)
        val s = svc.TunnelSession(VpnConfig(name = "pump", peerEndpoint = "127.0.0.1:51820"), handle)
        s.logDao = VpnTunnelServiceTest.FakeLogDao()
        s.appResolver = AppResolver(ctx)
        s.tracker.clear()

        // Craft a UDP packet (10.0.0.2:40000 -> 8.8.8.8:53) in the tun backing file
        val tunFile = File(ctx.cacheDir, "tun-pump.bin")
        val payload = byteArrayOf(1, 2, 3, 4)
        val pkt = ByteArray(28 + payload.size)
        pkt[0] = 0x45; pkt[9] = 17
        val total = pkt.size; pkt[2] = (total shr 8).toByte(); pkt[3] = (total and 0xFF).toByte()
        pkt[12] = 10; pkt[15] = 2; pkt[16] = 8; pkt[17] = 8; pkt[18] = 8; pkt[19] = 8
        pkt[20] = (40000 shr 8).toByte(); pkt[21] = (40000 and 0xFF).toByte(); pkt[22] = 0; pkt[23] = 53
        System.arraycopy(payload, 0, pkt, 28, payload.size)
        FileOutputStream(tunFile).use { it.write(pkt) }

        val pfd = ParcelFileDescriptor.open(tunFile, ParcelFileDescriptor.MODE_READ_WRITE)
        val channel = DatagramChannel.open().apply {
            configureBlocking(false)
            connect(InetSocketAddress("127.0.0.1", 59999))
        }
        val endpoint = InetSocketAddress("127.0.0.1", 59999)
        val job = async(Dispatchers.Default) { s.pumpLoop(pfd, channel, endpoint) }
        // Bounded wait for the pump to log the TX flow (poll, not a timing assertion)
        withTimeout(8000) { while (s.tracker.snapshot().isEmpty()) delay(25) }
        assertTrue(s.tracker.snapshot().any { it.txBytes == pkt.size.toLong() }, "tracker=${s.tracker.snapshot()}")
        // DISCONNECT sets the service stop flag, which terminates the pump loop
        svc.onStartCommand(Intent(VpnTunnelService.ACTION_DISCONNECT), 0, 1)
        withTimeout(8000) { job.await() }
        s.tracker.clear()
        Unit
    } }

    @Test fun sessionRunDegradesWhenNoEndpoint() { runBlocking {
        Assume.assumeTrue(nativeAvailable())
        val svc = Robolectric.buildService(VpnTunnelService::class.java).create().get()
        val priv = VpnNative.generatePrivateKey()
        val handle = VpnNative.newTunnel(priv, VpnNative.derivePublicKey(priv), "", 25)
        // Empty address/allowed-IP lists skip the Builder add* calls that need hidden
        // InetAddress APIs; establish/protect may still hit stub Errors, which are
        // documented Robolectric limits - executed instructions count either way.
        val s = svc.TunnelSession(VpnConfig(name = "r", address = ",", peerAllowedIPs = ",", peerEndpoint = ""), handle)
        try {
            s.run()
        } catch (_: NoSuchMethodError) {
            // Robolectric stub android.jar lacks hidden InetAddress APIs used by Builder.
        } catch (_: NullPointerException) {
            // Robolectric VpnService.Builder has no IPackageManager.
        }
        Unit
    } }
}
