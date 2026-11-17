package com.vayunmathur.things

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

/** Navigation destinations for the Things app. */
@Serializable
sealed interface Route : NavKey {
    /** Device list and pairing destination. */
    @Serializable
    data object Devices : Route

    /** Hydration home destination. */
    @Serializable
    data object Home : Route
}
