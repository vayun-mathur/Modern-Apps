package com.vayunmathur.networklocation

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface Route : NavKey {
    /** Offline-database download. Shown until every database is on disk. */
    @Serializable
    data object Setup : Route

    /** Live GPS, network-location and geocoder check, once the databases are installed. */
    @Serializable
    data object Status : Route
}
