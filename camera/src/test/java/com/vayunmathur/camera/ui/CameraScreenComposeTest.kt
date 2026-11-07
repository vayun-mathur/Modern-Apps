package com.vayunmathur.camera.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.camera.Route
import com.vayunmathur.camera.util.CameraMode
import com.vayunmathur.camera.util.CameraViewModel
import com.vayunmathur.library.util.NavBackStack
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class CameraScreenComposeTest {

    @get:Rule val rule = createComposeRule()

    private fun vm() = CameraViewModel(ApplicationProvider.getApplicationContext())

    @Test fun cameraScreenRendersInPhotoMode() {
        val backStack = NavBackStack<Route>(arrayOf(Route.Camera))
        rule.setContent { CameraScreen(backStack, vm()) }
        // The viewfinder composes its top/bottom bars around the preview.
        assertTrue(rule.onRoot().fetchSemanticsNode() != null)
    }

    @Test fun cameraScreenRendersInVideoMode() {
        val viewModel = vm()
        viewModel.cameraModeMutable.value = CameraMode.VIDEO
        val backStack = NavBackStack<Route>(arrayOf(Route.Camera))
        rule.setContent { CameraScreen(backStack, viewModel) }
        assertTrue(rule.onRoot().fetchSemanticsNode() != null)
    }
}
