package com.vayunmathur.findfamily.wear.ui

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
import com.vayunmathur.findfamily.wear.domain.WearFindFamilySnapshot
import com.vayunmathur.findfamily.wear.domain.WearMotion
import com.vayunmathur.findfamily.wear.domain.WearPerson
import com.vayunmathur.findfamily.wear.domain.WearRegion

/** Round watch, 454x454px class (227dp at 320dpi). */
private const val ROUND = "spec:width=227dp,height=227dp,dpi=320"

/** Fixed preview instant: 2026-11-25 14:41 UTC. */
private const val FIXED_NOW_MILLIS = 1795617660000L

private val fixturePeople = listOf(
    WearPerson(id = 1, name = "You", placeLabel = "Home", latE7 = 525200000, lonE7 = 134050000, accuracyMeters = 12, updatedAtMillis = FIXED_NOW_MILLIS - 60_000L, batteryPercent = 82, motion = WearMotion.STATIONARY, isSelf = true),
    WearPerson(id = 2, name = "Ana Sol", placeLabel = "Office", latE7 = 525220000, lonE7 = 134070000, accuracyMeters = 18, updatedAtMillis = FIXED_NOW_MILLIS - 300_000L, batteryPercent = 64, motion = WearMotion.MOVING),
    WearPerson(id = 3, name = "Ben Carter", placeLabel = "Park", latE7 = 525180000, lonE7 = 134030000, accuracyMeters = 25, updatedAtMillis = FIXED_NOW_MILLIS - 900_000L, batteryPercent = 41, motion = WearMotion.STATIONARY),
)

private val fixtureSnapshot = WearFindFamilySnapshot(
    generatedAtMillis = FIXED_NOW_MILLIS,
    people = fixturePeople,
    sharingEnabled = true,
    region = WearRegion(centerLatE7 = 525200000, centerLonE7 = 134050000, radiusM = 500, hasCenter = true),
    totalPeople = 3,
)

/**
 * Store listing images for `:findfamily-wear` — every top-level screen/route
 * (people home, person detail, all-people relative map). No basemap is drawn:
 * the relative canvas is the app's real renderer over a literal snapshot.
 * `./gradlew :findfamily-wear:metadata` copies renders into `metadata_data/photos/findfamily-wear/wear/`.
 */
class MetadataPreviews {

    @PreviewTest
    @Preview(name = "1-people-round", device = ROUND, group = "wear")
    @Composable
    fun Preview1PeopleRound() {
        RoundPreview {
            WearPeopleContent(snapshot = fixtureSnapshot, connected = true, nowMillis = FIXED_NOW_MILLIS, onOpenPerson = {}, onOpenMap = {}, onRequestRefresh = { true }, onStopSharing = { true })
        }
    }

    @PreviewTest
    @Preview(name = "2-person-round", device = ROUND, group = "wear")
    @Composable
    fun Preview2PersonRound() {
        RoundPreview {
            WearPersonContent(person = fixturePeople[1], self = fixturePeople[0], nowMillis = FIXED_NOW_MILLIS, onBack = {})
        }
    }

    @PreviewTest
    @Preview(name = "3-all-map-round", device = ROUND, group = "wear")
    @Composable
    fun Preview3AllMapRound() {
        RoundPreview {
            WearAllMapContent(snapshot = fixtureSnapshot, connected = true, nowMillis = FIXED_NOW_MILLIS, onOpenPerson = {}, onBack = {})
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
