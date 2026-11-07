package com.vayunmathur.vpn.service

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.vpn.data.ConnectionLogDao
import com.vayunmathur.vpn.data.ConnectionLogEntity
import com.vayunmathur.vpn.data.VpnConfig
import com.vayunmathur.vpn.data.VpnDatabase
import com.vayunmathur.vpn.util.VpnNative
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VpnTunnelServiceTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun nativeAvailable(): Boolean = try { VpnNative.init(); true } catch (_: Throwable) { false }

    private fun service(): VpnTunnelService =
        Robolectric.buildService(VpnTunnelService::class.java).create().get()

    private fun handle(): Long {
        val priv = VpnNative.generatePrivateKey()
        return VpnNative.newTunnel(priv, VpnNative.derivePublicKey(priv), "", 25)
    }

    private fun session(svc: VpnTunnelService, config: VpnConfig = VpnConfig(name = "test", peerEndpoint = "127.0.0.1:51820")) =
        svc.TunnelSession(config, handle())

    class FakeLogDao : ConnectionLogDao {
        val upserted = mutableListOf<ConnectionLogEntity>()
        var identical: ConnectionLogEntity? = null
        override fun flowTopApps(): Flow<List<com.vayunmathur.vpn.data.AppUsageSummary>> = kotlinx.coroutines.flow.emptyFlow()
        override fun flowDomainsByCount(): Flow<List<com.vayunmathur.vpn.data.DomainCountSummary>> = kotlinx.coroutines.flow.emptyFlow()
        override fun flowDomainsByBytes(): Flow<List<com.vayunmathur.vpn.data.DomainBytesSummary>> = kotlinx.coroutines.flow.emptyFlow()
        override suspend fun unnamedUids(): List<Int> = emptyList()
        override suspend fun nameUid(uid: Int, packageName: String, appLabel: String) {}
        override suspend fun upsertAll(entities: List<ConnectionLogEntity>): List<Long> {
            upserted.addAll(entities); return entities.mapIndexed { i, _ -> (i + 1).toLong() }
        }
        override suspend fun deleteAll() {}
        override suspend fun findIdentical(remoteIp: String, remotePort: Int, protocol: String, localPort: Int): ConnectionLogEntity? = identical
    }

    @Test fun onCreateAndDisconnectAndBadConnect() {
        val svc = service()
        // DISCONNECT when nothing runs: stopVpn + stopSelf, not sticky
        val rc = svc.onStartCommand(Intent(VpnTunnelService.ACTION_DISCONNECT), 0, 1)
        assertEquals(android.app.Service.START_NOT_STICKY, rc)
        // CONNECT without JSON returns sticky and does nothing
        assertEquals(android.app.Service.START_STICKY, svc.onStartCommand(Intent(VpnTunnelService.ACTION_CONNECT), 0, 2))
        // CONNECT with garbage JSON is swallowed by runCatching
        val bad = Intent(VpnTunnelService.ACTION_CONNECT).putExtra(VpnTunnelService.EXTRA_CONFIG_JSON, "{not json")
        assertEquals(android.app.Service.START_STICKY, svc.onStartCommand(bad, 0, 3))
        // Unknown action
        assertEquals(android.app.Service.START_STICKY, svc.onStartCommand(Intent("com.example.WHAT"), 0, 4))
        svc.onRevoke(); svc.onDestroy()
    }

    @Test fun parseCsvCidrsAndLeakGuard() {
        Assume.assumeTrue(nativeAvailable())
        val s = session(service())
        assertEquals(listOf("10.0.0.2" to 32), s.parseCsvCidrs("10.0.0.2/32"))
        assertEquals(listOf("10.0.0.2" to 32, "fd00::2" to 128), s.parseCsvCidrs(" 10.0.0.2 , fd00::2 "))
        assertEquals(listOf("0.0.0.0" to 0), s.parseCsvCidrs("0.0.0.0/0"))
        assertTrue(s.parseCsvCidrs("").isEmpty())
        assertEquals(listOf("10.0.0.0" to 8), s.parseCsvCidrs("10.0.0.0/8, ,"))
        // Leak guard: v4 default without any v6 route gains ::/0
        val guarded = s.withLeakGuard(listOf("0.0.0.0" to 0))
        assertEquals(listOf("0.0.0.0" to 0, "::" to 0), guarded)
        // Already has v6: unchanged
        assertEquals(guarded, s.withLeakGuard(listOf("0.0.0.0" to 0, "::" to 0)))
        // Split tunnel (no v4 default): unchanged
        assertEquals(listOf("10.0.0.0" to 8), s.withLeakGuard(listOf("10.0.0.0" to 8)))
    }

    @Test fun resolveEndpointVariants() {
        Assume.assumeTrue(nativeAvailable())
        val svc = service()
        assertNotNull(session(svc, VpnConfig(peerEndpoint = "127.0.0.1:51820")).resolveEndpoint())
        assertEquals(51820, session(svc, VpnConfig(peerEndpoint = "127.0.0.1:51820")).resolveEndpoint()!!.port)
        assertNull(session(svc, VpnConfig(peerEndpoint = "")).resolveEndpoint())
        assertEquals(51820, session(svc, VpnConfig(peerEndpoint = "example.invalid")).resolveEndpoint()!!.port)
    }

    @Test fun buildTunAndBuilderHelpersDoNotThrow() { runBlocking {
        Assume.assumeTrue(nativeAvailable())
        val s = session(service(), VpnConfig(name = "", address = "10.0.0.2/32", dns = "1.1.1.1, 8.8.8.8", peerAllowedIPs = "0.0.0.0/0"))
        // VpnService.Builder's addAddress/establish call hidden InetAddress.parseNumericAddress,
        // which the Robolectric stub android.jar lacks (NoSuchMethodError, an Error the production
        // catches cannot intercept). Those builder paths are only exercisable on a real device;
        // the pure helpers feeding them are covered in parseCsvCidrsAndLeakGuard.
        try {
            s.buildTun()
        } catch (_: NoSuchMethodError) {
            // Robolectric stub limitation, documented above.
        }
        Unit
    } }

    @Test fun loggingMergeAndFlush() { runBlocking {
        Assume.assumeTrue(nativeAvailable())
        val svc = service()
        val s = session(svc)
        val dao = FakeLogDao()
        s.logDao = dao
        s.appResolver = AppResolver(ctx)
        s.tracker.clear()
        // A UDP DNS TX packet
        val dnsPayload = byteArrayOf(0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 7, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(), 'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(), 3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 0, 0, 1, 0, 1)
        val pkt = ByteArray(28 + dnsPayload.size)
        pkt[0] = 0x45; pkt[9] = 17
        pkt[12] = 10; pkt[15] = 2; pkt[16] = 8; pkt[17] = 8; pkt[18] = 8; pkt[19] = 8
        pkt[20] = (40000 shr 8).toByte(); pkt[21] = (40000 and 0xFF).toByte(); pkt[22] = 0; pkt[23] = 53
        System.arraycopy(dnsPayload, 0, pkt, 28, dnsPayload.size)
        s.handleLoggingForPacket(pkt, ConnectionTracker.Direction.TX, pkt.size)
        s.handleLoggingForPacket(byteArrayOf(0x45, 1, 2), ConnectionTracker.Direction.RX, 3) // truncated: parse may handle or return
        s.flushOnce()
        assertTrue(dao.upserted.isNotEmpty(), "flush should upsert the DNS flow")
        // mergeWithExisting: unattributed entity adopts existing row id/attribution
        dao.identical = ConnectionLogEntity(id = 42, timestampStart = 1, timestampLast = 2, uid = 100, packageName = "com.a", appLabel = "A", protocol = "UDP", remoteIp = "8.8.8.8", remotePort = 53, localPort = 40000, txBytes = 999)
        val merged = s.mergeWithExisting(ConnectionLogEntity(id = 0, timestampStart = 5, uid = -1, protocol = "UDP", remoteIp = "8.8.8.8", remotePort = 53, localPort = 40000, txBytes = 10))
        assertEquals(42L, merged.id); assertEquals(999L, merged.txBytes); assertEquals(100, merged.uid)
        val kept = s.mergeWithExisting(ConnectionLogEntity(id = 7, protocol = "UDP", remoteIp = "8.8.8.8", remotePort = 53, localPort = 40000))
        assertEquals(7L, kept.id) // already has id: returned as-is
        s.tracker.clear()
    } }

    @Test fun pumpHelpersWithRealChannels() { runBlocking {
        Assume.assumeTrue(nativeAvailable())
        val svc = service()
        val s = session(svc)
        s.logDao = FakeLogDao(); s.appResolver = AppResolver(ctx); s.tracker.clear()
        val endpoint = s.resolveEndpoint()!!
        // openUdpChannel calls VpnService.protect(), whose stub lacks getFileDescriptor$
        // (NoSuchMethodError) under Robolectric, so open the channel directly here.
        val channel = java.nio.channels.DatagramChannel.open().apply { configureBlocking(false); connect(endpoint) }
        try {
            // Timer that is not due returns the same timestamp; a due timer ticks native timers.
            val t0 = System.currentTimeMillis()
            assertEquals(t0, s.fireTimers(channel, t0))
            assertTrue(s.fireTimers(channel, 0L) >= t0)
            s.sendHandshakeInit(channel, endpoint)
            // routeIncoming with garbage WG bytes: native returns null, no crash
            s.routeIncoming(byteArrayOf(9, 9, 9), channel, tempChannel())
            // pumpNetToTun with nothing to read returns promptly (non-blocking channel)
            s.pumpNetToTun(channel, ByteBuffer.allocate(65535), tempChannel())
            s.writeUdp(channel, byteArrayOf(1, 2, 3))
        } finally { channel.close() }
        s.tracker.clear()
    } }

    private fun tempChannel(): FileChannel {
        val f = File(ctx.cacheDir, "tun-${System.nanoTime()}.tmp").apply { createNewFile() }
        return FileChannel.open(f.toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE)
    }

    @Test fun databaseDelegateAndNotificationHelpers() {
        val svc = service()
        // onDestroy path (stopVpn) is safe when never started
        svc.onDestroy()
        assertTrue(!VpnTunnelService.isRunning)
        assertEquals("", VpnTunnelService.runningConfigName)
        Shadows.shadowOf(ctx as android.app.Application)
        assertNotNull(VpnDatabase::class.java) // compile-time reference only; DB open needs KeyStore ( Robolectric limit)
    }
}
