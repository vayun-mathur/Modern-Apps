package com.vayunmathur.games.pipes

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

/** Contract for route. */
@Serializable
sealed interface Route : NavKey {
    /** Singleton holding pack selector state and operations. */
    @Serializable
    data object PackSelector : Route
    /** Immutable value describing level selector; carries packIndex. */
    @Serializable
    data class LevelSelector(val packIndex: Int) : Route
    /** Immutable value describing game; carries packIndex, levelIndex. */
    @Serializable
    data class Game(val packIndex: Int, val levelIndex: Int) : Route
    /** Singleton holding daily selector state and operations. */
    @Serializable
    data object DailySelector : Route
    /** Immutable value describing daily game; carries levelIndex. */
    @Serializable
    data class DailyGame(val levelIndex: Int) : Route
    /** Singleton holding game center state and operations. */
    @Serializable
    data object GameCenter : Route
    /** Singleton holding settings state and operations. */
    @Serializable
    data object Settings : Route
}
