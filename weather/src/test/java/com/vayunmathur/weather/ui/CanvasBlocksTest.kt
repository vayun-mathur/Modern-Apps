package com.vayunmathur.weather.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.weather.domain.MetricPoint
import com.vayunmathur.weather.ui.components.MetricGraphSheet
import com.vayunmathur.weather.ui.components.blocks.MoonBlock
import com.vayunmathur.weather.ui.components.blocks.SunBlock
import com.vayunmathur.weather.ui.components.blocks.UvIndexBlock
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test

/**
 * Canvas-heavy blocks under Robolectric's NATIVE graphics mode so the
 * DrawScope lambdas (sun/moon tracks, moon disc, UV dots, graph lines)
 * actually execute instead of being skipped by the legacy shadow canvas.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(AndroidJUnit4::class)
class CanvasBlocksTest {

    @get:Rule val rule = createComposeRule()

    @Test fun sunMoonUvDraw() {
        rule.setContent {
            androidx.compose.foundation.layout.Column {
                SunBlock(1767225600L, 1767268800L, true, 43200.0, 1767247200L)
                MoonBlock(0.5, 1767225600L, 1767268800L, false, 1767247200L)
                MoonBlock(0.9, null, null, true, 1767247200L)
                MoonBlock(null, 1767225600L, 1767200000L, true, 1767247200L)
                UvIndexBlock(9.0)
                UvIndexBlock(2.0)
                UvIndexBlock(null)
            }
        }
        rule.waitForIdle()
        // Force a real render pass so every Canvas DrawScope lambda runs.
        rule.onRoot().captureToImage()
    }

    @Test fun graphSheetDraws() {
        rule.setContent {
            MetricGraphSheet(
                title = "Temperature",
                points = (0..10).map { MetricPoint(1767225600L + it * 3600, 10.0 + it % 3) },
                valueLabel = { "${it.toInt()}°" },
                timeLabel = { "h$it" },
                onOpenMap = {},
                onDismiss = {},
            )
        }
        rule.waitForIdle()
        rule.onRoot().captureToImage()
    }
}
