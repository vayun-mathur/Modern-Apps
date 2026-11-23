package com.vayunmathur.camera.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
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

/** Preview-box state variations, gallery launch, panorama canvas drawing. */
@RunWith(AndroidJUnit4::class)
class PreviewGalleryComposeExtraTest {

    @get:Rule val rule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun vm() = CameraViewModel(context.applicationContext as android.app.Application)

    private fun stateFor(mode: CameraMode, viewModel: CameraViewModel, mask: Bitmap? = null) = CameraScreenState(
        cameraMode = mode,
        lensFacing = android.hardware.camera2.CameraMetadata.LENS_FACING_BACK,
        selectedLens = null, availableLenses = emptyList(), hasFlashUnit = true,
        flashMode = FlashMode.AUTO, torchEnabled = false,
        videoCodec = viewModel.videoCodecMutable.value,
        audioInputDevice = viewModel.audioInputDeviceMutable.value,
        isRecording = mode == CameraMode.VIDEO, recordingDuration = 3, timerCountdown = 0,
        qrResult = null,
        aspectRatio = AspectRatioOption.RATIO_16_9,
        zoomRatio = 1f, mirrorFront = false,
        timerDuration = TimerDuration.NONE,
        isCapturing = false, burstActive = true, burstCount = 2, focusLocked = true,
        recordingPaused = false, micMuted = true, videoSnapshotSupported = false,
        lastCaptureUri = null, gridEnabled = true, levelEnabled = true, roll = 10f,
        blurStrength = 0.8f, exposureComp = 0f, warmth = 0f, shadows = 0f,
        exposureTimeIndex = 0, manualIsoIndex = 0, isoStops = listOf(100),
        longExposureProgress = 0.5f, longExposureRemaining = "2.0s",
        lowLightDetected = true, nightModeActive = true, extensionStrength = 50,
        sloMoSupported = true, highSpeedActive = false, photoSessionActive = true,
        analysisStreamActive = true, nightExtAvailable = true,
        availableZoomLevels = listOf("1x" to 1f), galleryBitmap = null,
        panoSweeping = mode == CameraMode.PANORAMA, panoStitching = false,
        panoDots = listOf(
            GuideDot(0, 0f, 0f, GuideDotState.CAPTURED),
            GuideDot(1, 30f, 10f, GuideDotState.ALIGNING),
            GuideDot(2, -30f, -10f, GuideDotState.PENDING),
        ),
        panoCurrentAngle = 3f, panoDirection = 1, panoPitch = 2f,
        activeSettingState = mutableStateOf<CameraSetting?>(null),
        maskBitmapState = mutableStateOf(mask),
        deviceRotationState = mutableIntStateOf(0),
    )

    @Test fun previewBoxPortraitWithMaskAndIndicators() {
        val viewModel = vm()
        val mask = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        rule.setContent {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                CameraPreviewBox(state = stateFor(CameraMode.PORTRAIT, viewModel, mask), viewModel = viewModel, animatedRotation = 0f)
            }
        }
        rule.waitForIdle()
        assertTrue(rule.onRoot().fetchSemanticsNode() != null)
    }

    @Test fun previewBoxPanoramaSweeping() {
        val viewModel = vm()
        rule.setContent {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                CameraPreviewBox(state = stateFor(CameraMode.PANORAMA, viewModel), viewModel = viewModel, animatedRotation = 0f)
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("1 / 3", substring = true).assertExists()
    }

    @Test fun panoramaOverlayCanvasActuallyDraws() {
        val dots = listOf(
            GuideDot(0, 0f, 0f, GuideDotState.CAPTURED),
            GuideDot(1, 15f, 5f, GuideDotState.ALIGNING),
            GuideDot(2, -15f, -5f, GuideDotState.CAPTURING),
            GuideDot(3, 45f, 20f, GuideDotState.PENDING),
        )
        rule.setContent {
            PanoramaOverlay(
                isSweeping = true, isStitching = false,
                guideDots = dots, currentAngle = 1f, currentPitch = 1f,
            )
        }
        rule.waitForIdle()
        // captureToImage forces a real draw pass, executing the canvas lambda.
        val image = rule.onRoot().captureToImage()
        assertTrue(image.width > 0 && image.height > 0)
    }

    @Test fun bottomControlsGalleryClickIsGraceful() {
        val viewModel = vm()
        val backStack = NavBackStack<Route>(arrayOf(Route.Camera))
        rule.setContent {
            CameraBottomControls(
                state = stateFor(CameraMode.PHOTO, viewModel), viewModel = viewModel,
                backStack = backStack, onCaptureResult = null,
                onCapture = { }, animatedRotation = 0f,
            )
        }
        rule.waitForIdle()
        // Click every clickable control (gallery, shutter, mode switch):
        // all must be graceful with no camera/gallery present.
        val nodes = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        assertTrue(nodes.isNotEmpty())
        for (i in nodes.indices) {
            rule.onAllNodes(hasClickAction())[i].performClick()
            rule.waitForIdle()
        }
    }
}
