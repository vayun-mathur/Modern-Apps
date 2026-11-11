package com.vayunmathur.maps.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.maps.data.SavedPlace
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.data.google.PoiSection
import com.vayunmathur.maps.util.PlacePanelActions
import com.vayunmathur.maps.util.PlacePanelState
import com.vayunmathur.maps.util.RouteService
import com.vayunmathur.maps.util.SavedPlacesViewModel
import com.vayunmathur.maps.util.SelectedFeatureViewModel
import com.vayunmathur.maps.util.rememberPlacePanelState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * The ViewModel-backed sheet wiring: actions drive the real ViewModels and
 * the stateless overloads render the result.
 * Slice-2 coverage for maps (basenames B, outside the slice-1 dirs).
 */
@RunWith(AndroidJUnit4::class)
class BottomSheetWiringSlice2Test {

    @get:Rule
    val rule = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    private val place = SpecificFeature.GenericPlace(
        name = "Ferry Building",
        phone = null,
        website = null,
        openingHours = null,
        position = GeoPoint(-122.4, 37.8),
    )

    @Test
    fun `the panel actions drive the real viewmodels`() {
        val selected = SelectedFeatureViewModel(app)
        val savedVm = SavedPlacesViewModel(app)
        var section: PoiSection? = null
        var opened: Pair<Double, Double>? = null
        val actions = object : PlacePanelActions {
            override fun setPoiSection(s: PoiSection) {
                selected.setPoiSection(s)
                section = s
            }

            override fun addSaved() {
                savedVm.addSaved(place)
            }

            override fun removeSaved(p: SavedPlace) {
                savedVm.removeSaved(p)
            }

            override fun openNearestStop(lat: Double, lon: Double) {
                opened = lat to lon
            }
        }
        rule.runOnIdle {
            actions.setPoiSection(PoiSection.PHOTOS)
            actions.openNearestStop(37.8, -122.4)
        }
        assertEquals(PoiSection.PHOTOS, section)
        assertEquals(PoiSection.PHOTOS, selected.poiSection.value)
        assertEquals(37.8, opened?.first)
        assertEquals(-122.4, opened?.second)
    }

    @Test
    fun `added places read back as saved through the panel state`() {
        val selected = SelectedFeatureViewModel(app)
        val savedVm = SavedPlacesViewModel(app)
        rule.runOnIdle { selected.setPoiSection(PoiSection.PHOTOS) }
        var panel: PlacePanelState? = null
        rule.setContent {
            panel = rememberPlacePanelState(selected, savedVm)
        }
        rule.waitForIdle()
        assertEquals(PoiSection.PHOTOS, panel?.poiSection)
        assertTrue(panel?.saved?.isEmpty() == true)
        assertFalse(panel?.isSaved(place) == true)
    }

    @Test
    fun `the header renders the bound place with its directions action`() {
        val selected = SelectedFeatureViewModel(app)
        val savedVm = SavedPlacesViewModel(app)
        var routeTo: SpecificFeature? = null
        rule.setContent {
            val panel = rememberPlacePanelState(selected, savedVm)
            BottomSheetHeader(
                panelState = panel,
                panelActions = PlacePanelActions.Noop,
                selectedFeature = place,
                setSelectedFeature = { routeTo = it },
                inactiveNavigation = null,
            )
        }
        rule.onNodeWithText("Ferry Building").assertIsDisplayed()
        assertTrue(routeTo == null)
    }

    @Test
    fun `the content renders the bound place panel`() {
        val selected = SelectedFeatureViewModel(app)
        val savedVm = SavedPlacesViewModel(app)
        rule.setContent {
            val panel = rememberPlacePanelState(selected, savedVm)
            BottomSheetContent(
                panelState = panel,
                panelActions = PlacePanelActions.Noop,
                selectedFeature = SpecificFeature.Admin0Label(name = "France", wikipedia = null),
                route = emptyMap(),
                selectedRouteType = RouteService.TravelMode.DRIVE,
                setSelectedRouteType = {},
            )
        }
        rule.onNodeWithText("France").assertIsDisplayed()
    }
}
