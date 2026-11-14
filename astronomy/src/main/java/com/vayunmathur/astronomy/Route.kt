package com.vayunmathur.astronomy

import com.vayunmathur.library.util.NavKey
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/** Navigation destinations for the astronomy app. */
@Serializable
sealed interface Route : NavKey {
    /** The live sky-map screen. */
    @Serializable data object SkyMap : Route

    /** Detail screen for the celestial object identified by [id]. */
    @Serializable data class ObjectDetail(val id: String) : Route

    /** Search screen for catalog objects. */
    @Serializable data object Search : Route

    /** Settings screen for display and observer options. */
    @Serializable data object Settings : Route

    /** Date picker for scrubbing to a historical date, starting at [initialDate]. */
    @Serializable data class HistoryDatePicker(val initialDate: LocalDate) : Route
}
