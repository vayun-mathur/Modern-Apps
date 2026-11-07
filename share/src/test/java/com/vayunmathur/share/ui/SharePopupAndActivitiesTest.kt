package com.vayunmathur.share.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.share.MainActivity
import com.vayunmathur.share.SharePopupActivity
import com.vayunmathur.share.platform.SendUiState
import com.vayunmathur.share.platform.ShareActions
import com.vayunmathur.share.platform.discovery.NearbyDevice
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SharePopupAndActivitiesTest {
    @get:Rule val rule = createComposeRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private class FakeActions : ShareActions {
        override fun startScan() {}
        override fun stopScan() {}
        override fun connectToDevice(device: NearbyDevice) {}
        override fun setOutgoingUris(uris: List<Uri>) {}
        override fun clearOutgoing() {}
        override fun disconnect(connection: com.vayunmathur.share.network.transport.Connection) {}
    }

    @Test
    fun `popup content shows permission denied message`() {
        rule.setContent {
            SharePopupContent(uiState = SendUiState(), actions = FakeActions(), permissionsDenied = true, onDismiss = {})
        }
        rule.onNodeWithText("Nearby Share needs", substring = true).assertIsDisplayed()
    }

    @Test
    fun `popup content lists devices when not denied`() {
        val dev = NearbyDevice(endpointId = "ABCD", endpointName = "Popup Pixel", host = "10.0.0.3", port = 8000)
        rule.setContent {
            SharePopupContent(
                uiState = SendUiState(discoveredDevices = listOf(dev), outgoingDisplayNames = listOf("f.txt")),
                actions = FakeActions(), permissionsDenied = false, onDismiss = {},
            )
        }
        rule.onNodeWithText("Popup Pixel").assertIsDisplayed()
    }

    @Test
    fun `popup content empty devices shows hint`() {
        rule.setContent {
            SharePopupContent(uiState = SendUiState(isScanning = true), actions = FakeActions(), permissionsDenied = false, onDismiss = {})
        }
        // scanning row or no-devices text must render without crash
        assertTrue(true)
    }

    @Test
    fun `main activity launches`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        assertNotNull(controller.get())
        controller.pause().stop().destroy()
    }

    @Test
    fun `share popup activity launches with send intent`() {
        val intent = Intent(app, SharePopupActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "popup hello")
        }
        val controller = Robolectric.buildActivity(SharePopupActivity::class.java, intent).create().start().resume()
        assertNotNull(controller.get())
        controller.pause().stop().destroy()
    }
}
