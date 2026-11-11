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
import com.vayunmathur.library.ui.Text
import com.vayunmathur.maps.data.Feature1
import com.vayunmathur.maps.data.SavedPlace
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.data.string
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Pin id — hit-tested in MapSurface.onMapClick so a tapped saved pin
 *  re-selects that place. */
const val SAVED_PLACE_LAYER_ID = "saved-place-pins"

private val PIN_SIZE = 26.dp

/** Bookmark accent so saved pins read distinctly from the category-coloured
 *  ambient POIs and the accent search-result pins. */
private val SAVED_PLACE_COLOR = Color(0xFF1A73E8)

/**
 * Saved-place overlay: the starred list drawn as plain
 * Compose over VectorMap. Tap → details via [toSelectedSavedPlace], reusing
 * [SpecificFeature.GenericPlace] so the existing enrichment + place sheet render with no new
 * detail path.
 *
 * Was GeoJSON + CircleLayer + SymbolLayer (bookmark bitmap glyph); the renderer has no
 * vector-layer API, so each pin is a blue circle with a star glyph positioned via Projection.
 */
@Composable
fun SavedPlacesLayer(places: List<SavedPlace>, cameraState: CameraState) {
    if (places.isEmpty()) return
    val projection = cameraState.projection ?: return
    Box(Modifier.fillMaxSize()) {
        for (place in places) {
            val offset = projection.screenLocationFromPosition(GeoPoint(place.lon, place.lat))
            Box(
                Modifier
                    .offset(offset.x - PIN_SIZE / 2, offset.y - PIN_SIZE / 2)
                    .size(PIN_SIZE)
                    .background(SAVED_PLACE_COLOR, CircleShape)
                    .border(2.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("★", color = Color.White, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * Convert a hit-tested saved pin feature back into a selectable place, reusing
 * [SpecificFeature.GenericPlace] so `SelectedFeatureViewModel.currentPoiInfo`
 * fetches the Google place details (photos/hours) and the place sheet renders.
 */
fun Feature1.toSelectedSavedPlace(): SpecificFeature? {
    val props = properties ?: return null
    val name = props.string("name")?.ifBlank { null } ?: return null
    val lat = props["lat"]?.jsonPrimitive?.doubleOrNull ?: return null
    val lng = props["lng"]?.jsonPrimitive?.doubleOrNull ?: return null
    return SpecificFeature.GenericPlace(name, null, null, null, GeoPoint(lng, lat))
}
