package com.vayunmathur.weather

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

/** Contract for route. */
@Serializable
sealed interface Route : NavKey {
    /** Singleton holding home state and operations. */
    @Serializable
    data object Home : Route

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
