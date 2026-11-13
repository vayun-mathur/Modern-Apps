package com.vayunmathur.games.nonogram.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import com.vayunmathur.games.nonogram.data.NonogramGameState
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text

// The clue gutters of [NonogramBoard]: column clues across the top, row clues down the side.

@Composable
internal fun ColumnClues(
    game: NonogramGameState,
    gutterCells: Int,
    cell: Dp,
    clueCell: Dp,
    fontSize: TextUnit,
    lineHeight: TextUnit,
) {
    Row {
        for (col in 0 until game.size) {
            val clues = game.puzzle.colClues[col]
            Column(
                Modifier.size(cell, clueCell * gutterCells),
                // Bottom-aligned so the last clue sits against the grid it describes.
                verticalArrangement = Arrangement.Bottom,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                for (clue in clues.ifEmpty { listOf(0) }) {
                    ClueText(clue, fontSize, lineHeight, Modifier.size(cell, clueCell))
                }
            }
        }
    }
}

@Composable
internal fun RowClues(
    game: NonogramGameState,
    gutterCells: Int,
    cell: Dp,
    clueCell: Dp,
    fontSize: TextUnit,
    lineHeight: TextUnit,
) {
    Column {
        for (row in 0 until game.size) {
            val clues = game.puzzle.rowClues[row]
            Row(
                Modifier.size(clueCell * gutterCells, cell),
                // End-aligned for the same reason: the final clue abuts the grid.
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (clue in clues.ifEmpty { listOf(0) }) {
                    ClueText(clue, fontSize, lineHeight, Modifier.size(clueCell, cell))
                }
            }
        }
    }
}

@Composable
private fun ClueText(
    clue: Int,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    modifier: Modifier = Modifier,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(
            text = clue.toString(),
            fontSize = fontSize,
            lineHeight = lineHeight,
            maxLines = 1,
            softWrap = false,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            color = if (clue == 0) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.onSurface,
        )
    }
}
