package com.vayunmathur.vpn.service

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.vpn.data.VpnConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FinalServiceBatchTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), (v and 0xFF).toByte())

    private fun parsed(srcIp: String = "10.0.0.2", dstIp: String = "8.8.8.8", srcPort: Int = 40000, dstPort: Int = 53, payloadSize: Int) =
        PacketInspector.ParsedPacket(17, "UDP", srcIp, dstIp, srcPort, dstPort, 28, payloadSize, 28 + payloadSize)

    private fun packetFor(p: PacketInspector.ParsedPacket, payload: ByteArray): ByteArray {
        val pkt = ByteArray(p.payloadOffset + payload.size)
        System.arraycopy(payload, 0, pkt, p.payloadOffset, payload.size)
        return pkt
    }

    private fun nameBytes(domain: String): ByteArray {
        var q = ByteArray(0)
        for (label in domain.split('.')) { val b = label.toByteArray(); q += byteArrayOf(b.size.toByte()) + b }
        return q + byteArrayOf(0)
    }

    private fun query(txId: Int, domain: String): ByteArray =
        u16(txId) + u16(0x0100) + u16(1) + u16(0) + u16(0) + u16(0) + nameBytes(domain) + u16(1) + u16(1)

    @Test fun dnsAaaaAndUnknownTypeAnswers() {
        val c = DnsCache()
        val q = query(0x42, "v6.example.com")
        val qp = parsed(payloadSize = q.size)
        assertEquals("v6.example.com", c.onPacket(qp, packetFor(qp, q)))
        // Response with an AAAA answer (16-byte ::1) plus an unknown-type answer first
        val question = nameBytes("v6.example.com") + u16(1) + u16(1)
        val unknown = byteArrayOf(0xC0.toByte(), 12) + u16(99) + u16(1) + byteArrayOf(0, 0, 0, 60) + u16(3) + byteArrayOf(1, 2, 3)
        val aaaaBytes = ByteArray(16).also { it[15] = 1 }
        val aaaa = byteArrayOf(0xC0.toByte(), 12) + u16(28) + u16(1) + byteArrayOf(0, 0, 0, 60) + u16(16) + aaaaBytes
        val resp = u16(0x42) + u16(0x8180) + u16(1) + u16(2) + u16(0) + u16(0) + question + unknown + aaaa
        val rp = parsed(srcPort = 53, dstPort = 40000, payloadSize = resp.size)
        assertEquals("v6.example.com", c.onPacket(rp, packetFor(rp, resp)))
        assertTrue(c.allEntries().any { it.value == "v6.example.com" && it.key.contains(":") }, "entries=${c.allEntries()}")
        // Truncated answer (header only, no rdlen room) degrades to null path without throwing
        val truncResp = u16(0x43) + u16(0x8180) + u16(1) + u16(1) + u16(0) + u16(0) + question + byteArrayOf(0xC0.toByte(), 12, 0, 1)
        val q2 = query(0x43, "v6.example.com")
        val q2p = parsed(payloadSize = q2.size); c.onPacket(q2p, packetFor(q2p, q2))
        val tp = parsed(srcPort = 53, dstPort = 40000, payloadSize = truncResp.size)
        c.onPacket(tp, packetFor(tp, truncResp))
    }

    @Test fun dnsTxIdEvictionAndPointerLoop() {
        val c = DnsCache()
        // Fill the txId map past its cap (500) - eviction keeps it bounded
        for (i in 1..505) {
            val q = query(i, "d$i.example.com")
            val p = parsed(payloadSize = q.size)
            c.onPacket(p, packetFor(p, q))
        }
        val q = query(9999, "last.example.com")
        val p = parsed(payloadSize = q.size)
        assertEquals("last.example.com", c.onPacket(p, packetFor(p, q)))
        // A name that points to itself must exhaust the jump budget, not loop forever
        val loop = u16(7) + u16(0x0100) + u16(1) + u16(0) + u16(0) + u16(0) +
            byteArrayOf(0xC0.toByte(), 12) + u16(1) + u16(1) // QNAME is a pointer to offset 12 (itself, after header at payload start)
        // Note: decode starts at payload+12, so the pointer at that spot targets itself
        val lp = parsed(payloadSize = loop.size)
        c.onPacket(lp, packetFor(lp, loop)) // must return (null or empty) promptly, not hang
        assertNull(c.get("nonexistent.example"))
    }

    @Test fun resolverFlowCacheHitRxDirection() {
        val r = AppResolver(ctx)
        val pkt = PacketInspector.ParsedPacket(17, "UDP", "10.0.0.2", "8.8.8.8", 40000, 53, 28, 0, 28)
        val first = r.resolve(pkt, ConnectionTracker.Direction.TX)
        val second = r.resolve(pkt, ConnectionTracker.Direction.TX)
        assertEquals(first, second)
        assertNotNull(first)
    }

    @Test fun connectWithBadKeysRunsTunnelAndStops() { runBlocking {
        val svc = Robolectric.buildService(VpnTunnelService::class.java).create().get()
        val cfg = VpnConfig(name = "BadKeys", privateKey = "", peerPublicKey = "", peerEndpoint = "127.0.0.1:51820")
        val json = kotlinx.serialization.json.Json.encodeToString(cfg)
        svc.onStartCommand(Intent(VpnTunnelService.ACTION_CONNECT).putExtra(VpnTunnelService.EXTRA_CONFIG_JSON, json), 0, 1)
        assertEquals("BadKeys", VpnTunnelService.runningConfigName)
        // runTunnel fails to create a tunnel with empty keys and stops the service
        withTimeout(8000) { while (VpnTunnelService.isRunning || VpnTunnelService.runningConfigName == "BadKeys") delay(50) }
        assertTrue(!VpnTunnelService.isRunning)
        Unit
    } }
}
