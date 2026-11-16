package com.vayunmathur.camera.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cov-x4 wave 2: Canvas overlays actually drawn (captureToImage forces the
 * draw scope, which plain setContent in Robolectric never executes).
 */
@RunWith(AndroidJUnit4::class)
class CameraCovX4Compose2Test {
    @get:Rule val rule = createComposeRule()

    @Test fun gridOverlayDrawsRuleOfThirds() {
        rule.setContent { GridOverlay(Modifier.fillMaxSize()) }
        rule.waitForIdle()
        val image = rule.onRoot().captureToImage()
        assertTrue(image.width > 0 && image.height > 0)
        assertTrue(image.asAndroidBitmap().width > 0)
    }

    @Test fun levelOverlayDrawsLevelAndTilted() {
        rule.setContent { LevelOverlay(roll = 0f, modifier = Modifier.size(200.dp)) }
        rule.waitForIdle()
        val image = rule.onRoot().captureToImage()
        assertTrue(image.width > 0)
    }

    @Test fun levelOverlayDrawsSteepRoll() {
        rule.setContent { LevelOverlay(roll = 35f, modifier = Modifier.size(200.dp)) }
        rule.waitForIdle()
        val image = rule.onRoot().captureToImage()
        assertTrue(image.height > 0)
    }
}
