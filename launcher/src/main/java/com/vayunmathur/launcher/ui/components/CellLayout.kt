package com.vayunmathur.launcher.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import com.vayunmathur.launcher.domain.GridSpec

/**
 * Pixel geometry of one page's grid.
 *
 * Published by [CellLayout] so the drag code can turn a finger position into a cell without
 * duplicating the division, and so a hosted widget can be told the dp size it was actually
 * given.
 */
data class CellMetrics(
    val columns: Int,
    val rows: Int,
    val cellWidthPx: Int,
    val cellHeightPx: Int,
) {
    /** The cell a point falls in, clamped into the grid. */
    fun cellAt(x: Float, y: Float): Pair<Int, Int> {
        if (cellWidthPx <= 0 || cellHeightPx <= 0) return 0 to 0
        val cellX = (x / cellWidthPx).toInt().coerceIn(0, columns - 1)
        val cellY = (y / cellHeightPx).toInt().coerceIn(0, rows - 1)
        return cellX to cellY
    }

    /** Shared constants and helpers for CellMetrics. */
    companion object {
        val Empty = CellMetrics(0, 0, 0, 0)
    }
}

/**
 * One page of the cell grid.
 *
 * A custom [Layout] rather than `LazyVerticalGrid`, which cannot express a row span or an
 * absolute `(cellX, cellY)`; a launcher page is a sparse fixed grid, not a flowing list.
 * Children without a [cell] modifier are laid out filling the page, which is what the drag
 * preview and the page background want.
 *
 * Cells divide the available space exactly, and the remainder is spread over the leading
 * cells rather than dropped, so the grid has no visible gap on its right or bottom edge.
 *
 * The [LookaheadScope] wrapper is what makes [cell] able to animate a child from the rect it had to
 * the rect it has. It adds a layout node but no geometry of its own: it sizes to its one child,
 * which is the grid filling the page.
 */
@Composable
fun CellLayout(
    spec: GridSpec,
    modifier: Modifier = Modifier,
    onMetrics: (CellMetrics) -> Unit = {},
    content: @Composable () -> Unit,
) {
    LookaheadScope {
        CompositionLocalProvider(LocalCellLookahead provides this) {
            CellGrid(spec = spec, modifier = modifier, onMetrics = onMetrics, content = content)
        }
    }
}

@Composable
private fun CellGrid(
    spec: GridSpec,
    modifier: Modifier,
    onMetrics: (CellMetrics) -> Unit,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val cellWidth = width / spec.columns
        val cellHeight = height / spec.rows
        onMetrics(CellMetrics(spec.columns, spec.rows, cellWidth, cellHeight))

        // Leftover pixels from the integer division, handed one at a time to the leading
        // columns/rows so the last cell still reaches the edge.
        val extraColumns = width - cellWidth * spec.columns
        val extraRows = height - cellHeight * spec.rows
        fun xOf(column: Int) = column * cellWidth + column.coerceAtMost(extraColumns)
        fun yOf(row: Int) = row * cellHeight + row.coerceAtMost(extraRows)

        val placements = measurables.map { measurable ->
            val rect = (measurable.parentData as? CellParentData)?.rect
            if (rect == null) {
                measurable.measure(constraints) to IntOffset.Zero
            } else {
                val left = xOf(rect.cellX)
                val top = yOf(rect.cellY)
                val cellsWide = xOf(rect.right.coerceAtMost(spec.columns)) - left
                val cellsTall = yOf(rect.bottom.coerceAtMost(spec.rows)) - top
                measurable.measure(Constraints.fixed(cellsWide, cellsTall)) to IntOffset(left, top)
            }
        }

        layout(width, height) {
            placements.forEach { (placeable, offset) -> placeable.place(offset) }
        }
    }
}
