package com.vayunmathur.vpn.service

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.vpn.data.VpnConfig
import com.vayunmathur.vpn.platform.BypassList
import com.vayunmathur.vpn.util.VpnNative
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
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
class TunnelSessionMoreTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun nativeAvailable(): Boolean = try { VpnNative.init(); true } catch (_: Throwable) { false }

    private fun service(): VpnTunnelService = Robolectric.buildService(VpnTunnelService::class.java).create().get()

    private fun session(svc: VpnTunnelService, config: VpnConfig): VpnTunnelService.TunnelSession {
        val priv = VpnNative.generatePrivateKey()
        val handle = VpnNative.newTunnel(priv, VpnNative.derivePublicKey(priv), "", 25)
        return svc.TunnelSession(config, handle)
    }

    private fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), (v and 0xFF).toByte())

    private fun clientHello(host: String): ByteArray {
        val h = host.toByteArray()
        val sniBody = u16(3 + h.size) + byteArrayOf(0) + u16(h.size) + h
        val sniExt = u16(0) + u16(sniBody.size) + sniBody
        val body = u16(0x0303) + ByteArray(32) + byteArrayOf(0) + u16(2) + byteArrayOf(0x13, 0x01) + byteArrayOf(1, 0) + u16(sniExt.size) + sniExt
        val handshake = byteArrayOf(1, (body.size shr 16).toByte(), (body.size shr 8).toByte(), body.size.toByte()) + body
        return byteArrayOf(0x16) + u16(0x0301) + u16(handshake.size) + handshake
    }

    private fun tcpPacket(payload: ByteArray, srcPort: Int, dstPort: Int): ByteArray {
        val pkt = ByteArray(40 + payload.size)
        pkt[0] = 0x45; pkt[9] = 6
        val total = 40 + payload.size
        pkt[2] = (total shr 8).toByte(); pkt[3] = (total and 0xFF).toByte()
        pkt[12] = 10; pkt[15] = 2; pkt[16] = 1; pkt[17] = 2; pkt[18] = 3; pkt[19] = 4
        System.arraycopy(u16(srcPort), 0, pkt, 20, 2); System.arraycopy(u16(dstPort), 0, pkt, 22, 2)
        pkt[20 + 12] = (5 shl 4).toByte()
        System.arraycopy(payload, 0, pkt, 40, payload.size)
        return pkt
    }

    @Test fun resolveDomainFromSniAndCache() {
        Assume.assumeTrue(nativeAvailable())
        val s = session(service(), VpnConfig(peerEndpoint = "127.0.0.1:51820"))
        val hello = clientHello("secure.example.com")
        val pkt = tcpPacket(hello, 40000, 443)
        val parsed = PacketInspector.parse(pkt)
        assertNotNull(parsed)
        assertEquals("secure.example.com", s.resolveDomain(parsed, pkt, ConnectionTracker.Direction.TX))
        // Now cached by dst IP
        assertEquals("secure.example.com", s.dnsCache.get("1.2.3.4"))
        assertEquals("secure.example.com", s.resolveDomain(parsed, pkt, ConnectionTracker.Direction.TX))
        // Non-TLS port gives nothing (fresh dst IP so the cache cannot answer first)
        val pkt2 = tcpPacket(hello, 40000, 80).also { it[16] = 5; it[17] = 6; it[18] = 7; it[19] = 8 }
        assertNull(s.resolveDomain(PacketInspector.parse(pkt2)!!, pkt2, ConnectionTracker.Direction.TX))
        // RX direction: SNI is in the TX-shaped packet; use srcPort 443 parsed packet
        val rxPkt = tcpPacket(hello, 443, 40000)
        val rxParsed = PacketInspector.parse(rxPkt)!!
        // Fresh session (empty cache) resolves via srcPort 443 on RX
        val s2 = session(service(), VpnConfig(peerEndpoint = "127.0.0.1:51820"))
        assertEquals("secure.example.com", s2.resolveDomain(rxParsed, rxPkt, ConnectionTracker.Direction.RX))
        // UDP packet never goes to SNI
        val udpParsed = PacketInspector.ParsedPacket(17, "UDP", "10.0.0.2", "1.2.3.4", 1, 443, 28, hello.size, 28 + hello.size)
        assertNull(s2.resolveDomain(udpParsed, pkt, ConnectionTracker.Direction.TX))
    }

    // applyBypassList is not exercised here: VpnService.Builder.addDisallowedApplication
    // dereferences a null IPackageManager under the Robolectric stub (an NPE the production
    // per-package catches do not intercept). BypassList itself is covered in VpnViewModelTest.

    @Test fun flushJobAndStopSession() { runBlocking {
        Assume.assumeTrue(nativeAvailable())
        val svc = service()
        val s = session(svc, VpnConfig(peerEndpoint = "127.0.0.1:51820"))
        val dao = VpnTunnelServiceTest.FakeLogDao()
        s.logDao = dao
        s.appResolver = AppResolver(ctx)
        s.tracker.clear()
        s.startFlushJob()
        assertNotNull(s.sessionFlushJob)
        // Stop session: drains (empty), cancels job, closes channel/pfd, stops service
        val pfd = android.os.ParcelFileDescriptor.open(java.io.File(ctx.cacheDir, "pfd.tmp").apply { createNewFile() }, android.os.ParcelFileDescriptor.MODE_READ_WRITE)
        val channel = java.nio.channels.DatagramChannel.open()
        s.stopSession(pfd, channel)
        assertTrue(!channel.isOpen)
        assertNull(s.sessionFlushJob)
        s.tracker.clear()
        Unit
    } }

    @Test fun connectValidJsonStartsTunnelAttempt() {
        val svc = service()
        val cfg = VpnConfig(name = "ConnTun", privateKey = "", peerPublicKey = "", peerEndpoint = "127.0.0.1:51820")
        val json = kotlinx.serialization.json.Json.encodeToString(cfg)
        val intent = Intent(VpnTunnelService.ACTION_CONNECT).putExtra(VpnTunnelService.EXTRA_CONFIG_JSON, json)
        assertEquals(android.app.Service.START_STICKY, svc.onStartCommand(intent, 0, 1))
        // startVpn ran synchronously up to launching the tunnel coroutine
        assertEquals("ConnTun", VpnTunnelService.runningConfigName)
        svc.onDestroy()
    }

    @Test fun startFlushJobLoopsAndFlushes() { runBlocking {
        Assume.assumeTrue(nativeAvailable())
        val svc = service()
        val s = session(svc, VpnConfig(peerEndpoint = "127.0.0.1:51820"))
        val dao = VpnTunnelServiceTest.FakeLogDao()
        s.logDao = dao
        s.tracker.clear()
        // Seed a dirty flow, then let flushOnce (invoked by the job loop after 1.5s) drain it.
        // We call flushOnce directly here too so the assertion does not depend on wall time.
        val parsed = PacketInspector.ParsedPacket(17, "UDP", "10.0.0.2", "8.8.8.8", 40000, 53, 28, 0, 28)
        s.tracker.onPacket(parsed, ConnectionTracker.Direction.TX, 10, uid = 1, packageName = "a.b", appLabel = "AB")
        s.startFlushJob()
        s.flushOnce()
        assertTrue(dao.upserted.isNotEmpty())
        s.sessionFlushJob?.cancel()
        s.tracker.clear()
        Unit
    } }
}
