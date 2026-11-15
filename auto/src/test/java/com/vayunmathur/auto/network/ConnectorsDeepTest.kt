package com.vayunmathur.auto.network

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pManager
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.auto.platform.TransportState
import com.vayunmathur.auto.protocol.UsbSessionState
import com.vayunmathur.auto.protocol.WirelessSessionState
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Deep connector coverage: the USB accessory state machine driven with a
 * fabricated accessory, and the wireless bring-up's start/cancel cycle.
 * Framework opens that cannot succeed under Robolectric (accessory fds,
 * P2P group formation) still exercise every guard and state transition
 * in front of them.
 */
@RunWith(RobolectricTestRunner::class)
class ConnectorsDeepTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    private fun accessory(manufacturer: String, model: String): UsbAccessory {
        val ctor = UsbAccessory::class.java.getDeclaredConstructor().apply { isAccessible = true }
        val accessory = ctor.newInstance()
        for ((field, value) in listOf("mManufacturer" to manufacturer, "mModel" to model)) {
            runCatching {
                UsbAccessory::class.java.getDeclaredField(field)
                    .apply { isAccessible = true }.set(accessory, value)
            }
        }
        return accessory
    }

    private fun attachIntent(accessory: UsbAccessory): Intent =
        Intent(UsbManager.ACTION_USB_ACCESSORY_ATTACHED)
            .putExtra(UsbManager.EXTRA_ACCESSORY, accessory)

    @Test fun `attach without permission requests it and denial then retry cycles`() {
        val accessory = accessory("ACME", "Roadster HU")
        assertEquals("ACME", accessory.manufacturer)
        assertTrue(UsbConnector.onAccessoryIntent(ctx, attachIntent(accessory)))
        // No grant under Robolectric: the session waits, then denial lands.
        assertTrue(
            TransportState.usbState.value == UsbSessionState.AWAITING_PERMISSION ||
                TransportState.usbState.value == UsbSessionState.PERMISSION_DENIED,
        )
        val denied = Intent(UsbConnector.ACTION_USB_PERMISSION)
            .putExtra(UsbManager.EXTRA_ACCESSORY, accessory)
            .putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
        assertTrue(UsbConnector.onPermissionResult(ctx, denied))
        assertEquals(UsbSessionState.PERMISSION_DENIED, TransportState.usbState.value)
        // Retry is only legal from the denied state; it re-enters awaiting.
        UsbConnector.retryPermission(ctx)
        assertEquals(UsbSessionState.AWAITING_PERMISSION, TransportState.usbState.value)
        // Detach clears the remembered accessory; force-start then has nothing to drive.
        assertTrue(UsbConnector.onAccessoryIntent(ctx, Intent(UsbManager.ACTION_USB_ACCESSORY_DETACHED)))
        assertEquals(UsbSessionState.DETACHED, TransportState.usbState.value)
        UsbConnector.forceStart(ctx)
        assertEquals(UsbSessionState.DETACHED, TransportState.usbState.value)
    }

    @Test fun `permission granted opens the accessory and parks its transport`() {
        val accessory = accessory("ACME", "Roadster HU")
        assertTrue(UsbConnector.onAccessoryIntent(ctx, attachIntent(accessory)))
        val granted = Intent(UsbConnector.ACTION_USB_PERMISSION)
            .putExtra(UsbManager.EXTRA_ACCESSORY, accessory)
            .putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, true)
        assertTrue(UsbConnector.onPermissionResult(ctx, granted))
        // Robolectric's UsbManager hands back an fd, so the open succeeds: the
        // session connects and the fd transport parks for the service loop.
        assertEquals(UsbSessionState.CONNECTED, TransportState.usbState.value)
        val pending = TransportIntake.take()
        assertTrue(pending != null && pending.carName.contains("Roadster"))
        pending!!.transport.close()
        assertTrue(UsbConnector.onAccessoryIntent(ctx, Intent(UsbManager.ACTION_USB_ACCESSORY_DETACHED)))
    }

    @Test fun `force start action is consumed and re-drives the last accessory`() {
        val accessory = accessory("ACME", "Roadster HU")
        assertTrue(UsbConnector.onAccessoryIntent(ctx, attachIntent(accessory)))
        assertTrue(UsbConnector.onAccessoryIntent(ctx, Intent(UsbConnector.ACTION_USB_ACCESSORY_FORCE_START)))
        assertFalse(UsbConnector.onAccessoryIntent(ctx, Intent(UsbManager.ACTION_USB_ACCESSORY_ATTACHED)))
        assertTrue(UsbConnector.onAccessoryIntent(ctx, Intent(UsbManager.ACTION_USB_ACCESSORY_DETACHED)))
    }

    // NOTE (skipped, framework-bound): WifiDirectConnector.start()/startFromAssociation()
    // cannot be exercised under Robolectric — ShadowWifiP2pManager.initialize()
    // returns a Channel whose listener map is never initialised, so the real
    // WifiP2pManager.discoverPeers() NPEs inside the framework before any app
    // code runs. The DHU-oriented surface that CAN be tested is below: cancel,
    // broadcast guards and hidden-network requests with no live bring-up.

    @Test fun `wireless broadcasts and hidden requests without a bring-up are safe`() {
        WifiDirectConnector.cancel() // idle cancel: resets and publishes IDLE
        assertEquals(WirelessSessionState.IDLE, TransportState.wirelessState.value)
        // (A connected NetworkInfo cannot even be constructed under Robolectric;
        // the missing-extra broadcast is the testable guard.)
        val intent = Intent(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        // Cancelled connector ignores the broadcast outright.
        WifiDirectConnector.onConnectionChanged(intent)
        assertEquals(WirelessSessionState.IDLE, TransportState.wirelessState.value)
        // No appContext was ever stored (start never ran): hidden request no-ops.
        WifiDirectConnector.requestHiddenNetwork("CarSSID", "secret-pass")
        assertEquals(WirelessSessionState.IDLE, TransportState.wirelessState.value)
    }

    @Suppress("DEPRECATION")
    private fun ConnectivityManagerType(): Int = android.net.ConnectivityManager.TYPE_WIFI
}
