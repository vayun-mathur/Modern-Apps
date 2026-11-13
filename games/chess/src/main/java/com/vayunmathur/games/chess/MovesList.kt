package com.vayunmathur.games.chess

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.chess.data.Move
import com.vayunmathur.games.chess.data.PieceColor
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.VerticalDivider

/** Width of the move-number column, sized for three digits. */
private val MoveNumberWidth = 40.dp

/** One full move: White's half-move and, once played, Black's reply. */
internal data class MoveListRow(val number: Int, val white: Move, val black: Move?)

/** Pairs half-moves into numbered rows: 1. e4 e5, 2. Nf3 ... */
internal fun moveListRows(moves: List<Move>): List<MoveListRow> =
    moves.chunked(2).mapIndexed { i, pair -> MoveListRow(i + 1, pair[0], pair.getOrNull(1)) }

/**
 * The game's moves in numbered rows under a White | Black header. The list follows the game:
 * every new half-move scrolls the newest row into view, so the box (about three rows tall)
 * never hides the move that was just played. Rows are keyed by move number so a new half-move
 * updates its row in place instead of re-laying out the list.
 */
@Composable
fun MovesList(moves: List<Move>, turn: PieceColor) {
    val rows = remember(moves) { moveListRows(moves) }
    val listState = rememberLazyListState()
    LaunchedEffect(moves.size) {
        // Item 0 is the header, so the newest row is item rows.size.
        if (rows.isNotEmpty()) listState.animateScrollToItem(rows.size)
    }
    Box(Modifier.height(100.dp)) {
        LazyColumn(
            Modifier
                .fillMaxHeight()
                .border(2.dp, Color.Gray),
            state = listState,
        ) {
            item(key = "header") {
                Row(Modifier.fillMaxWidth()) {
                    val whiteBold = turn == PieceColor.WHITE
                    val blackBold = turn == PieceColor.BLACK
                    Spacer(Modifier.width(MoveNumberWidth))
                    Text(
                        stringResource(R.string.color_white),
                        Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        fontWeight = if (whiteBold) FontWeight.Bold else FontWeight.Normal
                    )
                    VerticalDivider(color = MaterialTheme.colorScheme.primary)
                    Text(
                        stringResource(R.string.color_black),
                        Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        fontWeight = if (blackBold) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
            items(rows, key = { it.number }) { row ->
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        "${row.number}.",
                        Modifier.width(MoveNumberWidth),
                        textAlign = TextAlign.End,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(row.white.toString(), Modifier.weight(1f), textAlign = TextAlign.Center)
                    VerticalDivider(color = MaterialTheme.colorScheme.primary)
                    Text(row.black?.toString().orEmpty(), Modifier.weight(1f), textAlign = TextAlign.Center)
                }
            }
        }
    }
}
