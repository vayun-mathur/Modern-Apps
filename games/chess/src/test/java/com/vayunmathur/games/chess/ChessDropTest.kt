package com.vayunmathur.games.chess

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.chess.data.PieceType
import com.vayunmathur.games.chess.data.Position
import com.vayunmathur.games.chess.util.ChessViewModel
import com.vayunmathur.games.chess.util.LearnStatus
import com.vayunmathur.games.chess.util.LearnViewModel
import com.vayunmathur.games.chess.util.PuzzleDifficulty
import com.vayunmathur.games.chess.util.PuzzleStatus
import com.vayunmathur.games.chess.util.PuzzleViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Drag-to-move (onSquareDrop) delegation: every drop behaves like its two taps. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ChessDropTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @BeforeTest fun setUp() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private fun sq(file: Char, rank: Int) = Position(8 - rank, file - 'a')

    private fun await(desc: String, timeoutMs: Long = 15_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(25)
        }
        assertTrue(cond(), "timed out: $desc")
    }

    @Test fun dragStateSquareAtMapsTouchOffsets() {
        val drag = BoardDragState()
        assertNull(drag.squareAt(Offset(10f, 10f)), "no board measured yet")
        drag.boardPx = 800f
        assertEquals(Position(0, 0), drag.squareAt(Offset(50f, 50f)))
        assertEquals(Position(0, 7), drag.squareAt(Offset(750f, 50f)))
        assertEquals(Position(7, 0), drag.squareAt(Offset(50f, 750f)))
        assertEquals(Position(7, 7), drag.squareAt(Offset(799f, 799f)))
        assertNull(drag.squareAt(Offset(800f, 100f)), "right edge is outside")
        assertNull(drag.squareAt(Offset(100f, -1f)), "above the board is outside")
    }

    @Test fun gameLegalDropCommitsLikeTwoTaps() {
        val vm = ChessViewModel(app)
        vm.onSquareDrop(sq('e', 2), sq('e', 4))
        val state = vm.uiState.value
        assertEquals(PieceType.PAWN, state.board.pieces[4][4]?.type)
        assertNull(state.board.pieces[6][4], "e2 is empty after the drop")
        assertNull(state.selectedPiece, "drop clears the selection like the second tap")
    }

    @Test fun gameSameSquareDropSelects() {
        val vm = ChessViewModel(app)
        vm.onSquareDrop(sq('e', 2), sq('e', 2))
        assertEquals(sq('e', 2), vm.uiState.value.selectedPiece)
    }

    @Test fun gameIllegalDropFallsBackToTapSelection() {
        val vm = ChessViewModel(app)
        vm.onSquareDrop(sq('e', 2), sq('e', 5))
        val state = vm.uiState.value
        // Two taps would select e2 then tap e5 (not a legal target), deselecting.
        assertEquals(PieceType.PAWN, state.board.pieces[6][4]?.type, "board is unchanged")
        assertNull(state.selectedPiece)
    }

    @Test fun puzzleDropReplaysSolutionLikeTaps() {
        val vm = PuzzleViewModel(app)
        vm.loadRandom(PuzzleDifficulty.EASY)
        await("puzzle solving") { vm.uiState.value.status == PuzzleStatus.Solving }
        var guard = 0
        while (vm.uiState.value.status == PuzzleStatus.Solving && guard++ < 12) {
            val st = vm.uiState.value
            val expected = st.puzzle!!.solution[st.solutionIndex]
            vm.onSquareDrop(expected.from, expected.to)
            if (vm.uiState.value.board.promotionPosition != null && expected.promotion != null) {
                vm.onPromote(expected.promotion!!)
            }
        }
        assertEquals(PuzzleStatus.Solved, vm.uiState.value.status)
        assertEquals(vm.uiState.value.puzzle!!.solution.size, vm.uiState.value.solutionIndex)
    }

    @Test fun learnDropCapturesLikeTwoTaps() {
        val vm = LearnViewModel(app)
        vm.loadStage("fundamentals", "capture")
        await("stage capture") { vm.uiState.value.level != null }
        val before = vm.uiState.value.board
        var dropped = false
        outer@ for (r in 0..7) for (c in 0..7) {
            val enemy = vm.uiState.value.board.pieces[r][c] ?: continue
            if (enemy.color == vm.uiState.value.playerColor) continue
            val target = Position(r, c)
            for (fr in 0..7) for (fc in 0..7) {
                val piece = vm.uiState.value.board.pieces[fr][fc] ?: continue
                if (piece.color != vm.uiState.value.playerColor) continue
                val from = Position(fr, fc)
                val board = vm.uiState.value.board
                if (board.isValidMove(from, target) || board.isPseudoLegalMove(from, target)) {
                    vm.onSquareDrop(from, target)
                    dropped = true
                    break@outer
                }
            }
        }
        assertTrue(dropped, "a capture must be available")
        assertTrue(
            vm.uiState.value.board != before || vm.uiState.value.status != LearnStatus.Playing,
            "the drop captures exactly like two taps would"
        )
    }
}
