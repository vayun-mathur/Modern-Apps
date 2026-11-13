package com.vayunmathur.games.chess

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
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

@Composable
fun MovesList(moves: List<Move>, turn: PieceColor) {
    Box(Modifier.height(100.dp)) {
        LazyColumn(
            Modifier
                .fillMaxHeight()
                .border(2.dp, Color.Gray)
        ) {
            item {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
                    val whiteBold = turn == PieceColor.WHITE
                    val blackBold = turn == PieceColor.BLACK
                    Text(
                        stringResource(R.string.color_white),
                        fontWeight = if (whiteBold) FontWeight.Bold else FontWeight.Normal
                    )
                    VerticalDivider(color = MaterialTheme.colorScheme.primary)
                    Text(
                        stringResource(R.string.color_black),
                        fontWeight = if (blackBold) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
            items(moves.chunked(2)) { move ->
                Row(Modifier.fillMaxWidth()) {
                    Text(move[0].toString(), Modifier.weight(1f), textAlign = TextAlign.Center)
                    if (move.size == 2) {
                        VerticalDivider(color = MaterialTheme.colorScheme.primary)
                        Text(move[1].toString(), Modifier.weight(1f), textAlign = TextAlign.Center)
                    } else {
                        VerticalDivider()
                        Text("", Modifier.weight(1f), textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}
