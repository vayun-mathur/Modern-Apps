package com.vayunmathur.games.chess

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.chess.data.PieceColor
import com.vayunmathur.games.chess.data.PieceType
import com.vayunmathur.games.chess.data.Position

private const val FLIPPED_ROTATION = 180f
private const val CAPTURE_HINT_FRACTION = 0.92f
private const val EMPTY_HINT_FRACTION = 0.30f

// File-scope so the chess board doesn't allocate 32 Color instances per recomposition.
private val lightSquareColor = Color(0xFFBBBBBB)
private val darkSquareColor = Color.Gray
private val lastMoveColor = Color(0xFF4CAF50)
private val moveHintColor = Color(0x66000000)
private const val BOARD_SQUARES = 8

private data class SquareHighlight(
    val selected: Boolean,
    val kingInCheck: Boolean,
    val lastMove: Boolean,
)

private fun squareHighlight(
    board: com.vayunmathur.games.chess.data.Board,
    selectedPiece: Position?,
    row: Int,
    col: Int,
    turn: PieceColor,
    lastMove: com.vayunmathur.games.chess.data.Move?,
): SquareHighlight {
    val piece = board.pieces[row][col]
    return SquareHighlight(
        selected = selectedPiece?.let { it.row == row && it.col == col } ?: false,
        kingInCheck = board.isKingInCheck(turn) &&
            piece?.type == PieceType.KING && piece.color == turn,
        lastMove = lastMove?.let {
            (it.start.row == row && it.start.col == col) ||
                (it.end.row == row && it.end.col == col)
        } ?: false,
    )
}

private fun squareBaseColor(row: Int, col: Int): Color =
    if ((row + col) % 2 == 0) lightSquareColor else darkSquareColor

private fun highlightModifier(highlight: SquareHighlight): Modifier {
    return when {
        highlight.selected -> Modifier.border(2.dp, Color.Yellow)
        highlight.kingInCheck -> Modifier.border(2.dp, Color.Red)
        highlight.lastMove -> Modifier.border(3.dp, lastMoveColor)
        else -> Modifier
    }
}

private fun legalDestinations(
    board: com.vayunmathur.games.chess.data.Board,
    selectedPiece: Position?,
): Set<Position> {
    val sel = selectedPiece ?: return emptySet()
    return buildSet {
        for (i in 0 until BOARD_SQUARES) {
            for (j in 0 until BOARD_SQUARES) {
                if (board.isValidMove(sel, Position(i, j))) add(Position(i, j))
            }
        }
    }
}

@Composable
fun BoardGrid(
    board: com.vayunmathur.games.chess.data.Board,
    selectedPiece: Position?,
    isFlipped: Boolean,
    turn: PieceColor,
    onSquareClick: (Position) -> Unit,
    onSquareDrop: (Position, Position) -> Unit = { _, _ -> },
    showLastMove: Boolean = false
) {
    val drag = remember { BoardDragState() }
    // During a drag the hints follow the dragged piece, otherwise the selection.
    val hintSource = drag.source ?: selectedPiece
    // The squares the selected piece can legally move to (for move-hint dots).
    val destinations = remember(board, hintSource) {
        legalDestinations(board, hintSource)
    }
    // The puzzle board seeds a synthetic zero-length lastMove to encode turn; skip it.
    val lastMove = board.lastMove?.takeIf { showLastMove && it.start != it.end }
    Column(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .onSizeChanged { drag.boardPx = it.width.toFloat() }
            .boardDragGesture(drag, onSquareDrop)
            .graphicsLayer { if (isFlipped) rotationZ = FLIPPED_ROTATION }
    ) {
        for (i in board.pieces.indices) {
            BoardRow(
                board = board,
                row = i,
                selectedPiece = hintSource,
                isFlipped = isFlipped,
                turn = turn,
                lastMove = lastMove,
                destinations = destinations,
                onSquareClick = onSquareClick
            )
        }
    }
}

@Composable
private fun ColumnScope.BoardRow(
    board: com.vayunmathur.games.chess.data.Board,
    row: Int,
    selectedPiece: Position?,
    isFlipped: Boolean,
    turn: PieceColor,
    lastMove: com.vayunmathur.games.chess.data.Move?,
    destinations: Set<Position>,
    onSquareClick: (Position) -> Unit,
) {
    Row(modifier = Modifier.weight(1f)) {
        for (j in board.pieces[row].indices) {
            val piece = board.pieces[row][j]
            val highlight = squareHighlight(board, selectedPiece, row, j, turn, lastMove)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(1f)
                    .background(squareBaseColor(row, j))
                    .clickable {
                        onSquareClick(Position(row, j))
                    }
                    .then(highlightModifier(highlight))
            ) {
                piece?.let {
                    ChessPiece(it, isFlipped = isFlipped)
                }
                if (Position(row, j) in destinations) {
                    MoveHintDot(piece != null)
                }
            }
        }
    }
}

@Composable
private fun MoveHintDot(occupied: Boolean) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (occupied) {
            // Capturable square: a ring around the piece.
            Box(
                Modifier
                    .fillMaxSize(CAPTURE_HINT_FRACTION)
                    .border(3.dp, moveHintColor, CircleShape)
            )
        } else {
            // Empty square: a centered dot.
            Box(
                Modifier
                    .fillMaxSize(EMPTY_HINT_FRACTION)
                    .clip(CircleShape)
                    .background(moveHintColor)
            )
        }
    }
}
