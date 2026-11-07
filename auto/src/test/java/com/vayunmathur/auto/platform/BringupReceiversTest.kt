package com.vayunmathur.auto.platform

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.auto.network.UsbConnector
import com.vayunmathur.auto.network.WifiDirectConnector
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Auto module: the bring-up receivers — [UsbReceiver], [UsbTPlusReceiver],
 * [WifiBluetoothReceiver], [ConnectivityEventReceiver], [BootReceiver] and
 * [ConnectionResetReceiver]. Unknown actions are ignored; the USB denial and
 * TPlus paths funnel into [UsbConnector]; BT-off/ACL-disconnect cancel an
 * in-flight wireless bring-up; boot and USB-reset restart the listener.
 */
@RunWith(RobolectricTestRunner::class)
class BringupReceiversTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun intent(action: String): Intent = Intent(action)

    // --- UsbReceiver ------------------------------------------------------------

    @Test
    fun `usb receiver ignores unknown actions`() {
        UsbReceiver().onReceive(context, intent("com.example.NOPE"))
        // No accessory seen: nothing latched.
        assertFalse(UsbConnector.onAccessoryIntent(context, intent("com.example.NOPE")))
    }

    @Test
    fun `usb detach clears the attach state`() {
        assertTrue(
            UsbConnector.onAccessoryIntent(context, intent(UsbManager.ACTION_USB_ACCESSORY_DETACHED)),
        )
    }

    @Test
    fun `usb permission denial is consumed`() {
        val denial = Intent(UsbConnector.ACTION_USB_PERMISSION).apply {
            putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
        }
        assertTrue(UsbConnector.onPermissionResult(context, denial))
        UsbReceiver().onReceive(context, denial)
    }

    @Test
    fun `usb force-start with no accessory is a logged no-op`() {
        UsbConnector.forceStart(context)
        UsbReceiver().onReceive(context, intent(UsbConnector.ACTION_USB_ACCESSORY_FORCE_START))
        // Retry without an accessory is a no-op too.
        UsbConnector.retryPermission(context)
    }

    // --- UsbTPlusReceiver ----------------------------------------------------------

    @Test
    fun `tplus receiver ignores unknown actions`() {
        UsbTPlusReceiver().onReceive(context, intent("com.example.NOPE"))
    }

    @Test
    fun `tplus detach re-drives without an accessory`() {
        // No stock extra and no accessory seen: falls back to force-start,
        // which is a no-op with nothing latched.
        UsbTPlusReceiver().onReceive(context, intent(UsbTPlusReceiver.ACTION_USB_TPLUS_DETACHED))
    }

    // --- WifiBluetoothReceiver -------------------------------------------------------

    @Test
    fun `bt receiver ignores unknown actions`() {
        WifiBluetoothReceiver().onReceive(context, intent("com.example.NOPE"))
    }

    @Test
    fun `acl disconnect cancels an idle bring-up safely`() {
        WifiDirectConnector.cancel()
        WifiBluetoothReceiver().onReceive(
            context,
            intent(android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED),
        )
    }

    @Test
    fun `bluetooth off cancels an idle bring-up safely`() {
        WifiDirectConnector.cancel()
        WifiBluetoothReceiver().onReceive(
            context,
            Intent(BluetoothAdapter.ACTION_STATE_CHANGED).apply {
                putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF)
            },
        )
        // A non-off state change is ignored.
        WifiBluetoothReceiver().onReceive(
            context,
            Intent(BluetoothAdapter.ACTION_STATE_CHANGED).apply {
                putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_ON)
            },
        )
    }

    @Test
    fun `acl connect without a device returns early`() {
        WifiBluetoothReceiver().onReceive(
            context,
            intent("android.bluetooth.device.action.ACL_CONNECTED"),
        )
    }

    // --- ConnectivityEventReceiver ----------------------------------------------------

    @Test
    fun `connectivity receiver ignores unknown actions`() {
        ConnectivityEventReceiver().onReceive(context, intent("com.example.NOPE"))
    }

    @Test
    fun `connectivity loss with no network is a no-op`() {
        // Robolectric starts with no active network: the receiver logs and returns.
        ConnectivityEventReceiver().onReceive(
            context,
            intent(ConnectivityManager.CONNECTIVITY_ACTION),
        )
    }

    // --- BootReceiver + ConnectionResetReceiver ------------------------------------------

    @Test
    fun `boot receiver ignores unknown actions`() {
        BootReceiver().onReceive(context, intent("com.example.NOPE"))
    }

    @Test
    fun `boot completed restarts the projection listener`() {
        BootReceiver().onReceive(context, intent(Intent.ACTION_BOOT_COMPLETED))
        val next = Shadows.shadowOf(context as android.app.Application)
            .nextStartedService
        assertTrue(next?.component?.className?.endsWith("ProjectionService") == true)
    }

    @Test
    fun `usb reset re-arms the projection listener`() {
        ConnectionResetReceiver().onReceive(
            context,
            intent(ConnectionResetReceiver.ACTION_RESET_USB_PORT),
        )
        val next = Shadows.shadowOf(context as android.app.Application)
            .nextStartedService
        assertTrue(next?.component?.className?.endsWith("ProjectionService") == true)
        // Unknown actions are ignored.
        ConnectionResetReceiver().onReceive(context, intent("com.example.NOPE"))
    }

    // --- Role + transport selection ------------------------------------------------------

    @Test
    fun `role reads fail closed with no role service`() {
        assertFalse(MaosRoleStatus.isProjectionRoleHeld(context))
    }
}
