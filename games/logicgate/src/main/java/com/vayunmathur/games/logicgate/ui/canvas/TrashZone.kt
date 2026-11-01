package com.vayunmathur.games.logicgate.ui.canvas

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/**
 * The area under the board that a dragged gate can be dropped on to delete it. [armed] while a
 * gate is being carried, [hovering] while it is over the zone.
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
}
