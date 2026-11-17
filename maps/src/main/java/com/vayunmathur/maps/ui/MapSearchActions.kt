package com.vayunmathur.maps.ui
import kotlinx.coroutines.CoroutineScope
import com.vayunmathur.maps.platform.SearchResult

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.ui.selectFeature
import com.vayunmathur.maps.platform.MapsSearchViewModel
import com.vayunmathur.maps.platform.SearchActions
import com.vayunmathur.maps.platform.SelectedFeatureViewModel
import kotlinx.coroutines.launch

/**
 * The search adapter: hoisted out of the sheet so the wide side panel can
 * pass the same callbacks — building it needs the open [SheetRoute.Search],
 * and both hosts close through the same `onCloseSearch`.
 */
@Composable
internal fun rememberSearchActions(
    searchRequest: com.vayunmathur.maps.ui.SheetRoute.Search?,
    searchViewModel: MapsSearchViewModel,
    viewModel: SelectedFeatureViewModel,
    coroutineScope: CoroutineScope,
    sheetStack: com.vayunmathur.library.util.NavBackStack<com.vayunmathur.maps.ui.SheetRoute>? = null,
    onCloseSearch: () -> Unit,
): SearchActions? {
    val openRequest = searchRequest ?: return null
    return remember(openRequest) {
        object : SearchActions {
            override fun setQuery(query: String) {
                searchViewModel.setQuery(query, openRequest.nearLat, openRequest.nearLon)
            }

            override fun clearRecents() {
                searchViewModel.clearRecents()
            }

            override fun selectResult(result: SearchResult) {
                searchViewModel.recordRecent(result.title)
                // Launched because building the feature reads the POI attribute sidecar
                // off the main thread; the close stays inside so the selection is in
                // place before the sheet hands the screen back.
                coroutineScope.launch {
                    val feature = searchViewModel.toFeature(result)
                    val index = openRequest.waypointIndex
                    // Editing a waypoint: rewrite that leg of the plan under
                    // the search sheet, then pop back to it.
                    val planIndex = sheetStack?.backStack?.indexOfLast {
                        it is com.vayunmathur.maps.ui.SheetRoute.RoutePlan
                    } ?: -1
                    val plan = sheetStack?.backStack?.getOrNull(planIndex)
                        as? com.vayunmathur.maps.ui.SheetRoute.RoutePlan
                    if (index != null && plan != null && sheetStack != null) {
                        sheetStack.set(
                            planIndex,
                            plan.copy(
                                waypoints = plan.waypoints.mapIndexed { i, waypoint ->
                                    if (i == index) feature as SpecificFeature.RoutableFeature else waypoint
                                },
                            ),
                        )
                        onCloseSearch()
                    } else {
                        // Focus as well as select: without the camera move a
                        // distant result opened its card over a map still
                        // showing somewhere else (and any region mask with
                        // it off-screen). selectAndFocus is the same path
                        // deep links use.
                        if (sheetStack != null) {
                            onCloseSearch()
                            sheetStack.selectFeature(feature)
                            viewModel.focusFeature(feature)
                        } else {
                            viewModel.selectAndFocus(feature)
                            onCloseSearch()
                        }
                    }
                }
            }

            override fun pickContactAddress(address: String) {
                searchViewModel.searchAndSelectFirst(
                    address, openRequest.nearLat, openRequest.nearLon,
                ) { first ->
                    if (first != null) selectResult(first)
                }
            }

            override fun back() {
                onCloseSearch()
            }
        }
    }
}
