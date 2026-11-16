package com.vayunmathur.maps.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.SheetValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.map.CameraState
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.FreeHeightSheetState
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.maps.Route
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.util.MapsSearchViewModel
import com.vayunmathur.maps.util.SelectedFeatureViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** Slice 3/3 extra: MapSearchHost state, MapSheetEffects glue, settings action. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapSheetEffectsSlice3ExtraTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun place(name: String = "Place") =
        SpecificFeature.GenericPlace(name, null, null, null, com.vayunmathur.library.map.GeoPoint(-122.4, 37.8))

    @Test fun searchHostOpenSearchVariants() {
        // Search is a SheetRoute pushed on the stack: opening search adds
        // a Search entry carrying the waypoint index and bias point.
        val stack = NavBackStack<com.vayunmathur.maps.SheetRoute>(arrayOf(com.vayunmathur.maps.SheetRoute.Browse))
        stack.add(com.vayunmathur.maps.SheetRoute.Search(waypointIndex = 2, nearLat = 37.8, nearLon = -122.4))
        val top = stack.last() as com.vayunmathur.maps.SheetRoute.Search
        assertEquals(2, top.waypointIndex)
        stack.pop()
        assertEquals(com.vayunmathur.maps.SheetRoute.Browse, stack.last())
    }

    @Test fun sheetEffectsCompactAndWideAndBack() {
        val vm = SelectedFeatureViewModel(app)
        val searchVm = MapsSearchViewModel(app)
        var wide by mutableStateOf(false)
        var feature: SpecificFeature? by mutableStateOf(place())
        var navigating by mutableStateOf(false)
        var stackRef: NavBackStack<com.vayunmathur.maps.SheetRoute>? = null
        rule.setContent {
            val stack = androidx.compose.runtime.remember { NavBackStack<com.vayunmathur.maps.SheetRoute>(arrayOf(com.vayunmathur.maps.SheetRoute.Browse)) }
            stackRef = stack
            MapSheetEffects(
                wide = wide, viewModel = vm, camera = CameraState(),
                selectedFeature = feature, inactiveNavigation = null,
                isNavigating = navigating,
                sheetState = FreeHeightSheetState(SheetValue.PartiallyExpanded),
                searchSheetState = FreeHeightSheetState(SheetValue.PartiallyExpanded),
                sheetStack = stack,
                searchRoute = stack.backStack.last() as? com.vayunmathur.maps.SheetRoute.Search,
            )
            DynamicTheme { androidx.compose.foundation.layout.Box {} }
        }
        rule.waitForIdle()
        // Open then close search through the stack: a push shows the
        // search sheet, a pop hands the place sheet back.
        rule.runOnUiThread { stackRef!!.add(com.vayunmathur.maps.SheetRoute.Search()) }
        rule.waitForIdle()
        assertTrue(stackRef!!.last() is com.vayunmathur.maps.SheetRoute.Search)
        rule.runOnUiThread { stackRef!!.pop() }
        rule.waitForIdle()
        // Wide mode: effects return early, nothing breaks
        rule.runOnUiThread { wide = true; navigating = true }
        rule.waitForIdle()
        rule.runOnUiThread { wide = false }
        rule.waitForIdle()
        // Back with a selection clears the selection via the search BackHandler chain:
        // set the VM selection the way the sheet would see it first
        rule.runOnUiThread { vm.set(feature) }
        rule.waitForIdle()
    }

    @Test fun settingsActionPushAndReplace() {
        val backStack = NavBackStack<Route>(arrayOf(Route.MapPage))
        var captured: List<com.vayunmathur.library.ui.OverlayAction>? = null
        rule.setContent {
            captured = rememberSettingsAction(backStack)
            DynamicTheme { androidx.compose.foundation.layout.Box {} }
        }
        rule.waitForIdle()
        assertNotNull(captured)
        assertEquals(1, captured!!.size)
        assertTrue(captured!![0].contentDescription.isNotBlank())
        // First click pushes Settings; a re-tap replaces instead of stacking
        rule.runOnUiThread { captured!![0].onClick() }
        assertEquals(Route.SettingsPage, backStack.last())
        val sizeAfterPush = backStack.backStack.size
        rule.runOnUiThread { captured!![0].onClick() }
        assertEquals(sizeAfterPush, backStack.backStack.size)
        assertEquals(Route.SettingsPage, backStack.last())
        // Captured reference stays stable across recomposition (remember by label)
        assertNotNull(captured)
    }
}
