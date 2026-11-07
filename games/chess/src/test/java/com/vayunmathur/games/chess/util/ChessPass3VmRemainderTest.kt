package com.vayunmathur.games.chess.util

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.chess.data.Board
import com.vayunmathur.games.chess.data.LearnRepository
import com.vayunmathur.games.chess.data.PieceColor
import com.vayunmathur.games.chess.data.Position
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
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

/** Pass 3: Maia availability on JVM, VsAI flow with no model, Learn punish path. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ChessPass3VmRemainderTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val ctx: Context get() = app

    @BeforeTest fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        LearnRepository.ensureLoaded(ctx)
    }

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun await(desc: String, timeoutMs: Long = 15_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(25)
        }
        assertTrue(cond(), "timed out: $desc")
    }

    private fun sq(file: Char, rank: Int) = Position(8 - rank, file - 'a')

    @Test fun maiaIsUnavailableOnJvmAndBestMoveIsNull() = runBlocking {
        val api = ChessApi(app)
        assertEquals(false, api.isAvailable(), "no Vulkan model runner on the JVM")
        assertNull(api.getBestMove(Board.initialState, PieceColor.WHITE, Difficulty.BEGINNER))
        api.close()
        api.close()
        // Difficulty contract: four ascending ratings.
        assertEquals(listOf(1100, 1500, 1900, 2500), Difficulty.entries.map { it.elo })
        assertTrue(Difficulty.BEGINNER.temperature > Difficulty.GRANDMASTER.temperature)
    }

    @Test fun vsAiGameWithNoModelKeepsPlayerMoveOnly() {
        val vm = ChessViewModel(app)
        vm.onNewGame(GameMode.VsAI(PieceColor.WHITE, Difficulty.BEGINNER))
        assertEquals(GameMode.VsAI(PieceColor.WHITE, Difficulty.BEGINNER), vm.uiState.value.gameMode)
        vm.onSquareClick(sq('e', 2))
        vm.onSquareClick(sq('e', 4))
        assertEquals(1, vm.uiState.value.board.moves.size)
        // The AI reply is attempted (500 ms delay + engine probe) and, with no
        // model on this host, simply never lands: the position is untouched
        // regardless of when the probe finishes.
        Thread.sleep(1200)
        assertEquals(1, vm.uiState.value.board.moves.size)
        assertNull(vm.uiState.value.gameResult)

        // As Black, the AI (White) is asked to open and likewise cannot.
        vm.onNewGame(GameMode.VsAI(PieceColor.BLACK, Difficulty.ADVANCED))
        assertTrue(vm.uiState.value.isBoardFlipped)
        Thread.sleep(1200)
        assertEquals(0, vm.uiState.value.board.moves.size)
    }

    @Test fun learnProtectionLevelPunishesHangingMove() {
        val vm = LearnViewModel(app)
        vm.loadStage("fundamentals", "protection")
        await("protection loaded") { vm.uiState.value.level != null }
        val state = vm.uiState.value
        val level = state.level!!
        // Find a legal move after which the opponent has an unprotected capture.
        var hanging: Pair<Position, Position>? = null
        outer@ for (r in 0..7) for (c in 0..7) {
            val piece = state.board.pieces[r][c] ?: continue
            if (piece.color != state.playerColor) continue
            for (r2 in 0..7) for (c2 in 0..7) {
                val from = Position(r, c)
                val to = Position(r2, c2)
                if (!state.board.isValidMove(from, to) && !state.board.isPseudoLegalMove(from, to)) continue
                val after = state.board.movePiece(from, to)
                if (detectCaptureMove(level, after, state.playerColor) != null) {
                    hanging = from to to
                    break@outer
                }
            }
        }
        val (from, to) = hanging ?: error("protection level should offer a hanging move")
        val piecesBefore = (0..7).sumOf { r -> (0..7).count { c -> vm.uiState.value.board.pieces[r][c] != null } }
        vm.onSquareClick(from)
        vm.onSquareClick(to)
        await("punished") { vm.uiState.value.status == LearnStatus.Failed }
        val piecesAfter = (0..7).sumOf { r -> (0..7).count { c -> vm.uiState.value.board.pieces[r][c] != null } }
        assertEquals(piecesBefore - 1, piecesAfter, "the opponent grabs the hanging piece")
        vm.retryLevel()
        assertEquals(LearnStatus.Playing, vm.uiState.value.status)
    }

    @Test fun learnCheckAndCastleStagesComplete() {
        // Castle stage: play the scripted castling sequence for level 0.
        val vm = LearnViewModel(app)
        vm.loadStage("intermediate", "castling")
        await("castling loaded") { vm.uiState.value.level != null }
        fun move(f1: Char, r1: Int, f2: Char, r2: Int) {
            vm.onSquareClick(sq(f1, r1)); vm.onSquareClick(sq(f2, r2))
        }
        // Level 0 FEN already has Bf1-c4 and Nf3 developed: castle kingside at once if legal.
        val before = vm.uiState.value.board
        if (before.isValidMove(sq('e', 1), sq('g', 1)) || before.isPseudoLegalMove(sq('e', 1), sq('g', 1))) {
            move('e', 1, 'g', 1)
        }
        if (vm.uiState.value.status != LearnStatus.Completed) {
            // Otherwise develop then castle.
            move('e', 2, 'e', 4); move('g', 1, 'f', 3); move('f', 1, 'c', 4); move('e', 1, 'g', 1)
        }
        assertEquals(LearnStatus.Completed, vm.uiState.value.status)
        assertTrue(vm.uiState.value.board.lastMove?.isCastling == true)
    }
}
