package com.vayunmathur.games.nonogram.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.longClick
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.vayunmathur.games.nonogram.R
import com.vayunmathur.games.nonogram.data.GameMode
import com.vayunmathur.games.nonogram.data.MarkMode
import com.vayunmathur.games.nonogram.data.NonogramGameState
import com.vayunmathur.games.nonogram.data.NonogramPuzzle
import com.vayunmathur.games.nonogram.data.STARTING_HEARTS
import com.vayunmathur.games.nonogram.platform.NonogramGameActions
import com.vayunmathur.games.nonogram.platform.NonogramUiState
import com.vayunmathur.games.nonogram.platform.SettingsActions
import com.vayunmathur.games.nonogram.platform.SettingsUiState
import com.vayunmathur.games.nonogram.ui.components.NonogramBoard
import com.vayunmathur.library.ui.R as UiR
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class NonogramScreensTest {
    @get:Rule val rule = createComposeRule()
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private fun str(id: Int, vararg args: Any) = if (args.isEmpty()) ctx.getString(id) else ctx.getString(id, *args)

    class Recording : NonogramGameActions {
        val taps = mutableListOf<Int>(); val crosses = mutableListOf<Int>()
        val modes = mutableListOf<MarkMode>(); val gameModes = mutableListOf<GameMode>()
        var next = 0; var restarts = 0; var retries = 0; var undos = 0
        override fun undo() { undos++ }
        /** Every stroke as the gesture handler reported it: begin cell, alternate?, then extensions. */
        val strokes = mutableListOf<MutableList<Any>>(); var openStroke = false
        override fun tapCell(index: Int) { taps += index }
        override fun crossCell(index: Int) { crosses += index }
        override fun beginStroke(index: Int, alternate: Boolean) { strokes += mutableListOf<Any>(index, alternate); openStroke = true }
        override fun extendStroke(index: Int) { strokes.last() += index }
        override fun endStroke() { openStroke = false }
        override fun setMarkMode(mode: MarkMode) { modes += mode }
        override fun nextLevel() { next++ }
        override fun restartLevel() { restarts++ }
        override fun retryGeneration() { retries++ }
        override fun setGameMode(mode: GameMode) { gameModes += mode }
    }

    private fun puzzle(): NonogramPuzzle {
        // 3x3 cross
        return NonogramPuzzle.from(3, listOf(false,true,false, true,true,true, false,true,false))
    }
    private fun game(filled: Set<Int> = emptySet(), crossed: Set<Int> = emptySet(), revealed: Set<Int> = emptySet(), hearts: Int = STARTING_HEARTS, mode: GameMode = GameMode.CASUAL) =
        NonogramGameState(puzzle(), filled, crossed, revealed, hearts, mode, level = 2)
    private fun answer() = puzzle().solution.indices.filter { puzzle().solution[it] }.toSet()

    /** The cell at 1-based [row], [col], asserted to announce [state] as its state. */
    private fun cell(row: Int, col: Int, state: Int) =
        rule.onNodeWithContentDescription(str(R.string.cd_cell_position, row, col))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, str(state)))

    @Test fun talkBackCanFillAndCrossThroughNamedActions() {
        val a = Recording()
        rule.setContent {
            NonogramBoard(game = game(filled = setOf(1), crossed = setOf(0)), actions = a, markMode = MarkMode.FILL)
        }
        // Fill mode: double tap fills, double-tap-and-hold crosses (or removes an existing cross).
        cell(2, 2, R.string.cd_blank)
            .assert(SemanticsMatcher("fill label") { it.config[SemanticsActions.OnClick].label == str(R.string.action_fill) })
            .assert(SemanticsMatcher("cross label") { it.config[SemanticsActions.OnLongClick].label == str(R.string.action_cross) })
            .performSemanticsAction(SemanticsActions.OnClick)
        cell(2, 2, R.string.cd_blank).performSemanticsAction(SemanticsActions.OnLongClick)
        cell(1, 1, R.string.cd_crossed)
            .assert(SemanticsMatcher("uncross label") { it.config[SemanticsActions.OnLongClick].label == str(R.string.action_uncross) })
        // A settled cell offers nothing to do.
        cell(1, 2, R.string.cd_filled).assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        assertEquals(listOf(4), a.taps); assertEquals(listOf(4), a.crosses)
    }

    @Test fun talkBackActionsFollowCrossMode() {
        rule.setContent { NonogramBoard(game = game(crossed = setOf(0)), actions = NonogramGameActions.Noop, markMode = MarkMode.CROSS) }
        cell(1, 1, R.string.cd_crossed)
            .assert(SemanticsMatcher("uncross on tap") { it.config[SemanticsActions.OnClick].label == str(R.string.action_uncross) })
            .assert(SemanticsMatcher("fill on hold") { it.config[SemanticsActions.OnLongClick].label == str(R.string.action_fill) })
    }

    @Test fun loadingStateShowsBoardChrome() {
        val a = Recording()
        rule.setContent { NonogramGameScreen(NonogramUiState(generating = true), a, {}, {}) }
        rule.onNodeWithText(str(R.string.level_title, 1)).assertExists()
    }

    @Test fun generationFailedOffersRetry() {
        val a = Recording()
        rule.setContent { NonogramGameScreen(NonogramUiState(generationFailed = true), a, {}, {}) }
        rule.onNodeWithText(str(R.string.generation_failed)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.try_again)).performClick()
        assertEquals(1, a.retries, "try-again must widen the seed search, not just clear marks")
        assertEquals(0, a.restarts)
    }

    @Test fun inProgressShowsHintToggleAndRestartWithoutConfirm() {
        val a = Recording()
        rule.setContent { NonogramGameScreen(NonogramUiState(game = game(), mode = GameMode.CASUAL, markMode = MarkMode.FILL, level = 2), a, {}, {}) }
        rule.onNodeWithText(str(R.string.how_to_play)).assertExists()
        rule.onNodeWithText(str(R.string.mode_cross)).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(MarkMode.CROSS), a.modes)
        rule.onNodeWithText(str(UiR.string.restart)).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, a.restarts, "untouched board restarts without confirmation")
    }

    @Test fun undoButtonFollowsCanUndo() {
        val a = Recording()
        var state by androidx.compose.runtime.mutableStateOf(NonogramUiState(game = game(filled = setOf(1)), level = 2, canUndo = false))
        rule.setContent { NonogramGameScreen(state, a, {}, {}) }
        rule.onNodeWithText(str(UiR.string.undo)).assertIsNotEnabled()
        state = state.copy(canUndo = true)
        rule.onNodeWithText(str(UiR.string.undo)).assertIsEnabled().performClick()
        assertEquals(1, a.undos)
    }

    @Test fun pinchZoomHitTestsInGridCoordinatesAndDoubleTapResets() {
        // 5x5 in a 200dp box: about 32dp cells, so this board allows exactly 2x.
        val p = NonogramPuzzle.from(5, "##.#######.#.#..#.#.#.#.#".map { it == '#' })
        val g = NonogramGameState(p, emptySet(), emptySet(), emptySet(), STARTING_HEARTS, GameMode.CASUAL, 1)
        val a = Recording()
        rule.setContent { androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.size(200.dp)) { NonogramBoard(game = g, actions = a) } }
        fun node(row: Int, col: Int) = rule.onNodeWithContentDescription(str(R.string.cd_cell_position, row, col))
        // Unzoomed centre of row 3, column 5 (index 14): the physical point we will keep tapping.
        val point = node(3, 5).fetchSemanticsNode().boundsInRoot.center
        val centre = node(3, 3).fetchSemanticsNode().boundsInRoot.center
        rule.onRoot().performTouchInput {
            pinch(
                start0 = centre - androidx.compose.ui.geometry.Offset(10f, 0f), end0 = centre - androidx.compose.ui.geometry.Offset(40f, 0f),
                start1 = centre + androidx.compose.ui.geometry.Offset(10f, 0f), end1 = centre + androidx.compose.ui.geometry.Offset(40f, 0f),
            )
        }
        assertTrue(a.strokes.isEmpty(), "a pinch never marks a cell")
        // Zoomed 2x about the centre, that same point is now over row 3, column 4.
        rule.onRoot().performTouchInput { click(point) }
        assertEquals(listOf<Any>(13, false), a.strokes.last())
        // Double-tapping the clues resets the zoom: the point is back over column 5.
        rule.onAllNodesWithText("5")[0].performTouchInput { doubleClick() }
        rule.onRoot().performTouchInput { click(point) }
        assertEquals(listOf<Any>(14, false), a.strokes.last())
    }

    @Test fun restartWithProgressAsksConfirmation() {
        val a = Recording()
        rule.setContent { NonogramGameScreen(NonogramUiState(game = game(filled = setOf(1)), level = 2), a, {}, {}) }
        rule.onNodeWithText(str(UiR.string.restart)).performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithText(str(R.string.confirm_restart_title)).assertExists()
        rule.onNodeWithText(str(UiR.string.cancel)).performClick()
        assertEquals(0, a.restarts)
        rule.onNodeWithText(str(UiR.string.restart)).performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithText(str(R.string.confirm_restart_title)).assertExists()
        // Two Restart nodes now exist (screen + dialog); the dialog's is last.
        val nodes = rule.onAllNodes(androidx.compose.ui.test.hasText(str(UiR.string.restart)))
        nodes.fetchSemanticsNodes().size.let { assertTrue(it >= 2) }
        nodes[1].performClick()
        assertEquals(1, a.restarts)
    }

    @Test fun wonCasualShowsNextLevel() {
        val a = Recording()
        rule.setContent { NonogramGameScreen(NonogramUiState(game = game(filled = answer()), level = 2), a, {}, {}) }
        rule.onNodeWithText(str(R.string.solved)).assertExists()
        rule.onNodeWithText(str(UiR.string.next_level)).performClick()
        assertEquals(1, a.next)
    }

    @Test fun nextLevelIsDisabledWhileTheNextPuzzleGenerates() {
        val a = Recording()
        rule.setContent { NonogramGameScreen(NonogramUiState(game = game(filled = answer()), level = 3, generating = true), a, {}, {}) }
        rule.onNodeWithText(str(UiR.string.next_level)).assertIsNotEnabled().performClick()
        assertEquals(0, a.next, "a second tap while generating must not advance again")
    }

    @Test fun wonDailyShowsComeBack() {
        val a = Recording()
        rule.setContent { NonogramGameScreen(NonogramUiState(game = game(filled = answer(), mode = GameMode.DAILY), mode = GameMode.DAILY, dailyStreak = 3), a, {}, {}) }
        rule.onNodeWithText(str(R.string.daily_come_back_tomorrow)).assertExists()
    }

    @Test fun failedShowsTryAgain() {
        val a = Recording()
        rule.setContent { NonogramGameScreen(NonogramUiState(game = game(hearts = 0), level = 2), a, {}, {}) }
        rule.onNodeWithText(str(R.string.out_of_hearts)).assertExists()
        rule.onNodeWithText(str(R.string.try_again)).performClick()
        assertEquals(1, a.restarts)
    }

    @Test fun boardCellsReportDescriptionsAndTaps() {
        val a = Recording()
        rule.setContent { NonogramBoard(game = game(filled = setOf(1), crossed = setOf(0)), actions = a) }
        cell(1, 2, R.string.cd_filled).assertExists()
        cell(1, 1, R.string.cd_crossed).assertExists()
        cell(1, 3, R.string.cd_blank).performClick()
        // A tap is a one-cell stroke in the current mode.
        assertEquals(listOf(listOf<Any>(2, false)), a.strokes)
        assertTrue(!a.openStroke)
    }

    @Test fun dragAcrossTheGridIsOneStroke() {
        val a = Recording()
        rule.setContent { NonogramBoard(game = game(), actions = a) }
        // From the middle of row 2, column 1 to column 3, all in one gesture.
        val first = rule.onNodeWithContentDescription(str(R.string.cd_cell_position, 2, 1)).fetchSemanticsNode().boundsInRoot
        val last = rule.onNodeWithContentDescription(str(R.string.cd_cell_position, 2, 3)).fetchSemanticsNode().boundsInRoot
        rule.onNodeWithContentDescription(str(R.string.cd_cell_position, 2, 1)).performTouchInput {
            down(center)
            moveBy(androidx.compose.ui.geometry.Offset(last.center.x - first.center.x, 0f), delayMillis = 50)
            up()
        }
        assertEquals(1, a.strokes.size, "one gesture, one stroke")
        assertEquals(listOf<Any>(3, false), a.strokes[0].take(2))
        assertEquals(5, a.strokes[0].last(), "the stroke reaches the cell under the finger")
        assertTrue(!a.openStroke)
    }

    @Test fun rtlGridMapsTouchesToMirroredColumns() {
        val a = Recording()
        rule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Rtl,
            ) { NonogramBoard(game = game(), actions = a) }
        }
        // Column 1 sits on the right under RTL; touching it must report column 1 (index 0).
        rule.onNodeWithContentDescription(str(R.string.cd_cell_position, 1, 1)).performClick()
        rule.onNodeWithContentDescription(str(R.string.cd_cell_position, 1, 3)).performClick()
        assertEquals(listOf(listOf<Any>(0, false), listOf<Any>(2, false)), a.strokes)
    }

    @Test fun boardTellsARevealedBlankApartFromANote() {
        rule.setContent { NonogramBoard(game = game(crossed = setOf(0), revealed = setOf(2)), actions = NonogramGameActions.Noop) }
        cell(1, 1, R.string.cd_crossed).assertExists()
        cell(1, 3, R.string.cd_revealed).assertExists()
    }

    @Test fun boardLongPressStartsAnAlternateStroke() {
        val a = Recording()
        rule.setContent { NonogramBoard(game = game(), actions = a) }
        cell(2, 2, R.string.cd_blank)
            .performTouchInput { longClick() }
        assertEquals(listOf(listOf<Any>(4, true)), a.strokes)
        assertTrue(!a.openStroke)
    }

    @Test fun boardWithEmptyLinesRendersZeroClues() {
        val p = com.vayunmathur.games.nonogram.data.NonogramPuzzle.from(4, listOf(
            true,true,false,false, false,false,false,false, false,false,true,false, false,false,false,true))
        val g = NonogramGameState(p, emptySet(), emptySet(), emptySet(), STARTING_HEARTS, GameMode.CASUAL, 1)
        rule.setContent { NonogramBoard(game = g, actions = NonogramGameActions.Noop) }
        cell(2, 1, R.string.cd_blank).assertExists()
        rule.onNodeWithText("0").assertExists()
    }

    @Test fun boardLargeGeneratedPuzzleRenders() {
        val p = requireNotNull(com.vayunmathur.games.nonogram.domain.NonogramGenerator.generateSeeded(15, 99L))
        val g = NonogramGameState(p, setOf(0), setOf(1), emptySet(), 2, GameMode.CASUAL, 25)
        rule.setContent { NonogramBoard(game = g, actions = NonogramGameActions.Noop) }
        cell(15, 15, R.string.cd_blank).assertExists()
    }

    @Test fun cluesFitTheirSlotsAtLargeFontScale() {
        val p = requireNotNull(com.vayunmathur.games.nonogram.domain.NonogramGenerator.generateSeeded(15, 25L))
        val g = NonogramGameState(p, emptySet(), emptySet(), emptySet(), STARTING_HEARTS, GameMode.CASUAL, 25)
        rule.setContent {
            val base = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(base.density, fontScale = 2f),
            ) {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.size(360.dp)) {
                    NonogramBoard(game = g, actions = NonogramGameActions.Noop)
                }
            }
        }
        val clues = rule.onAllNodes(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult))
            .fetchSemanticsNodes()
        assertTrue(clues.isNotEmpty())
        for (node in clues) {
            val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
            val r = results.single()
            val text = r.layoutInput.text.text
            val fontPx = with(r.layoutInput.density) { r.layoutInput.style.fontSize.toPx() }
            val linePx = with(r.layoutInput.density) { r.layoutInput.style.lineHeight.toPx() }
            assertTrue(fontPx <= linePx + 0.5f, "clue '$text' is ${fontPx}px in a ${linePx}px slot")
            assertTrue(!r.hasVisualOverflow, "clue '$text' clips at 200% font scale")
        }
    }

    @Test fun settingsPageWithRealViewModelRenders() {
        val vm = com.vayunmathur.games.nonogram.platform.NonogramViewModel(
            androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>())
        var backed = false
        rule.setContent { SettingsPage(vm, onBack = { backed = true }) }
        rule.onNodeWithText(str(UiR.string.settings)).assertExists()
        assertTrue(!backed)
    }

    @Test fun settingsScreenRendersAndToggles() {
        var enabled: Boolean? = null; var time: Pair<Int, Int>? = null
        val actions = object : SettingsActions {
            override fun setReminderEnabled(e: Boolean) { enabled = e }
            override fun setReminderTime(hour: Int, minute: Int) { time = hour to minute }
        }
        rule.setContent { SettingsScreen(SettingsUiState(false, 20, 0), actions, {}) }
        rule.onNodeWithText(str(UiR.string.settings)).assertExists()
        assertTrue(enabled == null && time == null)
    }
}
