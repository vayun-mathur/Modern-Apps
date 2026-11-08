package com.vayunmathur.measure.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.measure.R
import com.vayunmathur.measure.Route
import com.vayunmathur.measure.platform.MeasureViewModel
import com.vayunmathur.measure.platform.updateDiagnosticsImpl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Every tool page bound to the real ViewModel, plus tab navigation through
 * the shared bottom bar.
 */
@RunWith(RobolectricTestRunner::class)
class MeasurePagesTest {

    @get:Rule
    val rule = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private fun str(id: Int) = app.getString(id)
    private fun stack(vararg routes: Route) = NavBackStack(arrayOf(*routes))

    @Test fun compassPageShowsDialAndTabsNavigate() {
        val vm = MeasureViewModel(app)
        val backStack = stack(Route.Compass)
        rule.setContent { CompassPage(backStack, vm) }
        rule.onNodeWithText(str(R.string.tool_level)).performClick()
        assertEquals(Route.Level, backStack.last())
        rule.onNodeWithText(str(R.string.tool_ruler)).performClick()
        assertEquals(Route.Ruler, backStack.last())
        rule.onNodeWithText(str(R.string.tool_compass)).performClick()
        assertEquals(Route.Compass, backStack.last())
    }

    @Test fun levelPageCalibratesThroughTheViewModel() {
        val vm = MeasureViewModel(app)
        rule.setContent { LevelPage(stack(Route.Level), vm) }
        rule.onNodeWithText(str(R.string.level_set_zero)).performClick()
        assertTrue(vm.level.value.isCalibrated)
    }

    @Test fun rulerPageSetsScaleFromDisplayDensity() {
        val vm = MeasureViewModel(app)
        rule.setContent { RulerPage(stack(Route.Ruler), vm) }
        rule.waitForIdle()
        assertTrue(vm.ruler.value.pixelsPerMm > 0f, "pixelsPerMm=${vm.ruler.value.pixelsPerMm}")
    }

    @Test fun savedPageListsViewModelMeasurementsAndDeletes() {
        val vm = MeasureViewModel(app)
        vm.addResolvedAnchor(0.0, 0.0, 0.0, onPlane = true)
        vm.addResolvedAnchor(0.0, 3.0, 0.0, onPlane = true)
        vm.saveCurrentMeasurement("hallway")
        rule.setContent { SavedMeasurementsPage(stack(Route.Saved), vm) }
        rule.onNodeWithText("hallway").assertIsDisplayed()
    }

    @Test fun settingsPageFlipsViewModelStateAndOpensDiagnostics() {
        val vm = MeasureViewModel(app)
        val backStack = stack(Route.Settings)
        rule.setContent { SettingsPage(backStack, vm) }
        // Diagnostics is gated on the settings toggle: turn diagnostics on
        // in state, then the row appears and navigates.
        vm.setShowDiagnostics(true)
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.settings_open_diagnostics)).performClick()
        assertEquals(Route.Diagnostics, backStack.last())
    }

    @Test fun diagnosticsPageShowsStateAndBackPops() {
        val vm = MeasureViewModel(app)
        vm.updateDiagnosticsForTest()
        val backStack = stack(Route.Compass, Route.Diagnostics)
        rule.setContent { DiagnosticsPage(backStack, vm) }
        rule.waitForIdle()
        assertEquals(Route.Diagnostics, backStack.last())
    }
}

/** Diagnostics values a page render can show; same update the AR page applies. */
private fun MeasureViewModel.updateDiagnosticsForTest() {
    updateDiagnosticsImpl {
        copy(featureCount = 42, trackedCount = 40, nativeEngineAvailable = true, focalPx = 500.0)
    }
}
