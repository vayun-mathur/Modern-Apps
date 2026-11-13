package com.vayunmathur.games.sudoku

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.games.sudoku.data.BoardSize
import com.vayunmathur.games.sudoku.data.Difficulty
import com.vayunmathur.games.sudoku.data.SudokuGameState
import com.vayunmathur.games.sudoku.domain.SudokuGenerator
import com.vayunmathur.games.sudoku.platform.SudokuActions
import com.vayunmathur.games.sudoku.platform.SudokuUiState
import com.vayunmathur.games.sudoku.ui.GameBoardScreen
import com.vayunmathur.library.ui.DynamicTheme
import kotlin.random.Random

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/**
 * The store listing images.
 *
 * Previews must be members of a class and carry both `@PreviewTest` and `@Preview`, or the
 * screenshot engine discovers no tests. The `PreviewN` names set the order they appear in the
 * listing, because the collector sorts by filename. Run `./gradlew :games:sudoku:metadata`.
 *
 * Boards come from [SudokuGenerator] with a fixed seed rather than 81 transcribed digits: the same
 * seed gives the same puzzle on any machine, so the images are reproducible from a clean checkout
 * and are guaranteed to be legal puzzles. [GameBoardScreen] has no polling `LaunchedEffect` — the
 * clock lives in the stateful `GameScreen` — so a preview settles on the first frame.
 */
class MetadataPreviews {

    @PreviewTest
    @Preview(name = "1-puzzle", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview1Puzzle() {
        Board(
            game(
                size = BoardSize.NINE,
                difficulty = Difficulty.MEDIUM,
                seed = 20260824,
                filled = 14,
                selected = 40,
            ),
            elapsed = 232,
        )
    }

    @PreviewTest
    @Preview(name = "2-notes", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview2Notes() {
        val base = game(
            size = BoardSize.NINE,
            difficulty = Difficulty.HARD,
            seed = 4711,
            filled = 6,
            selected = 30,
        )
        Board(base.copy(notesMode = true, notes = pencilMarks(base)), elapsed = 96)
    }

    @PreviewTest
    @Preview(name = "3-twelve", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview3Twelve() {
        // The largest board, where digits past nine are lettered and the pad becomes a 3x4 grid.
        Board(
            game(
                size = BoardSize.TWELVE,
                difficulty = Difficulty.MEDIUM,
                seed = 1212,
                filled = 18,
                selected = 40,
            ),
            elapsed = 604,
        )
    }

    @PreviewTest
    @Preview(name = "4-six", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview4Six() {
        Board(
            game(
                size = BoardSize.SIX,
                difficulty = Difficulty.EASY,
                seed = 991,
                filled = 5,
                selected = 20,
            ),
            elapsed = 41,
        )
    }

    @PreviewTest
    @Preview(name = "5-solved", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview5Solved() {
        val base = game(BoardSize.NINE, Difficulty.EXPERT, seed = 2024, filled = 0)
        Board(base.copy(entries = base.blanksFilled(), isWon = true), elapsed = 511)
    }

    @Composable
    private fun Board(game: SudokuGameState, elapsed: Int) {
        // Layoutlib has no wallpaper to sample, so Material You falls back to the default palette.
        DynamicTheme(darkTheme = true) {
            GameBoardScreen(
                state = SudokuUiState(game = game, canUndo = game.moveCount > 0),
                actions = SudokuActions.Noop,
                onExit = {},
                elapsedSeconds = { elapsed },
            )
        }
    }

    /**
     * A game part-way through, with [filled] of the blanks correctly entered.
     *
     * Only correct digits are placed, so no preview shows an error state by accident.
     */
    private fun game(
        size: BoardSize,
        difficulty: Difficulty,
        seed: Int,
        filled: Int,
        selected: Int = -1,
    ): SudokuGameState {
        val puzzle = SudokuGenerator.generate(size, difficulty, Random(seed))
        val state = SudokuGameState.from(puzzle)
        val blanks = puzzle.givens.indices.filter { puzzle.givens[it] == 0 }.take(filled)
        val entries = state.entries.toMutableList()
        blanks.forEach { entries[it] = puzzle.solution[it] }
        return state.copy(
            entries = entries,
            selected = selected,
            moveCount = filled,
        )
    }

    /** Every remaining blank filled in, for the win shot. */
    private fun SudokuGameState.blanksFilled(): List<Int> =
        List(size.cellCount) { if (isGiven(it)) 0 else solution[it] }

    /**
     * Plausible pencil marks: two or three candidates in the first few blank cells, so the notes
     * shot shows the mini-grid layout rather than an empty board.
     */
    private fun pencilMarks(game: SudokuGameState): List<Int> {
        val notes = game.notes.toMutableList()
        val blanks = game.givens.indices.filter { game.valueAt(it) == 0 }
        blanks.take(9).forEachIndexed { position, index ->
            val first = game.solution[index]
            val second = (first % game.size.side) + 1
            val third = ((first + 3) % game.size.side) + 1
            var mask = (1 shl (first - 1)) or (1 shl (second - 1))
            if (position % 2 == 0) mask = mask or (1 shl (third - 1))
            notes[index] = mask
        }
        return notes
    }
}
