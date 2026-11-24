package com.vayunmathur.camera.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.camera.domain.LensFacing
import com.vayunmathur.camera.domain.LensType
import com.vayunmathur.camera.domain.PhysicalLens
import com.vayunmathur.camera.platform.CameraMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * Panorama/Photosphere zoom lock, UI side: no ZOOM setting is offered,
 * and the settings column renders neither the zoom bar nor the lens
 * bar (a physical-lens pick would change the native field of view,
 * which is the zoom change the lock forbids).
 */
@RunWith(AndroidJUnit4::class)
class PanoramaZoomLockComposeTest {

    @get:Rule val rule = createComposeRule()

    private val lenses = listOf(
        PhysicalLens(
            logicalCameraId = "0", lensType = LensType.ULTRA_WIDE, facing = LensFacing.BACK,
            focalLengthMm35Eq = 13f, nominalZoomRatio = 0.5f, fallbackPriority = 1,
            labelKey = "ultrawide",
        ),
        PhysicalLens(
            logicalCameraId = "0", lensType = LensType.WIDE, facing = LensFacing.BACK,
            focalLengthMm35Eq = 24f, nominalZoomRatio = 1f, fallbackPriority = 0,
            labelKey = "wide",
        ),
    )

    private fun column(mode: CameraMode, setting: CameraSetting? = null) {
        rule.setContent {
            CameraSettingsColumn(
                activeSetting = setting,
                availableLenses = lenses, selectedLens = lenses[1],
                onLensSelected = {}, zoomRatio = 3f,
                availableZoomLevels = listOf("1x" to 1f, "2x" to 2f, "5x" to 5f),
                onZoomSelected = {}, exposureComp = 0f, onExposureComp = {},
                shadows = 0f, onShadows = {}, warmth = 0f, onWarmth = {},
                exposureTimeIndex = 0, onExposureTimeIndex = {},
                blurStrength = 0.5f, onBlurStrength = {},
                manualIsoIndex = 0, isoStops = listOf(100, 200), onManualIsoIndex = {},
                cameraMode = mode, onSelectSetting = {},
            )
        }
        rule.waitForIdle()
    }

    @Test
    fun settingsForModeExcludesZoomOnlyInLockedModes() {
        for (mode in listOf(CameraMode.PANORAMA, CameraMode.PHOTOSPHERE)) {
            assertFalse(CameraSetting.ZOOM in settingsForMode(mode), "ZOOM offered in $mode")
            assertEquals(emptyList(), settingsForMode(mode), "settings offered in $mode")
            // A ZOOM pane left open from Photo resolves to no pane there.
            assertNull(effectiveSettingForMode(CameraSetting.ZOOM, mode))
        }
        // Every other mode keeps its zoom entry, unchanged.
        for (mode in CameraMode.entries - CameraMode.PANORAMA - CameraMode.PHOTOSPHERE) {
            assertTrue(CameraSetting.ZOOM in settingsForMode(mode), "ZOOM missing in $mode")
        }
        assertEquals(
            CameraSetting.ZOOM,
            effectiveSettingForMode(CameraSetting.ZOOM, CameraMode.PHOTO),
        )
    }

    @Test
    fun panoramaShowsNeitherZoomBarNorLensBar() {
        column(CameraMode.PANORAMA)
        assertTrue(rule.onAllNodesWithText("2x").fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodesWithText("5x").fetchSemanticsNodes().isEmpty())
        // "1x" would come from either bar (zoom pill or wide-lens pill).
        assertTrue(rule.onAllNodesWithText("1x").fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodesWithText(".5").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun photosphereShowsNeitherZoomBarNorLensBar() {
        column(CameraMode.PHOTOSPHERE, CameraSetting.ZOOM)
        assertTrue(rule.onAllNodesWithText("2x").fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodesWithText("1x").fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodesWithText(".5").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun lockedModeSettingsRowIsEmpty() {
        rule.setContent {
            SettingsButtonRow(
                activeSetting = null, cameraMode = CameraMode.PANORAMA, onSelect = {},
            )
        }
        rule.waitForIdle()
        assertTrue(rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun photoStillShowsZoomBar() {
        column(CameraMode.PHOTO)
        rule.onNodeWithText("2x").assertIsDisplayed()
    }
}
