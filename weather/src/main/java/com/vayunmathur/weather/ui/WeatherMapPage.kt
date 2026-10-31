package com.vayunmathur.weather.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.IconBack
import com.vayunmathur.library.ui.FilledTonalIconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Slider
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.weather.R
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.weather.Route
import com.vayunmathur.weather.domain.WeatherMetric
import com.vayunmathur.weather.domain.colorRamp
import com.vayunmathur.weather.domain.formatInstantInZone
import com.vayunmathur.weather.domain.metricValueFormatter
import com.vayunmathur.weather.platform.rememberPressureUnit
import com.vayunmathur.weather.platform.rememberTempUnit
import com.vayunmathur.weather.platform.rememberUse24Hour
import com.vayunmathur.weather.platform.rememberWindUnit
import kotlin.math.roundToInt
import kotlinx.datetime.TimeZone
import com.vayunmathur.library.map.CameraPosition
import com.vayunmathur.library.map.GestureOptions
import com.vayunmathur.library.map.ImageOverlay
import com.vayunmathur.library.map.MapOptions
import com.vayunmathur.library.map.OrnamentOptions
import com.vayunmathur.library.map.MapStyle
import com.vayunmathur.library.map.VectorMap
import com.vayunmathur.library.map.rememberCameraState
import com.vayunmathur.library.util.ConnectivityMonitor
import com.vayunmathur.library.map.GeoPoint

/**
 * Full-screen map that shades an area by a chosen weather [metric], decoded
 * natively from the self-hosted binary `.om` spatial files (keyless,
 * model-native resolution) via the Rust/JNI [OmTilesNative] bridge. The decoded field is
 * colorized to a bitmap and drawn as a translucent [RasterLayer] over a muted
 * raster basemap. Panning/zooming re-decodes only the visible region; the
 * measure and time step can be changed on the fly. Pre-set from the graph sheet
 * to a location + time + measure.
 */
@Composable
fun WeatherMapPage(
    backStack: NavBackStack<Route>,
    latitude: Double,
    longitude: Double,
    isoTime: String?,
    metric: String,
) {
    val tempUnit = rememberTempUnit()
    val windUnit = rememberWindUnit()
    val pressureUnit = rememberPressureUnit()
    val use24Hour = rememberUse24Hour()

    val state = rememberWeatherMapState(metric)

    // north-up, no tilt so the axis-aligned image quad stays correct.
    val camera = rememberCameraState(
        CameraPosition(target = GeoPoint(longitude, latitude), zoom = 7.0),
    )

    WeatherMapMetadataEffect(state, isoTime)
    WeatherMapCameraEffects(state, camera)
    WeatherMapDecodeEffect(state)

    val selectedMetric = state.selectedMetric
    val times = state.times

    // The viewer's own zone: the primary time label is always shown in it.
    val userZone = remember { TimeZone.currentSystemDefault() }

    val ramp = selectedMetric.colorRamp
    val valueFormatter = metricValueFormatter(selectedMetric, tempUnit, windUnit, pressureUnit)

    Box(modifier = Modifier.fillMaxSize()) {
        VectorMap(
            modifier = Modifier.fillMaxSize(),
            cameraState = camera,
            // Muted, so the colour-ramp overlay below reads over the basemap rather than
            // competing with it. This is what TileSource.CartoPositron was for.
            style = MapStyle.Muted,
            options = MapOptions(
                // Lock rotation/tilt so the north-up image quad stays aligned.
                gestureOptions = GestureOptions.TiltLocked,
                // Ornaments off. Attribution is not an ornament any more — the overlay that
                // drew it was removed, and nothing renders it in its place, so this app
                // ships without the ODbL credit the OpenStreetMap data requires. Weather's
                // own copy in the bottom panel was removed rather than duplicated; adding
                // the credit back somewhere visible is a separate, still-open call.
                ornamentOptions = OrnamentOptions.AllDisabled,
            ),
            imageOverlay = state.overlay?.let { bmp ->
                state.overlayBounds?.let { bounds -> ImageOverlay(bmp, bounds, 0.7f) }
            },
            online = ConnectivityMonitor.isOnline,
        )

        // Top bar: back + title + measure selector.
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalIconButton(onClick = { backStack.pop() }) {
                    IconBack()
                }
                MeasureSelector(
                    selected = selectedMetric,
                    options = state.supportedMetrics,
                    onSelect = { state.selectedMetric = it },
                )
            }
            if (state.loading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }

        // Bottom panel: time scrubber + legend.
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(12.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            shape = MaterialTheme.shapes.largeIncreased,
            tonalElevation = 3.dp,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                val currentIso = times.getOrNull(state.timeIndex)
                if (currentIso != null) {
                    Text(
                        text = formatInstantInZone(currentIso, userZone, use24Hour),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    val region = state.regionTz
                    val regionZone = region?.timezone?.let {
                        runCatching { TimeZone.of(it) }.getOrNull()
                    }
                    if (regionZone != null && regionZone.id != userZone.id) {
                        val abbrev = region.abbreviation?.let { " · $it" }.orEmpty()
                        Text(
                            text = formatInstantInZone(currentIso, regionZone, use24Hour) + abbrev,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Text(
                        text = stringResource(UiR.string.time),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (times.size > 1) {
                    Slider(
                        value = state.timeIndex.toFloat(),
                        onValueChange = {
                            state.userScrubbed = true
                            state.timeIndex = it.roundToInt().coerceIn(0, times.size - 1)
                        },
                        valueRange = 0f..(times.size - 1).toFloat(),
                        steps = (times.size - 2).coerceAtLeast(0),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Legend(
                    metric = selectedMetric,
                    minLabel = valueFormatter(ramp.first().value),
                    maxLabel = valueFormatter(ramp.last().value),
                )
            }
        }
    }
}
