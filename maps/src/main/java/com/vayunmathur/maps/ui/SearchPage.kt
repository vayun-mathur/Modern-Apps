package com.vayunmathur.maps.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.maps.R
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.util.round
import com.vayunmathur.maps.Route
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.util.MapsSearchViewModel
import com.vayunmathur.maps.util.SelectedFeatureViewModel
import com.vayunmathur.maps.data.AmenityDatabase

/**
 * A Search Page that filters amenities based on a text query and a geographic bounding box.
 * Results are dispatched back to the navigation registry upon selection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchPage(
    backStack: NavBackStack<Route>,
    viewModel: SelectedFeatureViewModel,
    searchViewModel: MapsSearchViewModel,
    db: AmenityDatabase,
    idx: Int?,
    east: Double,
    west: Double,
    north: Double,
    south: Double
) {
    val searchQuery by searchViewModel.query.collectAsState()
    val results by searchViewModel.results.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    TextField(
                        value = searchQuery,
                        onValueChange = { query ->
                            searchViewModel.setQuery(query, db, west, east, south, north)
                        },
                        placeholder = { Text(stringResource(R.string.search_nearby)) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                        ),
                        leadingIcon = { IconSearch() },
                        singleLine = true
                    )
                },
                navigationIcon = {
                    IconNavigation(backStack)
                }
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            if (results.isEmpty() && searchQuery.length >= 2) {
                Text(
                    text = stringResource(R.string.no_results_found),
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.bodyLarge
                )
            } else if (searchQuery.length < 2) {
                Text(
                    text = stringResource(R.string.type_to_search),
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(results, key = { it.id }) { amenity ->
                        ListItem(
                            headlineContent = { Text(amenity.name.ifBlank { stringResource(R.string.unnamed_amenity) }) },
                            supportingContent = {
                                Text(stringResource(R.string.coordinates, amenity.lat.round(4), amenity.lon.round(4)))
                            },
                            modifier = Modifier.clickable {
                                searchViewModel.resolveAmenity(amenity, db) { feature ->
                                    if (idx != null) {
                                        val f = viewModel.selectedFeature.value as SpecificFeature.Route
                                        viewModel.set(f.copy(waypoints = f.waypoints.mapIndexed { idx2, it ->
                                            if (idx2 == idx) feature else it
                                        }))
                                    } else {
                                        viewModel.set(feature)
                                    }
                                    backStack.pop()
                                }
                            }
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
        }
    }
}
