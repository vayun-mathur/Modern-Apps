package com.vayunmathur.games.chess.util

import com.vayunmathur.games.chess.data.Board
import com.vayunmathur.games.chess.data.PieceColor
import com.vayunmathur.games.chess.data.Puzzle
import com.vayunmathur.games.chess.data.PuzzleMove
import com.vayunmathur.games.chess.data.Position
import com.vayunmathur.games.chess.data.toFen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ChessProgressCodecTest {

    private fun sq(file: Char, rank: Int) = Position(8 - rank, file - 'a')

    private fun midGameState(): ChessUiState {
        var board = Board.initialState
        board = board.movePiece(sq('e', 2), sq('e', 4))
        board = board.movePiece(sq('e', 7), sq('e', 5))
        board = board.movePiece(sq('g', 1), sq('f', 3))
        return ChessUiState(
            board = board,
            gameMode = GameMode.VsAI(PieceColor.WHITE, Difficulty.INTERMEDIATE),
            turn = PieceColor.BLACK,
            isBoardFlipped = false,
        )
    }

    @Test
    fun midGameLocalRoundTripsThroughFenAndMoveList() {
        val state = midGameState()
        val decoded = assertNotNull(ChessProgressCodec.decode(ChessProgressCodec.encode(state)))
        assertEquals(state.board, decoded.board)
        assertEquals(state.board.moves.map { it.toString() }, decoded.board.moves.map { it.toString() })
        assertEquals(state.gameMode, decoded.gameMode)
        assertEquals(PieceColor.BLACK, decoded.turn)
    }

    @Test
    fun twoPlayerMidGameRoundTrips() {
        val state = midGameState().copy(gameMode = GameMode.TwoPlayer)
        val decoded = assertNotNull(ChessProgressCodec.decode(ChessProgressCodec.encode(state)))
        assertEquals(GameMode.TwoPlayer, decoded.gameMode)
        assertEquals(state.board, decoded.board)
    }

    @Test
    fun corruptAndMismatchedFenAreDropped() {
        val encoded = ChessProgressCodec.encode(midGameState())
        assertNull(ChessProgressCodec.decode("not json"))
        assertNull(ChessProgressCodec.decode("""{"v":2}"""))
        // Same moves, wrong claimed end position: the FEN check rejects it.
        assertNull(ChessProgressCodec.decode(encoded.replace(" b ", " w ")))
        assertNull(
            ChessProgressCodec.decode(
                encoded.replace("e2e4", "e2e5"),
            ),
        )
    }

    @Test
    fun midPuzzleRoundTripsThroughFenAndSolution() {
        // Fool's-mate position as a puzzle: setup f2f3, then the solver line.
        val solution = listOf(
            PuzzleMove(sq('f', 2), sq('f', 3), null),
            PuzzleMove(sq('e', 7), sq('e', 5), null),
            PuzzleMove(sq('g', 2), sq('g', 4), null),
            PuzzleMove(sq('d', 8), sq('h', 4), null),
        )
        val puzzle = Puzzle(rating = 900, board = Board.initialState, solution = solution)
        var board = puzzle.board
        board = board.movePiece(solution[0].from, solution[0].to, solution[0].promotion)
        board = board.movePiece(solution[1].from, solution[1].to, solution[1].promotion)
        val state = PuzzleUiState(
            puzzle = puzzle,
            board = board,
            playerColor = PieceColor.BLACK,
            isBoardFlipped = true,
            rating = 900,
            solutionIndex = 2,
            status = PuzzleStatus.Solving,
            difficulty = PuzzleDifficulty.EASY,
        )
        val decoded = assertNotNull(PuzzleProgressCodec.decode(PuzzleProgressCodec.encode(state)))
        // Board equality includes the puzzle loader's synthetic seed move, so compare the position.
        // FEN round-trip also normalises hasMoved on non-castling pieces (only kings/rooks
        // retain it, and only they read it), so compare type+color per square, not Piece equality.
        assertEquals(
            board.pieces.map { row -> row.map { it?.let { p -> p.type to p.color } } },
            decoded.board.pieces.map { row -> row.map { it?.let { p -> p.type to p.color } } },
        )
        assertEquals(board.toFen(), decoded.board.toFen())
        assertEquals(2, decoded.solutionIndex)
        assertEquals(PieceColor.BLACK, decoded.playerColor)
        assertEquals(solution, decoded.puzzle.solution)
        assertEquals(900, decoded.puzzle.rating)
        assertNull(PuzzleProgressCodec.decode("not json"))
    }
}
