package com.vayunmathur.games.chess

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.chess.data.Piece
import com.vayunmathur.games.chess.data.PieceColor
import com.vayunmathur.games.chess.data.PieceType
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton

private val promotionChoices = listOf(PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP, PieceType.KNIGHT)
private val ChoiceShape = RoundedCornerShape(12.dp)

/**
 * The promotion picker. Each choice is an outlined, button-sized tile so the four read as
 * options rather than loose glyphs. Cancel, Back or a tap outside calls [onDismiss], which the
 * callers wire to undo the pawn's step, so a pawn pushed by mistake can be taken back.
 */
@Composable
fun PawnPromotionDialog(color: PieceColor, onPromote: (PieceType) -> Unit, onDismiss: () -> Unit = {}) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.promote_pawn)) },
        text = {
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                for (pieceType in promotionChoices) {
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(ChoiceShape)
                            .border(2.dp, MaterialTheme.colorScheme.outline, ChoiceShape)
                            .clickable(role = Role.Button) { onPromote(pieceType) }
                            .padding(6.dp)
                    ) {
                        ChessPiece(Piece(pieceType, color))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.cancel)) }
        },
    )
}
