package com.vayunmathur.maps.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.util.MapsSearchViewModel
import com.vayunmathur.maps.util.SearchActions
import com.vayunmathur.maps.util.SearchResult
import com.vayunmathur.maps.util.SelectedFeatureViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The search adapter: hoisted out of the sheet so the wide side panel can
 * pass the same callbacks — building it needs the open request, and both
 * hosts close through the same `onCloseSearch`.
 */
@Composable
internal fun rememberSearchActions(
    searchRequest: SearchRequest?,
    searchViewModel: MapsSearchViewModel,
    viewModel: SelectedFeatureViewModel,
    coroutineScope: CoroutineScope,
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
                    // The selection can have changed under the sheet — the map is live —
                    // so tolerate a non-Route current selection rather than crashing.
                    val current = viewModel.selectedFeature.value
                    if (index != null && current is SpecificFeature.Route) {
                        viewModel.set(
                            current.copy(
                                waypoints = current.waypoints.mapIndexed { i, waypoint ->
                                    if (i == index) feature else waypoint
                                },
                            ),
                        )
                    } else {
                        // Focus as well as select: without the camera move a
                        // distant result opened its card over a map still
                        // showing somewhere else (and any region mask with
                        // it off-screen). selectAndFocus is the same path
                        // deep links use.
                        viewModel.selectAndFocus(feature)
                        onCloseSearch()
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
