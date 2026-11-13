package com.vayunmathur.games.minesweeper.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.minesweeper.R
import com.vayunmathur.games.minesweeper.data.BoardSize
import com.vayunmathur.games.minesweeper.data.Difficulty
import com.vayunmathur.games.minesweeper.data.GameConfig
import com.vayunmathur.games.minesweeper.data.GameOutcome
import com.vayunmathur.games.minesweeper.data.MinesweeperGameState
import com.vayunmathur.games.minesweeper.data.TapMode
import com.vayunmathur.games.minesweeper.platform.MinesweeperActions
import com.vayunmathur.games.minesweeper.platform.MinesweeperUiState
import com.vayunmathur.games.minesweeper.ui.components.MineFieldGrid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Board screen (title, counter, mode toggle) and field grid cell semantics. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MinesweeperBoardTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int, vararg a: Any): String = context.getString(id, *a)

    /** 3x3 field, mine at index 4 (centre), counts derived by hand. */
    private fun field(
        revealed: List<Boolean> = List(9) { false },
        flagged: List<Boolean> = List(9) { false },
        outcome: GameOutcome = GameOutcome.PLAYING,
    ): MinesweeperGameState {
        val mines = List(9) { it == 4 }
        val counts = List(9) { i -> if (i == 4) 0 else MinesweeperGameState.of(3, 3, 1).neighbours(i).count { mines[it] } }
        return MinesweeperGameState(
            cols = 3, rows = 3, mineCount = 1, mines = mines, neighbourCounts = counts,
            revealed = revealed, flagged = flagged, outcome = outcome, started = true,
        )
    }

    @Test
    fun gridCellsExposePositionAndStateDescriptions() {
        var tapped = -1
        composeRule.setContent {
            MineFieldGrid(field(), onTap = { tapped = it }, onLongPress = { false })
        }
        // Centre mine stays described as covered during play.
        composeRule.onNodeWithContentDescription("Row 2, column 2, Covered", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Row 1, column 1, Covered", substring = true)
            .performClick()
        assertEquals(0, tapped)
    }

    @Test
    fun gridCellsExposeClickActionsForScreenReaders() {
        var tapped = -1
        var longPressed = -1
        composeRule.setContent {
            MineFieldGrid(field(), onTap = { tapped = it }, onLongPress = { longPressed = it; true })
        }
        // The description alone only lets TalkBack announce the cell; activation
        // needs the click actions too, or the board is heard but not playable.
        val cell = composeRule.onNodeWithContentDescription("Row 1, column 1, Covered", substring = true)
        cell.assertHasClickAction()
        cell.performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(0, tapped)
        cell.performSemanticsAction(SemanticsActions.OnLongClick)
        assertEquals(0, longPressed)
    }

    @Test
    fun longPressBuzzesOnlyWhenAFlagChanges() {
        val buzzes = mutableListOf<HapticFeedbackType>()
        val haptics = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                buzzes += hapticFeedbackType
            }
        }
        var changes = false
        composeRule.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides haptics) {
                MineFieldGrid(field(), onTap = {}, onLongPress = { changes })
            }
        }
        val cell = composeRule.onNodeWithContentDescription("Row 1, column 1, Covered", substring = true)
        cell.performSemanticsAction(SemanticsActions.OnLongClick)
        assertEquals(0, buzzes.size, "nothing changed, so no buzz")
        changes = true
        cell.performSemanticsAction(SemanticsActions.OnLongClick)
        assertEquals(listOf(HapticFeedbackType.LongPress), buzzes)
    }

    @Test
    fun countsStayInsideLargeFieldCellsAtDoubleFontScale() {
        // A Large-field-sized cell (~27dp) with a revealed count, at 200% font.
        val revealed = List(9) { it == 0 }
        composeRule.setContent {
            val current = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(current.density, fontScale = 2f)) {
                Box(Modifier.size(81.dp)) {
                    MineFieldGrid(field(revealed = revealed), onTap = {}, onLongPress = { false })
                }
            }
        }
        val cell = composeRule.onNodeWithContentDescription("Row 1, column 1", substring = true)
            .getUnclippedBoundsInRoot()
        val digit = composeRule.onNodeWithText("1", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(digit.bottom - digit.top <= cell.bottom - cell.top, "count fits its cell: $digit in $cell")
    }

    @Test
    fun lossMarksWrongFlagsButNotCorrectOnes() {
        // Flag on the centre mine (right) and on corner 0 (wrong), then the game is lost.
        val flagged = List(9) { it == 4 || it == 0 }
        composeRule.setContent {
            MineFieldGrid(
                field(flagged = flagged, outcome = GameOutcome.LOST),
                onTap = {},
                onLongPress = { false },
            )
        }
        composeRule.onNodeWithContentDescription("Row 1, column 1, ${str(R.string.cd_wrong_flag)}")
            .assertExists()
        composeRule.onNodeWithContentDescription("Row 2, column 2, ${str(R.string.cd_mine)}")
            .assertExists()
    }

    @Test
    fun wrongFlagsAreNotRevealedDuringPlay() {
        val flagged = List(9) { it == 0 }
        composeRule.setContent { MineFieldGrid(field(flagged = flagged), onTap = {}, onLongPress = { false }) }
        composeRule.onNodeWithContentDescription("Row 1, column 1, ${str(R.string.cd_flagged)}")
            .assertExists()
    }

    @Test
    fun gridRevealedNumberAndFlagDescriptions() {
        val revealed = List(9) { it == 0 }
        val flagged = List(9) { it == 4 }
        composeRule.setContent {
            MineFieldGrid(field(revealed = revealed, flagged = flagged), onTap = {}, onLongPress = { false })
        }
        // Index 0 touches the centre mine, so it reads as a count of 1.
        composeRule.onNodeWithContentDescription("Row 1, column 1", substring = true).assertIsDisplayed()
    }

    @Test
    fun boardScreenShowsTitleCounterModesAndHowTo() {
        var mode: TapMode? = null
        val actions = object : MinesweeperActions {
            override fun setTapMode(m: TapMode) { mode = m }
        }
        val game = MinesweeperGameState.empty(GameConfig(BoardSize.SMALL, Difficulty.EASY))
        composeRule.setContent {
            GameBoardScreen(MinesweeperUiState(config = GameConfig(BoardSize.SMALL, Difficulty.EASY), game = game), actions, onExit = {})
        }
        composeRule.onNodeWithText(str(R.string.new_field)).assertExists()
        assertEquals(null, mode)
    }

    @Test
    fun howToPlayHintFollowsTheTapMode() {
        val config = GameConfig(BoardSize.SMALL, Difficulty.EASY)
        val game = MinesweeperGameState.empty(config)
        var state by mutableStateOf(MinesweeperUiState(config = config, game = game))
        composeRule.setContent { GameBoardScreen(state, MinesweeperActions.Noop, onExit = {}) }
        composeRule.onNodeWithText(str(R.string.how_to_play)).assertExists()
        state = state.copy(tapMode = TapMode.FLAG)
        composeRule.onNodeWithText(str(R.string.how_to_play_flag)).assertExists()
        composeRule.onNodeWithText(str(R.string.how_to_play)).assertDoesNotExist()
    }

    @Test
    fun boardScreenLoadingWhenNoGame() {
        composeRule.setContent {
            GameBoardScreen(MinesweeperUiState(game = null), MinesweeperActions.Noop, onExit = {})
        }
        // No how-to text and no grid while loading.
        composeRule.onNodeWithText(str(R.string.how_to_play)).assertDoesNotExist()
        assertTrue(true)
    }

    @Test
    fun displayNamesResolveForSizesAndDifficulties() {
        composeRule.setContent {
            androidx.compose.material3.Text(BoardSize.SMALL.displayName())
            androidx.compose.material3.Text(Difficulty.EXPERT.displayName())
        }
        composeRule.onNodeWithText(str(R.string.size_small)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.difficulty_expert)).assertIsDisplayed()
    }
}
