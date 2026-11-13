package com.vayunmathur.games.nonogram.platform

import com.vayunmathur.games.nonogram.data.GameMode
import com.vayunmathur.games.nonogram.data.MarkMode
import com.vayunmathur.games.nonogram.data.NonogramGameState

/**
 * The UI contract between [NonogramViewModel] and the board.
 *
 * The board takes this interface rather than the ViewModel itself so it can be rendered by a
 * `@Preview` — which is what the store listing images are generated from. It lives in `platform`
 * rather than next to the board so the dependency runs one way: `ui` depends on `platform`, and the
 * ViewModel implements this.
 *
 * Every member has a no-op default, so `Noop` is the whole implementation a preview needs.
 */
interface NonogramGameActions {
    /** Tap: places whichever mark the current [MarkMode] selects. A one-cell stroke. */
    fun tapCell(index: Int) {}

    /** Long press: places the other mark, whatever the mode is. A one-cell alternate stroke. */
    fun crossCell(index: Int) {}

    /**
     * A finger went down on [index] and the gesture became a stroke: a tap, a drag, or (with
     * [alternate]) a long press, which paints with the other mark for the rest of the stroke.
     */
    fun beginStroke(index: Int, alternate: Boolean) {}

    /** The stroke's finger is now over [index]. */
    fun extendStroke(index: Int) {}

    /** The stroke's finger lifted (or the gesture was cancelled). */
    fun endStroke() {}

    fun setMarkMode(mode: MarkMode) {}
    fun nextLevel() {}
    fun restartLevel() {}

    /**
     * Retries a failed puzzle generation with a widened seed search.
     *
     * This is separate from [restartLevel] on purpose: restart only clears marks, which cannot
     * retrigger generation, and re-running the same seed would find the same nothing.
     */
    fun retryGeneration() {}
    fun setGameMode(mode: GameMode) {}

    companion object { val Noop: NonogramGameActions = object : NonogramGameActions {} }
}

/** What the board needs to draw itself. [game] is null only while a puzzle is being generated. */
data class NonogramUiState(
    val game: NonogramGameState? = null,
    val mode: GameMode = GameMode.CASUAL,
    val markMode: MarkMode = MarkMode.FILL,
    val level: Int = 1,
    val dailyStreak: Long = 0,
    /** True once today's daily has been completed, which swaps the next-level button for a note. */
    val dailyDone: Boolean = false,
    val generating: Boolean = false,
    /** Set when even a widened seed search found no fair puzzle, so the board can offer a retry. */
    val generationFailed: Boolean = false,
)

interface SettingsActions {
    fun setReminderEnabled(enabled: Boolean) {}
    fun setReminderTime(hour: Int, minute: Int) {}

    companion object { val Noop: SettingsActions = object : SettingsActions {} }
}

data class SettingsUiState(
    val reminderEnabled: Boolean = false,
    val reminderHour: Int = 20,
    val reminderMinute: Int = 0,
)
