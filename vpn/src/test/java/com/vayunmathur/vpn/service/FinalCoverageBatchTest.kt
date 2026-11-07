package com.vayunmathur.vpn.service

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.vpn.data.MIGRATION_1_2
import com.vayunmathur.vpn.data.VpnConfig
import com.vayunmathur.vpn.data.VpnDatabase
import com.vayunmathur.vpn.util.VpnNative
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertEquals
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
class FinalCoverageBatchTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun nativeAvailable(): Boolean = try { VpnNative.init(); true } catch (_: Throwable) { false }

    @Test fun migration12ExecutesAgainstRealConnection() { runBlocking {
        // Run the migration against a fresh, empty SQLite file so its CREATE TABLE
        // and CREATE INDEX statements actually execute (not just no-op).
        val driver = androidx.sqlite.driver.AndroidSQLiteDriver()
        val file = File(ctx.cacheDir, "migration-test.db").apply { if (exists()) delete() }
        val conn = driver.open(file.absolutePath)
        try {
            MIGRATION_1_2.migrate(conn)
            MIGRATION_1_2.migrate(conn) // idempotent second run (IF NOT EXISTS)
            // The table exists and accepts a row
            conn.prepare("INSERT INTO ConnectionLogEntity (timestampStart, timestampLast, uid, appLabel, protocol, localIp, localPort, remoteIp, remotePort, txBytes, rxBytes, requestCount) VALUES (0,0,0,'a','UDP','',0,'',0,0,0,1)").use { it.step() }
            conn.prepare("SELECT COUNT(*) FROM ConnectionLogEntity").use { st ->
                assertTrue(st.step()); assertEquals(1L, st.getLong(0))
            }
        } finally { conn.close() }
        Unit
    } }

    @Test fun dnsLabelOverrunRejected() {
        val c = DnsCache()
        fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), (v and 0xFF).toByte())
        // QNAME claims a 10-byte label but only 3 bytes follow
        val q = u16(1) + u16(0x0100) + u16(1) + u16(0) + u16(0) + u16(0) + byteArrayOf(10, 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte())
        val p = PacketInspector.ParsedPacket(17, "UDP", "10.0.0.2", "8.8.8.8", 40000, 53, 28, q.size, 28 + q.size)
        val pkt = ByteArray(28 + q.size); System.arraycopy(q, 0, pkt, 28, q.size)
        // Must return promptly with no domain recorded for the truncated name
        c.onPacket(p, pkt)
        assertTrue(c.allEntries().isEmpty())
    }

    @Test fun pumpTunAfterEstablishedHandshake() { runBlocking {
        Assume.assumeTrue(nativeAvailable())
        val svc = Robolectric.buildService(VpnTunnelService::class.java).create().get()
        val privA = VpnNative.generatePrivateKey(); val pubA = VpnNative.derivePublicKey(privA)
        val privB = VpnNative.generatePrivateKey(); val pubB = VpnNative.derivePublicKey(privB)
        val a = VpnNative.newTunnel(privA, pubB, "", 0)
        val b = VpnNative.newTunnel(privB, pubA, "", 0)
        assertTrue(a > 0 && b > 0)
        try {
            // Drive a full handshake: B init -> A response -> B completes -> B data -> A
            val init = VpnNative.formatHandshakeInit(b)!!
            val respTagged = VpnNative.consumeIncomingPacketDetailed(a, init)!!
            val resp = respTagged.copyOfRange(1, respTagged.size)
            VpnNative.consumeIncomingPacketDetailed(b, resp)
            fun ip(payload: ByteArray): ByteArray {
                val pkt = ByteArray(28 + payload.size)
                pkt[0] = 0x45; pkt[9] = 17
                val t = pkt.size; pkt[2] = (t shr 8).toByte(); pkt[3] = (t and 0xFF).toByte()
                pkt[12] = 10; pkt[15] = 2; pkt[16] = 8; pkt[17] = 8; pkt[18] = 8; pkt[19] = 8
                pkt[20] = (40000 shr 8).toByte(); pkt[21] = (40000 and 0xFF).toByte(); pkt[22] = 0; pkt[23] = 53
                System.arraycopy(payload, 0, pkt, 28, payload.size)
                return pkt
            }
            val encData = VpnNative.encapsulate(b, ip(byteArrayOf(1)))!!
            VpnNative.consumeIncomingPacketDetailed(a, encData)
            // A is established: pumpTunToNet's encapsulate path returns a real WG packet
            val sessionA = svc.TunnelSession(VpnConfig(name = "A", peerEndpoint = "127.0.0.1:51820"), a)
            sessionA.logDao = VpnTunnelServiceTest.FakeLogDao()
            sessionA.appResolver = AppResolver(ctx)
            sessionA.tracker.clear()
            val tunFile = File(ctx.cacheDir, "tun-established.bin")
            FileOutputStream(tunFile).use { it.write(ip(byteArrayOf(7, 7, 7))) }
            val pfd = android.os.ParcelFileDescriptor.open(tunFile, android.os.ParcelFileDescriptor.MODE_READ_WRITE)
            val tunIn = java.io.FileInputStream(pfd.fileDescriptor).channel
            val udp = DatagramChannel.open().apply { configureBlocking(false); connect(InetSocketAddress("127.0.0.1", 59998)) }
            sessionA.pumpTunToNet(tunIn, ByteBuffer.allocate(65535), udp)
            assertTrue(sessionA.tracker.snapshot().isNotEmpty())
            tunIn.close(); udp.close()
            sessionA.tracker.clear()
        } finally {
            VpnNative.freeTunnel(a); VpnNative.freeTunnel(b)
        }
        Unit
    } }

    @Test fun handleLoggingMalformedAndMergeCombos() { runBlocking {
        Assume.assumeTrue(nativeAvailable())
        val svc = Robolectric.buildService(VpnTunnelService::class.java).create().get()
        val priv = VpnNative.generatePrivateKey()
        val handle = VpnNative.newTunnel(priv, VpnNative.derivePublicKey(priv), "", 25)
        val s = svc.TunnelSession(VpnConfig(name = "x", peerEndpoint = "127.0.0.1:51820"), handle)
        s.appResolver = AppResolver(ctx)
        s.tracker.clear()
        // Malformed (version 0) and truncated packets are dropped, not thrown
        s.handleLoggingForPacket(byteArrayOf(0x00, 1, 2, 3), ConnectionTracker.Direction.TX, 4)
        s.handleLoggingForPacket(byteArrayOf(0x45), ConnectionTracker.Direction.RX, 1)
        // mergeWithExisting: attributed entity keeps its own attribution over the stored row
        val dao = VpnTunnelServiceTest.FakeLogDao()
        dao.identical = com.vayunmathur.vpn.data.ConnectionLogEntity(id = 9, timestampStart = 100, uid = 5, packageName = "old.pkg", appLabel = "Old", domain = "old.com", txBytes = 1, rxBytes = 2)
        s.logDao = dao
        val merged = s.mergeWithExisting(com.vayunmathur.vpn.data.ConnectionLogEntity(id = 0, timestampStart = 50, uid = 7, packageName = "new.pkg", appLabel = "New", domain = null, txBytes = 10, rxBytes = 0))
        assertEquals(9L, merged.id); assertEquals(50L, merged.timestampStart)
        assertEquals(7, merged.uid); assertEquals("new.pkg", merged.packageName) // attributed wins
        assertEquals("old.com", merged.domain) // domain falls back to stored
        assertEquals(10L, merged.txBytes); assertEquals(2L, merged.rxBytes) // max of each
        s.tracker.clear()
        Unit
    } }

    @Test fun connectWithValidKeysExercisesRunTunnel() { runBlocking {
        Assume.assumeTrue(nativeAvailable())
        val svc = Robolectric.buildService(VpnTunnelService::class.java).create().get()
        val priv = VpnNative.generatePrivateKey(); val pub = VpnNative.derivePublicKey(priv)
        // Empty address lists keep Builder.add* (hidden InetAddress APIs) out of buildTun;
        // whatever establish/protect then do under Robolectric, runTunnel's native
        // lifecycle (newTunnel -> session -> freeTunnel) is exercised.
        val cfg = VpnConfig(name = "ValidKeys", privateKey = priv, peerPublicKey = pub, peerEndpoint = "127.0.0.1:51820", address = ",", peerAllowedIPs = ",")
        val json = kotlinx.serialization.json.Json.encodeToString(cfg)
        svc.onStartCommand(Intent(VpnTunnelService.ACTION_CONNECT).putExtra(VpnTunnelService.EXTRA_CONFIG_JSON, json), 0, 1)
        // Let the IO tunnel coroutine run its course (bounded poll on the running flag)
        withTimeout(9000) { while (VpnTunnelService.isRunning) delay(50) }
        assertTrue(!VpnTunnelService.isRunning)
        Unit
    } }
}
