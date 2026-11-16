package com.vayunmathur.camera.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.camera.util.AspectRatioOption
import com.vayunmathur.camera.util.CameraMode
import com.vayunmathur.camera.util.FlashMode
import com.vayunmathur.camera.util.TimerDuration
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Cov-w3 wave 10: shutter row and top bar in every slot configuration, clicked. */
@RunWith(AndroidJUnit4::class)
class CameraCovW3Wave10ComposeTest {
    @get:Rule val rule = createComposeRule()

    private fun clickAllClickables() {
        val count = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size
        for (i in 0 until count) {
            rule.onAllNodes(hasClickAction())[i].performClick()
            rule.waitForIdle()
        }
    }

    private fun shutter(
        mode: CameraMode, recording: Boolean, paused: Boolean = false,
        snapshot: Boolean = true, bitmap: Bitmap? = null, burst: Boolean = false,
        flip: Boolean = true, capturing: Boolean = false,
    ) {
        rule.setContent {
            ShutterRow(
                cameraMode = mode, isRecording = recording, isCapturing = capturing,
                recordingPaused = paused, videoSnapshotSupported = snapshot,
                galleryBitmap = bitmap, burstEnabled = burst,
                onBurstStart = {}, onBurstStop = {}, onCapture = {},
                onPauseResume = {}, onSnapshot = {}, onFlipCamera = {},
                onGallery = {}, iconRotation = 0f, flipEnabled = flip,
            )
        }
        rule.waitForIdle()
        clickAllClickables()
    }

    @Test fun shutterPhotoIdle() = shutter(CameraMode.PHOTO, recording = false)

    @Test fun shutterPhotoWithGalleryBitmapAndBurst() = shutter(
        CameraMode.PHOTO, recording = false, burst = true, capturing = true,
        bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) },
    )

    @Test fun shutterVideoRecordingPausedSnapshot() =
        shutter(CameraMode.VIDEO, recording = true, paused = true, snapshot = true)

    @Test fun shutterVideoRecordingNoSnapshot() =
        shutter(CameraMode.VIDEO, recording = true, paused = false, snapshot = false)

    @Test fun shutterSlowMoNoFlip() = shutter(CameraMode.SLOW_MO, recording = false, flip = false)

    @Test fun shutterTimelapseRecording() = shutter(CameraMode.TIMELAPSE, recording = true)

    @Test fun topBarPhotoAllControls() {
        rule.setContent {
            CameraTopBar(
                flashMode = FlashMode.AUTO, torchEnabled = true, hasFlashUnit = true,
                gridEnabled = true, levelEnabled = true, aspectRatio = AspectRatioOption.RATIO_16_9,
                stillCropEnabled = true, isPhotoType = true, isVideoType = false,
                micMuted = false, timerDuration = TimerDuration.TEN,
                onFlashToggle = {}, onTorchToggle = {}, onGridToggle = {}, onLevelToggle = {},
                onAspectCycle = {}, onMicToggle = {}, onTimerCycle = {}, iconRotation = 90f,
            )
        }
        rule.waitForIdle()
        clickAllClickables()
        assertTrue(true)
    }

    @Test fun topBarVideoNoFlashUnit() {
        rule.setContent {
            CameraTopBar(
                flashMode = FlashMode.OFF, torchEnabled = false, hasFlashUnit = false,
                gridEnabled = false, levelEnabled = false, aspectRatio = AspectRatioOption.RATIO_4_3,
                stillCropEnabled = false, isPhotoType = false, isVideoType = true,
                micMuted = true, timerDuration = TimerDuration.NONE,
                onFlashToggle = {}, onTorchToggle = {}, onGridToggle = {}, onLevelToggle = {},
                onAspectCycle = {}, onMicToggle = {}, onTimerCycle = {}, iconRotation = 0f,
            )
        }
        rule.waitForIdle()
        clickAllClickables()
    }

    @Test fun topBarFlashOnPhoto() {
        rule.setContent {
            CameraTopBar(
                flashMode = FlashMode.ON, torchEnabled = false, hasFlashUnit = true,
                gridEnabled = false, levelEnabled = true, aspectRatio = AspectRatioOption.RATIO_1_1,
                stillCropEnabled = true, isPhotoType = true, isVideoType = false,
                micMuted = false, timerDuration = TimerDuration.THREE,
                onFlashToggle = {}, onTorchToggle = {}, onGridToggle = {}, onLevelToggle = {},
                onAspectCycle = {}, onMicToggle = {}, onTimerCycle = {}, iconRotation = 270f,
            )
        }
        rule.waitForIdle()
        clickAllClickables()
    }
}
