package com.vayunmathur.measure.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.measure.R
import com.vayunmathur.measure.Route
import com.vayunmathur.measure.data.model.TrackingQuality
import com.vayunmathur.measure.platform.MeasureViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The AR measure page bound to the real ViewModel with no camera grant:
 * the initialisation gating and the anchor math behind the overlay.
 */
@RunWith(RobolectricTestRunner::class)
class ArMeasurePageTest {

    @get:Rule
    val rule = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private fun str(id: Int) = app.getString(id)

    @Test fun arPageWithoutCameraShowsTheNotice() {
        val vm = MeasureViewModel(app)
        val backStack = NavBackStack<Route>(arrayOf(Route.ArMeasure))
        rule.setContent { ArMeasurePage(backStack, vm) }
        rule.waitForIdle()
        // Permission was reported back to the ViewModel by the page.
        assertEquals(false, vm.ar.value.cameraPermissionGranted)
        // Either unavailable notice is correct here: no camera grant (and, on
        // hosts without the engine, no native VIO either).
        val nativeNotice = runCatching { rule.onNodeWithText(str(R.string.ar_unavailable_native)).assertIsDisplayed() }
        if (nativeNotice.isFailure) {
            rule.onNodeWithText(str(R.string.ar_unavailable_camera)).assertIsDisplayed()
        }
    }

    @Test fun arPageWithAnchorsShowsDistanceThroughTheViewModel() {
        val vm = MeasureViewModel(app)
        vm.onTrackingUpdate(TrackingQuality.Good, hasPlane = true)
        vm.addResolvedAnchor(0.0, 0.0, 0.0, onPlane = true)
        vm.addResolvedAnchor(1.0, 0.0, 0.0, onPlane = true)
        val backStack = NavBackStack<Route>(arrayOf(Route.ArMeasure))
        rule.setContent { ArMeasurePage(backStack, vm) }
        rule.waitForIdle()
        // The overlay reprojection has no session (handle 0), so the page
        // renders the state's distance readout without projecting.
        assertEquals(1.0, vm.ar.value.distanceM)
    }
}
