package com.vayunmathur.maps.ui.streetview
import com.vayunmathur.maps.data.string

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.PanoramaCrop
import com.vayunmathur.library.ui.PanoramaSphere
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBarOverlay
import com.vayunmathur.library.ui.rememberPanoramaCameraState
import com.vayunmathur.maps.R
import com.vayunmathur.maps.data.google.StreetViewDataSource
import com.vayunmathur.maps.data.google.StreetViewPano
import kotlinx.coroutines.launch

/**
 * Full-screen Google Street View viewer.
 *
 * RENDERER: a Street View panorama is equirectangular, so it is projected onto the
 * inside of a sphere by the shared [PanoramaSphere] — the same GLES renderer the
 * photos app uses for 360 photos, lifted into `:library:ui`. Dragging looks around
 * and pinching changes the field of view; there is no flat image to pan.
 *
 * NAVIGATION: arrows drawn into the scene by [StreetViewArrows], one per walkable
 * neighbour, pinned to their compass bearings. Tapping one travels there.
 */
@Composable
fun StreetViewScreen(initialPano: StreetViewPano, onClose: () -> Unit) {
    var pano by remember { mutableStateOf(initialPano) }
    var image by remember { mutableStateOf<Bitmap?>(null) }
    var loading by remember { mutableStateOf(true) }
    // Where to open the next panorama, so a step keeps the viewer facing the way
    // they were already going. Null for the first pano, which opens at the
    // capture heading.
    var carriedYaw by remember { mutableStateOf<Float?>(null) }

    val camera = rememberPanoramaCameraState()
    val scope = rememberCoroutineScope()

    // Load (and reload on pano change) the stitched equirect off the main thread.
    LaunchedEffect(pano.panoId) {
        loading = true
        image = null
        image = StreetViewDataSource.loadPanorama(pano)
        loading = false
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val current = image
        when {
            current != null -> {
                PanoramaSphere(
                    crop = PanoramaCrop.full(current.width, current.height),
                    textureKey = pano.panoId,
                    modifier = Modifier.fillMaxSize(),
                    cameraState = camera,
                    initialYaw = carriedYaw,
                ) { current }

                StreetViewArrows(pano = pano, camera = camera) { link ->
                    // Resolve first, then swap: a failed lookup leaves the viewer
                    // where it is rather than blanking the screen.
                    val facing = panoBearingForYaw(pano.headingDeg, camera.yaw)
                    scope.launch {
                        val next = StreetViewDataSource.byPano(link.panoId) ?: return@launch
                        carriedYaw = panoYawForBearing(next.headingDeg, facing)
                        pano = next
                    }
                }
            }
            loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            else -> Text(
                stringResource(R.string.street_view_unavailable),
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        TopAppBarOverlay(Modifier.align(Alignment.TopStart), onNavigateBack = onClose)

        Attribution(
            pano = pano,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(Spacing.md),
        )
    }
}

@Composable
private fun Attribution(pano: StreetViewPano, modifier: Modifier = Modifier) {
    val date = if (pano.captureYear != null && pano.captureMonth != null) {
        " · ${pano.captureYear}-${pano.captureMonth.toString().padStart(2, '0')}"
    } else {
        ""
    }
    Text(
        (pano.copyright ?: "\u00A9 Google") + date,
        color = Color.White.copy(alpha = 0.7f),
        style = MaterialTheme.typography.labelSmall,
        textAlign = TextAlign.Center,
        modifier = modifier,
    )
}
