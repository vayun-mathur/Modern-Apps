package com.vayunmathur.games.chess

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.vayunmathur.games.chess.data.Piece
import com.vayunmathur.games.chess.data.PieceColor
import com.vayunmathur.games.chess.data.PieceType

private const val FLIPPED_ROTATION = 180f

/**
 * Outline width as a fraction of the piece's box. The glyphs' viewport leaves an 8% margin, so
 * the outline never clips; it comes out around 1.5-3dp from the 32dp captured row to the 64dp
 * promotion picker.
 */
private const val OUTLINE_FRACTION = 0.045f
private const val DIAGONAL = 0.7071f

/** Eight directions the silhouette is offset in to build its outline. */
private val outlineDirections = listOf(
    Offset(1f, 0f), Offset(-1f, 0f), Offset(0f, 1f), Offset(0f, -1f),
    Offset(DIAGONAL, DIAGONAL), Offset(-DIAGONAL, DIAGONAL),
    Offset(DIAGONAL, -DIAGONAL), Offset(-DIAGONAL, -DIAGONAL),
)

/**
 * Two-tone piece inks: a body plus a contrasting outline, so every piece reads on both square
 * colours and on the captured-pieces card in light and dark themes. White's dark outline carries
 * it on light squares and light cards; Black's light outline carries it on a dark card, where a
 * flat black glyph used to vanish.
 */
internal val WhitePieceFill = Color(0xFFFAFAFA)
internal val WhitePieceOutline = Color(0xFF262626)
internal val BlackPieceFill = Color(0xFF1E1E1E)
internal val BlackPieceOutline = Color(0xFFE6E6E6)

private val whiteFillFilter = ColorFilter.tint(WhitePieceFill)
private val whiteOutlineFilter = ColorFilter.tint(WhitePieceOutline)
private val blackFillFilter = ColorFilter.tint(BlackPieceFill)
private val blackOutlineFilter = ColorFilter.tint(BlackPieceOutline)

/**
 * A chess piece glyph, drawn as a two-tone silhouette: the glyph tinted with the outline ink and
 * nudged a few percent in eight directions, then the body ink on top. The vector is rasterised
 * once by its painter and only re-tinted per pass, so the nine passes are cheap.
 */
@Composable
fun ChessPiece(piece: Piece, size: Dp? = null, isFlipped: Boolean = false) {
    val description = stringResource(pieceDescriptionRes(piece))
    val painter = painterResource(id = piece.type.resID)
    val white = piece.color == PieceColor.WHITE
    val fill = if (white) whiteFillFilter else blackFillFilter
    val outline = if (white) whiteOutlineFilter else blackOutlineFilter
    Canvas(
        (if (size != null) Modifier.size(size) else Modifier.fillMaxSize())
            .graphicsLayer { if (isFlipped) rotationZ = FLIPPED_ROTATION }
            .semantics {
                contentDescription = description
                role = Role.Image
            }
    ) {
        val box = this.size
        val stroke = box.minDimension * OUTLINE_FRACTION
        with(painter) {
            for (dir in outlineDirections) {
                translate(dir.x * stroke, dir.y * stroke) { draw(box, colorFilter = outline) }
            }
            draw(box, colorFilter = fill)
        }
    }
}

internal fun pieceDescriptionRes(piece: Piece): Int {
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
