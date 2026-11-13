package com.vayunmathur.games.pipes.domain

import com.vayunmathur.games.pipes.data.CellPos
import com.vayunmathur.games.pipes.data.LevelData
import kotlin.math.abs

/**
 * Pure rules for growing one colour's stroke as the finger moves.
 *
 * [owners] is the board's cell ownership the stroke is checked against (the committed
 * board with the stroke's own colour already lifted when it was grabbed by an endpoint).
 */
object StrokeRules {

    /**
     * The stroke after the finger enters [cell].
     *
     * - Re-entering a cell already on the stroke cuts the stroke back to it, however far
     *   back it is, so a fast backtrack never stalls.
     * - An adjacent free cell is appended.
     * - A cell two steps away through a shared neighbour (a diagonal move, or a sample that
     *   skipped a cell) is reached through that neighbour.
     * - A finished stroke (it reached its partner dot) stays put.
     */
    fun extend(
        level: LevelData,
        owners: Map<CellPos, Int>,
        color: Int,
        path: List<CellPos>,
        cell: CellPos,
    ): List<CellPos> {
        if (path.isEmpty() || cell !in level.cells || isComplete(level, color, path)) return path
        val idx = path.indexOf(cell)
        if (idx >= 0) return path.subList(0, idx + 1).toList()
        if (isValidStep(level, owners, color, path, cell)) return path + cell
        val via = level.adjacency[path.last()].orEmpty().firstOrNull { n ->
            cell in level.adjacency[n].orEmpty() &&
                isValidStep(level, owners, color, path, n) &&
                !isComplete(level, color, path + n) &&
                isValidStep(level, owners, color, path + n, cell)
        }
        return if (via != null) path + via + cell else path
    }

    /** Whether [path] has reached its partner dot (pairs) or visited every dot (triplets). */
    fun isComplete(level: LevelData, color: Int, path: List<CellPos>): Boolean {
        val endpointCells = level.endpoints.find { it.colorIndex == color }?.cells ?: return false
        if (path.isEmpty() || path.first() !in endpointCells || path.last() !in endpointCells) return false
        if (path.first() == path.last()) return false
        return if (endpointCells.size == 2) {
            val paired = if (path.first() == endpointCells[0]) endpointCells[1] else endpointCells[0]
            path.last() == paired
        } else {
            endpointCells.all { it in path }
        }
    }

    /** Whether [cell] may follow [path] directly. */
    fun isValidStep(
        level: LevelData,
        owners: Map<CellPos, Int>,
        color: Int,
        path: List<CellPos>,
        cell: CellPos,
    ): Boolean {
        if (cell in path) return false
        val neighbors = level.adjacency[path.last()] ?: return false
        if (cell !in neighbors || isForeignEndpoint(level, color, cell)) return false
        if (!isStraightThroughBridge(level, path, cell)) return false
        val owner = owners[cell] ?: return true
        return owner == color || cell in level.bridges
    }

    private fun isForeignEndpoint(level: LevelData, color: Int, cell: CellPos): Boolean =
        level.endpoints.any { it.colorIndex != color && cell in it.cells }

    /**
     * Bridges read as an overpass only when both pipes run straight through. When the path
     * leaves a bridge cell, the exit must continue the entry line, so a pipe cannot corner
     * on the crossing. Long-range exits (portal/wrap jumps) are not grid lines and skip
     * the check.
     */
    private fun isStraightThroughBridge(level: LevelData, path: List<CellPos>, cell: CellPos): Boolean {
        val origin = path.lastOrNull() ?: return true
        if (origin !in level.bridges || path.size < 2) return true
        if (abs(cell.row - origin.row) + abs(cell.col - origin.col) != 1) return true
        val entry = path[path.size - 2]
        return (entry.row == origin.row && origin.row == cell.row) ||
            (entry.col == origin.col && origin.col == cell.col)
    }
}
