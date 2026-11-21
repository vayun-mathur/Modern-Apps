package com.vayunmathur.camera.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.camera.platform.AspectRatioOption
import com.vayunmathur.camera.platform.CameraMode
import com.vayunmathur.camera.platform.CameraViewModel
import com.vayunmathur.camera.platform.FlashMode
import com.vayunmathur.camera.platform.TimerDuration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Bind-failure preview: ErrorState with a working manual retry, not black. */
@RunWith(AndroidJUnit4::class)
class CameraBindFailedComposeTest {
    @get:Rule val rule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun vm() = CameraViewModel(context.applicationContext as android.app.Application)

    private fun failedState(viewModel: CameraViewModel) = CameraScreenState(
        cameraMode = CameraMode.PHOTO,
        lensFacing = androidx.camera.core.CameraSelector.LENS_FACING_BACK,
        selectedLens = null,
        availableLenses = emptyList(),
        hasFlashUnit = true,
        flashMode = FlashMode.OFF,
        torchEnabled = false,
        videoCodec = viewModel.videoCodecMutable.value,
        audioInputSource = viewModel.audioInputSourceMutable.value,
        isRecording = false,
        recordingDuration = 0,
        timerCountdown = 0,
        qrResult = null,
        aspectRatio = AspectRatioOption.RATIO_4_3,
        zoomRatio = 1f,
        mirrorFront = true,
        timerDuration = TimerDuration.NONE,
        isCapturing = false,
        burstActive = false,
        burstCount = 0,
        focusLocked = false,
        recordingPaused = false,
        micMuted = false,
        videoSnapshotSupported = false,
        lastCaptureUri = null,
        gridEnabled = false,
        levelEnabled = false,
        roll = 0f,
        blurStrength = 0.5f,
        exposureComp = 0f,
        warmth = 0f,
        shadows = 0f,
        exposureTimeIndex = 0,
        manualIsoIndex = 0,
        isoStops = emptyList(),
        longExposureProgress = 0f,
        longExposureRemaining = "",
        lowLightDetected = false,
        nightModeActive = false,
        extensionStrength = null,
        sloMoSupported = null,
        highSpeedActive = false,
        photoSessionActive = false,
        analysisStreamActive = false,
        nightExtAvailable = false,
        surfaceRequest = null,
        availableZoomLevels = listOf("1x" to 1f),
        galleryBitmap = null,
        panoSweeping = false,
        panoStitching = false,
        panoDots = emptyList(),
        panoCurrentAngle = 0f,
        panoDirection = 0,
        panoPitch = 0f,
        activeSettingState = mutableStateOf<CameraSetting?>(null),
        maskBitmapState = mutableStateOf<android.graphics.Bitmap?>(null),
        deviceRotationState = mutableIntStateOf(0),
        sessionBindFailed = true,
        sessionBindRetry = 0,
    )

    @Test fun bindFailedShowsErrorWithManualRetry() {
        val viewModel = vm()
        viewModel.sessionBindFailedMutable.value = true
        val retriesBefore = viewModel.sessionBindRetryMutable.value
        rule.setContent {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                CameraPreviewBox(state = failedState(viewModel), viewModel = viewModel, animatedRotation = 0f)
            }
        }
        rule.onNodeWithText("Camera couldn't start").assertIsDisplayed()
        rule.onNodeWithText("Retry").assertIsDisplayed().performClick()
        assertEquals(retriesBefore + 1, viewModel.sessionBindRetryMutable.value)
        assertFalse(viewModel.sessionBindFailedMutable.value)
    }
}
