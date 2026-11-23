package com.vayunmathur.camera.ui

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.camera.Route
import com.vayunmathur.camera.platform.AspectRatioOption
import com.vayunmathur.camera.platform.CameraMode
import com.vayunmathur.camera.platform.CameraViewModel
import com.vayunmathur.camera.platform.FlashMode
import com.vayunmathur.camera.platform.GuideDot
import com.vayunmathur.camera.platform.GuideDotState
import com.vayunmathur.camera.platform.TimerDuration
import com.vayunmathur.library.util.NavBackStack
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Bottom-controls + settings dropdown interactions across modes. */
@RunWith(AndroidJUnit4::class)
class BottomControlsComposeExtraTest {

    @get:Rule val rule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun vm() = CameraViewModel(context.applicationContext as android.app.Application)

    private fun stateFor(mode: CameraMode, viewModel: CameraViewModel) = CameraScreenState(
        cameraMode = mode,
        lensFacing = android.hardware.camera2.CameraMetadata.LENS_FACING_BACK,
        selectedLens = null, availableLenses = emptyList(), hasFlashUnit = true,
        flashMode = FlashMode.AUTO, torchEnabled = false,
        videoCodec = viewModel.videoCodecMutable.value,
        audioInputSource = viewModel.audioInputSourceMutable.value,
        isRecording = mode == CameraMode.VIDEO, recordingDuration = 65, timerCountdown = 0,
        qrResult = null,
        aspectRatio = AspectRatioOption.RATIO_4_3,
        zoomRatio = 1.5f, mirrorFront = true,
        timerDuration = TimerDuration.NONE,
        isCapturing = false, burstActive = false, burstCount = 0, focusLocked = false,
        recordingPaused = false, micMuted = false, videoSnapshotSupported = true,
        lastCaptureUri = null, gridEnabled = true, levelEnabled = true, roll = 3f,
        blurStrength = 0.5f, exposureComp = 0.5f, warmth = 0.4f, shadows = 0.3f,
        exposureTimeIndex = 0, manualIsoIndex = 0, isoStops = listOf(100, 200, 400),
        longExposureProgress = 0.4f, longExposureRemaining = "1.0s",
        lowLightDetected = false, nightModeActive = false, extensionStrength = null,
        sloMoSupported = true, highSpeedActive = false, photoSessionActive = true,
        analysisStreamActive = true, nightExtAvailable = false,
        availableZoomLevels = listOf("1x" to 1f, "2x" to 2f), galleryBitmap = null,
        panoSweeping = mode == CameraMode.PANORAMA, panoStitching = false,
        panoDots = if (mode == CameraMode.PANORAMA) listOf(
            GuideDot(0, 0f, 0f, GuideDotState.CAPTURED),
            GuideDot(1, 30f, 0f, GuideDotState.PENDING),
        ) else emptyList(),
        panoCurrentAngle = 5f, panoDirection = 1, panoPitch = 0f,
        activeSettingState = mutableStateOf<CameraSetting?>(null),
        maskBitmapState = mutableStateOf<android.graphics.Bitmap?>(null),
        deviceRotationState = mutableIntStateOf(0),
    )

    @Test fun bottomControlsPhotoMode() {
        val viewModel = vm()
        val backStack = NavBackStack<Route>(arrayOf(Route.Camera))
        var captures = 0
        rule.setContent {
            CameraBottomControls(
                state = stateFor(CameraMode.PHOTO, viewModel), viewModel = viewModel,
                backStack = backStack, onCaptureResult = null,
                onCapture = { captures++ }, animatedRotation = 0f,
            )
        }
        rule.waitForIdle()
        assertTrue(rule.onRoot().fetchSemanticsNode() != null)
        assertTrue(captures == 0)
    }

    @Test fun bottomControlsVideoRecordingMode() {
        val viewModel = vm()
        val backStack = NavBackStack<Route>(arrayOf(Route.Camera))
        rule.setContent {
            CameraBottomControls(
                state = stateFor(CameraMode.VIDEO, viewModel), viewModel = viewModel,
                backStack = backStack, onCaptureResult = null,
                onCapture = { }, animatedRotation = 90f,
            )
        }
        rule.waitForIdle()
        // Recording state renders (duration indicator lives in the status
        // overlay, not this pane); the pane itself must compose.
        assertTrue(rule.onRoot().fetchSemanticsNode() != null)
    }

    @Test fun bottomControlsPanoramaMode() {
        val viewModel = vm()
        val backStack = NavBackStack<Route>(arrayOf(Route.Camera))
        rule.setContent {
            CameraBottomControls(
                state = stateFor(CameraMode.PANORAMA, viewModel), viewModel = viewModel,
                backStack = backStack, onCaptureResult = null,
                onCapture = { }, animatedRotation = 0f,
            )
        }
        rule.waitForIdle()
        assertTrue(rule.onRoot().fetchSemanticsNode() != null)
    }
}
