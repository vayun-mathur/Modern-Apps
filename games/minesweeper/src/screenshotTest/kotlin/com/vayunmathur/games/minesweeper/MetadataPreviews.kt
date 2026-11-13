package com.vayunmathur.games.minesweeper

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.games.minesweeper.data.BoardSize
import com.vayunmathur.games.minesweeper.data.Difficulty
import com.vayunmathur.games.minesweeper.data.GameConfig
import com.vayunmathur.games.minesweeper.data.MinesweeperGameState
import com.vayunmathur.games.minesweeper.domain.FieldGenerator
import com.vayunmathur.games.minesweeper.domain.MinesweeperRules
import com.vayunmathur.games.minesweeper.platform.MinesweeperActions
import com.vayunmathur.games.minesweeper.platform.MinesweeperUiState
import com.vayunmathur.games.minesweeper.ui.GameBoardScreen
import com.vayunmathur.library.ui.DynamicTheme
import kotlin.random.Random

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/**
 * The store listing images.
 *
 * Previews must be members of a class and carry both `@PreviewTest` and `@Preview`, or the screenshot
 * engine discovers no tests. The `PreviewN` names set the order they appear in the listing, because
 * the collector sorts by filename. Run `./gradlew :games:minesweeper:metadata`.
 *
 * Fields come from [FieldGenerator] with a fixed seed and are then played through the real
 * [MinesweeperRules], so every image is a position that could actually occur — no hand-forged state
 * that the rules would never produce. [GameBoardScreen] has no polling `LaunchedEffect` — the clock
 * lives in the stateful `GameScreen` — so a preview settles on the first frame.
 */
class MetadataPreviews {

    @PreviewTest
    @Preview(name = "1-midgame", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview1MidGame() {
        val config = GameConfig(BoardSize.MEDIUM, Difficulty.MEDIUM)
        var game = opened(config, seed = 20260824)
        // A few flags, so the counter and the flag glyph both appear.
        for (index in game.mines.indices.filter { game.mines[it] }.take(4)) {
            game = MinesweeperRules.toggleFlag(game, index)
        }
        Board(config, game, elapsed = 74)
    }

    @PreviewTest
    @Preview(name = "2-large", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview2Large() {
        val config = GameConfig(BoardSize.LARGE, Difficulty.HARD)
        val game = opened(config, seed = 77)
        Board(config, game, elapsed = 142)
    }

    @PreviewTest
    @Preview(name = "3-fresh", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview3Fresh() {
        val config = GameConfig(BoardSize.SMALL, Difficulty.EASY)
        Board(config, MinesweeperGameState.empty(config))
    }

    @PreviewTest
    @Preview(name = "4-cleared", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview4Cleared() {
        val config = GameConfig(BoardSize.SMALL, Difficulty.EASY)
        val blank = MinesweeperGameState.empty(config)
        var game = FieldGenerator.lay(blank, safeIndex = interior(config), rng = Random(5))
        // Open every safe cell, which is what the rules treat as a win.
        for (index in 0 until game.cellCount) {
            if (!game.mines[index]) game = MinesweeperRules.reveal(game, index)
        }
        Board(config, game, elapsed = 53)
    }

    @Composable
    private fun Board(config: GameConfig, game: MinesweeperGameState, elapsed: Int = 0) {
        // Layoutlib has no wallpaper to sample, so Material You falls back to the default palette.
        DynamicTheme(darkTheme = true) {
            GameBoardScreen(
                state = MinesweeperUiState(config = config, game = game),
                actions = MinesweeperActions.Noop,
                onExit = {},
                elapsedSeconds = { elapsed },
            )
        }
    }

    /** A field with the opening area revealed, the position a player actually starts from. */
    private fun opened(config: GameConfig, seed: Int): MinesweeperGameState {
        val blank = MinesweeperGameState.empty(config)
        val safeIndex = interior(config)
        val laid = FieldGenerator.lay(blank, safeIndex, Random(seed))
        return MinesweeperRules.reveal(laid, safeIndex)
    }

    /** A cell with all eight neighbours on the board, so the opening reveal always cascades. */
    private fun interior(config: GameConfig): Int =
        (config.size.rows / 2) * config.size.cols + config.size.cols / 2
}
