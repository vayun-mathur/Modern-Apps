package com.vayunmathur.games.kart

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

/** Contract for route. */
@Serializable
sealed interface Route : NavKey {
    /** The garage screen: which karts/tracks are unlocked and what the next race looks like. */
    @Serializable
    data object Game : Route
}
