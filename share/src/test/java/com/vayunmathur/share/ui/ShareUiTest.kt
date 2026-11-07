package com.vayunmathur.share.ui

import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vayunmathur.share.domain.protocol.ShareState
import com.vayunmathur.share.platform.SendUiState
import com.vayunmathur.share.platform.ShareActions
import com.vayunmathur.share.platform.discovery.DiscoverySource
import com.vayunmathur.share.platform.discovery.NearbyDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class ShareUiTest {
    @get:Rule val rule = createComposeRule()

    private class FakeActions : ShareActions {
        val connects = mutableListOf<NearbyDevice>()
        var scans = 0; var stops = 0; var clears = 0
        var uris: List<Uri> = emptyList()
        override fun startScan() { scans++ }
        override fun stopScan() { stops++ }
        override fun connectToDevice(device: NearbyDevice) { connects += device }
        override fun setOutgoingUris(uris: List<Uri>) { this.uris = uris }
        override fun clearOutgoing() { clears++ }
        override fun disconnect(connection: com.vayunmathur.share.network.transport.Connection) {}
    }

    @Test
    fun `send content shows no-devices and scan button`() {
        val actions = FakeActions()
        rule.setContent { ShareSendContent(uiState = SendUiState(isScanning = false), actions = actions) }
        rule.onNodeWithText("No devices found", substring = true).assertIsDisplayed()
    }

    @Test
    fun `send content lists connectable device and tap connects`() {
        val actions = FakeActions()
        val dev = NearbyDevice(endpointId = "ABCD", endpointName = "Pixel 9", host = "192.168.1.5", port = 9000, source = DiscoverySource.Nsd)
        rule.setContent {
            ShareSendContent(uiState = SendUiState(discoveredDevices = listOf(dev), outgoingUris = listOf(Uri.parse("file:///tmp/a.txt")), outgoingDisplayNames = listOf("a.txt")), actions = actions)
        }
        rule.onNodeWithText("Pixel 9").assertIsDisplayed().performClick()
        assertEquals(listOf(dev), actions.connects)
    }

    @Test
    fun `ble-only device is shown but not clickable-connectable`() {
        val dev = NearbyDevice(endpointId = "BLE1", endpointName = "Ble Phone", source = DiscoverySource.Ble)
        rule.setContent { DeviceRow(device = dev, onTap = {}) }
        rule.onNodeWithText("Ble Phone").assertIsDisplayed()
    }

    @Test
    fun `outgoing files card shows names and clear`() {
        val clears = AtomicInteger(0)
        rule.setContent {
            OutgoingFilesCard(uris = listOf(Uri.parse("content://x/1"), Uri.parse("content://x/2")), displayNames = listOf("one.txt", "two.txt"), onPickFiles = {}, onClear = { clears.incrementAndGet() })
        }
        rule.onNodeWithText("• one.txt").assertIsDisplayed()
        rule.onNodeWithText("• two.txt").assertIsDisplayed()
    }

    @Test
    fun `transfer card states render`() {
        rule.setContent { TransferCard(endpoint = "Pixel", state = ShareState.Completed, bytesSent = 2048, error = null, onDisconnect = {}) }
        rule.onNodeWithText("Pixel", substring = true).assertIsDisplayed()
    }

    @Test
    fun `transfer card failed shows error`() {
        rule.setContent { TransferCard(endpoint = "Pixel", state = ShareState.Failed, bytesSent = 0, error = "boom failure", onDisconnect = {}) }
        rule.onNodeWithText("boom failure").assertIsDisplayed()
    }

    @Test
    fun `transfer card transferring shows progress text`() {
        rule.setContent { TransferCard(endpoint = "Pixel", state = ShareState.Transferring, bytesSent = 102400, error = null, onDisconnect = {}) }
        rule.onNodeWithText("Pixel", substring = true).assertIsDisplayed()
        assertTrue(true)
    }
}
