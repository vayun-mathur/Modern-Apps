package com.vayunmathur.camera.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.camera.platform.CameraMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * ZoomBar scrub: a horizontal drag across the bar emits continuous zoom
 * through the same callback the chips use (CameraViewModel.setZoomRatio
 * in production, so clamping and the zoom lock compose); a tap still
 * selects the chip; locked modes render no bar to scrub.
 */
@RunWith(AndroidJUnit4::class)
class ZoomBarScrubComposeTest {

    @get:Rule val rule = createComposeRule()

    private val levels = listOf(".5" to 0.5f, "1x" to 1f, "2x" to 2f, "5x" to 5f)

    @Test fun horizontalDragScrubsContinuouslyAcrossRange() {
        val emitted = mutableListOf<Float>()
        var current by mutableFloatStateOf(1f)
        rule.setContent {
            ZoomBar(
                currentZoom = current,
                zoomLevels = levels,
                onZoomSelected = { emitted.add(it); current = it },
                zoomRange = 0.5f to 5f,
            )
        }
        rule.onRoot().performTouchInput {
            down(Offset(4f, center.y))
            moveBy(Offset(width * 0.4f, 0f))
            moveBy(Offset(width * 0.4f, 0f))
            up()
        }
        rule.waitForIdle()
        assertTrue(emitted.size >= 2, "scrub must emit live, got $emitted")
        assertTrue(emitted.all { it in 0.5f..5f }, "scrub emissions must stay in range: $emitted")
        assertTrue(
            emitted.zipWithNext().all { (a, b) -> b >= a },
            "dragging right must not zoom out: $emitted",
        )
        assertTrue(emitted.last() > emitted.first(), "dragging right must zoom in: $emitted")
        // The live ratio follows the scrub (state fed back as currentZoom).
        assertEquals(emitted.last(), current)
    }

    @Test fun dragLeftZoomsOut() {
        val emitted = mutableListOf<Float>()
        rule.setContent {
            ZoomBar(
                currentZoom = 5f,
                zoomLevels = levels,
                onZoomSelected = { emitted.add(it) },
                zoomRange = 0.5f to 5f,
            )
        }
        rule.onRoot().performTouchInput {
            down(Offset(width - 4f, center.y))
            moveBy(Offset(-width * 0.6f, 0f))
            up()
        }
        rule.waitForIdle()
        assertTrue(emitted.isNotEmpty(), "leftward scrub must emit")
        assertTrue(emitted.all { it in 0.5f..5f }, "emissions in range: $emitted")
        assertTrue(emitted.last() < 5f, "dragging left must zoom out: $emitted")
    }

    @Test fun tapStillSelectsChip() {
        var selected: Float? = null
        rule.setContent {
            ZoomBar(
                currentZoom = 1f,
                zoomLevels = levels,
                onZoomSelected = { selected = it },
                zoomRange = 0.5f to 5f,
            )
        }
        rule.onNodeWithText("2x").assertIsDisplayed()
        rule.onNodeWithText("2x").performClick()
        assertEquals(2f, selected)
    }

    @Test fun lockedModesRenderNoZoomBarToScrub() {
        // Panorama/Photosphere: the column hides the bar entirely, and any
        // setZoomRatio that did arrive is ignored by the ViewModel (see
        // PanoramaZoomLockTest), so a scrub cannot move the locked 1x.
        rule.setContent {
            CameraSettingsColumn(
                activeSetting = null,
                availableLenses = emptyList(), selectedLens = null,
                onLensSelected = {}, zoomRatio = 1f,
                availableZoomLevels = levels,
                onZoomSelected = {}, exposureComp = 0f, onExposureComp = {},
                shadows = 0f, onShadows = {}, warmth = 0f, onWarmth = {},
                exposureTimeIndex = 0, onExposureTimeIndex = {},
                blurStrength = 0.5f, onBlurStrength = {},
                manualIsoIndex = 0, isoStops = emptyList(), onManualIsoIndex = {},
                cameraMode = CameraMode.PANORAMA, onSelectSetting = {},
                zoomRange = 0.5f to 5f,
            )
        }
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("2x").fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodesWithText("5x").fetchSemanticsNodes().isEmpty())
    }
}
