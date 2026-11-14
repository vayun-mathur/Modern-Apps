package com.vayunmathur.launcher.domain

/**
 * Which cells of one page are taken.
 *
 * A plain bitmap rather than a list of rects: every placement question ("does this fit",
 * "what is the nearest hole") is asked many times per drag frame, and scanning a
 * `BooleanArray` is both simpler and faster than pairwise rectangle intersection.
 *
 * Out-of-bounds is treated as occupied, so callers never need a separate bounds check
 * before asking whether something fits.
 */
class CellOccupancy(val columns: Int, val rows: Int) {
    private val cells = BooleanArray(columns * rows)

    constructor(spec: GridSpec) : this(spec.columns, spec.rows)

    fun isFree(rect: CellRect): Boolean {
        if (isOutOfBounds(rect)) {
            return false
        }
        for (y in rect.cellY until rect.bottom) {
            for (x in rect.cellX until rect.right) {
                if (cells[y * columns + x]) return false
            }
        }
        return true
    }

    private fun isOutOfBounds(rect: CellRect): Boolean =
        rect.cellX < 0 || rect.cellY < 0 || rect.right > columns || rect.bottom > rows

    fun mark(rect: CellRect, occupied: Boolean = true) {
        for (y in rect.cellY.coerceAtLeast(0) until rect.bottom.coerceAtMost(rows)) {
            for (x in rect.cellX.coerceAtLeast(0) until rect.right.coerceAtMost(columns)) {
                cells[y * columns + x] = occupied
            }
        }
    }

    val freeCellCount: Int get() = cells.count { !it }

    /** Shared constants and helpers for CellOccupancy. */
    companion object {
        fun of(spec: GridSpec, rects: Iterable<CellRect>): CellOccupancy =
            CellOccupancy(spec).apply { rects.forEach { mark(it) } }
    }
}
