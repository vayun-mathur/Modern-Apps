package com.vayunmathur.camera.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.camera.platform.CameraMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith

/** Video-mode settings: no stale photo panes, zoom + exposure available. */
@RunWith(AndroidJUnit4::class)
class CameraVideoSettingsComposeTest {

    @get:Rule val rule = createComposeRule()

    private fun column(
        mode: CameraMode,
        setting: CameraSetting?,
        onZoom: (Float) -> Unit = {},
        onSelect: (CameraSetting?) -> Unit = {},
    ) {
        rule.setContent {
            CameraSettingsColumn(
                activeSetting = setting,
                availableLenses = emptyList(), selectedLens = null,
                onLensSelected = {}, zoomRatio = 1f,
                availableZoomLevels = listOf("1x" to 1f, "2x" to 2f),
                onZoomSelected = onZoom, exposureComp = 0.5f, onExposureComp = {},
                shadows = -0.5f, onShadows = {}, warmth = 0f, onWarmth = {},
                exposureTimeIndex = 0, onExposureTimeIndex = {},
                blurStrength = 0.5f, onBlurStrength = {},
                manualIsoIndex = 0, isoStops = listOf(100, 200), onManualIsoIndex = {},
                cameraMode = mode, onSelectSetting = onSelect,
            )
        }
        rule.waitForIdle()
    }

    @Test fun settingsApplicabilityPerMode() {
        assertEquals(
            listOf(
                CameraSetting.BRIGHTNESS, CameraSetting.SHADOWS, CameraSetting.WARMTH,
                CameraSetting.EXPOSURE_TIME, CameraSetting.ISO, CameraSetting.ZOOM,
            ),
            settingsForMode(CameraMode.PHOTO),
        )
        assertEquals(
            listOf(CameraSetting.BRIGHTNESS, CameraSetting.ZOOM),
            settingsForMode(CameraMode.VIDEO),
        )
        assertEquals(settingsForMode(CameraMode.VIDEO), settingsForMode(CameraMode.TIMELAPSE))
        assertEquals(settingsForMode(CameraMode.VIDEO), settingsForMode(CameraMode.CINEMATIC))
        assertTrue(CameraSetting.PORTRAIT_BLUR in settingsForMode(CameraMode.PORTRAIT))
        assertTrue(CameraSetting.SHADOWS !in settingsForMode(CameraMode.VIDEO))
        // Stale photo-only panes resolve to the zoom bar in video; shared panes survive.
        assertNull(effectiveSettingForMode(CameraSetting.SHADOWS, CameraMode.VIDEO))
        assertNull(effectiveSettingForMode(CameraSetting.ISO, CameraMode.VIDEO))
        assertNull(effectiveSettingForMode(CameraSetting.EXPOSURE_TIME, CameraMode.VIDEO))
        assertEquals(
            CameraSetting.BRIGHTNESS,
            effectiveSettingForMode(CameraSetting.BRIGHTNESS, CameraMode.VIDEO),
        )
        assertEquals(
            CameraSetting.ZOOM,
            effectiveSettingForMode(CameraSetting.ZOOM, CameraMode.VIDEO),
        )
    }

    @Test fun switchingPhotoShadowsPanelToVideoShowsZoomAndAppliesIt() {
        var mode by mutableStateOf(CameraMode.PHOTO)
        var zoom: Float? = null
        // The stored pane stays SHADOWS (what the old mode switch left behind);
        // rendering in VIDEO must not show the stale slider.
        rule.setContent {
            CameraSettingsColumn(
                activeSetting = CameraSetting.SHADOWS,
                availableLenses = emptyList(), selectedLens = null,
                onLensSelected = {}, zoomRatio = 1f,
                availableZoomLevels = listOf("1x" to 1f, "2x" to 2f),
                onZoomSelected = { zoom = it }, exposureComp = 0f, onExposureComp = {},
                shadows = -0.5f, onShadows = {}, warmth = 0f, onWarmth = {},
                exposureTimeIndex = 0, onExposureTimeIndex = {},
                blurStrength = 0.5f, onBlurStrength = {},
                manualIsoIndex = 0, isoStops = listOf(100, 200), onManualIsoIndex = {},
                cameraMode = mode, onSelectSetting = {},
            )
        }
        rule.onNodeWithText("-0.5").assertIsDisplayed()
        rule.runOnIdle { mode = CameraMode.VIDEO }
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("-0.5").fetchSemanticsNodes().isEmpty())
        rule.onNodeWithText("2x").assertIsDisplayed()
        rule.onNodeWithText("2x").performClick()
        assertEquals(2f, zoom)
    }

    @Test fun videoModeShowsBrightnessSlider() {
        column(CameraMode.VIDEO, CameraSetting.BRIGHTNESS)
        // Exposure-compensation slider (BRIGHTNESS) is live for video.
        rule.onNodeWithText("+0.5").assertIsDisplayed()
    }

    @Test fun videoModeZoomSettingShowsZoomBar() {
        column(CameraMode.VIDEO, CameraSetting.ZOOM)
        rule.onNodeWithText("2x").assertIsDisplayed()
    }

    @Test fun videoSettingsRowOffersOnlyBrightnessAndZoom() {
        rule.setContent {
            SettingsButtonRow(
                activeSetting = null, cameraMode = CameraMode.VIDEO, onSelect = {},
            )
        }
        rule.waitForIdle()
        assertEquals(2, rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size)
    }
}
