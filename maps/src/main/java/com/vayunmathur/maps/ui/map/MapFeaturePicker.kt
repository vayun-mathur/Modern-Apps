package com.vayunmathur.maps.ui.map
import com.vayunmathur.maps.data.regionId

import com.vayunmathur.library.log.Log
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.map.PlacedLabel
import com.vayunmathur.maps.data.Feature1
import com.vayunmathur.maps.data.Point
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.data.transit.TransitStop
import com.vayunmathur.maps.ui.FAMILY_LOCATION_LAYER_ID
import com.vayunmathur.maps.ui.PARKING_PIN_LAYER_ID
import com.vayunmathur.maps.ui.SAVED_PLACE_LAYER_ID
import com.vayunmathur.maps.ui.SEARCH_RESULT_LAYER_ID
import com.vayunmathur.maps.ui.TRANSIT_STOP_LAYER_ID
import com.vayunmathur.maps.ui.theme.MapChromeMetrics
import com.vayunmathur.maps.ui.toSelectedFamilyMember
import com.vayunmathur.maps.ui.toSelectedSavedPlace
import com.vayunmathur.maps.ui.toSelectedSearchResult
import com.vayunmathur.maps.ui.toTransitStop
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What a tap on the map resolved to.
 *
 * A closed set, because the caller has to do something different for each and the compiler
 * should say so when a new one appears.
 */
sealed interface MapHit {
    /** The saved parking spot: opens the parking sheet rather than selecting a place. */
    data object Parking : MapHit

    /** A baked GTFS stop: opens its departure board. */
    data class Stop(val stop: TransitStop) : MapHit

    /** A pin or label that resolves to a selectable place. */
    data class Place(val feature: SpecificFeature) : MapHit
}

/**
 * Where features can be queried from. One method, so the picker can be tested without a
 * renderer — the whole reason this is a class and not a lambda inside a gesture handler.
 */
fun interface FeatureSource {
    fun query(box: DpRect, layerIds: Set<String>): List<Feature1>
}

/**
 * Resolves a tap to the topmost thing under it.
 *
 * Hit-testing is a **priority order**, not a set of conditions: a parking pin beats a transit
 * stop beats a search result, and so on down to the basemap's own labels. Expressed as an
 * ordered list of probes resolved by `firstNotNullOfOrNull`, so the order is a value that can
 * be read and asserted on rather than the shape of a hundred-line chain of `?:` and early
 * `return`s.
 *
 * Not a composable. [pickPin] suspends because a probe's resolver may do I/O. The admin-label
 * branch is the opposite arrangement — label resolution belongs to the ViewModel, which is why
 * [ADMIN_LABEL_LAYER_IDS] hits come back as raw features for the caller to resolve.
 *
 * Ambient POIs are **not** probed here. The renderer draws them, so the renderer picks them:
 * a tap arrives as `MapClick.poi` already resolved against the collision pass that put the icon
 * on screen. Rebuilding them as Compose features to hit-test in parallel is what this probe used
 * to do, and it meant the same viewport query ran twice per tap and could disagree with what was
 * drawn.
 */
class MapFeaturePicker(
    private val source: FeatureSource,
    /** Whether the transit layer is on. Stops are only probed when they are drawn. */
    private val transitEnabled: Boolean,
) {

    /**
     * One layer to probe, and how to turn a hit on it into a [MapHit].
     *
     * [enabled] rather than filtering the list at construction, so a disabled probe still
     * appears in [probes] and the order stays legible.
     */
    private class Probe(
        val layerId: String,
        val enabled: Boolean = true,
        val resolve: suspend (List<Feature1>) -> MapHit?,
    )

    private val probes: List<Probe> = listOf(
        Probe(PARKING_PIN_LAYER_ID) { hits -> if (hits.isNotEmpty()) MapHit.Parking else null },
        Probe(TRANSIT_STOP_LAYER_ID, enabled = transitEnabled) { hits ->
            hits.firstNotNullOfOrNull { it.toTransitStop() }?.let { MapHit.Stop(it) }
        },
        Probe(SEARCH_RESULT_LAYER_ID) { hits ->
            hits.firstNotNullOfOrNull { it.toSelectedSearchResult() }?.let { MapHit.Place(it) }
        },
        Probe(SAVED_PLACE_LAYER_ID) { hits ->
            hits.firstNotNullOfOrNull { it.toSelectedSavedPlace() }?.let { MapHit.Place(it) }
        },
        Probe(FAMILY_LOCATION_LAYER_ID) { hits ->
            hits.firstNotNullOfOrNull { it.toSelectedFamilyMember() }?.let { MapHit.Place(it) }
        },
    )

    /** The layers probed, in priority order. Disabled ones are omitted. */
    val probeOrder: List<String> get() = probes.filter { it.enabled }.map { it.layerId }

    /**
     * The topmost pin under [offset], or null when the tap landed on no pin.
     *
     * Every probe uses the same tolerance box so a tap NEAR a small glyph still counts; see
     * [MapChromeMetrics.hitSlop]. Ground fills are not probed: a tap on `landtype` (park,
     * water, farmland, …) returns null here and the caller falls through to reverse-geocode.
     */
    suspend fun pickPin(offset: DpOffset): MapHit? {
        val box = hitBox(offset)
        return probes.firstNotNullOfOrNull { probe ->
            if (!probe.enabled) null else probe.resolve(source.query(box, setOf(probe.layerId)))
        }
    }

    /**
     * The basemap's own place labels under [offset], returned unresolved.
     *
     * Queried at the exact point rather than through the tolerance box, matching the behaviour
     * this replaced. The native pick returns placed labels in placement order, so this is a
     * handful of candidates for the caller to resolve.
     */
    fun pickAdminLabels(offset: DpOffset): List<Feature1> =
        source.query(DpRect(offset, DpSize.Zero), ADMIN_LABEL_LAYER_IDS)

    private fun hitBox(offset: DpOffset): DpRect {
        val pad = MapChromeMetrics.hitSlop
        return DpRect(offset.x - pad, offset.y - pad, offset.x + pad, offset.y + pad)
    }

    /** Shared constants and helpers for MapFeaturePicker. */
    companion object {
        /**
         * The basemap label layers probed for a "what's here?" tap.
         *
         * Kept in the MapLibre split-id form (`places_country_base`, …) because
         * [MapFeaturePickerTest] pins the probe contract on it and the car path
         * still resolves the same names. [NATIVE_LABEL_LAYER_IDS] is what the
         * Vulkan renderer's [PlacedLabel.layerId] actually emits (flat dash ids
         * from `basemap.flat.json`); [toFeature1] maps between the two.
         */
        val ADMIN_LABEL_LAYER_IDS: Set<String> =
            setOf("places_country", "places_region", "places_locality")
                .flatMap { listOf("${it}_base", "${it}_hybrid") }
                .toSet()

        /**
         * The flat layer ids the native pick emits for the same labels
         * (see `basemap.flat.json`: `places-country`, …). `places-subplace`
         * (neighbourhoods) converts with `locality` semantics so a linked
         * subplace can mask; unlinked (`regionId == 0`) it null-masks.
         */
        val NATIVE_LABEL_LAYER_IDS: Set<String> = setOf(
            "places-country",
            "places-region",
            "places-locality",
            "places-subplace",
        )

        /** `[nativeId] -> [adminSplitBase]`, e.g. `places-country` -> `places_country`. */
        private fun nativeToBase(nativeId: String): String? = when (nativeId) {
            "places-country" -> "places_country"
            "places-region" -> "places_region"
            "places-locality" -> "places_locality"
            "places-subplace" -> "places_locality"
            else -> null
        }

        /**
         * A native [PlacedLabel] as the [Feature1] [SpecificFeature.parse] reads:
         * point geometry at [PlacedLabel.position] plus `{kind, name, name:en}`
         * properties and the baked `regionId` the tile stamped on the label.
         * `kind` is the `country`/`region`/`locality` discriminator
         * `parse` switches on (a linked `places-subplace` arrives as `locality`
         * semantics so it can mask; unlinked it still null-masks);
         * only labels whose native id maps to a known admin
         * base id convert, the rest return null and never enter the candidate list.
         */
        fun PlacedLabel.toFeature1(): Feature1? {
            if (nativeToBase(layerId) == null) return null
            Log.dev("RegionDbg", "pick layer=$layerId name=$name kind=$kind regionId=$regionId")
            return Feature1(
                Point(position),
                JsonObject(
                    mapOf(
                        "kind" to JsonPrimitive(if (layerId == "places-subplace") "locality" else kind),
                        "name" to JsonPrimitive(name),
                        "name:en" to JsonPrimitive(name),
                        "regionId" to JsonPrimitive(regionId),
                    )
                ),
            )
        }
    }
}
