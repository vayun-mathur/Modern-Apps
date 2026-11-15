package com.vayunmathur.maps.data

import com.vayunmathur.library.map.GeoPoint
import kotlinx.serialization.Serializable

/**
 * A place the user starred from the place sheet. Only the name
 * and coordinates are kept — that's all we need to recenter the map or start a
 * route to it.
 */
@Serializable
data class SavedPlace(val name: String, val lat: Double, val lon: Double) {

    /** Turns this saved place back into something the router can use as a destination. */
    fun toFeature(): SpecificFeature.GenericPlace =
        SpecificFeature.GenericPlace(
            name = name,
            phone = null,
            website = null,
            openingHours = null,
            position = GeoPoint(lon, lat),
        )

    /** True if [feature] is the same place that is stored in this slot. */
    fun matches(feature: SpecificFeature.RoutableFeature): Boolean =
        name == feature.name &&
            lat == feature.position.latitude &&
            lon == feature.position.longitude

    /** Shared constants and helpers for SavedPlace. */
    companion object {
        fun from(feature: SpecificFeature.RoutableFeature): SavedPlace =
            SavedPlace(feature.name, feature.position.latitude, feature.position.longitude)
    }
}
