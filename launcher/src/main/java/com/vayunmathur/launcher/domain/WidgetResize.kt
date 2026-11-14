package com.vayunmathur.launcher.domain

/**
 * Turning a drag on one edge of a widget into a new cell rectangle.
 *
 * Pure, because the interesting part is not the gesture but the arithmetic: dragging the
 * **left** edge left has to grow the span *and* move the origin, so the right edge stays put.
 * Getting that backwards makes a widget appear to slide rather than resize, and it is much
 * easier to pin down in a test than on a device.
 */
object WidgetResize {

    /** Kinds of edge. */
    enum class Edge { Left, Top, Right, Bottom }

    /** Which way a resize on [edge] shoves whatever is in the way. */
    fun pushDirection(edge: Edge): PushDirection = when (edge) {
        Edge.Left -> PushDirection.Left
        Edge.Top -> PushDirection.Up
        Edge.Right -> PushDirection.Right
        Edge.Bottom -> PushDirection.Down
    }

    /**
     * [rect] adjusted by [steps] whole cells on [edge], or null when the result would be
     * degenerate (a zero-width span, or an origin off the top or left of the grid).
     *
     * Positive [steps] means right or down for every edge, so a caller can hand over the raw
     * sign of the drag without knowing which edge it is on.
     *
     * Bounds against the right and bottom of the grid are **not** checked here; that is the
     * occupancy check's job, and it has to be done against the page anyway.
     */
    fun resized(rect: CellRect, edge: Edge, steps: Int): CellRect? {
        if (steps == 0) return null
        val next = when (edge) {
            Edge.Right -> rect.copy(spanX = rect.spanX + steps)
            Edge.Bottom -> rect.copy(spanY = rect.spanY + steps)
            // The origin moves with the edge, so the opposite edge does not shift.
            Edge.Left -> rect.copy(cellX = rect.cellX + steps, spanX = rect.spanX - steps)
            Edge.Top -> rect.copy(cellY = rect.cellY + steps, spanY = rect.spanY - steps)
        }
        if (next.spanX < 1 || next.spanY < 1) return null
        if (next.cellX < 0 || next.cellY < 0) return null
        return next
    }

    /**
     * Whether [candidate] can replace [current] on a page holding [others].
     *
     * [others] must exclude the widget being resized: measuring it against its own cells would
     * refuse every enlargement, since a growing rectangle always overlaps where it already is.
     */
    fun canPlace(
        candidate: CellRect,
        current: CellRect,
        others: List<CellRect>,
        spec: GridSpec,
    ): Boolean {
        if (candidate == current) return false
        val occupancy = CellOccupancy.of(spec, others)
        return occupancy.isFree(candidate)
    }

    /**
     * The neighbours a resize to [candidate] has to shove aside, or null when it cannot be done.
     *
     * This replaces refusing the step. Launcher3 does not stop a widget growing into its
     * neighbours; it pushes them, on the same cascade a drag uses, and only gives up at a wall. A
     * refusal instead reads as the handle being stuck, with nothing on screen saying why.
     *
     * An empty map means the space was already clear — which is also the answer for every shrink.
     */
    fun resizeWithPush(
        spec: GridSpec,
        candidate: CellRect,
        others: Map<Long, CellRect>,
        direction: PushDirection,
    ): Map<Long, CellRect>? {
        val occupancy = CellOccupancy.of(spec, others.values)
        // Off the grid is refused here rather than by the cascade: with nothing in the way, an
        // out-of-bounds candidate would otherwise come back as "nothing to push".
        if (candidate.cellX < 0 || candidate.cellY < 0) return null
        if (candidate.right > spec.columns || candidate.bottom > spec.rows) return null
        if (occupancy.isFree(candidate)) return emptyMap()
        return GridReorder.pushAlong(spec, others, candidate, direction)
    }
}
