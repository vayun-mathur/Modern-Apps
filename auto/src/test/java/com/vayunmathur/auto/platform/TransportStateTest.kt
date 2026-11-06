package com.vayunmathur.auto.platform

import com.vayunmathur.auto.protocol.TransportKind
import com.vayunmathur.auto.protocol.UsbSession
import com.vayunmathur.auto.protocol.UsbSessionState
import com.vayunmathur.auto.protocol.WirelessSession
import com.vayunmathur.auto.protocol.WirelessSessionState
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Auto module: [TransportState] — USB/wireless/role publishing and the
 * winning-transport selection the pairing screen reads.
 */
@RunWith(RobolectricTestRunner::class)
class TransportStateTest {

    private fun reset() {
        TransportState.publishUsb(UsbSession())
        TransportState.publishWireless(WirelessSession())
        TransportState.publishRole(false)
    }

    @Test
    fun `fresh state is detached idle with the dev loopback fallback`() {
        reset()
        assertEquals(UsbSessionState.DETACHED, TransportState.usbState.value)
        assertNull(TransportState.usbLabel.value)
        assertEquals(WirelessSessionState.IDLE, TransportState.wirelessState.value)
        assertEquals(false, TransportState.roleHeld.value)
        // Dev variant: TCP_LOOPBACK is allowed, so it wins with nothing ready.
        assertEquals(TransportKind.TCP_LOOPBACK, TransportState.selected.value)
    }

    @Test
    fun `usb attach publishes the accessory label`() {
        reset()
        val session = UsbSession().also { it.onAttached("ACME", "Car") }
        TransportState.publishUsb(session)
        assertEquals(UsbSessionState.AWAITING_PERMISSION, TransportState.usbState.value)
        assertEquals("ACME Car", TransportState.usbLabel.value)
    }

    @Test
    fun `awaiting permission alone does not select usb`() {
        reset()
        val usb = UsbSession().also { it.onAttached("ACME", "Car") }
        TransportState.publishUsb(usb)
        assertEquals(UsbSessionState.AWAITING_PERMISSION, TransportState.usbState.value)
        // Falls back to the dev loopback, not USB.
        assertEquals(TransportKind.TCP_LOOPBACK, TransportState.selected.value)
    }

    @Test
    fun `role publishing lands`() {
        reset()
        TransportState.publishRole(true)
        assertEquals(true, TransportState.roleHeld.value)
    }

    @Test
    fun `wireless session mirrors into the flow`() {
        reset()
        val wireless = WirelessSession().also { it.onBluetoothAssociated() }
        TransportState.publishWireless(wireless)
        assertEquals(WirelessSessionState.NEGOTIATING_WIFI, TransportState.wirelessState.value)
    }

    @Test
    fun `usb connected selects usb transport`() {
        reset()
        val usb = UsbSession().also {
            it.onAttached("ACME", "Car")
            it.onPermissionGranted()
        }
        TransportState.publishUsb(usb)
        assertEquals(UsbSessionState.CONNECTED, TransportState.usbState.value)
        assertEquals(TransportKind.USB, TransportState.selected.value)
    }

    @Test
    fun `denial clears the label only on detach, retry re-arms`() {
        reset()
        val usb = UsbSession().also {
            it.onAttached("ACME", "Car")
            it.onPermissionDenied()
        }
        TransportState.publishUsb(usb)
        assertEquals(UsbSessionState.PERMISSION_DENIED, TransportState.usbState.value)
        assertEquals(true, usb.retryPermission())
        assertEquals(UsbSessionState.AWAITING_PERMISSION, usb.state)
        assertEquals(false, usb.retryPermission())
    }
}
