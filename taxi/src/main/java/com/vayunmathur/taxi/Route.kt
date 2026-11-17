package com.vayunmathur.taxi

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

/** Contract for route. */
@Serializable
sealed interface Route : NavKey {
    /** Immutable value describing ride tracking; carries rideId. */
    @Serializable data object Ride : Route
    /** Current active ride route. */
    @Serializable data object CurrentRide : Route
    /** Ride-provider accounts route. */
    @Serializable data object Accounts : Route
    /** Lyft sign-in route. */
    @Serializable data object LyftSignIn : Route
    /** Live tracking route for the ride identified by [rideId]. */
    @Serializable data class RideTracking(val rideId: String) : Route
}
