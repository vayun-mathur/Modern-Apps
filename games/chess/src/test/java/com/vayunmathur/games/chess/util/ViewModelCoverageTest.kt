package com.vayunmathur.games.chess.util

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.chess.R
import com.vayunmathur.games.chess.data.PieceColor
import com.vayunmathur.games.chess.data.PieceType
import com.vayunmathur.games.chess.data.Position
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Deeper behavioural flows for Chess/Puzzle/Learn ViewModels via their public actions. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ViewModelCoverageTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private fun sq(file: Char, rank: Int) = Position(8 - rank, file - 'a')

    @BeforeTest fun setUp() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private fun await(desc: String, timeoutMs: Long = 15_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(40)
        }
        assertTrue(cond(), "timed out waiting for $desc")
    }

    // -- ChessViewModel -------------------------------------------------------

    @Test fun `chess drop commits a move and same-square drop only selects`() {
        val vm = ChessViewModel(app)
        vm.onSquareDrop(sq('e', 2), sq('e', 2))
        assertEquals(sq('e', 2), vm.uiState.value.selectedPiece)
        assertEquals(PieceColor.WHITE, vm.uiState.value.turn)
        vm.onSquareDrop(sq('e', 2), sq('e', 5)) // illegal target: deselects, no move
        assertNull(vm.uiState.value.selectedPiece)
        assertEquals(PieceColor.WHITE, vm.uiState.value.turn)
        vm.onSquareDrop(sq('e', 2), sq('e', 4)) // legal drop plays the move
        assertEquals(PieceColor.BLACK, vm.uiState.value.turn)
        assertEquals(PieceType.PAWN, vm.uiState.value.board.pieces[sq('e', 4).row][sq('e', 4).col]?.type)
        assertNull(vm.uiState.value.selectedPiece)
    }

    @Test fun `chess check is reported without ending the game and the king can capture out`() {
        val vm = ChessViewModel(app)
        fun move(f1: Char, r1: Int, f2: Char, r2: Int) {
            vm.onSquareClick(sq(f1, r1)); vm.onSquareClick(sq(f2, r2))
        }
        move('e', 2, 'e', 4); move('e', 7, 'e', 5)
        move('d', 1, 'h', 5); move('b', 8, 'c', 6)
        move('h', 5, 'f', 7) // Qxf7+
        val checked = vm.uiState.value
        assertNull(checked.gameResult)
        assertEquals(app.getString(R.string.check), checked.gameStatus)
        assertEquals(PieceColor.BLACK, checked.turn)
        move('e', 8, 'f', 7) // Kxf7
        assertEquals(PieceColor.WHITE, vm.uiState.value.turn)
        assertEquals(PieceType.KING, vm.uiState.value.board.pieces[sq('f', 7).row][sq('f', 7).col]?.type)
        assertTrue(vm.uiState.value.board.capturedByBlack.any { it.type == PieceType.QUEEN })
    }

    @Test fun `chess new game after mate resets the board and win scoring`() {
        val vm = ChessViewModel(app)
        fun move(f1: Char, r1: Int, f2: Char, r2: Int) {
            vm.onSquareClick(sq(f1, r1)); vm.onSquareClick(sq(f2, r2))
        }
        move('f', 2, 'f', 3); move('e', 7, 'e', 5); move('g', 2, 'g', 4); move('d', 8, 'h', 4)
        assertTrue(vm.uiState.value.gameResult is GameResult.Checkmate)
        assertTrue(vm.claimWinScoring())
        vm.onNewGame(GameMode.TwoPlayer)
        val fresh = vm.uiState.value
        assertNull(fresh.gameResult)
        assertEquals(PieceColor.WHITE, fresh.turn)
        assertEquals(32, fresh.board.pieces.flatten().count { it != null })
        assertTrue(vm.claimWinScoring(), "a new game may be scored again")
        assertTrue(GameResult.Stalemate.isGameOver)
        assertEquals(4, Difficulty.entries.size)
        assertTrue(Difficulty.entries.zipWithNext().all { (a, b) -> a.elo < b.elo },
            "difficulty ratings ascend")
    }

    // -- PuzzleViewModel ------------------------------------------------------

    @Test fun `puzzle drop plays the expected move and same-square drop selects`() {
        val vm = PuzzleViewModel(app)
        vm.loadRandom(PuzzleDifficulty.EASY)
        await("solving") { vm.uiState.value.status == PuzzleStatus.Solving }
        val st = vm.uiState.value
        val expected = st.puzzle!!.solution[st.solutionIndex]
        vm.onSquareDrop(expected.from, expected.from)
        assertEquals(expected.from, vm.uiState.value.selectedPiece)
        vm.onSquareDrop(expected.from, expected.to)
        if (vm.uiState.value.board.promotionPosition != null && expected.promotion != null) {
            vm.onPromote(expected.promotion)
        }
        assertTrue(vm.uiState.value.status != PuzzleStatus.Failed, "the solution move must not fail")
        await("advanced") {
            vm.uiState.value.solutionIndex != st.solutionIndex ||
                vm.uiState.value.status == PuzzleStatus.Solved
        }
    }

    @Test fun `puzzle selection toggles reselects and an illegal target only deselects`() {
        val vm = PuzzleViewModel(app)
        vm.onCancelPromotion() // nothing pending: no-op
        vm.loadRandom(PuzzleDifficulty.MEDIUM)
        await("solving") { vm.uiState.value.status == PuzzleStatus.Solving }
        val st = vm.uiState.value
        val own = st.board.pieces.flatMapIndexed { r, row ->
            row.mapIndexedNotNull { c, p -> if (p?.color == st.playerColor) Position(r, c) else null }
        }
        assertTrue(own.size >= 2)
        vm.onSquareClick(own[0])
        assertEquals(own[0], vm.uiState.value.selectedPiece)
        vm.onSquareClick(own[1])
        assertEquals(own[1], vm.uiState.value.selectedPiece, "tapping a second own piece reselects")
        vm.onSquareClick(own[1])
        assertNull(vm.uiState.value.selectedPiece, "tapping the selected piece deselects")
        // Illegal target: an empty square the selected piece cannot reach.
        val legalEnds = st.board.legalMoves(st.playerColor).filter { it.start == own[0] }.map { it.end }.toSet()
        val illegal = (0 until 8).flatMap { r -> (0 until 8).map { c -> Position(r, c) } }
            .first { it !in legalEnds && st.board.pieces[it.row][it.col] == null }
        vm.onSquareClick(own[0])
        vm.onSquareClick(illegal)
        assertNull(vm.uiState.value.selectedPiece)
        assertEquals(PuzzleStatus.Solving, vm.uiState.value.status, "illegal clicks never fail a puzzle")
    }

    @Test fun `puzzle failed state can replay the solution to solved`() {
        val vm = PuzzleViewModel(app)
        vm.loadRandom(PuzzleDifficulty.EASY)
        await("solving") { vm.uiState.value.status == PuzzleStatus.Solving }
        val st = vm.uiState.value
        val expected = st.puzzle!!.solution[st.solutionIndex]
        val wrong = st.board.legalMoves(st.playerColor)
            .firstOrNull { it.start != expected.from || it.end != expected.to }
        kotlin.test.assertNotNull(wrong)
        vm.onSquareClick(wrong.start); vm.onSquareClick(wrong.end)
        await("failed") { vm.uiState.value.status == PuzzleStatus.Failed }
        vm.onSquareClick(expected.from) // input is closed while Failed
        assertNull(vm.uiState.value.selectedPiece)
        vm.showSolution()
        await("replayed") { vm.uiState.value.status == PuzzleStatus.Solved }
        assertEquals(st.puzzle!!.solution.size, vm.uiState.value.solutionIndex)
    }

    // -- LearnViewModel -------------------------------------------------------

    @Test fun `learn rook level completes on the apple and advances`() {
        val vm = LearnViewModel(app)
        vm.loadStage("chessPieces", "rook")
        await("stage") { vm.uiState.value.level != null }
        vm.goToLevel(0)
        val scoreBefore = vm.uiState.value.stageScore
        vm.onSquareClick(sq('e', 2))
        assertEquals(sq('e', 2), vm.uiState.value.selectedPiece)
        vm.onSquareClick(sq('e', 7)) // Rxe7 collects the only apple
        val done = vm.uiState.value
        assertEquals(LearnStatus.Completed, done.status)
        assertEquals(3, done.starsEarned, "one move is par: three stars")
        assertEquals(1, done.nbMoves)
        assertTrue(done.stageScore > scoreBefore, "apple points plus the completion bonus")
        vm.onSquareClick(sq('e', 7)) // completed levels ignore the board
        assertEquals(LearnStatus.Completed, vm.uiState.value.status)
        vm.nextLevel()
        assertEquals(1, vm.uiState.value.levelIndex)
        assertEquals(LearnStatus.Playing, vm.uiState.value.status)
    }

    @Test fun `learn drop completes like taps and a non-check move fails the check stage`() {
        val rook = LearnViewModel(app)
        rook.loadStage("chessPieces", "rook")
        await("rook stage") { rook.uiState.value.level != null }
        rook.goToLevel(0)
        rook.onSquareDrop(sq('e', 2), sq('e', 7))
        assertEquals(LearnStatus.Completed, rook.uiState.value.status)

        val check = LearnViewModel(app)
        check.loadStage("fundamentals", "check1")
        await("check stage") { check.uiState.value.level != null }
        check.goToLevel(0)
        check.onSquareClick(sq('a', 1))
        check.onSquareClick(sq('a', 2)) // Ra2 gives no check: the one-move goal is missed
        assertEquals(LearnStatus.Failed, check.uiState.value.status)
        check.retryLevel()
        assertEquals(LearnStatus.Playing, check.uiState.value.status)
        assertEquals(0, check.uiState.value.levelIndex)
        check.onSquareClick(sq('a', 1))
        check.onSquareClick(sq('e', 1)) // Re1+ down the e-file, out of the bishop's reach
        assertEquals(LearnStatus.Completed, check.uiState.value.status)
    }

    @Test fun `learn hanging a piece is punished by the capture then fails`() {
        val vm = LearnViewModel(app)
        vm.loadStage("fundamentals", "check1")
        await("check stage") { vm.uiState.value.level != null }
        vm.goToLevel(0)
        vm.onSquareClick(sq('a', 1))
        vm.onSquareClick(sq('h', 1)) // Rh1 hangs to the c6 bishop and gives no check
        await("punished") { vm.uiState.value.status == LearnStatus.Failed }
        val bishop = vm.uiState.value.board.pieces[sq('h', 1).row][sq('h', 1).col]
        assertEquals(PieceType.BISHOP, bishop?.type)
        assertEquals(PieceColor.BLACK, bishop?.color)
    }

    @Test fun `learn en passant scenario waits for the opponent then accepts the capture`() {
        val vm = LearnViewModel(app)
        vm.loadStage("intermediate", "enpassant")
        await("enpassant stage") { vm.uiState.value.level != null }
        vm.goToLevel(0)
        // Black plays d7d5 first; only then is it the player's turn.
        await("opponent setup") {
            vm.uiState.value.board.pieces[sq('d', 5).row][sq('d', 5).col]?.color == PieceColor.BLACK
        }
        vm.onSquareClick(sq('c', 5))
        vm.onSquareClick(sq('d', 6)) // cxd6 en passant finishes the script
        assertEquals(LearnStatus.Completed, vm.uiState.value.status)
        assertNull(vm.uiState.value.board.pieces[sq('d', 5).row][sq('d', 5).col])
    }

    @Test fun `learn castling completes and level navigation guards its bounds`() {
        val vm = LearnViewModel(app)
        vm.loadStage("intermediate", "castling")
        await("castle stage") { vm.uiState.value.level != null }
        vm.goToLevel(0)
        vm.goToLevel(-1)
        assertEquals(0, vm.uiState.value.levelIndex)
        vm.onSquareClick(sq('e', 1))
        vm.onSquareClick(sq('g', 1)) // O-O
        assertEquals(LearnStatus.Completed, vm.uiState.value.status)
        assertEquals(PieceType.ROOK, vm.uiState.value.board.pieces[sq('f', 1).row][sq('f', 1).col]?.type)

        val king = LearnViewModel(app)
        king.loadStage("chessPieces", "king")
        await("king stage") { king.uiState.value.level != null }
        val last = king.uiState.value.stage!!.levels.lastIndex
        king.goToLevel(last)
        king.nextLevel()
        assertTrue(king.uiState.value.stageFinished, "next past the final level finishes the stage")
        assertNotNull(king.uiState.value.stage)
    }

    @Test fun `learn promotion choice promotes to the picked piece`() {
        val vm = LearnViewModel(app)
        vm.loadStage("chessPieces", "pawn")
        await("pawn stage") { vm.uiState.value.level != null }
        vm.goToLevel(0)
        fun move(from: Position, to: Position) {
            if (vm.uiState.value.selectedPiece != from) vm.onSquareClick(from)
            vm.onSquareClick(to)
        }
        move(sq('a', 5), sq('a', 6))
        move(sq('a', 6), sq('a', 7))
        move(sq('a', 7), sq('a', 8))
        assertNotNull(vm.uiState.value.board.promotionPosition)
        vm.onPromote(PieceType.KNIGHT)
        assertNull(vm.uiState.value.board.promotionPosition)
        assertEquals(PieceType.KNIGHT, vm.uiState.value.board.pieces[sq('a', 8).row][sq('a', 8).col]?.type)
        vm.onCancelPromotion() // nothing pending any more: no-op
        assertEquals(PieceType.KNIGHT, vm.uiState.value.board.pieces[sq('a', 8).row][sq('a', 8).col]?.type)
    }
}
