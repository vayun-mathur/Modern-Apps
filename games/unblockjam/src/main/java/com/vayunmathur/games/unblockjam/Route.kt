package com.vayunmathur.games.unblockjam

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

/** Navigation destinations for Unblock Jam. */
@Serializable
sealed interface Route : NavKey {
    /** Pack list destination. */
    @Serializable
    data object PackSelector : Route
    /** Level grid for one pack. */
    @Serializable
    data class LevelSelector(val packIndex: Int) : Route
    /** Board for one level in a pack. */
    @Serializable
    data class Game(val packIndex: Int, val levelIndex: Int) : Route
    /** Daily challenge level list destination. */
    @Serializable
    data object DailySelector : Route
    /** Board for one daily challenge level. */
    @Serializable
    data class DailyGame(val levelIndex: Int) : Route
    /** Games Hub and achievements destination. */
    @Serializable
    data object GameCenter : Route
    /** Daily reminder settings destination. */
    @Serializable
    data object Settings : Route
}
