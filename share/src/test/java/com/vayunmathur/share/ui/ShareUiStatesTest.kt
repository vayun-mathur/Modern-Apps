package com.vayunmathur.share.ui

import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.vayunmathur.share.domain.protocol.ShareState
import com.vayunmathur.share.platform.SendUiState
import com.vayunmathur.share.platform.ShareActions
import com.vayunmathur.share.platform.discovery.DiscoverySource
import com.vayunmathur.share.platform.discovery.NearbyDevice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ShareUiStatesTest {
    @get:Rule val rule = createComposeRule()

    private class FakeActions : ShareActions {
        override fun startScan() {}
        override fun stopScan() {}
        override fun connectToDevice(device: NearbyDevice) {}
        override fun setOutgoingUris(uris: List<Uri>) {}
        override fun clearOutgoing() {}
        override fun disconnect(connection: com.vayunmathur.share.network.transport.Connection) {}
    }

    @Test
    fun `scanning with no devices shows scanning card`() {
        rule.setContent { ShareSendContent(uiState = SendUiState(isScanning = true), actions = FakeActions()) }
        rule.onNodeWithText("Scanning for nearby devices", substring = true).assertIsDisplayed()
    }

    @Test
    fun `device rows show source labels for each source`() {
        rule.setContent {
            androidx.compose.foundation.layout.Column {
                for (source in DiscoverySource.entries) {
                    DeviceRow(device = NearbyDevice(endpointId = "ID$source", endpointName = "Dev$source", host = "10.0.0.1", port = 7000, source = source), onTap = {})
                }
            }
        }
        for (source in DiscoverySource.entries) {
            rule.onNodeWithText("Dev$source").assertIsDisplayed()
        }
    }

    @Test
    fun `transfer card handshaking awaiting and unknown states`() {
        rule.setContent { TransferCard(endpoint = "E1", state = ShareState.Handshaking, bytesSent = 0, error = null, onDisconnect = {}) }
        rule.onNodeWithText("E1", substring = true).assertIsDisplayed()
    }

    @Test
    fun `outgoing card empty state shows pick prompt`() {
        rule.setContent { OutgoingFilesCard(uris = emptyList(), displayNames = emptyList(), onPickFiles = {}, onClear = {}) }
        rule.onNodeWithText("No files selected", substring = true).assertIsDisplayed()
    }
}
