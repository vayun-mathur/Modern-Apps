package com.vayunmathur.games.pipes.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.vayunmathur.games.pipes.data.CellPos
import com.vayunmathur.games.pipes.data.LevelData
import kotlin.math.ceil

/**
 * The board's layout in cell units, independent of the pixel size it is drawn at.
 *
 * Computed once per level: scanning every cell for the bounding box is cheap once, but
 * the board recomposes on every drag step, so it must not run per frame.
 */
class BoardLayout(val levelData: LevelData) {
    /** Board side length in cells: the canvas is square and fits this many cells across. */
    val maxDim: Float

    private val shiftX: Float
    private val shiftY: Float
    private val offsetCol: Float
    private val offsetRow: Float

    init {
        val positions = levelData.renderPositions?.filterKeys { it in levelData.cells }?.values
        val hasPositions = levelData.renderPositions != null && levelData.cells.isNotEmpty()
        val (contentW, contentH) = if (hasPositions && !positions.isNullOrEmpty()) {
            (positions.maxOf { it.x } - positions.minOf { it.x } + 1f) to
                (positions.maxOf { it.y } - positions.minOf { it.y } + 1f)
        } else if (hasPositions) {
            1f to 1f
        } else {
            levelData.cols.toFloat() to levelData.rows.toFloat()
        }
        // Hex rows overlap vertically (0.866 spacing) so shrink slightly to avoid clipping.
        val hexShrink = if (levelData.grid == "hex") 0.92f else 1f
        val effectiveMaxDim = maxOf(contentW, contentH, 1f) / hexShrink
        maxDim = maxOf(maxOf(levelData.rows, levelData.cols).toFloat(), effectiveMaxDim)

        // Center the board within the square canvas using the actual cells' bounding
        // box, so non-square / non-rectangular shapes aren't stuck in the top-left.
        // Render-position layouts are shifted so their minimum sits at 0 instead.
        if (levelData.renderPositions != null || levelData.cells.isEmpty()) {
            offsetCol = 0f
            offsetRow = 0f
        } else {
            val minRow = levelData.cells.minOf { it.row }
            val minCol = levelData.cells.minOf { it.col }
            val usedCols = levelData.cells.maxOf { it.col } - minCol + 1
            val usedRows = levelData.cells.maxOf { it.row } - minRow + 1
            offsetCol = (maxDim - usedCols) / 2f - minCol
            offsetRow = (maxDim - usedRows) / 2f - minRow
        }
        if (hasPositions && !positions.isNullOrEmpty()) {
            shiftX = (maxDim - contentW) / 2f - positions.minOf { it.x }
            shiftY = (maxDim - contentH) / 2f - positions.minOf { it.y }
        } else {
            shiftX = 0f
            shiftY = 0f
        }
    }

    /** Top-left corner of [cell] in cell units. */
    fun origin(cell: CellPos): Offset {
        val pos = levelData.renderPositions?.get(cell)
        return if (pos != null) {
            Offset(pos.x + shiftX, pos.y + shiftY)
        } else {
            Offset(cell.col + offsetCol, cell.row + offsetRow)
        }
    }
}

/**
 * [BoardLayout] scaled to pixels, plus hit-testing.
 *
 * Every touch handler must use the geometry of the size the board is drawn at *now*:
 * rebuild this whenever [cellSizePx] changes (rotation, fold, window resize).
 */
class BoardGeometry(val layout: BoardLayout, val cellSizePx: Float) {
    val levelData: LevelData get() = layout.levelData
    val isHex: Boolean = layout.levelData.grid == "hex"

    val cellRects: Map<CellPos, Rect> = layout.levelData.cells.associateWith { cell ->
        val o = layout.origin(cell)
        Rect(Offset(o.x * cellSizePx, o.y * cellSizePx), Size(cellSizePx, cellSizePx))
    }

    val boardBounds: Rect = if (cellRects.isEmpty()) Rect.Zero else Rect(
        left = cellRects.values.minOf { it.left },
        top = cellRects.values.minOf { it.top },
        right = cellRects.values.maxOf { it.right },
        bottom = cellRects.values.maxOf { it.bottom },
    )

    fun hitTest(offset: Offset): CellPos? {
        if (!isHex) {
            return cellRects.entries.firstOrNull { (_, rect) -> rect.contains(offset) }?.key
        }
        // Hex: nearest center within the hex radius wins (rects overlap vertically).
        val radius = cellSizePx * HEX_HIT_RADIUS
        var best: CellPos? = null
        var bestDist = Float.MAX_VALUE
        for ((cell, rect) in cellRects) {
            val d = (rect.center - offset).getDistanceSquared()
            if (d < bestDist && d <= radius * radius) {
                bestDist = d
                best = cell
            }
        }
        return best ?: cellRects.entries.firstOrNull { (_, rect) -> rect.contains(offset) }?.key
    }

    /**
     * While dragging, a finger that leaves the board keeps driving the border cell nearest
     * to it, so the user doesn't have to come back inside to continue the pipe.
     */
    fun hitTestDrag(offset: Offset): CellPos? {
        hitTest(offset)?.let { return it }
        if (cellRects.isEmpty() || boardBounds.contains(offset)) return null
        return cellRects.entries.minWithOrNull(
            compareBy(
                { squaredDistanceToRect(it.value, offset) },
                { (it.value.center - offset).getDistanceSquared() },
            )
        )?.key
    }

    /**
     * Every cell a straight finger movement from [from] to [to] passes over, in order and
     * without consecutive repeats (the cell under [from] itself is not included unless the
     * path returns to it). Touch events arrive far apart on a fast swipe; feeding only
     * their end points to the stroke skipped cells and stalled it.
     */
    fun cellsAlong(from: Offset, to: Offset): List<CellPos> {
        if (cellSizePx <= 0f) return listOfNotNull(hitTestDrag(to))
        val distance = (to - from).getDistance()
        val steps = ceil(distance / (cellSizePx * SAMPLE_FRACTION)).toInt().coerceIn(1, MAX_SAMPLES)
        val out = ArrayList<CellPos>()
        val start = hitTestDrag(from)
        for (i in 1..steps) {
            val cell = hitTestDrag(from + (to - from) * (i / steps.toFloat())) ?: continue
            if (cell != (out.lastOrNull() ?: start)) out += cell
        }
        return out
    }

    private companion object {
        const val HEX_HIT_RADIUS = 0.62f

        /** Sample spacing along a movement, as a fraction of a cell: fine enough to never jump a cell. */
        const val SAMPLE_FRACTION = 0.25f
        const val MAX_SAMPLES = 4096
    }
}

private fun squaredDistanceToRect(rect: Rect, point: Offset): Float {
    val dx = maxOf(rect.left - point.x, 0f, point.x - rect.right)
    val dy = maxOf(rect.top - point.y, 0f, point.y - rect.bottom)
    return dx * dx + dy * dy
}
