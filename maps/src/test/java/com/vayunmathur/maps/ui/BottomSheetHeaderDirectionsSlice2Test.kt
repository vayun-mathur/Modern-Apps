package com.vayunmathur.maps.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.util.PlacePanelActions
import com.vayunmathur.maps.util.PlacePanelState
import com.vayunmathur.maps.util.SavedPlacesViewModel
import com.vayunmathur.maps.util.SelectedFeatureViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * The bottom-sheet header's Directions action: [routeTo] starting a fresh
 * route and appending to an inactive one, through both header overloads
 * and both routable feature types.
 * Slice-2 coverage for maps (basename B, outside the slice-1 dirs).
 */
// Robolectric fixtures build real ViewModels inside setContent on purpose: these tests drive
// the composables against live VM state, with no ViewModelStoreOwner to obtain them from.
@Suppress("ViewModelConstructorInComposable")
@RunWith(AndroidJUnit4::class)
class BottomSheetHeaderDirectionsSlice2Test {

    @get:Rule
    val rule = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    private val cafe = SpecificFeature.Restaurant(
        name = "Blue Bottle",
        phone = null,
        website = null,
        menu = null,
        openingHours = null,
        position = GeoPoint(-122.4, 37.8),
    )
    private val place = SpecificFeature.GenericPlace(
        name = "Ferry Building",
        phone = null,
        website = null,
        openingHours = null,
        position = GeoPoint(-122.39, 37.79),
    )

    @Test fun `stateless restaurant header directions starts a fresh route`() {
        var selected: SpecificFeature? = null
        rule.setContent {
            BottomSheetHeader(
                panelState = PlacePanelState(),
                panelActions = PlacePanelActions.Noop,
                selectedFeature = cafe,
                setSelectedFeature = { selected = it },
                inactiveNavigation = null,
            )
        }
        rule.onNodeWithText("Blue Bottle").assertIsDisplayed()
        rule.onNodeWithText("Directions").performClick()
        val route = selected as? SpecificFeature.Route
        assertEquals(listOf(null, cafe), route?.waypoints)
    }

    @Test fun `stateless place header directions appends to the inactive route`() {
        var selected: SpecificFeature? = null
        val inactive = SpecificFeature.Route(listOf(null, cafe))
        rule.setContent {
            BottomSheetHeader(
                panelState = PlacePanelState(),
                panelActions = PlacePanelActions.Noop,
                selectedFeature = place,
                setSelectedFeature = { selected = it },
                inactiveNavigation = inactive,
            )
        }
        rule.onNodeWithText("Add Stop to Route").performClick()
        val route = selected as? SpecificFeature.Route
        assertEquals(listOf(null, cafe, place), route?.waypoints)
    }

    @Test fun `viewmodel restaurant header directions starts a fresh route`() {
        var selected: SpecificFeature? = null
        rule.setContent {
            BottomSheetHeader(
                viewModel = SelectedFeatureViewModel(app),
                selectedFeature = cafe,
                setSelectedFeature = { selected = it },
                inactiveNavigation = null,
                savedPlacesViewModel = SavedPlacesViewModel(app),
            )
        }
        rule.onNodeWithText("Blue Bottle").assertIsDisplayed()
        rule.onNodeWithText("Directions").performClick()
        assertEquals(listOf(null, cafe), (selected as? SpecificFeature.Route)?.waypoints)
    }

    @Test fun `viewmodel place header directions appends to the inactive route`() {
        var selected: SpecificFeature? = null
        val inactive = SpecificFeature.Route(listOf(place))
        rule.setContent {
            BottomSheetHeader(
                viewModel = SelectedFeatureViewModel(app),
                selectedFeature = place,
                setSelectedFeature = { selected = it },
                inactiveNavigation = inactive,
                savedPlacesViewModel = SavedPlacesViewModel(app),
            )
        }
        rule.onNodeWithText("Directions").assertDoesNotExist()
        rule.onNodeWithText("Add Stop to Route").performClick()
        assertEquals(listOf(place, place), (selected as? SpecificFeature.Route)?.waypoints)
    }

    @Test fun `a stateless non-place selection renders no header`() {
        var selected: SpecificFeature? = null
        rule.setContent {
            BottomSheetHeader(
                panelState = PlacePanelState(),
                panelActions = PlacePanelActions.Noop,
                selectedFeature = SpecificFeature.Admin0Label(name = "France", wikipedia = null),
                setSelectedFeature = { selected = it },
                inactiveNavigation = null,
            )
        }
        rule.onNodeWithText("France").assertDoesNotExist()
        rule.onNodeWithText("Directions").assertDoesNotExist()
        assertNull(selected)
    }

    @Test fun `a viewmodel header with nothing selected renders nothing`() {
        var selected: SpecificFeature? = null
        rule.setContent {
            BottomSheetHeader(
                viewModel = SelectedFeatureViewModel(app),
                selectedFeature = null,
                setSelectedFeature = { selected = it },
                inactiveNavigation = null,
                savedPlacesViewModel = SavedPlacesViewModel(app),
            )
        }
        rule.onNodeWithText("Directions").assertDoesNotExist()
        assertNull(selected)
    }
}
