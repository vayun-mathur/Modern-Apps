package com.vayunmathur.games.chess

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import com.vayunmathur.games.chess.data.Piece
import com.vayunmathur.games.chess.data.PieceColor
import com.vayunmathur.games.chess.data.PieceType

private const val FLIPPED_ROTATION = 180f

@Composable
fun ChessPiece(piece: Piece, size: Dp? = null, isFlipped: Boolean = false) {
    val description = stringResource(pieceDescriptionRes(piece))
    Image(
        painterResource(id = piece.type.resID),
        description,
        (if (size != null) Modifier.size(size) else Modifier.fillMaxSize())
            .graphicsLayer { if (isFlipped) rotationZ = FLIPPED_ROTATION },
        colorFilter = ColorFilter.tint(if (piece.color == PieceColor.WHITE) Color.White else Color.Black)
    )
}

private fun pieceDescriptionRes(piece: Piece): Int {
    return if (piece.color == PieceColor.WHITE) whitePieceRes(piece.type) else blackPieceRes(piece.type)
}

private fun whitePieceRes(type: PieceType): Int = when (type) {
    PieceType.KING -> R.string.piece_white_king
    PieceType.QUEEN -> R.string.piece_white_queen
    PieceType.ROOK -> R.string.piece_white_rook
    PieceType.BISHOP -> R.string.piece_white_bishop
    PieceType.KNIGHT -> R.string.piece_white_knight
    PieceType.PAWN -> R.string.piece_white_pawn
}

private fun blackPieceRes(type: PieceType): Int = when (type) {
    PieceType.KING -> R.string.piece_black_king
    PieceType.QUEEN -> R.string.piece_black_queen
    PieceType.ROOK -> R.string.piece_black_rook
    PieceType.BISHOP -> R.string.piece_black_bishop
    PieceType.KNIGHT -> R.string.piece_black_knight
    PieceType.PAWN -> R.string.piece_black_pawn
}
