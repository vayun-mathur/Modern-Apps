package com.vayunmathur.auto.network

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
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

@RunWith(RobolectricTestRunner::class)
class ConnectorsTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun `usb unrelated and missing-accessory intents are not consumed`() {
        assertFalse(UsbConnector.onAccessoryIntent(ctx, Intent("com.example.OTHER")))
        assertFalse(UsbConnector.onAccessoryIntent(ctx, Intent(UsbManager.ACTION_USB_ACCESSORY_ATTACHED)))
    }

    @Test fun `usb detach is consumed and publishes detached`() {
        assertTrue(UsbConnector.onAccessoryIntent(ctx, Intent(UsbManager.ACTION_USB_ACCESSORY_DETACHED)))
        assertEquals(UsbSessionState.DETACHED, TransportState.usbState.value)
    }

    @Test fun `usb force start with no accessory and permission edge cases`() {
        UsbConnector.forceStart(ctx) // no accessory seen: ignored, no crash
        assertFalse(UsbConnector.onPermissionResult(ctx, Intent("com.example.OTHER")))
        assertTrue(UsbConnector.onPermissionResult(ctx, Intent(UsbConnector.ACTION_USB_PERMISSION)))
        // denied / no accessory publishes denied-or-detached state without opening
        assertTrue(TransportState.usbState.value == UsbSessionState.DETACHED || TransportState.usbState.value == UsbSessionState.PERMISSION_DENIED)
        UsbConnector.retryPermission(ctx) // no accessory: no-op
    }

    @Test fun `wifi cancel when idle resets wireless state`() {
        WifiDirectConnector.cancel()
        assertEquals(WirelessSessionState.IDLE, TransportState.wirelessState.value)
        // connection-changed with no network info is ignored
        WifiDirectConnector.onConnectionChanged(Intent("x"))
        assertEquals(WirelessSessionState.IDLE, TransportState.wirelessState.value)
    }

    @Test fun `wifi request hidden network without start is safe`() {
        WifiDirectConnector.requestHiddenNetwork("CarSSID", "secret-pass")
        // appContext is set only via start(); without it this is a no-op, with it a framework request
        WifiDirectConnector.cancel()
        assertEquals(WirelessSessionState.IDLE, TransportState.wirelessState.value)
    }
}
