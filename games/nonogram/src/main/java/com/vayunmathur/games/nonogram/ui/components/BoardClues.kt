package com.vayunmathur.games.nonogram.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import com.vayunmathur.games.nonogram.data.NonogramGameState
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import kotlin.math.roundToInt

/**
 * Sizes shared by both clue gutters.
 *
 * @property cell a grid cell's side at zoom 1.
 * @property clueCell one clue slot's depth into its gutter.
 * @property fontSize clue text size, already fitted to the slot.
 * @property lineHeight clue line box, equal to the slot.
 */
@Immutable
internal data class ClueMetrics(
    val cell: Dp,
    val clueCell: Dp,
    val fontSize: TextUnit,
    val lineHeight: TextUnit,
)

/**
 * The column clues across the top of [NonogramBoard].
 *
 * Each column's stack keeps its unzoomed size but is placed over wherever that column is currently
 * drawn, so the clues stay aligned with the grid at any zoom and pan, and the gutter clips the ones
 * that are panned out of view.
 */
@Composable
internal fun ColumnClues(
    game: NonogramGameState,
    gutterCells: Int,
    metrics: ClueMetrics,
    zoom: State<BoardZoom>,
) {
    ZoomedLine(horizontal = true, zoom = zoom) {
        for (col in 0 until game.size) {
            val clues = game.puzzle.colClues[col]
            Column(
                Modifier.size(metrics.cell, metrics.clueCell * gutterCells),
                // Bottom-aligned so the last clue sits against the grid it describes.
                verticalArrangement = Arrangement.Bottom,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                for (clue in clues.ifEmpty { listOf(0) }) {
                    ClueText(clue, metrics, Modifier.size(metrics.cell, metrics.clueCell))
                }
            }
        }
    }
}

/** The row clues down the side of [NonogramBoard]; zoom-aligned like [ColumnClues]. */
@Composable
internal fun RowClues(
    game: NonogramGameState,
    gutterCells: Int,
    metrics: ClueMetrics,
    zoom: State<BoardZoom>,
) {
    ZoomedLine(horizontal = false, zoom = zoom) {
        for (row in 0 until game.size) {
            val clues = game.puzzle.rowClues[row]
            Row(
                Modifier.size(metrics.clueCell * gutterCells, metrics.cell),
                // End-aligned for the same reason: the final clue abuts the grid.
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (clue in clues.ifEmpty { listOf(0) }) {
                    ClueText(clue, metrics, Modifier.size(metrics.clueCell, metrics.cell))
                }
            }
        }
    }
}

/**
 * Lays its children (one per grid line, all the same size) end to end along one axis, each centred on
 * where its grid line is currently drawn ([GridViewport.columnCenter] / [GridViewport.rowCenter]).
 *
 * The zoom is read only in the placement block, so a pinch re-places the clue stacks every frame
 * without recomposing or re-measuring any of them.
 */
@Composable
private fun ZoomedLine(horizontal: Boolean, zoom: State<BoardZoom>, content: @Composable () -> Unit) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Layout(content, Modifier.clipToBounds()) { measurables, _ ->
        val placeables = measurables.map { it.measure(Constraints()) }
        val n = placeables.size.coerceAtLeast(1)
        val side = if (horizontal) placeables.sumOf { it.width } else placeables.sumOf { it.height }
        val width = if (horizontal) side else (placeables.maxOfOrNull { it.width } ?: 0)
        val height = if (horizontal) (placeables.maxOfOrNull { it.height } ?: 0) else side
        layout(width, height) {
            val viewport = GridViewport(side.toFloat(), n, rtl, zoom.value)
            placeables.forEachIndexed { i, p ->
                if (horizontal) {
                    p.place((viewport.columnCenter(i) - p.width / 2f).roundToInt(), 0)
                } else {
                    p.place(0, (viewport.rowCenter(i) - p.height / 2f).roundToInt())
                }
            }
        }
    }
}

@Composable
private fun ClueText(
    clue: Int,
    metrics: ClueMetrics,
    modifier: Modifier = Modifier,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(
            text = clue.toString(),
            fontSize = metrics.fontSize,
            lineHeight = metrics.lineHeight,
            maxLines = 1,
            softWrap = false,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            color = if (clue == 0) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.onSurface,
        )
    }
}
