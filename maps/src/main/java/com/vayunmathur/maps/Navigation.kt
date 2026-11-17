package com.vayunmathur.maps

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.library.util.FullscreenPage
import com.vayunmathur.library.util.ListDetailPage
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.maps.platform.MapSettingsViewModel
import com.vayunmathur.maps.platform.MapsSearchViewModel
import com.vayunmathur.maps.platform.ParkingViewModel
import com.vayunmathur.maps.platform.SavedPlacesViewModel
import com.vayunmathur.maps.platform.SelectedFeatureViewModel
import com.vayunmathur.maps.platform.TransitStopsViewModel
import com.vayunmathur.maps.ui.MapPage
import com.vayunmathur.maps.ui.SavedPlacesPage
import com.vayunmathur.maps.ui.settings.MapSettingsPage

@Composable
fun Navigation(
    viewModel: SelectedFeatureViewModel = viewModel(),
    searchViewModel: MapsSearchViewModel = viewModel(),
    savedPlacesViewModel: SavedPlacesViewModel = viewModel(),
    settingsViewModel: MapSettingsViewModel = viewModel(),
    parkingViewModel: com.vayunmathur.maps.platform.ParkingViewModel = viewModel(),
    transitViewModel: com.vayunmathur.maps.platform.TransitStopsViewModel = viewModel(),
) {
    val backStack = rememberNavBackStack<Route>(Route.MapPage)
    // A deep-link navigation starts the engine from the Activity, before
    // any composition can push: the request is bridged through the VM and
    // pushed here, once, when it lands.
    val pendingNavigating by viewModel.pendingNavigating.collectAsState()
    androidx.compose.runtime.LaunchedEffect(pendingNavigating) {
        val request = pendingNavigating ?: return@LaunchedEffect
        if (backStack.last() !is Route.Navigating) backStack.add(request)
        viewModel.consumeNavigating()
    }
    MainNavigation(backStack) {
        // The map is the list pane and settings/saved-places are detail panes,
        // so medium widths show them side by side automatically. The map itself
        // never splits: its own expanded side panel is separate (see MapPage),
        // and these entries carry no shared-element keys — there is nothing to
        // morph between a map surface and a settings form.
        entry<Route.MapPage>(metadata = ListPage()) {
            MapPage(
                backStack,
                viewModel,
                savedPlacesViewModel,
                searchViewModel,
                settingsViewModel,
                parkingViewModel,
                transitViewModel,
                onStartNavigating = { label, mode ->
                    if (backStack.last() !is Route.Navigating) {
                        backStack.add(Route.Navigating(label, mode))
                    }
                },
            )
        }
        entry<Route.Navigating>(metadata = FullscreenPage()) {
            MapPage(
                backStack,
                viewModel,
                savedPlacesViewModel,
                searchViewModel,
                settingsViewModel,
                parkingViewModel,
                transitViewModel,
                exitNavigating = { backStack.pop() },
            )
        }
        entry<Route.SettingsPage>(metadata = ListDetailPage()) {
            MapSettingsPage(backStack, settingsViewModel)
        }
        entry<Route.SavedPlacesPage>(metadata = ListDetailPage()) {
            SavedPlacesPage(backStack, savedPlacesViewModel)
        }
        entry<Route.StreetView>(metadata = FullscreenPage()) { route ->
            com.vayunmathur.maps.ui.streetview.StreetViewRoute(
                lat = route.lat, lon = route.lon, onClose = { backStack.pop() },
            )
        }
    }
}
