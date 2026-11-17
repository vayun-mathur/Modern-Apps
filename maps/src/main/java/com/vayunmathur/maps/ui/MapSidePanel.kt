package com.vayunmathur.maps.ui
import androidx.compose.foundation.layout.Arrangement
import com.vayunmathur.maps.platform.SearchActions

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.platform.NavigationSessionManager
import com.vayunmathur.maps.platform.PlacePanelActions
import com.vayunmathur.maps.platform.PlacePanelState
import com.vayunmathur.maps.domain.RouteService
import com.vayunmathur.maps.platform.SearchUiState

/**
 * The expanded-width side panel: the same content the compact layout shows in
 * bottom sheets, rendered as a full-height column next to the map.
 *
 * Which content is decided by the caller ([searchOpen] / [selectedFeature]);
 * this composable only lays it out. Search reuses [SearchSheet] as-is — its
 * back arrow is what closes it — while a place or route gets a close button
 * on top (a sheet is dismissed by dragging; a panel needs an affordance) over
 * the shared [BottomSheetHeader] + [BottomSheetContent]. The inner lists keep
 * their own scrolling; nothing here scrolls, so the panel simply bounds them
 * the way a sheet does.
 */
@Composable
fun MapSidePanel(
    searchOpen: Boolean,
    searchState: SearchUiState,
    searchActions: SearchActions,
    panelState: PlacePanelState,
    panelActions: PlacePanelActions,
    selectedFeature: SpecificFeature?,
    onSelectFeature: (SpecificFeature?) -> Unit,
    route: Map<RouteService.TravelMode, RouteService.RouteType?>?,
    selectedRouteType: RouteService.TravelMode,
    onSelectedRouteType: (RouteService.TravelMode) -> Unit,
    inactiveNavigation: SpecificFeature.Route?,
    navState: NavigationSessionManager.NavState,
    onClosePanel: () -> Unit,
    modifier: Modifier = Modifier,
    onStartNavigation: ((String, RouteService.TravelMode) -> Unit)? = null,
    alternatives: List<RouteService.Route> = emptyList(),
    selectedAlternative: RouteService.Route? = null,
    onSelectAlternative: (RouteService.Route?) -> Unit = {},
) {
    Surface(modifier = modifier) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).imePadding()) {
            if (searchOpen) {
                SearchSheet(searchState, searchActions, Modifier.padding(top = Spacing.sm))
            } else {
                val feature = selectedFeature
                if (feature != null) {
                    // The close control first, so the panel has a dismissal affordance
                    // a sheet gets from its drag handle; inset with the content below.
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        IconButton(onClick = onClosePanel) { IconClose() }
                    }
                    BottomSheetHeader(
                        panelState,
                        panelActions,
                        selectedFeature,
                        onSelectFeature,
                        inactiveNavigation,
                        Modifier.padding(horizontal = Spacing.lg),
                    )
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.lg)
                            .padding(top = Spacing.sm),
                    ) {
                        BottomSheetContent(
                            panelState,
                            panelActions,
                            feature,
                            route,
                            selectedRouteType,
                            onSelectedRouteType,
                            navState,
                            onStartNavigation = onStartNavigation,
                            alternatives = alternatives,
                            selectedAlternative = selectedAlternative,
                            onSelectAlternative = onSelectAlternative,
                        )
                    }
                }
            }
        }
    }
}
