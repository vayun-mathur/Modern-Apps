package com.vayunmathur.maps.car

import androidx.car.app.navigation.model.LaneDirection
import androidx.car.app.navigation.model.Maneuver
import com.vayunmathur.maps.util.RouteService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Routing maneuvers onto the Car App Library navigation model.
 * Slice-2 coverage for maps (basename C, outside the slice-1 dirs).
 */
class CarManeuversSlice2Test {

    @Test
    fun `straight maneuvers map to straight types`() {
        assertEquals(Maneuver.TYPE_DEPART, CarManeuvers.typeFor(RouteService.API.Maneuver.DEPART))
        assertEquals(Maneuver.TYPE_NAME_CHANGE, CarManeuvers.typeFor(RouteService.API.Maneuver.NAME_CHANGE))
        assertEquals(Maneuver.TYPE_STRAIGHT, CarManeuvers.typeFor(RouteService.API.Maneuver.STRAIGHT))
    }

    @Test
    fun `every turn maps to its turn type`() {
        val cases = mapOf(
            RouteService.API.Maneuver.TURN_SLIGHT_LEFT to Maneuver.TYPE_TURN_SLIGHT_LEFT,
            RouteService.API.Maneuver.TURN_SLIGHT_RIGHT to Maneuver.TYPE_TURN_SLIGHT_RIGHT,
            RouteService.API.Maneuver.TURN_LEFT to Maneuver.TYPE_TURN_NORMAL_LEFT,
            RouteService.API.Maneuver.TURN_RIGHT to Maneuver.TYPE_TURN_NORMAL_RIGHT,
            RouteService.API.Maneuver.TURN_SHARP_LEFT to Maneuver.TYPE_TURN_SHARP_LEFT,
            RouteService.API.Maneuver.TURN_SHARP_RIGHT to Maneuver.TYPE_TURN_SHARP_RIGHT,
            RouteService.API.Maneuver.UTURN_LEFT to Maneuver.TYPE_U_TURN_LEFT,
            RouteService.API.Maneuver.UTURN_RIGHT to Maneuver.TYPE_U_TURN_RIGHT,
        )
        for ((maneuver, expected) in cases) {
            assertEquals(expected, CarManeuvers.typeFor(maneuver), "$maneuver")
        }
    }

    @Test
    fun `ramps and merges map to ramp types`() {
        assertEquals(
            Maneuver.TYPE_ON_RAMP_NORMAL_LEFT,
            CarManeuvers.typeFor(RouteService.API.Maneuver.RAMP_LEFT),
        )
        assertEquals(
            Maneuver.TYPE_ON_RAMP_NORMAL_RIGHT,
            CarManeuvers.typeFor(RouteService.API.Maneuver.RAMP_RIGHT),
        )
        assertEquals(Maneuver.TYPE_FORK_LEFT, CarManeuvers.typeFor(RouteService.API.Maneuver.FORK_LEFT))
        assertEquals(Maneuver.TYPE_FORK_RIGHT, CarManeuvers.typeFor(RouteService.API.Maneuver.FORK_RIGHT))
        assertEquals(
            Maneuver.TYPE_MERGE_SIDE_UNSPECIFIED,
            CarManeuvers.typeFor(RouteService.API.Maneuver.MERGE),
        )
    }

    @Test
    fun `roundabouts and ferries map to special types`() {
        assertEquals(
            Maneuver.TYPE_ROUNDABOUT_ENTER_CCW,
            CarManeuvers.typeFor(RouteService.API.Maneuver.ROUNDABOUT_LEFT),
        )
        assertEquals(
            Maneuver.TYPE_ROUNDABOUT_ENTER_CW,
            CarManeuvers.typeFor(RouteService.API.Maneuver.ROUNDABOUT_RIGHT),
        )
        assertEquals(Maneuver.TYPE_FERRY_BOAT, CarManeuvers.typeFor(RouteService.API.Maneuver.FERRY))
        assertEquals(Maneuver.TYPE_FERRY_TRAIN, CarManeuvers.typeFor(RouteService.API.Maneuver.FERRY_TRAIN))
    }

    @Test
    fun `transit-only maneuvers fall back to straight`() {
        assertEquals(Maneuver.TYPE_STRAIGHT, CarManeuvers.typeFor(RouteService.API.Maneuver.WAIT))
        assertEquals(Maneuver.TYPE_STRAIGHT, CarManeuvers.typeFor(RouteService.API.Maneuver.RIDE))
        assertEquals(
            Maneuver.TYPE_STRAIGHT,
            CarManeuvers.typeFor(RouteService.API.Maneuver.MANEUVER_UNSPECIFIED),
        )
    }

    @Test
    fun `building a maneuver keeps its type`() {
        val built = CarManeuvers.build(RouteService.API.Maneuver.TURN_LEFT)
        assertEquals(Maneuver.TYPE_TURN_NORMAL_LEFT, built.type)
    }

    @Test
    fun `an empty lane falls back to a straight direction`() {
        val lanes = CarManeuvers.lanes(listOf(RouteService.API.Lane(emptyList(), active = false)))
        assertEquals(1, lanes.size)
        assertEquals(1, lanes[0].directions.size)
        assertEquals(LaneDirection.SHAPE_STRAIGHT, lanes[0].directions[0].getShape())
        assertTrue(!lanes[0].directions[0].isRecommended())
    }

    @Test
    fun `lane directions keep their shapes and highlight`() {
        val lanes = CarManeuvers.lanes(
            listOf(
                RouteService.API.Lane(
                    listOf(
                        RouteService.API.Maneuver.TURN_LEFT,
                        RouteService.API.Maneuver.TURN_SHARP_RIGHT,
                    ),
                    active = true,
                ),
            )
        )
        assertEquals(1, lanes.size)
        assertEquals(2, lanes[0].directions.size)
        assertEquals(LaneDirection.SHAPE_NORMAL_LEFT, lanes[0].directions[0].getShape())
        assertEquals(LaneDirection.SHAPE_SHARP_RIGHT, lanes[0].directions[1].getShape())
        assertTrue(lanes[0].directions.all { it.isRecommended() })
    }
}
