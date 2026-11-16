package com.vayunmathur.weather

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

/** Contract for route. */
@Serializable
sealed interface Route : NavKey {
    /**
     * Home forecast for [locationId]. Null (the start destination and any
     * restored entry without a selection) means the first saved location;
     * [com.vayunmathur.weather.ui.HomePage] resolves null — and any id whose
     * row was deleted — to that first location, so the selection lives
     * exactly here in the back stack, never in remembered UI state.
     */
    @Serializable
    data class Home(val locationId: Long? = null) : Route

    /** Singleton holding search location state and operations. */
    @Serializable
    data object SearchLocation : Route

    /** Immutable value describing weather map. */
    @Serializable
    data class WeatherMap(
        val latitude: Double,
        val longitude: Double,
        val name: String,
        val isoTime: String? = null,
        val metric: String,
    ) : Route
}
