package com.vayunmathur.games.logicgate

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

/** Navigation destinations. */
@Serializable
sealed interface Route : NavKey {
    /** The level map. */
    @Serializable data object Progression : Route

    /** The circuit editor for one level. */
    @Serializable data class Game(val levelId: String) : Route

    /** Achievements. */
    @Serializable data object GameCenter : Route
}
