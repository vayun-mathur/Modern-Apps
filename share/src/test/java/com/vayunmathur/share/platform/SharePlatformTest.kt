package com.vayunmathur.share.platform

import android.net.Uri
import com.vayunmathur.share.domain.protocol.PendingFile
import com.vayunmathur.share.domain.protocol.ShareState
import com.vayunmathur.share.network.transport.isTerminal
import com.vayunmathur.share.platform.discovery.DiscoverySource
import com.vayunmathur.share.platform.discovery.NearbyDevice
import com.vayunmathur.share.protocol.EndpointInfoFields
import com.vayunmathur.share.protocol.ShareNative
import com.vayunmathur.share.protocol.WifiLanServiceInfoFields
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SharePlatformTest {

    @Test
    fun `ShareState fromCode maps known codes and falls back to Unknown`() {
        assertEquals(ShareState.Handshaking, ShareState.fromCode(0))
        assertEquals(ShareState.AwaitingAccept, ShareState.fromCode(1))
        assertEquals(ShareState.Transferring, ShareState.fromCode(2))
        assertEquals(ShareState.Completed, ShareState.fromCode(3))
        assertEquals(ShareState.Failed, ShareState.fromCode(4))
        assertEquals(ShareState.Unknown, ShareState.fromCode(-1))
        assertEquals(ShareState.Unknown, ShareState.fromCode(99))
        assertEquals(ShareState.Unknown, ShareState.fromCode(Int.MIN_VALUE))
    }

    @Test
    fun `isTerminal only for Completed and Failed`() {
        assertTrue(ShareState.Completed.isTerminal)
        assertTrue(ShareState.Failed.isTerminal)
        assertFalse(ShareState.Handshaking.isTerminal)
        assertFalse(ShareState.AwaitingAccept.isTerminal)
        assertFalse(ShareState.Transferring.isTerminal)
        assertFalse(ShareState.Unknown.isTerminal)
    }

    @Test
    fun `PendingFile serializes round trip`() {
        val files = listOf(PendingFile("a.txt", 123L, "text/plain"), PendingFile("b.bin", 0L, "application/octet-stream"))
        val json = Json.encodeToString(files)
        assertEquals(files, Json.decodeFromString<List<PendingFile>>(json))
    }

    @Test
    fun `NearbyDevice defaults and copy`() {
        val d = NearbyDevice(endpointId = "ABCD", endpointName = "Pixel")
        assertNull(d.host); assertNull(d.port); assertEquals(DiscoverySource.Nsd, d.source)
        val c = d.copy(host = "10.0.0.2", port = 5000, source = DiscoverySource.Both)
        assertEquals("10.0.0.2", c.host); assertEquals(5000, c.port)
        assertEquals(DiscoverySource.Both, c.source)
        // serializable round trip
        val json = Json.encodeToString(NearbyDevice.serializer(), c)
        assertEquals(c, Json.decodeFromString(NearbyDevice.serializer(), json))
    }

    @Test
    fun `SendUiState defaults are empty and not scanning`() {
        val s = SendUiState()
        assertTrue(s.discoveredDevices.isEmpty())
        assertFalse(s.isScanning)
        assertTrue(s.outgoingUris.isEmpty())
        assertNull(s.activeConnection)
    }

    @Test
    fun `ShareActions Noop tolerates all calls`() {
        val noop = ShareActions.Noop
        noop.startScan(); noop.stopScan(); noop.clearOutgoing()
        noop.setOutgoingUris(listOf(Uri.parse("content://x/1")))
        noop.connectToDevice(NearbyDevice("ABCD", "Pixel"))
        // disconnect needs a Connection; Noop with null-cast would crash, so only test the above.
    }

    @Test
    fun `SharePermissions flow arrays contain expected permissions without duplicates`() {
        val send = SharePermissions.sendFlowPermissions().toList()
        val recv = SharePermissions.receiveFlowPermissions().toList()
        val all = SharePermissions.allSharePermissions().toList()
        assertTrue(send.contains(SharePermissions.BLE_SCAN))
        assertFalse(send.contains(SharePermissions.BLE_ADVERTISE))
        assertTrue(recv.contains(SharePermissions.BLE_ADVERTISE))
        assertFalse(recv.contains(SharePermissions.BLE_SCAN))
        assertTrue(all.containsAll(send)); assertTrue(all.containsAll(recv))
        assertEquals(all.size, all.distinct().size)
        assertEquals(send.size, send.distinct().size)
    }

    @Test
    fun `EndpointInfoFields defaults`() {
        val f = EndpointInfoFields()
        assertNull(f.deviceName); assertEquals(0, f.deviceType)
        val f2 = EndpointInfoFields(deviceName = "Pixel", deviceType = 1, version = 2, vendorId = 3)
        assertEquals("Pixel", f2.deviceName)
        assertEquals(ShareNative.DEVICE_TYPE_PHONE, 1)
        val w = WifiLanServiceInfoFields(endpointId = "ABCD", pcp = 1)
        assertEquals("ABCD", w.endpointId)
    }
}
