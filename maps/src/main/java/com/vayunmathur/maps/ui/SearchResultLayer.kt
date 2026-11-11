package com.vayunmathur.maps.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.map.CameraState
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.maps.data.Feature1
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.data.string
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Pin id — hit-tested in MapSurface.onMapClick (before the ambient POI
 *  layer) so a tapped search pin re-selects that place. */
const val SEARCH_RESULT_LAYER_ID = "search-result-pins"

private val PIN_SIZE = 26.dp
private val GLYPH_SIZE = 8.dp

/**
 * Search-result pin overlay: the offline index search results drawn
 * as plain Compose over VectorMap. Distinct from the ambient POI overlay so search pins read
 * as "your results" (a single accent colour) rather than the category-coloured ambient POIs.
 * Tap → details via [toSelectedSearchResult].
 *
 * Was GeoJSON + CircleLayer + SymbolLayer (bitmap glyph); the renderer has no vector-layer
 * API, so each pin is an accent circle with a white centre dot positioned via Projection.
 */
@Composable
fun SearchResultLayer(results: List<com.vayunmathur.maps.util.SearchResult>, cameraState: CameraState) {
    if (results.isEmpty()) return
    val projection = cameraState.projection ?: return
    val accent = MaterialTheme.colorScheme.primary
    Box(Modifier.fillMaxSize()) {
        for (result in results) {
            val offset = projection.screenLocationFromPosition(GeoPoint(result.lon, result.lat))
            Box(
                Modifier
                    .offset(offset.x - PIN_SIZE / 2, offset.y - PIN_SIZE / 2)
                    .size(PIN_SIZE)
                    .background(accent, CircleShape)
                    .border(2.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(GLYPH_SIZE)
                        .background(Color.White, CircleShape)
                )
            }
        }
    }
}

/**
 * Convert a hit-tested search pin feature back into a selectable place, reusing
 * [SpecificFeature.GenericPlace] so the existing
 * `SelectedFeatureViewModel.currentPoiInfo` flow fetches the Google place details (photos/hours)
 * and `GooglePoiEnrichment` renders in the sheet — no new detail path needed.
 */
fun Feature1.toSelectedSearchResult(): SpecificFeature? {
    val props = properties ?: return null
    val name = props.string("name")?.ifBlank { null } ?: return null
    val lat = props["lat"]?.jsonPrimitive?.doubleOrNull ?: return null
    val lng = props["lng"]?.jsonPrimitive?.doubleOrNull ?: return null
    return SpecificFeature.GenericPlace(name, null, null, null, GeoPoint(lng, lat))
}
