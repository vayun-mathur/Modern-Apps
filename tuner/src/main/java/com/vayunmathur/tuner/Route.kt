package com.vayunmathur.tuner

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

/** Contract for route. */
@Serializable
sealed interface Route : NavKey {
    /** Note tuner tab (sibling route, the start destination). */
    @Serializable
    data object Note : Route

    /** Chord detector tab (sibling route). */
    @Serializable
    data object Chord : Route
}
