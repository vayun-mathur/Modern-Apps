package com.vayunmathur.games.wordmaker

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

/** Navigation destinations for Word Maker. */
@Serializable
sealed interface Route : NavKey {
    /** Crossword board destination. */
    @Serializable
    data object Game : Route
    /**
     * The between-levels competitive lobby. Shown instead of [Game] while
     * the mode is competitive and no level is active; starting a level
     * swaps the stack back to [Game] (see Navigation's route sync).
     */
    @Serializable
    data object CompetitiveLobby : Route
    /** Games Hub and achievements destination. */
    @Serializable
    data object GameCenter : Route
    /** Game settings destination. */
    @Serializable
    data object Settings : Route
}
