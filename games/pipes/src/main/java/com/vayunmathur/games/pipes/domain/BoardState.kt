package com.vayunmathur.games.pipes.domain

import com.vayunmathur.games.pipes.data.CellPos
import com.vayunmathur.games.pipes.data.LevelData

/**
 * One board position: each colour's drawn line, plus which colour owns each covered cell.
 *
 * Every non-bridge cell is covered by at most one line. A bridge cell can carry two lines;
 * its owner is the one drawn first (the under-pipe), and the other humps over it.
 */
data class BoardState(
    val paths: Map<Int, List<CellPos>> = emptyMap(),
    val cellOwner: Map<CellPos, Int> = emptyMap(),
)

/** Pure edits on a [BoardState]. */
object BoardEdits {

    /**
     * [base] with [color]'s line replaced by [stroke] (or removed, when the stroke is shorter
     * than two cells). Any other colour's line that the stroke crosses outside a bridge is
     * cut just before the crossing, keeping the part attached to the dot it was drawn from;
     * a remnant shorter than two cells is dropped.
     */
    fun applyStroke(level: LevelData, base: BoardState, color: Int, stroke: List<CellPos>): BoardState {
        val strokeCells = stroke.toSet()
        val paths = mutableMapOf<Int, List<CellPos>>()
        for ((c, path) in base.paths) {
            if (c == color) continue
            val cut = path.indexOfFirst { it in strokeCells && it !in level.bridges }
            val kept = if (cut < 0) path else path.subList(0, cut).toList()
            if (kept.size >= 2) paths[c] = kept
        }
        if (stroke.size >= 2) paths[color] = stroke
        return BoardState(paths, owners(level, paths, base.cellOwner, color))
    }

    /**
     * Ownership for [paths]. A bridge keeps its previous owner while that line still crosses
     * it; otherwise a crosser other than the newest line ([newest]) takes it, so a fresh
     * stroke humps over whatever was already there.
     */
    fun owners(
        level: LevelData,
        paths: Map<Int, List<CellPos>>,
        prior: Map<CellPos, Int> = emptyMap(),
        newest: Int? = null,
    ): Map<CellPos, Int> {
        val out = mutableMapOf<CellPos, Int>()
        val crossers = mutableMapOf<CellPos, MutableList<Int>>()
        for ((c, path) in paths.entries.sortedBy { it.key }) {
            for (cell in path) {
                if (cell in level.bridges) crossers.getOrPut(cell) { mutableListOf() }.add(c) else out[cell] = c
            }
        }
        for ((cell, colors) in crossers) {
            val previous = prior[cell]
            out[cell] = if (previous != null && previous in colors) {
                previous
            } else {
                colors.firstOrNull { it != newest } ?: colors.first()
            }
        }
        return out
    }
}
