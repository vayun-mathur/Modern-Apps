package com.vayunmathur.camera.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.camera.Route
import com.vayunmathur.camera.util.CameraMode
import com.vayunmathur.camera.util.CameraViewModel
import com.vayunmathur.camera.util.setQrResult
import com.vayunmathur.library.util.NavBackStack
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Cov-c2: CameraScreen in every mode and with live capture state overlays. */
@RunWith(AndroidJUnit4::class)
class CameraCovC2ScreenStatesTest {

    @get:Rule val rule = createComposeRule()

    private fun vm() = CameraViewModel(ApplicationProvider.getApplicationContext())

    private fun composeInMode(mode: CameraMode, configure: (CameraViewModel) -> Unit = {}) {
        val viewModel = vm()
        viewModel.cameraModeMutable.value = mode
        configure(viewModel)
        val backStack = NavBackStack<Route>(arrayOf(Route.Camera))
        rule.setContent { CameraScreen(backStack, viewModel) }
        assertTrue(rule.onRoot().fetchSemanticsNode() != null)
    }

    @Test fun screenInPhotoMode() = composeInMode(CameraMode.PHOTO)
    @Test fun screenInVideoMode() = composeInMode(CameraMode.VIDEO)
    // PORTRAIT is omitted: composing it creates the BokehAnalyzer, whose
    // SelfieSegmenter loads native MlNative (unavailable on the JVM).
    @Test fun screenInPhotosphereMode() = composeInMode(CameraMode.PHOTOSPHERE)
    @Test fun screenInTimelapseMode() = composeInMode(CameraMode.TIMELAPSE)
    @Test fun screenInCinematicMode() = composeInMode(CameraMode.CINEMATIC)
    @Test fun screenInPanoramaMode() = composeInMode(CameraMode.PANORAMA)
    @Test fun screenInSlowMoMode() = composeInMode(CameraMode.SLOW_MO)

    @Test fun screenWithQrResultAndTimer() = composeInMode(CameraMode.PHOTO) { viewModel ->
        viewModel.setQrResult("https://example.com")
        viewModel.timerCountdownMutable.value = 3
    }

    @Test fun screenWhileRecording() = composeInMode(CameraMode.VIDEO) { viewModel ->
        viewModel.isRecordingMutable.value = true
        viewModel.recordingDurationSecMutable.value = 75L
    }

    @Test fun screenWithGridLevelAndFocusLock() = composeInMode(CameraMode.PHOTO) { viewModel ->
        viewModel.gridEnabledMutable.value = true
        viewModel.levelEnabledMutable.value = true
        viewModel.focusLockedMutable.value = true
    }
}
