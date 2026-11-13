package com.vayunmathur.maps.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.map.CameraState
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.ui.FreeHeightSheetState
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.OverlayAction
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.maps.Route
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.util.MapsSearchViewModel
import com.vayunmathur.maps.util.SelectedFeatureViewModel
import com.vayunmathur.maps.util.searchBiasPoint
import com.vayunmathur.maps.util.visibleBoundsOrWorld
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * State the sheet effects own: the open search request plus the latched
 * search-was-open flag. A holder (rather than bare `remember`s in MapPage)
 * so [MapSheetEffects] can own both the state and the effects that drive it.
 */
internal class MapSearchHost(
    val searchViewModel: MapsSearchViewModel,
    val camera: CameraState,
    private val userPosition: StateFlow<GeoPoint> = NoFix,
) {
    var searchRequest by mutableStateOf<SearchRequest?>(null)

    // Latched rather than derived, so the hand-back below cannot fire on first composition. A
    // cold start can arrive with a place already selected and a deep link about to focus it, and
    // that path owns the pane — see the `pendingFocus` effect in MapSheetEffects.
    var searchWasOpen by mutableStateOf(false)

    fun openSearch(query: String? = null, waypointIndex: Int? = null) {
        val near = searchBiasPoint(camera.visibleBoundsOrWorld(), userPosition.value)
        val nearLat = near.latitude
        val nearLon = near.longitude
        // The category chips and the voice transcript both arrive as a query to run, which used
        // to be a route argument the destination replayed on arrival. There is no arrival any
        // more, so it runs here.
        if (!query.isNullOrBlank()) searchViewModel.setQuery(query, nearLat, nearLon)
        searchRequest = SearchRequest(waypointIndex, nearLat, nearLon)
    }
}

/** "No fix yet" for hosts built without a location source (previews, tests). */
private val NoFix: StateFlow<GeoPoint> = MutableStateFlow(GeoPoint(0.0, 0.0))

/** Remembers a [MapSearchHost] bound to this composition. */
@Composable
internal fun rememberMapSearchHost(
    searchViewModel: MapsSearchViewModel,
    camera: CameraState,
    userPosition: StateFlow<GeoPoint> = NoFix,
): MapSearchHost = remember(searchViewModel, camera, userPosition) {
    MapSearchHost(searchViewModel, camera, userPosition)
}

/**
 * Sheet-driving effects, back handling and the settings action.
 *
 * Extracted from MapPage so no ui file exceeds the [FileLength] limit. These
 * are the imperative glue between navigation state and the sheet scaffolds:
 * every effect that touches a sheet is keyed on `wide` and returns early when
 * true, because suspending on an unmeasured scaffold hangs rather than no-ops.
 */
@Composable
internal fun MapSheetEffects(
    wide: Boolean,
    viewModel: SelectedFeatureViewModel,
    camera: CameraState,
    selectedFeature: SpecificFeature?,
    inactiveNavigation: SpecificFeature.Route?,
    isNavigating: Boolean,
    sheetState: FreeHeightSheetState?,
    searchSheetState: FreeHeightSheetState?,
    searchHost: MapSearchHost,
) {
    val searchRequest = searchHost.searchRequest

    LaunchedEffect(Unit, wide) {
        // Raise the sheet if something is already selected — unless a deep link is about to open
        // the compact pane instead, which the pendingFocus effect below handles.
        if (!wide && selectedFeature != null && viewModel.pendingFocus.value == null) {
            sheetState?.partialExpand()
        }
    }

    // Contact-address auto-select and external geo:/maps deep links land here. A StateFlow-backed
    // request survives a cold start, so a link that selected a place before the map composed
    // still animates and peeks once it is ready.
    val pendingFocus by viewModel.pendingFocus.collectAsState()
    LaunchedEffect(pendingFocus, wide) {
        val request = pendingFocus ?: return@LaunchedEffect
        camera.animateTo(
            camera.position.copy(
                target = request.position,
                zoom = request.zoom ?: (camera.position.zoom).coerceAtLeast(14.0),
            )
        )
        if (!wide) sheetState?.partialExpand()
        viewModel.consumeFocus()
        // Search/deep-link selections are GenericPlaces, which carry no
        // boundary link, so they could never show the region mask. Now the
        // camera is on the place, upgrade the selection to the rendered
        // admin label of the same name at this spot when one is linked —
        // the mask and admin card follow from the selection as they do
        // for a tapped label. No name match, no upgrade.
        val place = viewModel.selectedFeature.value as? SpecificFeature.GenericPlace
            ?: return@LaunchedEffect
        val projection = camera.projection ?: return@LaunchedEffect
        val centre = runCatching {
            projection.screenLocationFromPosition(request.position)
        }.getOrNull() ?: return@LaunchedEffect
        val slop = com.vayunmathur.maps.ui.theme.MapChromeMetrics.hitSlop
        val candidates = projection.queryRenderedLabels(
            androidx.compose.ui.unit.DpRect(
                centre.x - slop, centre.y - slop, centre.x + slop, centre.y + slop,
            ),
            com.vayunmathur.maps.ui.map.MapFeaturePicker.NATIVE_LABEL_LAYER_IDS,
        ).mapNotNull {
            with(com.vayunmathur.maps.ui.map.MapFeaturePicker.Companion) { it.toFeature1() }
        }
        // (toFeature1 is a companion extension; the with() scopes it.)
        if (candidates.isEmpty()) return@LaunchedEffect
        val parsed = candidates.mapNotNull { raw ->
            runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.vayunmathur.maps.data.parse(raw)
                }
            }.getOrNull()
        }
        val upgrade = com.vayunmathur.maps.ui.map.adminUpgradeFor(place, parsed)
        // The selection may have moved on while parsing; only upgrade the
        // exact selection this focus was for.
        if (upgrade != null && viewModel.selectedFeature.value === place) {
            viewModel.set(upgrade)
        }
    }

    // While navigating the in-screen overlay is the primary UI, so the sheet stays down.
    LaunchedEffect(isNavigating, wide) {
        if (!wide) sheetState?.let { if (isNavigating) it.hide() }
    }

    // Nothing selected means the sheet has nothing to draw, and its peek height is fixed — so
    // leaving it up would show a blank card rather than collapsing.
    LaunchedEffect(selectedFeature, wide) {
        if (!wide) sheetState?.let { if (selectedFeature == null) it.hide() }
    }

    // The two sheets take turns: search covers the place pane while it is up, and hands it back
    // on the way out. The selection is read off the ViewModel rather than the collected state
    // because picking a result sets it and closes search in the same breath, and the flow has not
    // necessarily delivered by the time this effect restarts.
    LaunchedEffect(searchRequest, wide) {
        if (wide) return@LaunchedEffect
        if (searchRequest != null) {
            searchHost.searchWasOpen = true
            launch { sheetState?.hide() }
            searchSheetState?.partialExpand()
        } else if (searchHost.searchWasOpen) {
            searchHost.searchWasOpen = false
            launch { searchSheetState?.hide() }
            if (viewModel.selectedFeature.value != null) sheetState?.partialExpand()
        }
    }

    // Selecting anything on the still-live map underneath supersedes the search: the user has
    // found what they were looking for by pointing at it. Guarded the same way
    // as the sheet effects below: in wide mode the panel reads this state directly.
    LaunchedEffect(selectedFeature, wide) {
        if (!wide && selectedFeature != null) searchHost.searchRequest = null
    }

    // Clearing the selection is enough: the `LaunchedEffect(selectedFeature)` above is the single
    // dismissal path for every sheet state, so back does not hide the sheet itself. Doing both
    // raced two `hide()` coroutines against each other for no benefit.
    BackHandler(selectedFeature != null) {
        viewModel.set(null)
    }

    BackHandler(selectedFeature == null && inactiveNavigation != null) {
        viewModel.setInactiveNavigation(null)
    }

    // Registered last so it wins: back out of search before back does anything to the selection
    // the search sheet is currently covering.
    BackHandler(searchRequest != null) {
        searchHost.searchRequest = null
    }
}

/**
 * The settings overflow action. Rebuilt only when the label changes:
 * `TopAppBarOverlay` takes a list, so an inline one would be a fresh instance
 * every recomposition of the map.
 */
@Composable
internal fun rememberSettingsAction(backStack: NavBackStack<Route>): List<OverlayAction> {
    // Rebuilt only when the label changes: `TopAppBarOverlay` takes a list, so an inline one
    // would be a fresh instance every recomposition of the map.
    val settingsLabel = stringResource(UiR.string.settings)
    return remember(settingsLabel, backStack) {
        listOf(
            OverlayAction(
                icon = { IconSettings() },
                contentDescription = settingsLabel,
                onClick = {
                    // Re-tap replaces the detail instead of stacking it, so the
                    // settings entry never piles up in two-pane layouts.
                    if (backStack.last() is Route.SettingsPage) backStack.setLast(Route.SettingsPage)
                    else backStack.add(Route.SettingsPage)
                },
            )
        )
    }
}
