package com.vayunmathur.games.chess

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import com.vayunmathur.library.ui.rememberHaptics
import kotlin.math.roundToInt
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.chess.data.PieceColor
import com.vayunmathur.games.chess.data.Position

private const val FLIPPED_ROTATION = 180f
private const val CAPTURE_HINT_FRACTION = 0.92f
private const val EMPTY_HINT_FRACTION = 0.30f
private const val BOARD_SQUARES = 8
private const val DRAG_PIECE_SCALE = 1.4f
private const val DRAG_PIECE_LIFT = 0.5f
private const val DRAG_GHOST_ALPHA = 0.35f

/** Test tag of the floating piece a drag draws under the finger. */
internal const val DRAGGED_PIECE_TAG = "chess_dragged_piece"

// File-scope so the chess board doesn't allocate 32 Color instances per recomposition.
private val lightSquareColor = Color(0xFFBBBBBB)
private val darkSquareColor = Color.Gray
private val lastMoveColor = Color(0xFF4CAF50)
private val moveHintColor = Color(0x66000000)
private val dropTargetColor = Color(0xCCFFFFFF)

/**
 * Everything that marks squares for one frame, computed once per board rather than per square:
 * the selection (or drag source), the checked king, the last move, the legal targets of the
 * selected piece and, during a drag, the square under the finger.
 */
private data class BoardMarks(
    val selected: Position?,
    val checkSquare: Position?,
    val lastMove: com.vayunmathur.games.chess.data.Move?,
    val destinations: Set<Position>,
    val dragSource: Position? = null,
    val hover: Position? = null,
) {
    fun isLastMove(pos: Position): Boolean = lastMove?.let { it.start == pos || it.end == pos } ?: false
}

private fun squareBaseColor(row: Int, col: Int): Color =
    if ((row + col) % 2 == 0) lightSquareColor else darkSquareColor

private fun highlightModifier(marks: BoardMarks, pos: Position): Modifier {
    return when {
        marks.selected == pos -> Modifier.border(2.dp, Color.Yellow)
        marks.checkSquare == pos -> Modifier.border(2.dp, Color.Red)
        marks.hover == pos && marks.dragSource != pos -> Modifier.border(3.dp, dropTargetColor)
        marks.isLastMove(pos) -> Modifier.border(3.dp, lastMoveColor)
        else -> Modifier
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
    // One check scan per position, not one per square per recomposition.
    val checkSquare = remember(board, turn) { checkedKingSquare(board, turn) }
    // The square under the finger, re-read only when the finger crosses into a new square.
    val hover by remember(drag) { derivedStateOf { drag.pointer?.let(drag::squareAt) } }
    val haptics = rememberHaptics()
    SideEffect {
        // The drag handler sits outside the 180-degree rotation, so it sees unrotated
        // coordinates; tell it which way round the board is drawn.
        drag.flipped = isFlipped
        // Only the side to move's pieces can be picked up; other presses stay taps/scrolls.
        drag.canPickUp = { p -> board.pieces[p.row][p.col]?.color == turn }
        drag.onPickUp = haptics::longPress
    }
    val marks = BoardMarks(
        selected = hintSource,
        checkSquare = checkSquare,
        // The puzzle board seeds a synthetic zero-length lastMove to encode turn; skip it.
        lastMove = board.lastMove?.takeIf { showLastMove && it.start != it.end },
        destinations = destinations,
        dragSource = drag.source,
        hover = hover,
    )
    // A chessboard never mirrors: a1 stays bottom-left from White's side in every locale. Under
    // RTL the Rows below would otherwise lay files out right-to-left, mirroring the board and
    // desynchronising the squares' own hit-testing from the drag handler's left-origin maths.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .onSizeChanged { drag.boardPx = it.width.toFloat() }
                .boardDragGesture(drag, onSquareDrop)
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { if (isFlipped) rotationZ = FLIPPED_ROTATION }
            ) {
                for (i in board.pieces.indices) {
                    BoardRow(board, row = i, isFlipped = isFlipped, marks = marks, onSquareClick = onSquareClick)
                }
            }
            DraggedPiece(drag = drag, board = board)
        }
    }
}

/**
 * The piece being dragged, drawn above every square (zIndex) at the finger in the board's
 * unrotated space, so it is upright on a flipped board too. Lifted half a square above the
 * touch point and enlarged so the finger does not hide it. Nothing clips it, so it can follow
 * the finger past the board's edge. Invisible to accessibility: TalkBack moves pieces by
 * double-tapping squares instead.
 */
@Composable
private fun BoxScope.DraggedPiece(drag: BoardDragState, board: com.vayunmathur.games.chess.data.Board) {
    val source = drag.source ?: return
    val piece = board.pieces[source.row][source.col] ?: return
    val cellPx = drag.boardPx / BOARD_SQUARES
    val sidePx = cellPx * DRAG_PIECE_SCALE
    val side = with(LocalDensity.current) { sidePx.toDp() }
    Box(
        Modifier
            .align(Alignment.TopStart)
            .zIndex(1f)
            .size(side)
            .absoluteOffset {
                val p = drag.pointer ?: Offset.Zero
                IntOffset(
                    (p.x - sidePx / 2).roundToInt(),
                    (p.y - sidePx / 2 - cellPx * DRAG_PIECE_LIFT).roundToInt(),
                )
            }
            .testTag(DRAGGED_PIECE_TAG)
            .clearAndSetSemantics { }
    ) {
        ChessPiece(piece)
    }
}

@Composable
private fun ColumnScope.BoardRow(
    board: com.vayunmathur.games.chess.data.Board,
    row: Int,
    isFlipped: Boolean,
    marks: BoardMarks,
    onSquareClick: (Position) -> Unit,
) {
    Row(modifier = Modifier.weight(1f)) {
        for (j in board.pieces[row].indices) {
            val piece = board.pieces[row][j]
            val pos = Position(row, j)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(1f)
                    .background(squareBaseColor(row, j))
                    .clickable {
                        onSquareClick(pos)
                    }
                    .then(highlightModifier(marks, pos))
            ) {
                piece?.let {
                    // The dragged piece floats under the finger; leave a ghost where it came from.
                    val alpha = if (marks.dragSource == pos) DRAG_GHOST_ALPHA else 1f
                    Box(Modifier.fillMaxSize().alpha(alpha)) {
                        ChessPiece(it, isFlipped = isFlipped)
                    }
                }
                if (pos in marks.destinations) {
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
