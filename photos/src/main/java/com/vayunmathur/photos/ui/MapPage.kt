package com.vayunmathur.photos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation3.runtime.NavBackStack
import coil.compose.AsyncImage
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.photos.NavigationBar
import com.vayunmathur.photos.Route
import com.vayunmathur.photos.data.Photo
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.spatialk.geojson.Position
import kotlin.math.pow
import kotlin.math.sqrt

// Helper class to hold cluster data
data class MapCluster(
    val position: DpOffset,
    val coverPhoto: Photo,
    val count: Int
)

@Composable
fun MapPage(backStack: NavBackStack<Route>, viewModel: DatabaseViewModel) {
    val photos by viewModel.data<Photo>().collectAsState()

    // Shuffle and take subset
    val photosFiltered = remember(photos) { photos.shuffled().take(100) }

    // Prepare raw GPS positions
    val positions = remember(photosFiltered) {
        photosFiltered.filter { it.lat != null && it.long != null }
            .map { (it.lat!! to it.long!!) to it }
    }

    val cameraState = rememberCameraState()
    val density = LocalDensity.current

    // State now holds Clusters instead of raw offsets
    var clusters: List<MapCluster> by remember { mutableStateOf(listOf()) }

    Scaffold(bottomBar = { NavigationBar(Route.Map, backStack) }) { paddingValues ->
        Box(Modifier.padding(paddingValues).fillMaxSize()) {
            MaplibreMap(
                cameraState = cameraState,
                onFrame = {
                    // 1. Calculate Screen Locations (Pixels)
                    val projection = cameraState.projection
                    if (projection != null) {
                        val rawLocations = positions.map { (gps, photo) ->
                            val screenLoc = projection.screenLocationFromPosition(
                                Position(gps.second, gps.first)
                            )
                            screenLoc to photo
                        }

                        // 3. Cluster them based on 50.dp threshold
                        clusters = clusterPhotos(rawLocations, 50.dp)
                    }
                }
            ) {}

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
                        AsyncImage(
                            model = cluster.coverPhoto.uri,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(MaterialTheme.shapes.extraSmall),
                            contentScale = ContentScale.Crop
                        )

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
            result[existingIndex] = existing.copy(count = existing.count + 1)
        } else {
            // Create new cluster
            result.add(MapCluster(pos, photo, 1))
        }
    }
    return result
}

private fun calculateDistance(p1: DpOffset, p2: DpOffset): Float {
    val dx = p1.x.value - p2.x.value
    val dy = p1.y.value - p2.y.value
    return sqrt(dx.pow(2) + dy.pow(2))
}