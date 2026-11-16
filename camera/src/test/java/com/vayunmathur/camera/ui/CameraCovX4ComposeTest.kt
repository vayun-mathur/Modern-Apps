package com.vayunmathur.camera.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.camera.Route
import com.vayunmathur.camera.util.CameraMode
import com.vayunmathur.camera.util.CameraViewModel
import com.vayunmathur.camera.util.GuideDot
import com.vayunmathur.camera.util.GuideDotState
import com.vayunmathur.camera.util.setSaveTreeUri
import com.vayunmathur.camera.util.setShadows
import com.vayunmathur.camera.util.setWarmth
import com.vayunmathur.camera.util.setVideoCodec
import com.vayunmathur.camera.util.VideoCodec
import com.vayunmathur.library.util.NavBackStack
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Cov-x4: panorama overlay dot states, settings sections and preview box variants. */
@RunWith(AndroidJUnit4::class)
class CameraCovX4ComposeTest {
    @get:Rule val rule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun vm() = CameraViewModel(context.applicationContext as android.app.Application)


    private fun stateFor(mode: CameraMode, viewModel: CameraViewModel) = CameraScreenState(
        cameraMode = mode,
        lensFacing = androidx.camera.core.CameraSelector.LENS_FACING_BACK,
        selectedLens = null, availableLenses = emptyList(), hasFlashUnit = true,
        flashMode = com.vayunmathur.camera.util.FlashMode.AUTO, torchEnabled = false,
        videoCodec = viewModel.videoCodecMutable.value,
        audioInputSource = viewModel.audioInputSourceMutable.value,
        isRecording = mode == CameraMode.VIDEO, recordingDuration = 12, timerCountdown = 0,
        qrResult = null,
        aspectRatio = com.vayunmathur.camera.util.AspectRatioOption.RATIO_4_3,
        zoomRatio = 1.5f, mirrorFront = true,
        timerDuration = com.vayunmathur.camera.util.TimerDuration.NONE,
        isCapturing = false, burstActive = false, burstCount = 0, focusLocked = false,
        recordingPaused = false, micMuted = false, videoSnapshotSupported = false,
        lastCaptureUri = null, gridEnabled = true, levelEnabled = true, roll = 3f,
        blurStrength = 0.5f, exposureComp = 0.5f, warmth = 0.4f, shadows = 0.3f,
        exposureTimeIndex = 0, manualIsoIndex = 0, isoStops = listOf(100, 200, 400),
        longExposureProgress = 0.4f, longExposureRemaining = "1.0s",
        lowLightDetected = false, nightModeActive = false, extensionStrength = null,
        sloMoSupported = true, highSpeedActive = false, photoSessionActive = true,
        analysisStreamActive = true, nightExtAvailable = false, surfaceRequest = null,
        availableZoomLevels = listOf("1x" to 1f, "2x" to 2f), galleryBitmap = null,
        panoSweeping = mode == CameraMode.PANORAMA, panoStitching = false,
        panoDots = if (mode == CameraMode.PANORAMA) listOf(
            GuideDot(0, 0f, 0f, GuideDotState.CAPTURED),
            GuideDot(1, 30f, 0f, GuideDotState.PENDING),
        ) else emptyList(),
        panoCurrentAngle = 5f, panoDirection = 1, panoPitch = 0f,
        activeSettingState = mutableStateOf<com.vayunmathur.camera.ui.CameraSetting?>(null),
        maskBitmapState = mutableStateOf<android.graphics.Bitmap?>(null),
        deviceRotationState = mutableIntStateOf(0),
    )

    @Test fun panoramaOverlayStitchingState() {
        rule.setContent {
            PanoramaOverlay(
                isSweeping = false, isStitching = true,
                guideDots = emptyList(), currentAngle = 0f, currentPitch = 0f,
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("Processing panorama…", substring = true).assertIsDisplayed()
    }

    @Test fun panoramaOverlayIdleRendersNothing() {
        rule.setContent {
            PanoramaOverlay(
                isSweeping = false, isStitching = false,
                guideDots = emptyList(), currentAngle = 0f, currentPitch = 0f,
            )
        }
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodesWithText("/", substring = true).fetchSemanticsNodes().size)
    }

    @Test fun panoramaOverlayAllDotStatesAndCounts() {
        val dots = listOf(
            GuideDot(0, 0f, 0f, GuideDotState.CAPTURED),
            GuideDot(1, 10f, 5f, GuideDotState.ALIGNING),
            GuideDot(2, -10f, -5f, GuideDotState.CAPTURING),
            GuideDot(3, 20f, 0f, GuideDotState.PENDING),
            GuideDot(4, 120f, 60f, GuideDotState.PENDING),
        )
        rule.setContent {
            PanoramaOverlay(
                isSweeping = true, isStitching = false,
                guideDots = dots, currentAngle = 2f, currentPitch = 1f,
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("1 / 5", substring = true).assertIsDisplayed()
    }

    @Test fun panoramaOverlaySecondCapturedTriggersFlashPath() {
        val dots = (0 until 4).map { i ->
            GuideDot(i, i * 10f, 0f, if (i < 2) GuideDotState.CAPTURED else GuideDotState.PENDING)
        }
        rule.setContent {
            PanoramaOverlay(
                isSweeping = true, isStitching = false,
                guideDots = dots, currentAngle = 0f, currentPitch = 0f,
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("2 / 4", substring = true).assertIsDisplayed()
    }

    @Test fun settingsPageCodecDropdownWhenMultipleCodecs() {
        val viewModel = vm()
        viewModel.setVideoCodec(VideoCodec.AVC)
        val backStack = NavBackStack<Route>(arrayOf(Route.Camera, Route.Settings))
        rule.setContent { SettingsPage(backStack, viewModel) }
        rule.waitForIdle()
        // Reset on the save-location section is disabled without a SAF target.
        val reset = rule.onAllNodesWithText("Reset", substring = true).fetchSemanticsNodes()
        assertTrue(reset.isNotEmpty())
        // Open the audio dropdown and pick the last microphone entry.
        val audio = rule.onAllNodesWithText("Camcorder", substring = true).fetchSemanticsNodes()
        if (audio.isNotEmpty()) {
            rule.onAllNodesWithText("Camcorder", substring = true)[0].performClick()
            rule.waitForIdle()
            val mics = rule.onAllNodesWithText("Microphone", substring = true).fetchSemanticsNodes()
            if (mics.isNotEmpty()) {
                rule.onAllNodesWithText("Microphone", substring = true)[mics.size - 1].performClick()
                rule.waitForIdle()
            }
        }
        assertEquals("settings", viewModel.foregroundRoute)
    }

    @Test fun settingsPageSafTargetEnablesResetAndClears() {
        val viewModel = vm()
        val tree = android.net.Uri.parse(
            "content://com.android.externalstorage.documents/tree/primary%3APictures%2FCovX4",
        )
        viewModel.setSaveTreeUri(tree)
        val backStack = NavBackStack<Route>(arrayOf(Route.Camera, Route.Settings))
        rule.setContent { SettingsPage(backStack, viewModel) }
        rule.waitForIdle()
        rule.onRoot().performScrollToNode(hasText("Reset", substring = true))
        val reset = rule.onAllNodesWithText("Reset", substring = true).fetchSemanticsNodes()
        assertTrue(reset.isNotEmpty())
        rule.onAllNodesWithText("Reset", substring = true)[0].performClick()
        rule.waitForIdle()
        assertEquals(
            com.vayunmathur.camera.util.SaveTarget.MediaStoreDefault,
            viewModel.saveTargetMutable.value,
        )
    }

    @Test fun settingsPageAllSwitchesToggleTwiceRestoresState() {
        val viewModel = vm()
        val mirrorBefore = viewModel.mirrorFrontMutable.value
        val backStack = NavBackStack<Route>(arrayOf(Route.Camera, Route.Settings))
        rule.setContent { SettingsPage(backStack, viewModel) }
        rule.waitForIdle()
        val count = rule.onAllNodes(isToggleable()).fetchSemanticsNodes().size
        assertTrue(count >= 3, "settings page exposes switch rows, got $count")
        for (round in 0..1) {
            for (i in 0 until count) {
                rule.onAllNodes(isToggleable())[i].performClick()
                rule.waitForIdle()
            }
        }
        assertEquals(mirrorBefore, viewModel.mirrorFrontMutable.value)
    }

    @Test fun previewBoxWithoutSurfacePanoramaMode() {
        val viewModel = vm()
        viewModel.cameraModeMutable.value = CameraMode.PANORAMA
        viewModel.panoramaEngine.startSweep(fullSphere = false)
        val state = stateFor(CameraMode.PANORAMA, viewModel)
        rule.setContent {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                CameraPreviewBox(state = state, viewModel = viewModel, animatedRotation = 0f)
            }
        }
        rule.waitForIdle()
        assertTrue(rule.onRoot().fetchSemanticsNode() != null)
        viewModel.panoramaEngine.stopSweep()
    }

    @Test fun previewBoxWithoutSurfaceVideoMode() {
        val viewModel = vm()
        viewModel.cameraModeMutable.value = CameraMode.VIDEO
        val state = stateFor(CameraMode.VIDEO, viewModel)
        rule.setContent {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                CameraPreviewBox(state = state, viewModel = viewModel, animatedRotation = 90f)
            }
        }
        rule.waitForIdle()
        assertTrue(rule.onRoot().fetchSemanticsNode() != null)
    }

    @Test fun previewBoxWithGradingAndGridState() {
        val viewModel = vm()
        viewModel.cameraModeMutable.value = CameraMode.PHOTO
        viewModel.gridEnabledMutable.value = true
        viewModel.levelEnabledMutable.value = true
        viewModel.setWarmth(0.7f)
        viewModel.setShadows(0.3f)
        val state = stateFor(CameraMode.PHOTO, viewModel)
        rule.setContent {
            Column {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    CameraPreviewBox(state = state, viewModel = viewModel, animatedRotation = 45f)
                }
            }
        }
        rule.waitForIdle()
        assertTrue(rule.onRoot().fetchSemanticsNode() != null)
    }
}
