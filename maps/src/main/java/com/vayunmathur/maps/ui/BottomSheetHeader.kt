package com.vayunmathur.maps.ui
import com.vayunmathur.maps.platform.PlacePanelState

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.data.google.PoiSection
import com.vayunmathur.maps.platform.PlacePanelActions
import com.vayunmathur.maps.platform.SavedPlacesViewModel
import com.vayunmathur.maps.platform.SelectedFeatureViewModel

/**
 * The fixed top of the map's bottom sheet, for whatever is selected — the part the
 * sheet's peek height is measured from.
 *
 * Split out of [BottomSheetContent] rather than being its first few rows because
 * `FreeHeightBottomSheetScaffold` takes it as a separate slot and measures it to
 * decide how tall the peek is. Anything drawn here is visible without expanding the
 * sheet; anything [BottomSheetContent] draws is not.
 *
 * Only places have one so far. A selection with no header — a route, an admin label —
 * leaves this empty, which the scaffold reads as "no measurement to work from" and
 * falls back to `MapChromeMetrics.sheetPeekHeight`, exactly as before. [modifier] is
 * applied per branch rather than to a wrapper for the same reason: an empty wrapper
 * still measures its own padding, and here that padding would become the peek height.
 */
@Composable
fun BottomSheetHeader(
    viewModel: SelectedFeatureViewModel,
    selectedFeature: SpecificFeature?,
    setSelectedFeature: (SpecificFeature?) -> Unit,
    inactiveNavigation: SpecificFeature.Route?,
    savedPlacesViewModel: SavedPlacesViewModel,
    modifier: Modifier = Modifier,
) {
    when (selectedFeature) {
        is SpecificFeature.Restaurant -> PlaceSheetHeader(
            viewModel, savedPlacesViewModel, inactiveNavigation, selectedFeature, modifier,
        ) { setSelectedFeature(routeTo(inactiveNavigation, selectedFeature)) }
        is SpecificFeature.GenericPlace -> PlaceSheetHeader(
            viewModel, savedPlacesViewModel, inactiveNavigation, selectedFeature, modifier,
        ) { setSelectedFeature(routeTo(inactiveNavigation, selectedFeature)) }
        // `RoutableFeature` is an intermediate sealed interface, so this cannot be made
        // exhaustive over the leaves; `else` covers null and any future subtype.
        else -> Unit
    }
}

/**
 * Stateless header: the same fixed top as [BottomSheetHeader], driven by
 * [PlacePanelState] instead of the ViewModels, so the side panel can render
 * it with no binding of its own. Directions still routes through
 * [setSelectedFeature], exactly like the sheet does.
 */
@Composable
fun BottomSheetHeader(
    panelState: PlacePanelState,
    panelActions: PlacePanelActions,
    selectedFeature: SpecificFeature?,
    setSelectedFeature: (SpecificFeature?) -> Unit,
    inactiveNavigation: SpecificFeature.Route?,
    modifier: Modifier = Modifier,
) {
    when (selectedFeature) {
        is SpecificFeature.Restaurant -> PlaceSheetHeader(
            panelState, panelActions, inactiveNavigation, selectedFeature, modifier,
        ) { setSelectedFeature(routeTo(inactiveNavigation, selectedFeature)) }
        is SpecificFeature.GenericPlace -> PlaceSheetHeader(
            panelState, panelActions, inactiveNavigation, selectedFeature, modifier,
        ) { setSelectedFeature(routeTo(inactiveNavigation, selectedFeature)) }
        // `RoutableFeature` is an intermediate sealed interface, so this cannot be made
        // exhaustive over the leaves; `else` covers null and any future subtype.
        else -> Unit
    }
}

/**
 * Directions to [destination]: a fresh route from the user's position, or one more
 * stop on the route already being planned.
 */
private fun routeTo(
    inactiveNavigation: SpecificFeature.Route?,
    destination: SpecificFeature.RoutableFeature,
) = if (inactiveNavigation == null) {
    SpecificFeature.Route(listOf(null, destination))
} else {
    SpecificFeature.Route(inactiveNavigation.waypoints + listOf(destination))
}
