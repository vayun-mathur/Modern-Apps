package com.vayunmathur.games.arrows.platform

import com.vayunmathur.games.arrows.data.ArrowsGameState
import com.vayunmathur.games.arrows.data.GameMode

/**
 * The UI contract between [ArrowsViewModel] and the board.
 *
 * The board takes this interface rather than the ViewModel itself so it can be rendered by a
 * `@Preview` — which is what the store listing images are generated from. It lives in `platform`
 * rather than next to the board so the dependency runs one way: `ui` depends on `platform`, and the
 * ViewModel implements this.
 *
 * Every member has a no-op default, so `Noop` is the whole implementation a preview needs.
 */
interface ArrowsGameActions {
    /** Sends the tapped arrow on its way, or spends a heart if it is boxed in. */
    fun tapArrow(pieceId: Int) {}

    /** Called once the board has finished animating [ArrowsUiState.move], to commit its outcome. */
    fun commitMove() {}

    fun nextLevel() {}
    fun restartLevel() {}
    /** Rebuilds the board after generation failed. Restarting cannot do this: it only resets progress. */
    fun retryGeneration() {}
    fun setGameMode(mode: GameMode) {}

    companion object { val Noop: ArrowsGameActions = object : ArrowsGameActions {} }
}

/**
 * A tap that is being animated but has not yet taken effect.
 *
 * The outcome is decided the moment the arrow is tapped, but nothing is written until the board has
 * finished showing it: an arrow that clears has to stay drawn while it flies out, and one that is blocked
 * has to be drawn moving and coming back. [ArrowsGameActions.commitMove] is what applies it.
 *
 * @param route the piece's cells followed by its head's escape path, so cell `i` sits at
 *   `route[i + advance]` at full advance.
 * @param advance how many cells the head travels.
 * @param clears whether the arrow gets out, which decides both the ending and whether a heart is spent.
 */
data class ArrowMove(
    val pieceId: Int,
    val route: List<Int>,
    val advance: Int,
    val clears: Boolean,
)

/** What the board needs to draw itself. [game] is null only while a board is being generated. */
data class ArrowsUiState(
    val game: ArrowsGameState? = null,
    val mode: GameMode = GameMode.CASUAL,
    val level: Int = 1,
    val showRoutes: Boolean = true,
    val dailyStreak: Long = 0,
    /** True once today's daily has been cleared, which swaps the next-level button for a note. */
    val dailyDone: Boolean = false,
    val generating: Boolean = false,
    /** Set when even a widened seed search found no clearable board, so the board can offer a retry. */
    val generationFailed: Boolean = false,
    /** The tap currently being animated, if any. Taps are ignored while this is set. */
    val move: ArrowMove? = null,
)

interface SettingsActions {
    fun setShowRoutes(enabled: Boolean) {}
    fun setReminderEnabled(enabled: Boolean) {}
    fun setReminderTime(hour: Int, minute: Int) {}

    companion object { val Noop: SettingsActions = object : SettingsActions {} }
}

data class SettingsUiState(
    val showRoutes: Boolean = true,
    val reminderEnabled: Boolean = false,
    val reminderHour: Int = 20,
    val reminderMinute: Int = 0,
)
