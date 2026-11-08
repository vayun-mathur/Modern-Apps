package com.vayunmathur.games.chess.util

import com.vayunmathur.games.chess.data.PieceType
import com.vayunmathur.games.chess.data.Position

/**
 * The UI contract between the ViewModels and the screens.
 *
 * The screens take their state ([ChessUiState] / [PuzzleUiState], both declared next to their
 * ViewModel) plus an actions interface rather than the ViewModel itself, so they can be
 * rendered by a `@Preview` — which is what the store listing images are generated from. That
 * also keeps the engine out of the picture: nothing here can reach the model, so a preview
 * renders a static position and never brings up a Vulkan device.
 *
 * Every method has a no-op default, so `Noop` is the whole implementation a preview needs.
 */
interface ChessActions {
    fun onSquareClick(position: Position) {}
    fun onPromote(pieceType: PieceType) {}

    /**
     * Drag-and-drop: the press started on [from] and lifted on [to]. A drop is the tap flow
     * in one gesture — pressing the piece selects it, releasing on a square taps that
     * square — so a legal drop commits exactly what two taps would. Anything else is a
     * single tap on [from], preserving tap-selection.
     */
    fun onSquareDrop(from: Position, to: Position) {}

    companion object {
        val Noop: ChessActions = object : ChessActions {}
    }
}

/** Puzzle callbacks. Same no-op-default arrangement as [ChessActions]. */
interface PuzzleActions {
    fun onSquareClick(position: Position) {}
    fun onPromote(pieceType: PieceType) {}

    /**
     * Drag-and-drop: the press started on [from] and lifted on [to]. Same two-taps-in-one
     * gesture arrangement as [ChessActions.onSquareDrop].
     */
    fun onSquareDrop(from: Position, to: Position) {}

    /** Loads a fresh random puzzle from the [difficulty] band. */
    fun loadRandom(difficulty: PuzzleDifficulty) {}
    fun retry() {}
    fun showSolution() {}

    companion object {
        val Noop: PuzzleActions = object : PuzzleActions {}
    }
}
