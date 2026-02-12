package com.vayunmathur.maps.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.util.LocalNavResultRegistry
import com.vayunmathur.library.util.pop
import com.vayunmathur.maps.Route
import com.vayunmathur.maps.SelectedFeatureViewModel
import com.vayunmathur.maps.data.AmenityDatabase
import com.vayunmathur.maps.data.AmenityEntity
import com.vayunmathur.maps.data.OpeningHours
import com.vayunmathur.maps.data.SpecificFeature
import kotlinx.coroutines.launch
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

/**
 * A Search Page that filters amenities based on a text query and a geographic bounding box.
 * Results are dispatched back to the navigation registry upon selection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchPage(
    backStack: NavBackStack<Route>,
    viewModel: SelectedFeatureViewModel,
    db: AmenityDatabase,
    idx: Int?,
    east: Double,
    west: Double,
    north: Double,
    south: Double
) {
    var searchQuery by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<AmenityEntity>()) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    TextField(
                        value = searchQuery,
                        onValueChange = { query ->
                            searchQuery = query
                            scope.launch {
                                // Trigger search if query length is sufficient
                                if (query.length >= 2) {
                                    // Using the bounding box provided (North, South, East, West)
                                    // and the FTS4 wildcard search pattern
                                    results = db.amenityDao().getInBBox(
                                        query = "*$query*",
                                        latMin = south,
                                        latMax = north,
                                        lonMin = west,
                                        lonMax = east
                                    )
                                } else {
                                    results = emptyList()
                                }
                            }
                        },
                        placeholder = { Text("Search nearby...") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                            disabledContainerColor = androidx.compose.ui.graphics.Color.Transparent,
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
                    text = "No results found in this area",
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.bodyLarge
                )
            } else if (searchQuery.length < 2) {
                Text(
                    text = "Type at least 2 characters to search",
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(results) { amenity ->
                        ListItem(
                            headlineContent = { Text(amenity.name.ifBlank { "Unnamed Amenity" }) },
                            supportingContent = {
                                Text("Coordinates: ${String.format("%.4f", amenity.lat)}, ${String.format("%.4f", amenity.lon)}")
                            },
                            modifier = Modifier.clickable {
                                scope.launch {
                                    println("CLICKED")
                                    val tags = db.tagDao().getTags(amenity.id).associate { it.key to it.value }
                                    println("GOT TAGS")
                                    val feature = SpecificFeature.Restaurant(tags["name"] ?: "", tags["phone"], tags["website"], tags["website:menu"], tags["opening_hours"]?.let { OpeningHours.from(it) },
                                        Position(amenity.lon, amenity.lat)
                                    )
                                    println("MADE FEATURES")
                                    // Dispatch the selected result back to the registry
                                    val f = viewModel.selectedFeature.value as SpecificFeature.Route
                                    viewModel.set(f.copy(waypoints = f.waypoints.mapIndexed { idx2, it ->
                                        if (idx2 == idx) feature else it
                                    }))

                                    println("DISPATCHED RESULT")
                                    // Pop the backstack to return to the previous screen (the map)
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