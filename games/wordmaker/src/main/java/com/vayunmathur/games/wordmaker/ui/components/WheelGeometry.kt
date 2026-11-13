package com.vayunmathur.games.wordmaker.ui.components

import androidx.compose.ui.geometry.Offset
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Where the letters of the wheel sit, in the wheel box's own pixel space.
 *
 * Built from the box's *measured* size rather than the nominal [WheelSpacing] size, so drawing,
 * the trail and hit-testing always agree with what is on screen: when a narrow phone or a wide
 * spacing gives the box less room than it asked for, the ring shrinks to fit instead of the
 * touch targets drifting away from the tiles.
 */
internal class WheelGeometry(
    /** Centre of the box. */
    val center: Offset,
    /** Distance from [center] to each letter's centre. */
    val ringRadius: Float,
    /** Half a letter tile: a touch this close to a letter's centre is on that letter. */
    val tileRadius: Float,
    val count: Int,
) {
    /** Letter centres, index 0 at the top and going clockwise. */
    val centers: List<Offset> = List(count) { index ->
        val angle = angleOf(index, count)
        center + Offset(cos(angle).toFloat() * ringRadius, sin(angle).toFloat() * ringRadius)
    }

    /** The letter whose tile contains [position] (the nearest one where tiles overlap), or null. */
    fun letterAt(position: Offset): Int? =
        centers.indices
            .filter { (centers[it] - position).getDistance() <= tileRadius }
            .minByOrNull { (centers[it] - position).getDistance() }

    companion object {
        /** Angle of letter [index] of [count], in radians, measured clockwise from the x axis. */
        fun angleOf(index: Int, count: Int): Double = 2 * PI * index / count - PI / 2

        /**
         * Fits a ring of [nominalRadius] into a [width] x [height] box: the radius is clamped so
         * every tile of [tileRadius] stays inside the box.
         */
        fun fit(width: Float, height: Float, nominalRadius: Float, tileRadius: Float, count: Int): WheelGeometry {
            val half = min(width, height) / 2
            val radius = nominalRadius.coerceAtMost(half - tileRadius).coerceAtLeast(0f)
            return WheelGeometry(Offset(width / 2, height / 2), radius, tileRadius, count)
        }

        /**
         * Tile size for a wheel box of [side], given the box the default spacing asks for: full size when the
         * box got at least the default room, shrunk in proportion below that so the letters keep
         * a gap between them on very narrow screens.
         */
        fun tileSize(side: Float, defaultSide: Float, nominalTile: Float): Float =
            if (defaultSide <= 0f) nominalTile else nominalTile * min(1f, side / defaultSide)
    }
}
