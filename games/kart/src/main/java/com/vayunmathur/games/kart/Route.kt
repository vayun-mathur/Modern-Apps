package com.vayunmathur.games.kart

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

/** Contract for route. */
@Serializable
sealed interface Route : NavKey {
    /** The garage/lobby: seats filling up before a race. */
    @Serializable
    data object Lobby : Route

    /**
     * A live race. The countdown is in-screen state of this destination
     * (a few seconds of "3-2-1-GO" before controls go live), not a
     * destination of its own.
     */
    @Serializable
    data object Race : Route

    /** The results after a race; leaving it returns to [Lobby]. */
    @Serializable
    data object Results : Route
}
