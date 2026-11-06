package com.vayunmathur.auto.ui

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.auto.R as AutoR
import com.vayunmathur.auto.protocol.TransportKind
import com.vayunmathur.auto.protocol.UsbSessionState
import com.vayunmathur.auto.protocol.WirelessSessionState
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Auto module: stateless [PairingScreen] — USB + wireless rows, transport and
 * role rows. Everything is a pure function of the published session states.
 */
@RunWith(RobolectricTestRunner::class)
class PairingScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val appContext: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private fun idleScreen(
        usbState: UsbSessionState = UsbSessionState.DETACHED,
        usbLabel: String? = null,
        wirelessState: WirelessSessionState = WirelessSessionState.IDLE,
        roleHeld: Boolean = false,
        selected: TransportKind? = null,
        onUsbRetry: () -> Unit = {},
        onWirelessSearch: () -> Unit = {},
        onWirelessCancel: () -> Unit = {},
        onBack: () -> Unit = {},
    ) {
        rule.setContent {
            PairingScreen(
                usbState = usbState,
                usbLabel = usbLabel,
                wirelessState = wirelessState,
                roleHeld = roleHeld,
                selected = selected,
                onNavigateBack = onBack,
                onUsbRetry = onUsbRetry,
                onWirelessSearch = onWirelessSearch,
                onWirelessCancel = onWirelessCancel,
            )
        }
    }

    @Test
    fun `detached idle shows the detached idle none and missing rows`() {
        idleScreen()
        rule.onNodeWithText(appContext.getString(AutoR.string.pairing_title)).assertExists()
        rule.onNodeWithText(appContext.getString(AutoR.string.pairing_usb_detached)).assertExists()
        rule.onNodeWithText(appContext.getString(AutoR.string.pairing_wireless_idle)).assertExists()
        rule.onNodeWithText(appContext.getString(AutoR.string.pairing_transport_none)).assertExists()
        rule.onNodeWithText(appContext.getString(AutoR.string.pairing_role_missing)).assertExists()
        rule.onNodeWithText(appContext.getString(AutoR.string.pairing_hint)).assertExists()
    }

    @Test
    fun `attached usb shows the label in the permission row`() {
        idleScreen(usbState = UsbSessionState.AWAITING_PERMISSION, usbLabel = "ACME Car")
        rule.onNodeWithText(
            appContext.getString(AutoR.string.pairing_usb_permission, "ACME Car").trim(),
        ).assertExists()
    }

    @Test
    fun `denied usb shows the retry action and connected shows the label`() {
        var retries = 0
        idleScreen(
            usbState = UsbSessionState.PERMISSION_DENIED,
            usbLabel = "ACME Car",
            selected = TransportKind.USB,
            onUsbRetry = { retries++ },
        )
        rule.onNodeWithText(appContext.getString(AutoR.string.pairing_usb_denied)).assertExists()
        rule.onNodeWithText(appContext.getString(AutoR.string.pairing_usb_retry)).performClick()
        assertEquals(1, retries)

        idleScreen(
            usbState = UsbSessionState.CONNECTED,
            usbLabel = "ACME Car",
            selected = TransportKind.USB,
        )
        rule.onNodeWithText(
            appContext.getString(AutoR.string.pairing_usb_connected, "ACME Car").trim(),
        ).assertExists()
        rule.onNodeWithText(appContext.getString(AutoR.string.pairing_transport_usb)).assertExists()
    }

    @Test
    fun `wireless searching shows cancel and wireless ready shows the ready row`() {
        var cancels = 0
        idleScreen(
            wirelessState = WirelessSessionState.NEGOTIATING_WIFI,
            selected = TransportKind.WIRELESS,
            onWirelessCancel = { cancels++ },
        )
        rule.onNodeWithText(appContext.getString(AutoR.string.pairing_wireless_searching))
            .assertExists()
        rule.onNodeWithText(appContext.getString(AutoR.string.pairing_wireless_cancel))
            .performClick()
        assertEquals(1, cancels)

        idleScreen(wirelessState = WirelessSessionState.WIFI_READY)
        rule.onNodeWithText(appContext.getString(AutoR.string.pairing_wireless_ready)).assertExists()
    }

    @Test
    fun `idle wireless shows the search action and connected shows connected`() {
        var searches = 0
        idleScreen(onWirelessSearch = { searches++ })
        rule.onNodeWithText(appContext.getString(AutoR.string.pairing_wireless_search))
            .performClick()
        assertEquals(1, searches)

        idleScreen(
            wirelessState = WirelessSessionState.CONNECTED,
            selected = TransportKind.WIRELESS,
        )
        rule.onNodeWithText(appContext.getString(AutoR.string.pairing_wireless_connected))
            .assertExists()
    }

    @Test
    fun `loopback selection and held role show their rows`() {
        idleScreen(selected = TransportKind.TCP_LOOPBACK, roleHeld = true)
        rule.onNodeWithText(appContext.getString(AutoR.string.pairing_transport_loopback))
            .assertExists()
        rule.onNodeWithText(appContext.getString(AutoR.string.pairing_role_held)).assertExists()
    }

    @Test
    fun `lost transports show the lost rows`() {
        idleScreen(
            usbState = UsbSessionState.DISCONNECTED,
            wirelessState = WirelessSessionState.DISCONNECTED,
        )
        // Both lost rows render the module's "lost" strings exactly once each.
        rule.onAllNodes(hasText(appContext.getString(AutoR.string.pairing_usb_lost)))
            .assertCountEquals(1)
        rule.onAllNodes(hasText(appContext.getString(AutoR.string.pairing_wireless_lost)))
            .assertCountEquals(1)
    }
}
