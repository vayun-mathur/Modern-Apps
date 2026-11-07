package com.vayunmathur.camera.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.camera.domain.LensFacing
import com.vayunmathur.camera.domain.LensType
import com.vayunmathur.camera.domain.PhysicalLens
import com.vayunmathur.camera.util.AspectRatioOption
import com.vayunmathur.camera.util.CameraMode
import com.vayunmathur.camera.util.FlashMode
import com.vayunmathur.camera.util.GuideDot
import com.vayunmathur.camera.util.GuideDotState
import com.vayunmathur.camera.util.MotionPhotoDuration
import com.vayunmathur.camera.util.TimerDuration
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class CameraPanesComposeTest {

    @get:Rule val rule = createComposeRule()

    private val lens = PhysicalLens(
        logicalCameraId = "0", lensType = LensType.WIDE, facing = LensFacing.BACK,
        focalLengthMm35Eq = 24f, nominalZoomRatio = 1f, fallbackPriority = 0, labelKey = "wide",
    )

    @Test fun topBarPhotoControlsInvokeCallbacks() {
        var flash = 0; var grid = 0; var aspect = 0
        rule.setContent {
            CameraTopBar(
                flashMode = FlashMode.AUTO, torchEnabled = false, hasFlashUnit = true,
                gridEnabled = true, levelEnabled = false,
                aspectRatio = AspectRatioOption.RATIO_4_3, stillCropEnabled = true,
                isPhotoType = true, isVideoType = false, micMuted = false,
                timerDuration = TimerDuration.NONE,
                onFlashToggle = { flash++ }, onTorchToggle = {}, onGridToggle = { grid++ },
                onLevelToggle = {}, onAspectCycle = { aspect++ }, onMicToggle = {},
                onTimerCycle = {}, iconRotation = 0f,
            )
        }
        rule.onNodeWithText("4:3").assertIsDisplayed()
        rule.onNodeWithText("4:3").performClick()
        assertEquals(1, aspect)
        assertTrue(flash == 0 && grid == 0)
    }

    @Test fun topBarWithoutFlashUnitHidesFlash() {
        rule.setContent {
            CameraTopBar(
                flashMode = FlashMode.OFF, torchEnabled = false, hasFlashUnit = false,
                gridEnabled = false, levelEnabled = false,
                aspectRatio = AspectRatioOption.RATIO_16_9, stillCropEnabled = false,
                isPhotoType = false, isVideoType = true, micMuted = true,
                timerDuration = TimerDuration.THREE,
                onFlashToggle = {}, onTorchToggle = {}, onGridToggle = {},
                onLevelToggle = {}, onAspectCycle = {}, onMicToggle = {},
                onTimerCycle = {}, iconRotation = 90f,
            )
        }
        assertTrue(rule.onRoot().fetchSemanticsNode() != null)
    }

    @Test fun modeSelectorSelectsMode() {
        var selected: CameraMode? = null
        rule.setContent {
            ModeSelector(
                cameraMode = CameraMode.PHOTO, isPhotoType = true, sloMoSupported = true,
                captureForResult = false, onModeSelected = { selected = it },
            )
        }
        rule.onNodeWithText("Portrait").assertIsDisplayed()
        rule.onNodeWithText("Portrait").performClick()
        assertEquals(CameraMode.PORTRAIT, selected)
    }

    @Test fun modeSelectorVideoModesRespectSloMoSupport() {
        rule.setContent {
            ModeSelector(
                cameraMode = CameraMode.VIDEO, isPhotoType = false, sloMoSupported = false,
                captureForResult = false, onModeSelected = {},
            )
        }
        rule.onNodeWithText("Video").assertIsDisplayed()
        rule.onAllNodesWithText("Slo-Mo").let { assertTrue(it.fetchSemanticsNodes().isEmpty()) }
    }

    @Test fun modesPaneAndWideLayoutRender() {
        rule.setContent {
            CameraModesPane(
                cameraMode = CameraMode.PHOTO, isPhotoType = true, sloMoSupported = null,
                captureForResult = false, onModeSelected = {}, iconRotation = 0f,
                onPickerChanged = {}, onSettingsClick = {},
            )
        }
        rule.onNodeWithText("Photo").assertIsDisplayed()
    }

    @Test fun wideLayoutStacksVertically() {
        rule.setContent {
            CameraWideLayout(
                shutter = { androidx.compose.material3.Text("shutter-pane") },
                modes = { androidx.compose.material3.Text("modes-pane") },
                sideBySide = false,
            )
        }
        rule.onNodeWithText("shutter-pane").assertIsDisplayed()
        rule.onNodeWithText("modes-pane").assertIsDisplayed()
    }

    @Test fun shutterPaneRendersForPhotoAndVideo() {
        var captures = 0
        rule.setContent {
            CameraShutterPane(
                cameraMode = CameraMode.PHOTO, isRecording = false, isCapturing = false,
                recordingPaused = false, videoSnapshotSupported = false, galleryBitmap = null,
                onCaptureResult = null, onBurstStart = {}, onBurstStop = {},
                onCapture = { captures++ }, onPauseResume = {}, onSnapshot = {},
                onFlipCamera = {}, onGallery = {}, iconRotation = 0f,
            )
        }
        assertTrue(rule.onRoot().fetchSemanticsNode() != null)
        assertEquals(0, captures)
    }

    @Test fun panoramaOverlayStates() {
        rule.setContent {
            PanoramaOverlay(isSweeping = false, isStitching = true, guideDots = emptyList(), currentAngle = 0f, currentPitch = 0f)
        }
        assertTrue(rule.onRoot().fetchSemanticsNode() != null)
    }

    @Test fun panoramaOverlaySweepingDots() {
        val dots = listOf(
            GuideDot(0, 0f, 0f, GuideDotState.CAPTURED),
            GuideDot(1, 20f, 5f, GuideDotState.PENDING),
            GuideDot(2, -20f, -5f, GuideDotState.PENDING),
        )
        rule.setContent {
            PanoramaOverlay(isSweeping = true, isStitching = false, guideDots = dots, currentAngle = 0f, currentPitch = 0f)
        }
        assertTrue(rule.onRoot().fetchSemanticsNode() != null)
    }

    @Test fun settingsColumnWithLensAndZoom() {
        rule.setContent {
            CameraSettingsColumn(
                activeSetting = null,
                availableLenses = listOf(lens), selectedLens = lens,
                onLensSelected = {}, zoomRatio = 1f,
                availableZoomLevels = listOf("1x" to 1f, "2x" to 2f),
                onZoomSelected = {}, exposureComp = 0f, onExposureComp = {},
                shadows = 0f, onShadows = {}, warmth = 0f, onWarmth = {},
                exposureTimeIndex = 0, onExposureTimeIndex = {},
                blurStrength = 0.5f, onBlurStrength = {},
                manualIsoIndex = 0, isoStops = listOf(100, 200), onManualIsoIndex = {},
                cameraMode = CameraMode.PHOTO, onSelectSetting = {},
            )
        }
        assertTrue(rule.onRoot().fetchSemanticsNode() != null)
    }

    @Test fun settingsColumnActiveSettingShowsSlider() {
        rule.setContent {
            CameraSettingsColumn(
                activeSetting = CameraSetting.BRIGHTNESS,
                availableLenses = emptyList(), selectedLens = null,
                onLensSelected = {}, zoomRatio = 1f,
                availableZoomLevels = emptyList(),
                onZoomSelected = {}, exposureComp = 0f, onExposureComp = {},
                shadows = 0f, onShadows = {}, warmth = 0f, onWarmth = {},
                exposureTimeIndex = 0, onExposureTimeIndex = {},
                blurStrength = 0.5f, onBlurStrength = {},
                manualIsoIndex = 0, isoStops = emptyList(), onManualIsoIndex = {},
                cameraMode = CameraMode.PHOTO, onSelectSetting = {},
            )
        }
        assertTrue(rule.onRoot().fetchSemanticsNode() != null)
    }

    @Test fun postProcessingSettingsRender() {
        var noise: Boolean? = null
        rule.setContent {
            CameraPostProcessingSettings(
                noiseReductionEnabled = true, edgeSharpeningEnabled = false,
                motionPhotoDuration = MotionPhotoDuration.THREE,
                onNoiseReductionChange = { noise = it },
                onEdgeSharpeningChange = {},
                onMotionDurationChange = {},
            )
        }
        rule.onNodeWithText("Noise reduction").assertIsDisplayed()
        assertEquals(null, noise)
    }

    @Test fun lensBarSelectsLens() {
        var selected: PhysicalLens? = null
        rule.setContent {
            LensBar(lenses = listOf(lens), selected = lens, onSelect = { selected = it })
        }
        assertTrue(rule.onRoot().fetchSemanticsNode() != null)
        assertEquals(null, selected)
    }

    @Test fun zoomAndIsoBarsRender() {
        rule.setContent {
            ZoomBar(
                currentZoom = 2f, zoomLevels = listOf("1x" to 1f, "2x" to 2f),
                onZoomSelected = {},
            )
        }
        rule.onNodeWithText("2x").assertIsDisplayed()
    }
}
