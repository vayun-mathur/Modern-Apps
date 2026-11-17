package com.vayunmathur.maps

import com.vayunmathur.library.util.NavKey
import com.vayunmathur.maps.domain.RouteService
import kotlinx.serialization.Serializable

/** Contract for route. */
@Serializable
sealed interface Route: NavKey {
    /** Singleton holding map page state and operations. */
    @Serializable
    data object MapPage: Route
    /** Singleton holding settings page state and operations. */
    @Serializable
    data object SettingsPage: Route
    /** Singleton holding saved places page state and operations. */
    @Serializable
    data object SavedPlacesPage: Route
    /** Full-screen Street View viewer for the nearest pano to a point. */
    @Serializable
    data class StreetView(val lat: Double, val lon: Double): Route
    /** Turn-by-turn UI over the map, driven by the guidance engine. */
    @Serializable
    data class Navigating(val destLabel: String, val mode: RouteService.TravelMode): Route
}
