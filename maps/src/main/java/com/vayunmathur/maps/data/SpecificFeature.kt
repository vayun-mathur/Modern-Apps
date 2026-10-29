package com.vayunmathur.maps.data

import com.vayunmathur.library.log.Log
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.maps.util.PoiIndex
import com.vayunmathur.maps.util.Wikidata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
sealed interface SpecificFeature {
    interface RoutableFeature : SpecificFeature {
        val position: GeoPoint
        val name: String
    }

    /**
     * A country. [iso] and [wikipedia] are optional because the basemap archive carries neither:
     * a picked label supplies a name and a position, and the Wikidata round trip that fills these
     * in needs the network. Requiring them meant tapping a country did nothing at all offline.
     *
     * [regionId] is the baked link: the tagged OSM relation id of the boundary this label
     * names, stamped at build time (see `region_links`). `0` means unlinked — mask nothing.
     */
    @Serializable
    data class Admin0Label(
        @SerialName("iso3166_1") val iso: String? = null,
        val wikipedia: String? = null,
        val name: String,
        @Serializable(with = GeoPointAsCoordinates::class) val position: GeoPoint? = null,
        val regionId: Long = 0L,
    ) : SpecificFeature
    /**
     * A state or region. [iso] and [wikipedia] are optional for the same reason as [Admin0Label].
     * [regionId] is the baked link, as in [Admin0Label].
     */
    @Serializable
    data class Admin1Label(
        @SerialName("iso3166_2") val iso: String? = null,
        val wikipedia: String? = null,
        val name: String,
        @Serializable(with = GeoPointAsCoordinates::class) val position: GeoPoint? = null,
        val regionId: Long = 0L,
    ) : SpecificFeature
    /**
     * A city / town. Unlike the country and region labels there is no ISO code to
     * key on — the baked `admin_city` layer carries only `name` / `name_en` — so
     * the border highlight matches on the name instead.
     *
     * [wikipedia] is optional for the same reason as [Admin0Label].
     * [regionId] is the baked link, as in [Admin0Label].
     */
    @Serializable
    data class Admin2Label(
        val wikipedia: String? = null,
        val name: String,
        @Serializable(with = GeoPointAsCoordinates::class) val position: GeoPoint? = null,
        val regionId: Long = 0L,
    ) : SpecificFeature
    @Serializable
    data class Restaurant(
        override val name: String,
        val phone: String?,
        val website: String?,
        val menu: String?,
        val openingHours: OpeningHours?,
        @Serializable(with = GeoPointAsCoordinates::class) override val position: GeoPoint,
    ) : RoutableFeature
    @Serializable
    data class GenericPlace(
        override val name: String,
        val phone: String?,
        val website: String?,
        val openingHours: OpeningHours?,
        @Serializable(with = GeoPointAsCoordinates::class) override val position: GeoPoint,
        val poiType: Int? = null,
        /** Street address from the OSM `addr:*` tags, when we have them. */
        val address: String? = null,
        /** Offline rating, review count and review summary from the archive, when baked in. */
        val rating: Double? = null,
        val reviewCount: Int? = null,
        val reviewSummary: String? = null,
    ) : RoutableFeature
    @Serializable
    data class Route(val waypoints: List<RoutableFeature?>) : SpecificFeature
}

/**
 * A place built from a coordinate, with whatever `poi_attrs.bin` knows about it.
 *
 * Every path that turns a name and a coordinate into a sheet goes through here, so
 * an offline search result opens the same populated sheet a tapped pin does — which
 * it did not before: search built these with every field null, so an offline result
 * showed a title and nothing else.
 *
 * Returns a bare place when the sidecar is absent or the point is not one of ours,
 * which is the same thing every caller used to produce unconditionally.
 *
 * Suspending, and on [Dispatchers.IO], because [PoiIndex.attributesNear] reads a mapped side
 * file. Every one of this function's call sites used to be on the main thread — including the
 * one inside the tap gesture handler — so a cold 316 MB mmap page-faulted on the UI thread.
 * That is true even now the lookup is a binary search rather than a scan: the scan was what
 * made it an ANR, the mmap is what makes it I/O.
 */
suspend fun osmPlace(
    name: String,
    position: GeoPoint,
    poiType: Int? = null,
): SpecificFeature.GenericPlace {
    val attrs: PoiIndex.PoiAttributes? = withContext(Dispatchers.IO) {
        PoiIndex.attributesNear(position.latitude, position.longitude, name)
    }
    return SpecificFeature.GenericPlace(
        name = name,
        phone = attrs?.phone,
        website = attrs?.website,
        // A string we could not parse would render as a confident week of "Closed",
        // so it is dropped in favour of whatever Google has.
        openingHours = attrs?.openingHours?.let(OpeningHours::from)?.takeIf { it.hasRules },
        position = position,
        poiType = poiType,
        address = attrs?.address,
        rating = attrs?.rating,
        reviewCount = attrs?.reviewCount,
        reviewSummary = attrs?.reviewSummary,
    )
}

typealias Feature1 = Feature<Geometry, JsonObject?>

fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.content

/**
 * Resolve a tapped basemap feature into a [SpecificFeature].
 *
 * Amenities are drawn by the renderer and picked there, arriving as `MapClick.poi`, so this
 * no longer reads the amenities DB: it only handles the country/region/city admin labels
 * (Wikidata-backed). Everything else returns null.
 */
suspend fun parse(feature: Feature1): SpecificFeature? {
    val properties = feature.properties ?: return null
    // The name is the only thing an admin label truly needs. Everything below it is enrichment
    // from Wikidata, which the basemap archive does not carry and which needs the network.
    //
    // Requiring that enrichment is what made tapping a city, state or country do nothing: a
    // picked label supplies `kind`, `name` and `name:en` and nothing else, so `wikidata` was
    // always absent, every branch returned null, and the tap fell through to reverse-geocode —
    // which is online-only and therefore silent offline. Two `runCatching` swallows on the path
    // meant it failed without a trace.
    val name = properties.string("name:en") ?: properties.string("name") ?: return null
    // Absent id, or a lookup that fails or times out, leaves the article and ISO code unset
    // rather than losing the whole feature.
    val wiki = properties.string("wikidata")?.let { id ->
        try { Wikidata.get(id) } catch (_: Exception) { null }
    }
    // Where the label sits, kept for search-picked regions and as a fallback anchor: the
    // mask itself is the baked `regionId` below, not a containment probe.
    val at = (feature.geometry as? Point)?.coordinates
    // The baked link: the tagged relation id of the boundary this label names, stamped at
    // build time (`region_links`). `0` is REGION_NONE — unlinked, mask nothing.
    val regionId = properties["regionId"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
    Log.dev("RegionDbg", "parse kind=${properties.string("kind")} name=$name regionId=$regionId")
    return when (properties.string("kind")) {
        "country" -> SpecificFeature.Admin0Label(
            iso = wiki?.getProperty("P297"),
            wikipedia = wiki?.getWikipedia(),
            name = name,
            position = at,
            regionId = regionId,
        )
        "region" -> SpecificFeature.Admin1Label(
            iso = wiki?.getProperty("P300"),
            wikipedia = wiki?.getWikipedia(),
            name = name,
            position = at,
            regionId = regionId,
        )
        // No ISO lookup: a city has no ISO 3166 code, so the Wikidata round trip is only for the
        // article URL.
        "locality" -> SpecificFeature.Admin2Label(
            wikipedia = wiki?.getWikipedia(),
            name = name,
            position = at,
            regionId = regionId,
        )
        else -> null
    }
}
