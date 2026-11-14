package com.vayunmathur.taxi

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

/** Contract for route. */
@Serializable
sealed interface Route : NavKey {
    /** Immutable value describing ride tracking; carries rideId. */
    @Serializable data object Ride : Route
    @Serializable data object CurrentRide : Route
    @Serializable data object Accounts : Route
    @Serializable data object LyftSignIn : Route
    @Serializable data class RideTracking(val rideId: String) : Route
}
