package com.vayunmathur.maps.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.vayunmathur.library.ui.FreeHeightBottomSheetScaffold
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.maps.ui.theme.MapChromeMetrics
import com.vayunmathur.maps.util.SearchActions
import com.vayunmathur.maps.util.SearchUiState

/**
 * The compact bottom sheets: search over place, both wrapping the shared map
 * box. Extracted from MapPage so no ui file exceeds the [FileLength] limit.
 *
 * The search sheet is the outer of the two, so it draws over the place pane
 * rather than fighting it for the bottom of the window. Its content lambda is
 * empty while search is closed, which the scaffold treats as "no sheet" and
 * does not place — so this wrapper costs nothing at all on the browse screen.
 */
@Composable
internal fun MapPageScope.MapSheets(
    searchActions: SearchActions?,
    searchState: SearchUiState,
) {
    // Non-null here: MapPage only reaches the sheets on compact widths, where
    // the scope was built with the real states (wide gets nulls and returns
    // through MapWideHost before ever arriving here).
    val sheet = searchSheetState ?: return
    val placeSheet = sheetState ?: return
    FreeHeightBottomSheetScaffold({
        if (searchActions != null) {
            SearchSheet(
                searchState,
                searchActions,
                // Top breathing room only. IME padding lives on the scaffold
                // root below, not here: this content is measured at a capped
                // height (the sheet's current offset), so padding inside it
                // shrinks the rows without lifting the sheet, and the keys
                // would still cover the field. Padding the scaffold root
                // shrinks the whole layout, which places the sheet above the
                // keyboard. Keep this free of inset modifiers so the sheet
                // is not double-counted once the scaffold lifts it.
                Modifier.padding(top = Spacing.sm),
            )
        }
    },
        // Ride above the keyboard: the field keeps focus while typing, and
        // the activity is edge-to-edge (MaActivity.enableEdgeToEdge), which
        // disables the manifest's adjustResize — the container does not
        // shrink on its own, and the scaffold only reads the navigation and
        // status bars as values. Without this the peek-0.35 search sheet
        // sits under the keys, input included.
        Modifier.imePadding(),
        sheet,
        // Roughly a third of the room the sheet has: enough to open on the field, the chips and
        // the first result or two, and no more. This is a floor as much as an opening height —
        // a drag cannot take the sheet below its peek — so a taller one would mean the map could
        // never be more than half uncovered for as long as search is open, which is the opposite
        // of the point. A fraction rather than a dp because it is a statement about the window,
        // not about the sheet's own type: it has to hold on a tablet and in landscape, and it
        // must not move when the user changes font size.
        sheetPeekFraction = 0.35f,
        // The phase, not just "is search open": the three phases with nothing to list are a fixed
        // height, so a sheet that opened on one of them has learned a ceiling that would trap the
        // results list at it once results arrive.
        contentKey = searchState.phase.takeIf { searchRequest != null },
    ) { _ ->
        FreeHeightBottomSheetScaffold({
            // Padding only when there is something to pad. An unconditional wrapper measures its
            // own padding even with no content, which defeats the scaffold's "don't place a sheet
            // that measured to nothing" guard and leaves a bare handle on screen.
            if (selectedFeature != null || route != null || inactiveNavigation != null) {
                Column(Modifier.padding(horizontal = Spacing.lg).padding(top = Spacing.sm)) {
                    BottomSheetContent(
                        viewModel,
                        selectedFeature,
                        route,
                        chrome.selectedRouteType,
                        { chrome.selectedRouteType = it },
                        savedPlacesViewModel,
                        transitViewModel,
                        navState,
                    )
                }
            }
        },
            Modifier,
            placeSheet,
            MapChromeMetrics.sheetPeekHeight,
            // The peek is measured off this rather than taken from sheetPeekHeight, so a place
            // peeks at exactly its title through its action row whatever the font scale. A
            // selection with no header falls back to the fixed height.
            sheetHeader = {
                BottomSheetHeader(
                    viewModel,
                    selectedFeature,
                    { viewModel.set(it) },
                    inactiveNavigation,
                    savedPlacesViewModel,
                    Modifier.padding(horizontal = Spacing.lg).padding(top = Spacing.sm),
                )
            },
            // The selection, the route-type, the POI tab AND the enrichment
            // identity: each names a different content height. The tab because
            // Details content teaches the scaffold a low ceiling that would
            // trap the taller Reviews list; the enrichment signature because
            // late-arriving reviews/photos grow the content after measurement.
            // Coarse (counts/presence, not the lists) so progressive streaming
            // settles the key instead of resetting it on every partial. Wide
            // layout has no sheet (MapSidePanel) and needs no key.
            contentKey = listOf(selectedFeature, chrome.selectedRouteType, poiSection, poiEnrichmentKey),
        ) { _ ->
            MapContentBox(Modifier.fillMaxSize())
        }
    }
}
