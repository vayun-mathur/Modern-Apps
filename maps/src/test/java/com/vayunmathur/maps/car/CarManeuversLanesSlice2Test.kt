package com.vayunmathur.maps.car

import androidx.car.app.navigation.model.LaneDirection
import com.vayunmathur.maps.util.RouteService
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Every lane shape the maneuver translation can produce, through the public
 * `lanes` entry point.
 * Slice-2 coverage for maps (basename C, outside the slice-1 dirs).
 */
@RunWith(RobolectricTestRunner::class)
class CarManeuversLanesSlice2Test {

    private fun lane(
        directions: List<RouteService.API.Maneuver>,
        active: Boolean = false,
    ) = RouteService.API.Lane(directions = directions, active = active)

    private fun shapes(maneuver: RouteService.API.Maneuver): List<Int> =
        CarManeuvers.lanes(listOf(lane(listOf(maneuver))))
            .single()
            .directions
            .map { it.shape }

    @Test fun `every maneuver maps to its lane shape`() {
        val expected = mapOf(
            RouteService.API.Maneuver.TURN_SLIGHT_LEFT to LaneDirection.SHAPE_SLIGHT_LEFT,
            RouteService.API.Maneuver.FORK_LEFT to LaneDirection.SHAPE_SLIGHT_LEFT,
            RouteService.API.Maneuver.RAMP_LEFT to LaneDirection.SHAPE_SLIGHT_LEFT,
            RouteService.API.Maneuver.TURN_SLIGHT_RIGHT to LaneDirection.SHAPE_SLIGHT_RIGHT,
            RouteService.API.Maneuver.FORK_RIGHT to LaneDirection.SHAPE_SLIGHT_RIGHT,
            RouteService.API.Maneuver.RAMP_RIGHT to LaneDirection.SHAPE_SLIGHT_RIGHT,
            RouteService.API.Maneuver.TURN_LEFT to LaneDirection.SHAPE_NORMAL_LEFT,
            RouteService.API.Maneuver.ROUNDABOUT_LEFT to LaneDirection.SHAPE_NORMAL_LEFT,
            RouteService.API.Maneuver.TURN_RIGHT to LaneDirection.SHAPE_NORMAL_RIGHT,
            RouteService.API.Maneuver.ROUNDABOUT_RIGHT to LaneDirection.SHAPE_NORMAL_RIGHT,
            RouteService.API.Maneuver.TURN_SHARP_LEFT to LaneDirection.SHAPE_SHARP_LEFT,
            RouteService.API.Maneuver.TURN_SHARP_RIGHT to LaneDirection.SHAPE_SHARP_RIGHT,
            RouteService.API.Maneuver.UTURN_LEFT to LaneDirection.SHAPE_U_TURN_LEFT,
            RouteService.API.Maneuver.UTURN_RIGHT to LaneDirection.SHAPE_U_TURN_RIGHT,
            RouteService.API.Maneuver.STRAIGHT to LaneDirection.SHAPE_STRAIGHT,
        )
        for ((maneuver, shape) in expected) {
            assertEquals(listOf(shape), shapes(maneuver), maneuver.name)
        }
        // Anything unrecognised also goes straight.
        assertEquals(
            listOf(LaneDirection.SHAPE_STRAIGHT),
            shapes(RouteService.API.Maneuver.DEPART),
        )
    }

    @Test fun `empty directions default to straight and active is carried`() {
        val lanes = CarManeuvers.lanes(
            listOf(
                lane(emptyList(), active = true),
                lane(listOf(RouteService.API.Maneuver.TURN_LEFT, RouteService.API.Maneuver.STRAIGHT)),
            ),
        )
        assertEquals(2, lanes.size)
        // No highlight accessor in this car-app alpha: equality is the
        // observable that carries the active flag.
        assertEquals(
            LaneDirection.create(LaneDirection.SHAPE_STRAIGHT, true),
            lanes[0].directions.single(),
        )
        assertEquals(2, lanes[1].directions.size)
        assertEquals(emptyList(), CarManeuvers.lanes(emptyList()))
    }
}
