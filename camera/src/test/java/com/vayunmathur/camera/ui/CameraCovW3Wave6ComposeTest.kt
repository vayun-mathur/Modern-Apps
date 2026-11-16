package com.vayunmathur.camera.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.camera.util.CameraMode
import com.vayunmathur.camera.util.CameraViewModel
import com.vayunmathur.camera.util.GuideDot
import com.vayunmathur.camera.util.GuideDotState
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Cov-w3 wave 6: panorama overlay canvas states and the preview box directly. */
@RunWith(AndroidJUnit4::class)
class CameraCovW3Wave6ComposeTest {
    @get:Rule val rule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun vm() = CameraViewModel(context.applicationContext as android.app.Application)

    private fun dots(): List<GuideDot> = listOf(
        GuideDot(0, 0f, 0f, GuideDotState.CAPTURED),
        GuideDot(1, 10f, 5f, GuideDotState.CAPTURED),
        GuideDot(2, -15f, -10f, GuideDotState.ALIGNING),
        GuideDot(3, 20f, 0f, GuideDotState.CAPTURING),
        GuideDot(4, -30f, 20f, GuideDotState.PENDING),
        GuideDot(5, 90f, 0f, GuideDotState.PENDING), // outside the drawn FOV window
        GuideDot(6, 0f, 80f, GuideDotState.CAPTURED), // outside vertically
    )

    @Test fun panoramaOverlaySweepingAllDotStates() {
        rule.setContent {
            PanoramaOverlay(
                isSweeping = true, isStitching = false,
                guideDots = dots(), currentAngle = 5f, currentPitch = 2f,
                modifier = Modifier.fillMaxSize(),
            )
        }
        rule.waitForIdle()
        assertTrue(rule.onRoot().fetchSemanticsNode() != null)
    }

    @Test fun panoramaOverlayStitchingAndIdle() {
        rule.setContent {
            PanoramaOverlay(
                isSweeping = false, isStitching = true,
                guideDots = dots(), currentAngle = 0f, currentPitch = 0f,
            )
        }
        rule.waitForIdle()
    }

    @Test fun panoramaOverlayIdleNotSweeping() {
        rule.setContent {
            PanoramaOverlay(
                isSweeping = false, isStitching = false,
                guideDots = emptyList(), currentAngle = 0f, currentPitch = 0f,
            )
        }
        rule.waitForIdle()
    }

    private fun stateFor(mode: CameraMode, viewModel: CameraViewModel): CameraScreenState {
        // Build the holder through the production remember-helper by composing
        // a probe is overkill; instead drive the VM and capture the state via
        // rememberCameraScreenState inside the PreviewBox composition itself.
        return CameraScreenState(
            cameraMode = mode,
            lensFacing = androidx.camera.core.CameraSelector.LENS_FACING_BACK,
            selectedLens = null,
            availableLenses = emptyList(),
            hasFlashUnit = true,
            flashMode = com.vayunmathur.camera.util.FlashMode.AUTO,
            torchEnabled = false,
            videoCodec = viewModel.videoCodecMutable.value,
            audioInputSource = viewModel.audioInputSourceMutable.value,
            isRecording = mode == CameraMode.VIDEO,
            recordingDuration = 12,
            timerCountdown = 0,
            qrResult = if (mode == CameraMode.PHOTO) "covw3-qr" else null,
            aspectRatio = com.vayunmathur.camera.util.AspectRatioOption.RATIO_4_3,
            zoomRatio = 1.5f,
            mirrorFront = true,
            timerDuration = com.vayunmathur.camera.util.TimerDuration.NONE,
            isCapturing = false,
            burstActive = false,
            burstCount = 0,
            focusLocked = true,
            recordingPaused = false,
            micMuted = false,
            videoSnapshotSupported = true,
            lastCaptureUri = null,
            gridEnabled = true,
            levelEnabled = true,
            roll = 3f,
            blurStrength = 0.5f,
            exposureComp = 0.5f,
            warmth = 0.4f,
            shadows = 0.3f,
            exposureTimeIndex = 0,
            manualIsoIndex = 0,
            isoStops = listOf(100, 200, 400),
            longExposureProgress = 0.4f,
            longExposureRemaining = "1.0s",
            lowLightDetected = true,
            nightModeActive = false,
            extensionStrength = 50,
            sloMoSupported = true,
            highSpeedActive = false,
            photoSessionActive = true,
            analysisStreamActive = true,
            nightExtAvailable = false,
            surfaceRequest = null,
            availableZoomLevels = listOf("1x" to 1f, "2x" to 2f),
            galleryBitmap = null,
            panoSweeping = mode == CameraMode.PANORAMA,
            panoStitching = false,
            panoDots = if (mode == CameraMode.PANORAMA) dots() else emptyList(),
            panoCurrentAngle = 5f,
            panoDirection = 1,
            panoPitch = 0f,
            activeSettingState = mutableStateOf<CameraSetting?>(null),
            maskBitmapState = mutableStateOf<android.graphics.Bitmap?>(null),
            deviceRotationState = mutableIntStateOf(0),
        )
    }

    @Test fun previewBoxPhotoWithOverlays() {
        val viewModel = vm()
        val state = stateFor(CameraMode.PHOTO, viewModel)
        rule.setContent {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                CameraPreviewBox(state = state, viewModel = viewModel, animatedRotation = 0f)
            }
        }
        rule.waitForIdle()
        assertTrue(rule.onRoot().fetchSemanticsNode() != null)
    }

    @Test fun previewBoxPanoramaSweeping() {
        val viewModel = vm()
        val state = stateFor(CameraMode.PANORAMA, viewModel)
        rule.setContent {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                CameraPreviewBox(state = state, viewModel = viewModel, animatedRotation = 90f)
            }
        }
        rule.waitForIdle()
    }

    @Test fun previewBoxVideoRecording() {
        val viewModel = vm()
        val state = stateFor(CameraMode.VIDEO, viewModel)
        rule.setContent {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                CameraPreviewBox(state = state, viewModel = viewModel, animatedRotation = 180f)
            }
        }
        rule.waitForIdle()
    }
}
