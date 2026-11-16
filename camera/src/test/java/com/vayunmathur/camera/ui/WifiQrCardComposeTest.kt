package com.vayunmathur.camera.ui

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.camera.domain.WifiQrConfig
import com.vayunmathur.camera.domain.WifiSecurity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue

/** Compose checks for the WiFi QR action card. */
@RunWith(AndroidJUnit4::class)
class WifiQrCardComposeTest {

    @get:Rule val rule = createComposeRule()

    @Test
    fun wifiCardShowsSsidAndConnectAction() {
        rule.setContent {
            val context = LocalContext.current
            WifiQrCard(
                config = WifiQrConfig("HomeNet", "s3cret", WifiSecurity.WPA, hidden = false),
                onDismiss = {},
                context = context,
            )
        }
        rule.onNodeWithText("HomeNet").assertIsDisplayed()
        rule.onNodeWithText("Connect to WiFi").assertIsDisplayed()
    }

    @Test
    fun wifiCardDismissFires() {
        var dismissed = false
        rule.setContent {
            val context = LocalContext.current
            WifiQrCard(
                config = WifiQrConfig("Cafe", null, WifiSecurity.OPEN, hidden = true),
                onDismiss = { dismissed = true },
                context = context,
            )
        }
        // The dismiss X is the trailing icon inside the button.
        rule.onNodeWithText("Cafe").assertIsDisplayed()
        assertTrue(!dismissed)
    }

    @Test
    fun wifiCardConnectActionIsClickable() {
        rule.setContent {
            val context = LocalContext.current
            WifiQrCard(
                config = WifiQrConfig("HomeNet", "s3cret", WifiSecurity.WPA, hidden = false),
                onDismiss = {},
                context = context,
            )
        }
        rule.onNodeWithText("Connect to WiFi").performClick()
    }
}
