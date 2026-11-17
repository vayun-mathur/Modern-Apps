package com.vayunmathur.games.minesweeper

import com.vayunmathur.games.minesweeper.data.GameConfig
import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

/** Navigation destinations for the Minesweeper app. */
@Serializable
sealed interface Route : NavKey {
    /** Destination for the Minesweeper home screen. */
    @Serializable
    data object Home : Route

    /**
     * Carries the [GameConfig] so the board can deal itself a field if it is ever entered without one
     * in memory — the ViewModel holds the field but nothing restores it after process death.
     */
    @Serializable
    data class Game(val config: GameConfig) : Route

    /** Destination for the shared game-center screen. */
    @Serializable
    data object GameCenter : Route
}
