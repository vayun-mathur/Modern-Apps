package com.vayunmathur.games.sudoku

import com.vayunmathur.games.sudoku.data.GameConfig
import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

/** Navigation destinations for the Sudoku app. */
@Serializable
sealed interface Route : NavKey {
    /** Destination for the Sudoku home screen. */
    @Serializable
    data object Home : Route

    /**
     * Carries the [GameConfig] so the board can deal itself a puzzle if it is ever entered without
     * one in memory — the ViewModel holds the grid but nothing restores it after process death.
     */
    @Serializable
    data class Game(val config: GameConfig) : Route

    /** Destination for the shared game-center screen. */
    @Serializable
    data object GameCenter : Route
}
