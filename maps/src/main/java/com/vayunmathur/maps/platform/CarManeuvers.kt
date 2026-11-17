package com.vayunmathur.maps.platform
import com.vayunmathur.maps.util.OfflineRouter

import androidx.car.app.navigation.model.Lane
import androidx.car.app.navigation.model.LaneDirection
import androidx.car.app.navigation.model.Maneuver
import com.vayunmathur.maps.domain.RouteService

/**
 * Maps the existing routing model ([RouteService.API.Maneuver] / lane data
 * emitted by the Rust [com.vayunmathur.maps.util.OfflineRouter]) onto the
 * AndroidX Car App Library's navigation model.
 *
 * The car host renders a built-in icon for each [Maneuver] type, so we only
 * need to pick the closest matching `TYPE_*`; no bitmap resources are required.
 * Lane guidance is translated from [RouteService.API.Lane] (P5a) where present.
 */
internal object CarManeuvers {

    /** Translate a routing maneuver into a Car App Library [Maneuver] type. */
    fun typeFor(maneuver: RouteService.API.Maneuver): Int =
        straightTypeFor(maneuver) ?: turnTypeFor(maneuver)
            ?: rampTypeFor(maneuver) ?: specialTypeFor(maneuver)
            // WAIT / RIDE are transit-only and never surface in a DRIVE session;
            // fall back to a neutral "continue" glyph.
            ?: Maneuver.TYPE_STRAIGHT

    private fun straightTypeFor(maneuver: RouteService.API.Maneuver): Int? = when (maneuver) {
        RouteService.API.Maneuver.DEPART -> Maneuver.TYPE_DEPART
        RouteService.API.Maneuver.NAME_CHANGE -> Maneuver.TYPE_NAME_CHANGE
        RouteService.API.Maneuver.STRAIGHT -> Maneuver.TYPE_STRAIGHT
        else -> null
    }

    private fun turnTypeFor(maneuver: RouteService.API.Maneuver): Int? = when (maneuver) {
        RouteService.API.Maneuver.TURN_SLIGHT_LEFT -> Maneuver.TYPE_TURN_SLIGHT_LEFT
        RouteService.API.Maneuver.TURN_SLIGHT_RIGHT -> Maneuver.TYPE_TURN_SLIGHT_RIGHT
        RouteService.API.Maneuver.TURN_LEFT -> Maneuver.TYPE_TURN_NORMAL_LEFT
        RouteService.API.Maneuver.TURN_RIGHT -> Maneuver.TYPE_TURN_NORMAL_RIGHT
        RouteService.API.Maneuver.TURN_SHARP_LEFT -> Maneuver.TYPE_TURN_SHARP_LEFT
        RouteService.API.Maneuver.TURN_SHARP_RIGHT -> Maneuver.TYPE_TURN_SHARP_RIGHT
        RouteService.API.Maneuver.UTURN_LEFT -> Maneuver.TYPE_U_TURN_LEFT
        RouteService.API.Maneuver.UTURN_RIGHT -> Maneuver.TYPE_U_TURN_RIGHT
        else -> null
    }

    private fun rampTypeFor(maneuver: RouteService.API.Maneuver): Int? = when (maneuver) {
        RouteService.API.Maneuver.RAMP_LEFT -> Maneuver.TYPE_ON_RAMP_NORMAL_LEFT
        RouteService.API.Maneuver.RAMP_RIGHT -> Maneuver.TYPE_ON_RAMP_NORMAL_RIGHT
        RouteService.API.Maneuver.FORK_LEFT -> Maneuver.TYPE_FORK_LEFT
        RouteService.API.Maneuver.FORK_RIGHT -> Maneuver.TYPE_FORK_RIGHT
        RouteService.API.Maneuver.MERGE -> Maneuver.TYPE_MERGE_SIDE_UNSPECIFIED
        else -> null
    }

    private fun specialTypeFor(maneuver: RouteService.API.Maneuver): Int? = when (maneuver) {
        RouteService.API.Maneuver.ROUNDABOUT_LEFT -> Maneuver.TYPE_ROUNDABOUT_ENTER_CCW
        RouteService.API.Maneuver.ROUNDABOUT_RIGHT -> Maneuver.TYPE_ROUNDABOUT_ENTER_CW
        RouteService.API.Maneuver.FERRY -> Maneuver.TYPE_FERRY_BOAT
        RouteService.API.Maneuver.FERRY_TRAIN -> Maneuver.TYPE_FERRY_TRAIN
        else -> null
    }

    /** Build a Car App Library [Maneuver] for a routing maneuver. */
    fun build(maneuver: RouteService.API.Maneuver): Maneuver =
        Maneuver.Builder(typeFor(maneuver)).build()

    /** Lane-arrow shape for a routing maneuver (P5a lane guidance). */
    private fun laneShapeFor(maneuver: RouteService.API.Maneuver): Int = when (maneuver) {
        RouteService.API.Maneuver.TURN_SLIGHT_LEFT,
        RouteService.API.Maneuver.FORK_LEFT,
        RouteService.API.Maneuver.RAMP_LEFT -> LaneDirection.SHAPE_SLIGHT_LEFT
        RouteService.API.Maneuver.TURN_SLIGHT_RIGHT,
        RouteService.API.Maneuver.FORK_RIGHT,
        RouteService.API.Maneuver.RAMP_RIGHT -> LaneDirection.SHAPE_SLIGHT_RIGHT
        RouteService.API.Maneuver.TURN_LEFT,
        RouteService.API.Maneuver.ROUNDABOUT_LEFT -> LaneDirection.SHAPE_NORMAL_LEFT
        RouteService.API.Maneuver.TURN_RIGHT,
        RouteService.API.Maneuver.ROUNDABOUT_RIGHT -> LaneDirection.SHAPE_NORMAL_RIGHT
        RouteService.API.Maneuver.TURN_SHARP_LEFT -> LaneDirection.SHAPE_SHARP_LEFT
        RouteService.API.Maneuver.TURN_SHARP_RIGHT -> LaneDirection.SHAPE_SHARP_RIGHT
        RouteService.API.Maneuver.UTURN_LEFT -> LaneDirection.SHAPE_U_TURN_LEFT
        RouteService.API.Maneuver.UTURN_RIGHT -> LaneDirection.SHAPE_U_TURN_RIGHT
        else -> LaneDirection.SHAPE_STRAIGHT
    }

    /** Translate the router's per-junction lanes into Car App Library [Lane]s. */
    fun lanes(source: List<RouteService.API.Lane>): List<Lane> = source.map { lane ->
        val builder = Lane.Builder()
        val directions = lane.directions.ifEmpty {
            listOf(RouteService.API.Maneuver.STRAIGHT)
        }
        for (dir in directions) {
            builder.addDirection(LaneDirection.create(laneShapeFor(dir), lane.active))
        }
        builder.build()
    }
}
