package com.vayunmathur.games.logicgate.ui.canvas

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/**
 * The area under the board that a dragged gate can be dropped on to delete it. [armed] while a
 * gate (or a chip just pulled from the tray) is being carried, [hovering] while it is over the
 * zone, where letting go places nothing.
 */
@Stable
class TrashZone {
    /** The zone, in root coordinates. */
    var bounds: Rect = Rect.Zero

    /** Where the canvas's own coordinates start, in root coordinates. */
    var canvasOrigin: Offset = Offset.Zero

    var armed by mutableStateOf(false)
    var hovering by mutableStateOf(false)

    fun contains(canvasPoint: Offset): Boolean = bounds.contains(canvasPoint + canvasOrigin)

    /**
     * Tracks a chip dragged out of the tray at [root] (root coordinates; null when the drag ends).
     * Returns true when it has just moved back over the zone.
     */
    fun carry(root: Offset?): Boolean {
        val over = root != null && bounds.contains(root)
        val entered = over && !hovering && armed
        armed = root != null
        hovering = over
        return entered
    }
}
