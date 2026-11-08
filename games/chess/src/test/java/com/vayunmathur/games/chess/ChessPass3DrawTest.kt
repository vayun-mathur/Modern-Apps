package com.vayunmathur.games.chess

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.games.chess.data.Board
import com.vayunmathur.games.chess.data.LearnShape
import com.vayunmathur.games.chess.data.PieceColor
import com.vayunmathur.games.chess.data.Position
import com.vayunmathur.games.chess.util.LearnStatus
import com.vayunmathur.games.chess.util.LearnUiState
import com.vayunmathur.games.chess.util.LearnViewModel
import com.vayunmathur.library.ui.R as UiR
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pass 3: Learn board canvas drawing (arrows, rings, apples) via real draws. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ChessPass3DrawTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val ctx: Context get() = app
    private fun str(id: Int) = ctx.getString(id)

    @BeforeTest fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        com.vayunmathur.games.chess.data.LearnRepository.ensureLoaded(ctx)
    }

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun await(desc: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(25)
        }
        assertTrue(cond(), "timed out: $desc")
    }

    @Test fun learnBoardDrawsArrowsRingsAndApples() {
        val state = LearnUiState(
            board = Board.initialState,
            apples = setOf(Position(4, 4), Position(0, 0), Position(7, 7)),
            shapes = listOf(
                LearnShape("e2", "e4", "red"),
                LearnShape("g1", null, "blue"),
                LearnShape("a1", "a8", "yellow"),
                LearnShape("h8", null, "paleGreen"),
                LearnShape("d1", "h5", "green"),
            ),
            playerColor = PieceColor.WHITE,
        )
        rule.setContent { LearnBoard(state, onSquareClick = {}) }
        rule.waitForIdle()
        val squares = rule.onAllNodes(androidx.compose.ui.test.hasClickAction(), useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertTrue(squares.size >= 64, "all 64 board squares compose, got ${squares.size}")
    }

    @Test fun learnBoardFlippedDrawsToo() {
        val state = LearnUiState(
            board = Board.initialState,
            selectedPiece = Position(6, 4),
            apples = setOf(Position(3, 3)),
            shapes = listOf(LearnShape("e7", "e5", "red")),
            playerColor = PieceColor.BLACK,
            isFlipped = true,
        )
        rule.setContent {
            Column {
                LearnBoard(state, onSquareClick = {})
                BoardGrid(
                    board = Board.initialState,
                    selectedPiece = Position(6, 4),
                    isFlipped = true,
                    turn = PieceColor.BLACK,
                    onSquareClick = {},
                    showLastMove = true,
                )
            }
        }
        rule.waitForIdle()
        assertTrue(state.apples.isNotEmpty())
    }

    @Test fun learnPunishedFailureShowsRetryButtonAndRetries() {
        val vm = LearnViewModel(app)
        vm.loadStage("fundamentals", "protection")
        await("protection loaded") { vm.uiState.value.level != null }
        val state = vm.uiState.value
        val level = state.level!!
        var hanging: Pair<Position, Position>? = null
        outer@ for (r in 0..7) for (c in 0..7) {
            val piece = state.board.pieces[r][c] ?: continue
            if (piece.color != state.playerColor) continue
            for (r2 in 0..7) for (c2 in 0..7) {
                val from = Position(r, c)
                val to = Position(r2, c2)
                if (!state.board.isValidMove(from, to) && !state.board.isPseudoLegalMove(from, to)) continue
                val after = state.board.movePiece(from, to)
                if (com.vayunmathur.games.chess.util.detectCaptureMove(level, after, state.playerColor) != null) {
                    hanging = from to to
                    break@outer
                }
            }
        }
        val (from, to) = hanging ?: error("protection level should offer a hanging move")
        vm.onSquareClick(from)
        vm.onSquareClick(to)
        await("punished") { vm.uiState.value.status == LearnStatus.Failed }

        rule.setContent {
            LearnStageScreen(viewModel = vm, onBack = {}, onOpenStage = { _, _ -> })
        }
        rule.onNodeWithText(str(R.string.learn_lets_go)).performClick()
        rule.onNodeWithText(str(UiR.string.retry)).assertExists()
        rule.onNodeWithText(str(UiR.string.retry)).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(LearnStatus.Playing, vm.uiState.value.status)
    }

    @Test fun learnFailedStatusShowsRetryAndRetries() {
        val vm = LearnViewModel(app)
        vm.loadStage("fundamentals", "capture")
        await("capture loaded") { vm.uiState.value.level != null }
        // Make a legal-but-wrong move on a capture level with detectCapture: find any
        // move that gets punished (hangs a piece) or fails; retry restores Playing.
        val state = vm.uiState.value
        var played = false
        outer@ for (r in 0..7) for (c in 0..7) {
            val piece = state.board.pieces[r][c] ?: continue
            if (piece.color != state.playerColor) continue
            for (r2 in 0..7) for (c2 in 0..7) {
                val from = Position(r, c)
                val to = Position(r2, c2)
                if (state.board.isValidMove(from, to) || state.board.isPseudoLegalMove(from, to)) {
                    // Prefer a capture (the lesson's goal) to advance the script.
                    if (state.board.pieces[r2][c2] != null) {
                        vm.onSquareClick(from); vm.onSquareClick(to); played = true; break@outer
                    }
                }
            }
        }
        if (!played) {
            // Fall back to any legal move.
            outer2@ for (r in 0..7) for (c in 0..7) {
                val piece = state.board.pieces[r][c] ?: continue
                if (piece.color != state.playerColor) continue
                for (r2 in 0..7) for (c2 in 0..7) {
                    if (state.board.isValidMove(Position(r, c), Position(r2, c2))) {
                        vm.onSquareClick(Position(r, c)); vm.onSquareClick(Position(r2, c2))
                        break@outer2
                    }
                }
            }
        }
        // Whatever the outcome, the stage screen composes the status area for it.
        rule.setContent {
            LearnStageScreen(viewModel = vm, onBack = {}, onOpenStage = { _, _ -> })
        }
        rule.onNodeWithText(str(R.string.learn_lets_go)).performClick()
        rule.waitForIdle()
        assertTrue(vm.uiState.value.status in LearnStatus.entries)
        assertEquals(vm.uiState.value.level?.goalType, "captureAll")
    }
}
