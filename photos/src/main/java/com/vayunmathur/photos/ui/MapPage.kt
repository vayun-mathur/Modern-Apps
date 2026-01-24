package com.vayunmathur.photos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation3.runtime.NavBackStack
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.photos.ImageLoader
import com.vayunmathur.photos.NavigationBar
import com.vayunmathur.photos.Route
import com.vayunmathur.photos.data.Photo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.ClickResult
import org.maplibre.spatialk.geojson.Position
import kotlin.math.pow
import kotlin.math.sqrt

// Helper class to hold cluster data
data class MapCluster(
    val position: DpOffset,
    val coverPhoto: Photo,
    val allPhotos: List<Photo>,
    val count: Int
)

@Composable
fun MapPage(backStack: NavBackStack<Route>, viewModel: DatabaseViewModel) {
    val photos by viewModel.data<Photo>().collectAsState()

    // Prepare raw GPS positions
    val positions = remember(photos) {
        photos.filter { it.lat != null && it.long != null }
            .map { (it.lat!! to it.long!!) to it }
    }

    val cameraState = rememberCameraState()

    // State now holds Clusters instead of raw offsets
    var generatedClusters: List<MapCluster> by remember { mutableStateOf(listOf()) }
    var clusters: List<MapCluster> by remember { mutableStateOf(listOf()) }
    var selectedCluster: MapCluster? by remember { mutableStateOf(null) }

    val dpsize = LocalWindowInfo.current.containerDpSize

    LaunchedEffect(photos) {
        cameraState.awaitProjection()
        while (true) {
            val projection = cameraState.projection ?: continue
            val rawLocations = positions.map { (gps, photo) ->
                val dpOffset = projection.screenLocationFromPosition(
                    Position(gps.second, gps.first)
                )
                dpOffset to photo
            }.filter { (dpOffset, _) ->
                dpOffset.x.value > 0 && dpOffset.y.value > 0 && dpOffset.x < dpsize.width && dpOffset.y < dpsize.height
            }
            withContext(Dispatchers.IO) {
                // Update groupings
                generatedClusters = clusterPhotos(rawLocations, 50.dp)
                if(selectedCluster != null) {
                    selectedCluster = generatedClusters.find { selectedCluster!!.coverPhoto.id in it.allPhotos.map(Photo::id) }
                }
            }
            delay(200)
        }
    }

    Scaffold(bottomBar = { NavigationBar(Route.Map, backStack) }) { paddingValues ->
        Box(Modifier.padding(paddingValues).fillMaxSize()) {
            MaplibreMap(
                baseStyle = BaseStyle.Uri("https://tiles.openfreemap.org/styles/liberty"),
                cameraState = cameraState,
                onMapClick = { _, _ ->
                    selectedCluster = null
                    ClickResult.Pass
                },
                onFrame = {
                    val projection = cameraState.projection

                    if (projection != null) {
                        // 1. FAST PATH: Update positions of EXISTING clusters every frame.
                        // This prevents markers from "drifting" or "lagging" when panning/zooming
                        // between re-clustering intervals.
                        val updatedClusters = generatedClusters.mapNotNull { cluster ->
                            // Use the photo's original GPS to calculate new screen position
                            val lat = cluster.coverPhoto.lat
                            val long = cluster.coverPhoto.long

                            val nc = if (lat != null && long != null) {
                                val newOffset = projection.screenLocationFromPosition(
                                    Position(long, lat)
                                )
                                cluster.copy(position = newOffset)
                            } else {
                                null
                            }

                            if(cluster == selectedCluster) {
                                selectedCluster = nc
                            }

                            nc
                        }
                        clusters = updatedClusters
                    }
                }
            )

            // Layer: Markers
            Box(Modifier.fillMaxSize()) {
                clusters.forEach { cluster ->
                    // Main Marker Box
                    Box(
                        Modifier
                            .offset(cluster.position.x, cluster.position.y)
                            .size(50.dp)
                            // Optional: Add shadow or border for better visibility against map
                            .background(Color.White, shape = MaterialTheme.shapes.small)
                            .padding(2.dp) // creates a small white border effect
                    ) {
                        // The Image
                        ImageLoader.PhotoItem(cluster.coverPhoto, Modifier.fillMaxSize()) {
                            selectedCluster = cluster
                        }

                        // The "Bubble" Count Indicator
                        if (cluster.count > 1) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 8.dp, y = (-8).dp) // Shift slightly outside/corner
                                    .size(22.dp)
                                    .background(Color.Red, CircleShape)
                                    .border(1.dp, Color.White, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = cluster.count.toString(),
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
                selectedCluster?.let { selectedCluster ->
                    Surface(Modifier.align(Alignment.BottomCenter), color = MaterialTheme.colorScheme.background) {
                        LazyRow(
                            Modifier.height(100.dp).padding(vertical = 8.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            item {
                                Spacer(Modifier.padding(8.dp))
                            }
                            items(selectedCluster.allPhotos, key = {it.id}, contentType = { "photo_thumbnail" }) {
                                ImageLoader.PhotoItem(it, Modifier.fillMaxHeight().aspectRatio(1f)) {
                                    backStack.add(Route.PhotoPage(it.id, selectedCluster.allPhotos))
                                }
                            }
                            item {
                                Spacer(Modifier.padding(8.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Greedy clustering algorithm.
 * Iterates through items and adds them to an existing cluster if within threshold,
 * otherwise creates a new cluster.
 */
fun clusterPhotos(
    items: List<Pair<DpOffset, Photo>>,
    threshold: Dp
): List<MapCluster> {
    val result = ArrayList<MapCluster>()

    // We need threshold in raw float value for distance check (Unit doesn't matter as long as X/Y are same unit)
    val thresholdVal = threshold.value

    for ((pos, photo) in items) {
        // Find the first cluster that is close enough to this photo
        val existingIndex = result.indexOfFirst { cluster ->
            calculateDistance(cluster.position, pos) < thresholdVal
        }

        if (existingIndex >= 0) {
            // "Absorb" into existing cluster
            val existing = result[existingIndex]
            result[existingIndex] = existing.copy(count = existing.count + 1, allPhotos = existing.allPhotos + photo)
        } else {
            // Create new cluster
            result.add(MapCluster(pos, photo, listOf(photo), 1))
        }
    }
    return result.map { it.copy(allPhotos = it.allPhotos.sortedByDescending(Photo::date)) }
}

private fun calculateDistance(p1: DpOffset, p2: DpOffset): Float {
    val dx = p1.x.value - p2.x.value
    val dy = p1.y.value - p2.y.value
    return sqrt(dx.pow(2) + dy.pow(2))
}