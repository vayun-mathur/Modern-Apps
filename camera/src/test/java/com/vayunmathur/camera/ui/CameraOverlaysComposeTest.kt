package com.vayunmathur.camera.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class CameraOverlaysComposeTest {

    @get:Rule val rule = createComposeRule()

    @Test fun gridAndLevelOverlaysRender() {
        rule.setContent {
            Box {
                GridOverlay(Modifier)
                LevelOverlay(roll = 12f, modifier = Modifier)
            }
        }
        assertTrue(rule.onRoot().fetchSemanticsNode() != null)
    }

    @Test fun recordingIndicatorShowsDuration() {
        rule.setContent { RecordingIndicator(durationSec = 65) }
        rule.onNodeWithText("1:05").assertIsDisplayed()
    }

    @Test fun qrResultOverlayShowsText() {
        var dismissed = false
        rule.setContent {
            val context = LocalContext.current
            QrResultOverlay(text = "https://example.com", onDismiss = { dismissed = true }, context = context)
        }
        rule.onNodeWithText("https://example.com").assertIsDisplayed()
        assertFalse(dismissed)
    }

    @Test fun fidoCardRenders() {
        rule.setContent {
            val context = LocalContext.current
            FidoQrCard(text = "FIDO:/12345", onDismiss = {}, context = context)
        }
        assertTrue(rule.onRoot().fetchSemanticsNode() != null)
    }

    @Test fun esimCardRenders() {
        rule.setContent {
            val context = LocalContext.current
            EsimQrCard(text = "LPA:1\$srv.example\$CODE", onDismiss = {}, context = context)
        }
        assertTrue(rule.onRoot().fetchSemanticsNode() != null)
    }

    @Test fun esimDetection() {
        assertTrue(isEsimActivationCode("LPA:1\$srv.example\$CODE"))
        assertTrue(isEsimActivationCode("lpa:1\$srv.example\$CODE"))
        assertFalse(isEsimActivationCode("https://example.com"))
        assertFalse(isEsimActivationCode(""))
    }

    @Test fun longExposureOverlayRenders() {
        rule.setContent {
            LongExposureOverlay(progress = 0.5f, remainingText = "2s")
        }
        rule.onNodeWithText("2s").assertIsDisplayed()
    }
}
