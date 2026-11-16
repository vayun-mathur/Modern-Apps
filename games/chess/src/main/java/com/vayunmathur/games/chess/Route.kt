package com.vayunmathur.games.chess

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

/** Contract for route. */
@Serializable
sealed interface Route: NavKey {
    /** Singleton holding game state and operations. */
    @Serializable
    data object Game: Route
    /** Singleton holding online state and operations. */
    @Serializable
    data object Online: Route
    /**
     * One live online game, pushed over [Online] while its session is
     * playing. Carries only the game id; the board lives in the online
     * ViewModel's Playing connection for that game.
     */
    @Serializable
    data class OnlineGame(val gameId: String): Route
    /** Singleton holding puzzles state and operations. */
    @Serializable
    data object Puzzles: Route
    /** Singleton holding learn state and operations. */
    @Serializable
    data object Learn: Route
    /** Immutable value describing learn stage; carries categoryKey, stageKey. */
    @Serializable
    data class LearnStage(val categoryKey: String, val stageKey: String): Route
    /** Singleton holding game center state and operations. */
    @Serializable
    data object GameCenter: Route
}
