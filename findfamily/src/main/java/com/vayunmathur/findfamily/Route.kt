package com.vayunmathur.findfamily

import com.vayunmathur.library.util.NavKey
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

@Serializable
/** Navigation destinations for FindFamily's single-activity graph. */
sealed interface Route: NavKey {
    @Serializable
    /** Map home destination. Selection lives in [PersonSheet]/[WaypointSheet], not here. */
    data object MainPage: Route

    @Serializable
    /** Staged first-run sheet (pair family, then enable location sharing), shown over the map only for new users. */
    data object FirstRunSheet: Route

    @Serializable
    /** Sheet for the given person, shown over the map. */
    data class PersonSheet(val userId: Long): Route

    @Serializable
    /** Sheet for the given waypoint (0 = create a new one), shown over the map. */
    data class WaypointSheet(val waypointId: Long): Route

    @Serializable
    /** Dialog for picking the date whose location history to show. */
    data class UserPageHistoryDatePicker(val initialDate: LocalDate): Route

    @Serializable
    /** Dialog for connecting to a person, optionally prefilled from an invite link. */
    data class AddPersonDialog(val id: Long? = null, val fingerprint: String? = null): Route

    @Serializable
    /** Dialog for creating a temporary share link. */
    data object AddLinkDialog: Route

    /** Dev-only (DEV_BUILD): bind a new custom UWB tracker. */
    @Serializable
    data object AddTrackerDialog: Route

    @Serializable
    /** Dialog explaining features unavailable on this device. */
    data object MissingFeaturesDialog: Route

    /** UWB Find Nearby (UWB) screen for the given peer. Full screen (no DialogPage metadata). */
    @Serializable
    data class UwbRangingPage(val userId: Long): Route
}
