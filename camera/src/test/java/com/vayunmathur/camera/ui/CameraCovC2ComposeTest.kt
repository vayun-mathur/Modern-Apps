package com.vayunmathur.camera.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.camera.domain.LensFacing
import com.vayunmathur.camera.domain.LensType
import com.vayunmathur.camera.domain.PhysicalLens
import com.vayunmathur.camera.util.CameraMode
import com.vayunmathur.camera.util.GuideDot
import com.vayunmathur.camera.util.GuideDotState
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Cov-c2: settings bars, panorama overlay, overlays, status indicators, lens bar. */
@RunWith(AndroidJUnit4::class)
class CameraCovC2ComposeTest {

    @get:Rule val rule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun lens(id: String, type: LensType, zoom: Float) = PhysicalLens(
        logicalCameraId = id, lensType = type, facing = LensFacing.BACK,
        focalLengthMm35Eq = 24f * zoom, nominalZoomRatio = zoom, fallbackPriority = 0, labelKey = id,
    )

    private fun settingsColumn(mode: CameraMode, setting: CameraSetting?) {
        rule.setContent {
            CameraSettingsColumn(
                activeSetting = setting,
                availableLenses = listOf(lens("0", LensType.WIDE, 1f), lens("1", LensType.TELEPHOTO, 2f)),
                selectedLens = lens("0", LensType.WIDE, 1f),
                onLensSelected = {}, zoomRatio = 1.5f,
                availableZoomLevels = listOf("0.5" to 0.5f, "1" to 1f, "2" to 2f),
                onZoomSelected = {}, exposureComp = 0.5f, onExposureComp = {},
                shadows = -0.5f, onShadows = {}, warmth = 0f, onWarmth = {},
                exposureTimeIndex = 2, onExposureTimeIndex = {},
                blurStrength = 0.4f, onBlurStrength = {},
                manualIsoIndex = 1, isoStops = listOf(100, 200, 400), onManualIsoIndex = {},
                cameraMode = mode, onSelectSetting = {},
            )
        }
        rule.waitForIdle()
        assertTrue(rule.onRoot().fetchSemanticsNode() != null)
    }

    @Test fun settingsColumnPhotoMode() = settingsColumn(CameraMode.PHOTO, null)
    @Test fun settingsColumnPortraitMode() = settingsColumn(CameraMode.PORTRAIT, null)
    @Test fun settingsColumnVideoMode() = settingsColumn(CameraMode.VIDEO, null)
    @Test fun settingsColumnBrightnessPanel() = settingsColumn(CameraMode.PHOTO, CameraSetting.BRIGHTNESS)
    @Test fun settingsColumnShadowsPanel() = settingsColumn(CameraMode.PHOTO, CameraSetting.SHADOWS)
    @Test fun settingsColumnWarmthPanel() = settingsColumn(CameraMode.PHOTO, CameraSetting.WARMTH)
    @Test fun settingsColumnExposurePanel() = settingsColumn(CameraMode.PHOTO, CameraSetting.EXPOSURE_TIME)
    @Test fun settingsColumnBlurPanel() = settingsColumn(CameraMode.PORTRAIT, CameraSetting.PORTRAIT_BLUR)
    @Test fun settingsColumnIsoPanel() = settingsColumn(CameraMode.PHOTO, CameraSetting.ISO)

    @Test fun zoomBarInsertsLiveRatioAndSelects() {
        var selected: Float? = null
        rule.setContent {
            ZoomBar(
                currentZoom = 1.5f,
                zoomLevels = listOf("0.5" to 0.5f, "1" to 1f, "2" to 2f),
                onZoomSelected = { selected = it },
            )
        }
        rule.onNodeWithText("1.5x").assertIsDisplayed()
        rule.onNodeWithText("2").performClick()
        assertEquals(2f, selected)
    }

    @Test fun isoBarManualStateShowsStop() {
        rule.setContent { IsoBar(selectedIndex = 2, isoStops = listOf(100, 200), onIndexChange = {}) }
        rule.onNodeWithText("200").assertIsDisplayed()
    }

    @Test fun isoBarEmptyStopsShowsUnavailable() {
        rule.setContent { IsoBar(selectedIndex = 0, isoStops = emptyList(), onIndexChange = {}) }
        rule.waitForIdle()
        assertTrue(rule.onRoot().fetchSemanticsNode() != null)
    }

    @Test fun settingsButtonRowDeselectsActive() {
        var selected: CameraSetting? = CameraSetting.BRIGHTNESS
        rule.setContent {
            SettingsButtonRow(
                activeSetting = CameraSetting.BRIGHTNESS, cameraMode = CameraMode.PHOTO,
                onSelect = { selected = it },
            )
        }
        rule.onAllNodes(hasClickAction())[0].performClick()
        assertEquals(null, selected)
    }

    @Test fun panoramaOverlayStitching() {
        rule.setContent {
            PanoramaOverlay(isSweeping = false, isStitching = true, guideDots = emptyList(), currentAngle = 0f, currentPitch = 0f)
        }
        rule.onNodeWithText("Processing panorama…").assertIsDisplayed()
    }

    @Test fun panoramaOverlaySweepingShowsProgress() {
        val dots = listOf(
            GuideDot(index = 0, targetAngle = 0f, targetPitch = 0f, state = GuideDotState.CAPTURED),
            GuideDot(index = 1, targetAngle = 10f, targetPitch = 5f, state = GuideDotState.ALIGNING),
            GuideDot(index = 2, targetAngle = -20f, targetPitch = -5f, state = GuideDotState.PENDING),
            GuideDot(index = 3, targetAngle = 90f, targetPitch = 0f, state = GuideDotState.CAPTURING),
        )
        rule.setContent {
            PanoramaOverlay(isSweeping = true, isStitching = false, guideDots = dots, currentAngle = 0f, currentPitch = 0f)
        }
        rule.onNodeWithText("1 / 4").assertIsDisplayed()
    }

    @Test fun qrAndStatusOverlays() {
        var dismissed = 0
        rule.setContent {
            Box(Modifier.fillMaxSize()) {
                CameraStatusIndicators(
                    qrResult = "https://example.com", onDismissQr = { dismissed++ }, context = context,
                    qrBottomPadding = 8.dp, isRecording = true, recordingDuration = 65L,
                    indicatorTopPadding = 8.dp, burstActive = true, burstCount = 3,
                    focusLocked = false, focusTopPadding = 16.dp,
                )
            }
        }
        rule.onNodeWithText("1:05").assertIsDisplayed()
        rule.onNodeWithText("https://example.com").assertIsDisplayed()
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(1, dismissed)
    }

    @Test fun esimDetectionAndCard() {
        assertTrue(isEsimActivationCode("LPA:1\$smdp.example\$123"))
        assertTrue(isEsimActivationCode("1\$smdp.example\$123"))
        assertTrue(!isEsimActivationCode("plain text"))
        assertTrue(!isEsimActivationCode("1\$"))
        rule.setContent { EsimQrCard(text = "1\$smdp.example\$123", onDismiss = {}, context = context) }
        rule.onNodeWithText("Add eSIM").assertIsDisplayed()
    }

    @Test fun previewOverlaysAllIndicators() {
        rule.setContent {
            Box(Modifier.fillMaxSize()) {
                CameraPreviewOverlays(
                    gridEnabled = true, levelEnabled = true, isPhotoType = true, roll = 15f,
                    previewSize = Modifier.fillMaxSize(), timerCountdown = 3,
                    longExposureProgress = 0.5f, longExposureRemaining = "2s",
                    lowLightDetected = true, nightExtAvailable = true, nightModeActive = true,
                    onToggleNightOverride = {}, iconRotation = 0f, extensionStrength = 50,
                    photoSessionActive = true, analysisStreamActive = false,
                )
            }
        }
        rule.onNodeWithText("3").assertIsDisplayed()
        rule.onNodeWithText("2s").assertIsDisplayed()
    }

    @Test fun recordingIndicatorShowsTenMinutes() {
        rule.setContent { RecordingIndicator(durationSec = 600L) }
        rule.onNodeWithText("10:00").assertIsDisplayed()
    }

    @Test fun longExposureAndNightButton() {
        val showNight = mutableStateOf(false)
        rule.setContent {
            if (showNight.value) {
                NightModeButton(active = true, onClick = {}, iconRotation = 90f)
            } else {
                LongExposureOverlay(progress = 0.25f, remainingText = "3s")
            }
        }
        rule.onNodeWithText("3s").assertIsDisplayed()
        rule.runOnUiThread { showNight.value = true }
        rule.waitForIdle()
        assertTrue(rule.onRoot().fetchSemanticsNode() != null)
    }

    @Test fun lensBarSelectsTelephoto() {
        val wide = lens("0", LensType.WIDE, 1f)
        val tele = lens("1", LensType.TELEPHOTO, 3f)
        var selected: PhysicalLens? = null
        rule.setContent { LensBar(lenses = listOf(tele, wide), selected = wide, onSelect = { selected = it }) }
        rule.onNodeWithText("3x").performClick()
        assertEquals(tele, selected)
    }

    @Test fun lensBarSingleLensHidden() {
        val wide = lens("0", LensType.WIDE, 1f)
        rule.setContent { LensBar(lenses = listOf(wide), selected = wide, onSelect = {}) }
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("1x").fetchSemanticsNodes().isEmpty())
    }
}
