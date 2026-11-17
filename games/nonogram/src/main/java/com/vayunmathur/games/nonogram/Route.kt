package com.vayunmathur.games.nonogram

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

/** Navigation destinations for the Nonogram app. */
@Serializable
sealed interface Route : NavKey {
    /** The only board there is: which puzzle it shows comes from the stored mode and level. */
    @Serializable
    data object Game : Route

    /** Destination for the shared game-center screen. */
    @Serializable
    data object GameCenter : Route

    /** Destination for the Nonogram settings screen. */
    @Serializable
    data object Settings : Route
}
