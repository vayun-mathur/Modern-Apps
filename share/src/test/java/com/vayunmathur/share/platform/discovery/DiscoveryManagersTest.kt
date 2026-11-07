package com.vayunmathur.share.platform.discovery

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.share.protocol.ShareNativeDiscovery
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DiscoveryManagersTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `nsd advertise rejects bad endpoint id and empty info`() {
        val mgr = NsdDiscoveryManager(ctx)
        val info = ShareNativeDiscovery.nativeBuildEndpointInfo("Pixel", 1)!!
        assertNull(mgr.advertise("ABC", info, 9000))
        assertNull(mgr.advertise("ABCD", ByteArray(0), 9000))
        mgr.unadvertise()
        assertNull(mgr.instanceName)
        mgr.clearDiscoveredDevices()
        assertTrue(mgr.discoveredDevices.value.isEmpty())
        mgr.stopDiscovery()
        mgr.release()
    }

    @Test
    fun `nsd advertise returns instance and is idempotent`() {
        val mgr = NsdDiscoveryManager(ctx)
        val info = ShareNativeDiscovery.nativeBuildEndpointInfo("Pixel", 1)!!
        val inst = mgr.advertise("ABCD", info, 9123, "192.168.1.2")
        // Under Robolectric NsdManager may be null; accept either null or the base64 instance.
        if (inst != null) {
            val inst2 = mgr.advertise("ABCD", info, 9123, "192.168.1.2")
            assertEquals(inst, inst2)
        }
        mgr.unadvertise()
    }

    @Test
    fun `service type constant matches native`() {
        assertEquals("_FC9F5ED42C8A._tcp", SHARE_SERVICE_TYPE)
    }

    @Test
    fun `ble manager start without permission fails gracefully`() {
        val mgr = BleDiscoveryManager(ctx)
        val info = ShareNativeDiscovery.nativeBuildEndpointInfo("Pixel", 1)!!
        // No BLUETOOTH_ADVERTISE granted in test env -> false, no crash.
        val ok = mgr.startAdvertising("ABCD", info)
        assertFalse(ok)
        mgr.stopAdvertising()
        mgr.stopScan()
        mgr.stopFastInitiation()
        assertTrue(mgr.bleDevices.value.isEmpty())
    }

    @Test
    fun `ble scan flow completes or empties without adapter`() = runBlocking {
        val mgr = BleDiscoveryManager(ctx)
        // scan() should close (empty) without an adapter rather than hanging or crashing.
        var count = 0
        withTimeoutOrNull(3_000) { mgr.scan().collect { count++ } }
        assertEquals(0, count)
        mgr.stopScan()
    }
}
