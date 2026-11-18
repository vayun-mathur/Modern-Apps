package com.vayunmathur.camera.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.camera.Route
import com.vayunmathur.camera.platform.CameraMode
import com.vayunmathur.camera.platform.CameraViewModel
import com.vayunmathur.library.util.NavBackStack
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** CameraScreen composition across every capture mode. */
@RunWith(AndroidJUnit4::class)
class CameraScreenModesComposeTest {

    @get:Rule val rule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun vm() = CameraViewModel(context.applicationContext as android.app.Application)

    private fun render(mode: CameraMode) {
        val viewModel = vm()
        viewModel.cameraModeMutable.value = mode
        val backStack = NavBackStack<Route>(arrayOf(Route.Camera))
        rule.setContent { CameraScreen(backStack, viewModel) }
        rule.waitForIdle()
        assertTrue(rule.onRoot().fetchSemanticsNode() != null)
    }

    @Test fun portraitMode() = render(CameraMode.PORTRAIT)
    @Test fun panoramaMode() = render(CameraMode.PANORAMA)
    @Test fun photosphereMode() = render(CameraMode.PHOTOSPHERE)
    @Test fun slowMoMode() = render(CameraMode.SLOW_MO)
    @Test fun timelapseMode() = render(CameraMode.TIMELAPSE)
    @Test fun cinematicMode() = render(CameraMode.CINEMATIC)
}
