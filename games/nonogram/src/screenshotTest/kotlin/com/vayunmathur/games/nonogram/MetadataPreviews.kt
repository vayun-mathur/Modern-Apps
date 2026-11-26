package com.vayunmathur.games.nonogram

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.games.nonogram.data.DAILY_SIZE
import com.vayunmathur.games.nonogram.data.GameMode
import com.vayunmathur.games.nonogram.data.NonogramGameState
import com.vayunmathur.games.nonogram.data.NonogramPuzzle
import com.vayunmathur.games.nonogram.data.STARTING_HEARTS
import com.vayunmathur.games.nonogram.data.sizeForLevel
import com.vayunmathur.games.nonogram.domain.NonogramClues
import com.vayunmathur.games.nonogram.domain.NonogramGenerator
import com.vayunmathur.games.nonogram.platform.NonogramGameActions
import com.vayunmathur.games.nonogram.platform.NonogramUiState
import com.vayunmathur.games.nonogram.ui.NonogramGameScreen
import com.vayunmathur.library.ui.DynamicTheme

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/**
 * The store listing images.
 *
 * Previews must be members of a class and carry both `@PreviewTest` and `@Preview`, or the screenshot
 * engine discovers no tests. The `PreviewN` names set the order they appear in the listing, because
 * the collector sorts by filename. Run `./gradlew :games:nonogram:metadata`.
 *
 * Puzzles come from [NonogramGenerator] with the same seeds the real levels use, so an image is a
 * genuine level rather than a hand-drawn approximation, and is reproducible from a clean checkout.
 * [NonogramGameScreen] has no polling `LaunchedEffect`, so a preview settles on the first frame.
 */
class MetadataPreviews {

    @PreviewTest
    @Preview(name = "1-level", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview1Level() {
        // Part-way through a 10x10, with some cells crossed out and one heart already spent.
        val puzzle = level(12)
        val correct = puzzle.solution.indices.filter { puzzle.solution[it] }
        val blanks = puzzle.solution.indices.filter { !puzzle.solution[it] }
        Board(
            game(
                puzzle = puzzle,
                level = 12,
                filled = correct.take(correct.size / 2).toSet(),
                crossed = blanks.drop(1).take(blanks.size / 3).toSet(),
                // One cell the player got wrong, which is what the missing heart paid for.
                revealedBlanks = setOf(blanks.first()),
                hearts = STARTING_HEARTS - 1,
            )
        )
    }

    @PreviewTest
    @Preview(name = "2-solved", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview2Solved() {
        val puzzle = level(6)
        Board(
            game(
                puzzle = puzzle,
                level = 6,
                filled = puzzle.solution.indices.filter { puzzle.solution[it] }.toSet(),
                crossed = emptySet(),
            )
        )
    }

    @PreviewTest
    @Preview(name = "3-daily", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview3Daily() {
        // Dailies are always DAILY_SIZE, so the shot has to be that size to be honest.
        val puzzle = requireNotNull(NonogramGenerator.generateSeeded(DAILY_SIZE, seed = 20260824L))
        val correct = puzzle.solution.indices.filter { puzzle.solution[it] }
        Board(
            game(
                puzzle = puzzle,
                level = 1,
                filled = correct.take(correct.size / 3).toSet(),
                crossed = emptySet(),
                mode = GameMode.DAILY,
            ),
            streak = 6,
        )
    }

    @PreviewTest
    @Preview(name = "4-large", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview4Large() {
        // A later level, where the grid steps up to 15x15.
        Board(game(puzzle = level(25), level = 25, filled = emptySet(), crossed = emptySet()))
    }

    @Composable
    private fun Board(game: NonogramGameState, streak: Long = 0) {
        // The same derivation the ViewModel applies to every move, so the
        // listing board carries the auto-crosses a played board would.
        val derived = NonogramClues.withAutoCrosses(game)
        // Layoutlib has no wallpaper to sample, so Material You falls back to the default palette.
        DynamicTheme(darkTheme = true) {
            NonogramGameScreen(
                state = NonogramUiState(
                    game = derived,
                    mode = derived.mode,
                    level = derived.level,
                    dailyStreak = streak,
                ),
                actions = NonogramGameActions.Noop,
                onOpenSettings = {},
                onOpenGameCenter = {},
            )
        }
    }

    /** The real puzzle for [level], so the preview shows a board the game would actually deal. */
    private fun level(level: Int): NonogramPuzzle =
        requireNotNull(NonogramGenerator.generateSeeded(sizeForLevel(level), level.toLong())) {
            "no puzzle for level $level"
        }

    private fun game(
        puzzle: NonogramPuzzle,
        level: Int,
        filled: Set<Int>,
        crossed: Set<Int>,
        revealedBlanks: Set<Int> = emptySet(),
        mode: GameMode = GameMode.CASUAL,
        hearts: Int = STARTING_HEARTS,
    ) = NonogramGameState(
        puzzle = puzzle,
        filled = filled,
        crossed = crossed,
        revealedBlanks = revealedBlanks,
        hearts = hearts,
        mode = mode,
        level = level,
    )
}
