package com.vayunmathur.games.wordmaker

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object Game : Route
    /**
     * The between-levels competitive lobby. Shown instead of [Game] while
     * the mode is competitive and no level is active; starting a level
     * swaps the stack back to [Game] (see Navigation's route sync).
     */
    @Serializable
    data object CompetitiveLobby : Route
    @Serializable
    data object GameCenter : Route
    @Serializable
    data object Settings : Route
}
