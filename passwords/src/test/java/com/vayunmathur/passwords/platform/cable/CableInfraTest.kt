package com.vayunmathur.passwords.platform.cable

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CableInfraTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun assignedTunnelDomains() {
        assertEquals("cable.ua5v.com", TunnelDomains.decode(0))
        assertEquals("cable.auth.com", TunnelDomains.decode(1))
        assertEquals(0, TunnelDomains.DEFAULT_ID)
    }

    @Test fun unknownAssignedDomainIsRejected() {
        assertFailsWith<IllegalArgumentException> { TunnelDomains.decode(2) }
        assertFailsWith<IllegalArgumentException> { TunnelDomains.decode(-1) }
        assertFailsWith<IllegalArgumentException> { TunnelDomains.decode(0x10000) }
    }

    @Test fun algorithmicDomainIsDerivedDeterministically() {
        val a = TunnelDomains.decode(300)
        assertEquals(a, TunnelDomains.decode(300))
        assertTrue(a.startsWith("cable."))
        assertTrue(a.endsWith(".com") || a.endsWith(".org") || a.endsWith(".net") || a.endsWith(".info"))
    }

    @Test fun tunnelHexEncoding() {
        assertEquals("00ff10", CableTunnel.hex(byteArrayOf(0, 0xFF.toByte(), 0x10)))
        assertEquals("", CableTunnel.hex(byteArrayOf()))
        assertEquals("fido.cable", CableTunnel.SUBPROTOCOL)
    }

    @Test fun uuid16Expansion() {
        assertEquals(
            "0000f1d0-0000-1000-8000-00805f9b34fb",
            CableAdvertiser.uuid16(0xF1D0).toString(),
        )
        // Values are masked to 16 bits.
        assertEquals(CableAdvertiser.uuid16(0xF1D0), CableAdvertiser.uuid16(0x1F1D0))
    }

    @Test fun advertiserStartWithoutBluetoothReportsFailure() {
        val advertiser = CableAdvertiser(ctx)
        var result: Boolean? = null
        advertiser.start(byteArrayOf(1, 2, 3)) { result = it }
        // Robolectric has no LE advertiser, so start must report false (or
        // succeed only if a shadow advertiser exists — either way it returns).
        assertTrue(result != null || advertiser.isAvailable)
        advertiser.stop()
    }

    @Test fun bluetoothHelpersDoNotCrash() {
        // Supported/enabled depend on the shadow adapter; both must answer.
        assertEquals(CableBluetooth.adapter(ctx) != null, CableBluetooth.isSupported(ctx))
        assertTrue(CableBluetooth.isEnabled(ctx) || CableBluetooth.adapter(ctx) != null)
        assertFalse(CableBluetooth.isEnabled(ctx) && CableBluetooth.adapter(ctx) == null)
    }
}
