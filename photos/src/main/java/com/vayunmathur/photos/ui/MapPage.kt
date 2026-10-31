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
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.photos.NavigationBar
import com.vayunmathur.photos.Route
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.util.GalleryViewModel
import com.vayunmathur.photos.util.ImageLoader
import com.vayunmathur.photos.util.PhotoMapViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withContext
import androidx.compose.runtime.snapshotFlow
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.map.GestureOptions
import com.vayunmathur.library.map.MapOptions
import com.vayunmathur.library.map.VectorMap
import com.vayunmathur.library.map.rememberCameraState

// Helper class to hold cluster data
data class MapCluster(
    /**
     * Geographic, not screen space. The marker layer places itself from the live camera, so
     * a cluster's position no longer goes stale between re-clustering passes and there is
     * nothing to re-project per frame.
     */
    val position: GeoPoint,
    val coverPhoto: Photo,
    val allPhotos: List<Photo>,
    val count: Int,
)

/** Minimum gap between re-clustering passes while the camera keeps moving. */
private const val CLUSTER_THROTTLE_MS = 200L

@Composable
fun MapPage(
    backStack: NavBackStack<Route>,
    galleryViewModel: GalleryViewModel,
    photoMapViewModel: PhotoMapViewModel,
) {
    val photos by galleryViewModel.photos.collectAsState()

    // Prepare raw GPS positions
    val positions = remember(photos) {
        photos.filter { it.lat != null && it.long != null }
            .map { (it.lat!! to it.long!!) to it }
    }

    val cameraState = rememberCameraState()

    // Clusters managed by VM (CPU-bound generation on Dispatchers.Default)
    val generatedClusters by photoMapViewModel.generatedClusters.collectAsState()
    var selectedCluster: MapCluster? by remember { mutableStateOf(null) }

    val dpsize = LocalWindowInfo.current.containerDpSize

    // Driven by camera movement rather than a fixed 200 ms tick, so an idle map
    // does no work. This also removes the old `?: continue` on a null projection,
    // which skipped the delay and spun the main dispatcher without suspending — a
    // hard UI freeze until the viewport was measured.
    //
    // Projection is pure maths over immutable captured values, so the pass is safe
    // off the main thread. conflate plus the trailing delay throttle a continuous
    // pan to one pass per interval, dropping superseded camera states.
    LaunchedEffect(positions, dpsize) {
        snapshotFlow { cameraState.projection }
            .filterNotNull()
            .conflate()
            .collect { projection ->
                val rawLocations = withContext(Dispatchers.Default) {
                    positions.mapNotNull { (gps, photo) ->
                        val geo = GeoPoint(gps.second, gps.first)
                        val dpOffset = projection.screenLocationFromPosition(geo)
                        val visible = dpOffset.x.value > 0 && dpOffset.y.value > 0 &&
                            dpOffset.x < dpsize.width && dpOffset.y < dpsize.height
                        if (visible) Triple(dpOffset, geo, photo) else null
                    }
                }
                photoMapViewModel.regenerateClusters(rawLocations, 50.dp)
                delay(CLUSTER_THROTTLE_MS)
            }
    }

    LaunchedEffect(generatedClusters) {
        selectedCluster = selectedCluster?.let { current ->
            generatedClusters.find { current.coverPhoto.id in it.allPhotos.map(Photo::id) }
        }
    }

    // RAW SCAFFOLD EXCEPTION: full-bleed map main-nav page with only a bottom
    // NavigationBar and no top app bar. AppScaffold always renders a top app bar (which
    // would break the immersive map) and the body is not a LazyColumn.
    Scaffold(bottomBar = { NavigationBar(Route.Map, backStack) }) { paddingValues ->
        Box(Modifier.padding(paddingValues).fillMaxSize()) {
            VectorMap(
                cameraState = cameraState,
                options = MapOptions(gestureOptions = GestureOptions.TiltLocked),
                onMapClick = {
                    selectedCluster = null
                },
                // Photos has no :library:network dependency (offline-first
                // gallery) so it keeps the constant-online default: its map
                // content is local photos plus cached tiles (#884).
            ) {
                // Chips are centre-anchored, which is a deliberate 25 dp shift: this used to
                // offset a 50 dp chip by the raw projected point, so every cluster was drawn
                // down and right of the place it described.
                generatedClusters.forEach { cluster ->
                    MapMarker(cluster.position) {
                        Box(
                            Modifier
                                .size(50.dp)
                                .background(Color.White, shape = MaterialTheme.shapes.small)
                                .padding(2.dp)
                        ) {
                            ImageLoader.PhotoItem(cluster.coverPhoto, Modifier.fillMaxSize()) {
                                selectedCluster = cluster
                            }
                            if (cluster.count > 1) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .offset(x = 8.dp, y = (-8).dp)
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

            selectedCluster?.let { selectedCluster ->
                Surface(Modifier.align(Alignment.BottomCenter), color = MaterialTheme.colorScheme.background) {
                    LazyRow(
                        Modifier.height(100.dp).padding(vertical = 8.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            Spacer(Modifier.padding(8.dp))
                        }
                        items(selectedCluster.allPhotos, key = { it.id }, contentType = { "photo_thumbnail" }) {
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
