package com.vayunmathur.maps.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.wear.compose.material.MaterialTheme
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.maps.wear.domain.WearLineKind
import com.vayunmathur.maps.wear.domain.WearManeuver
import com.vayunmathur.maps.wear.domain.WearMapLine
import com.vayunmathur.maps.wear.domain.WearMapPoint
import com.vayunmathur.maps.wear.domain.WearMapSnapshot
import com.vayunmathur.maps.wear.domain.WearPosition
import com.vayunmathur.maps.wear.domain.WearRouteState
import com.vayunmathur.maps.wear.domain.WearSavedPlace

/** Round watch, 454x454px class (227dp at 320dpi). */
private const val ROUND = "spec:width=227dp,height=227dp,dpi=320"

/** Fixed preview instant: 2026-11-25 14:41 UTC. */
private const val FIXED_NOW_MILLIS = 1795617660000L

// Berlin centre; geometry is quantized metres relative to it (see WearMapsProtocol).
private val fixtureSnapshot = WearMapSnapshot(
    generatedAtMillis = FIXED_NOW_MILLIS,
    centerLatE7 = 525200000,
    centerLonE7 = 134050000,
    lines = listOf(
        WearMapLine(kind = WearLineKind.ROAD, pts = listOf(-60, 0, 60, 0)),
        WearMapLine(kind = WearLineKind.ROAD, pts = listOf(0, -60, 0, 60)),
        WearMapLine(kind = WearLineKind.PATH, pts = listOf(-60, -40, 60, 40)),
    ),
    points = listOf(WearMapPoint(name = "Cafe", dx = 20, dy = 15)),
    saved = listOf(
        WearSavedPlace(name = "Home", latE7 = 525210000, lonE7 = 134060000),
        WearSavedPlace(name = "Office", latE7 = 525180000, lonE7 = 134020000),
    ),
)

private val fixtureRoute = WearRouteState(
    active = true,
    destinationName = "Central Station",
    nextText = "Turn left",
    distanceToNextM = 120,
    distanceRemainingM = 1800,
    maneuvers = listOf(
        WearManeuver(text = "Head north on Main Street", kind = "STRAIGHT", distanceFromStartM = 0),
        WearManeuver(text = "Turn left onto High Street", kind = "TURN_LEFT", distanceFromStartM = 120),
        WearManeuver(text = "Arrive at Central Station", kind = "ARRIVE", distanceFromStartM = 1800),
    ),
)

/**
 * Store listing images for `:maps:wear` — every top-level screen/route (map, route detail, saved).
 * The map capture renders the real Canvas vector renderer over a literal snapshot fixture.
 * `./gradlew :maps:wear:metadata` copies renders into `metadata_data/photos/maps-wear/wear/`.
 */
class MetadataPreviews {

    @PreviewTest
    @Preview(name = "1-map-round", device = ROUND, group = "wear")
    @Composable
    fun Preview1MapRound() {
        RoundPreview {
            WearMapContent(snapshot = fixtureSnapshot, route = fixtureRoute, position = WearPosition(latE7 = 525200000, lonE7 = 134050000, bearingDeg = 90), connected = true, nowMillis = FIXED_NOW_MILLIS, onOpenRoute = {}, onOpenSaved = {}, onRequestRegion = { true })
        }
    }

    @PreviewTest
    @Preview(name = "2-route-round", device = ROUND, group = "wear")
    @Composable
    fun Preview2RouteRound() {
        RoundPreview { WearRouteDetailContent(route = fixtureRoute, onBack = {}) }
    }

    @PreviewTest
    @Preview(name = "3-saved-round", device = ROUND, group = "wear")
    @Composable
    fun Preview3SavedRound() {
        RoundPreview {
            WearSavedContent(snapshot = fixtureSnapshot, position = WearPosition(latE7 = 525200000, lonE7 = 134050000), onBack = {})
        }
    }
}

/** Round-watch framing: clip Layoutlib's rectangular canvas to the watch shape. */
@Composable
private fun RoundPreview(content: @Composable () -> Unit) {
    MaterialTheme {
        Box(Modifier.fillMaxSize().clip(CircleShape).background(MaterialTheme.colors.background)) { content() }
    }
}
