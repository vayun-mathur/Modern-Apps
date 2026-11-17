package com.vayunmathur.maps.ui
import com.vayunmathur.maps.data.string

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
import com.vayunmathur.maps.ui.effectiveFeature
import com.vayunmathur.maps.ui.popSheet
import com.vayunmathur.maps.ui.replaceFeature
import com.vayunmathur.maps.ui.selectFeature
import com.vayunmathur.maps.platform.MapsSearchViewModel
import com.vayunmathur.maps.platform.SelectedFeatureViewModel
import kotlinx.coroutines.launch

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
    sheetStack: com.vayunmathur.library.util.NavBackStack<com.vayunmathur.maps.ui.SheetRoute> =
        com.vayunmathur.library.util.NavBackStack(arrayOf(com.vayunmathur.maps.ui.SheetRoute.Browse)),
    searchRoute: com.vayunmathur.maps.ui.SheetRoute.Search? = null,
) {
    val searchRequest = searchRoute

    LaunchedEffect(Unit, wide) {
        // Raise the sheet if something is already selected — unless a deep link is about to open
        // the compact pane instead, which the pendingFocus effect below handles.
        if (!wide && selectedFeature != null && viewModel.pendingFocus.value == null) {
            sheetState?.partialExpand()
        }
    }

    // A place requested by a non-composition caller (deep link, contact
    // shortcut) is pushed on the stack here; the focus effect below flies.
    val pendingPlace by viewModel.pendingPlace.collectAsState()
    LaunchedEffect(pendingPlace) {
        val feature = pendingPlace ?: return@LaunchedEffect
        sheetStack.selectFeature(feature)
        viewModel.consumePendingPlace()
    }

    // Offline enrichment of a bare GenericPlace, written back onto its
    // stack entry (the choke point the ViewModel selection used to be).
    LaunchedEffect(selectedFeature) {
        val place = selectedFeature as? SpecificFeature.GenericPlace ?: return@LaunchedEffect
        val enriched = viewModel.enrichedOrNull(place) ?: return@LaunchedEffect
        if (sheetStack.backStack.effectiveFeature() === place) {
            sheetStack.replaceFeature(place, enriched)
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
        val place = sheetStack.backStack.effectiveFeature() as? SpecificFeature.GenericPlace
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
        if (upgrade != null && sheetStack.backStack.effectiveFeature() === place) {
            sheetStack.replaceFeature(place, upgrade)
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
    // Search is a stack entry: while it is on top the search sheet is up
    // and the place sheet is down; popping it hands the place sheet back.
    // No latch is needed — the previous top is still on the stack.
    var searchWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(searchRequest, wide) {
        if (wide) return@LaunchedEffect
        if (searchRequest != null) {
            searchWasOpen = true
            launch { sheetState?.hide() }
            searchSheetState?.partialExpand()
        } else if (searchWasOpen) {
            searchWasOpen = false
            launch { searchSheetState?.hide() }
            if (sheetStack.backStack.effectiveFeature() != null) sheetState?.partialExpand()
        }
    }

    // Selecting anything on the still-live map underneath supersedes the search: the user has
    // found what they were looking for by pointing at it. Guarded the same way
    // as the sheet effects below: in wide mode the panel reads this state directly.
    LaunchedEffect(selectedFeature, wide) {
        if (!wide && selectedFeature != null && sheetStack.last() is com.vayunmathur.maps.ui.SheetRoute.Search) {
            sheetStack.pop()
        }
    }

    // One back handler for the whole sheet stack: search pops back to the
    // place/route it covered, a place over a parked route pops back to the
    // route, a route pops back to its place, and Browse lets the outer
    // graph handle back. The old three stacked handlers were this stack
    // spelled out in registration order.
    BackHandler(sheetStack.backStack.size > 1) {
        sheetStack.popSheet()
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
