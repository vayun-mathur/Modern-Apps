package com.vayunmathur.maps.ui

import com.vayunmathur.maps.ui.nav.LaneComboGlyph
import com.vayunmathur.maps.ui.nav.laneComboGlyph
import com.vayunmathur.maps.domain.RouteService.API.Maneuver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The lane-guidance glyph contract: hand-authored `Path` data no lint, compile or metadata
 * check evaluates.
 *
 * Two tables, both pinned here because neither is observable any other way on the JVM (the
 * composable lambdas they produce are not comparable):
 *
 * - [Maneuver.glyph]: every router [Maneuver] maps to exactly one [ManeuverGlyph], or to
 *   nothing. In particular the router's geometric distinction between [Maneuver.UTURN_LEFT]
 *   and [Maneuver.UTURN_RIGHT] survives — the bug this guards collapsed both onto one glyph
 *   and drew every right U-turn as a left hook.
 * - [laneComboGlyph]: every OSM `turn:lanes` combination the strip claims to cover selects
 *   exactly one [LaneComboGlyph], and anything else falls back to a single arrow explicitly.
 *
 * `iconContent` and `combinedLaneIcon` derive from these tables rather than authoring the
 * mapping twice, so pinning the tables pins the UI.
 */
class ManeuverGlyphTest {

    @Test
    fun every_maneuver_maps_to_exactly_one_glyph_or_to_nothing() {
        val expected = mapOf(
            Maneuver.TURN_SLIGHT_LEFT to ManeuverGlyph.TURN_SLIGHT_LEFT,
            Maneuver.TURN_SHARP_LEFT to ManeuverGlyph.TURN_SHARP_LEFT,
            Maneuver.UTURN_LEFT to ManeuverGlyph.UTURN_LEFT,
            Maneuver.TURN_LEFT to ManeuverGlyph.TURN_LEFT,
            Maneuver.TURN_SLIGHT_RIGHT to ManeuverGlyph.TURN_SLIGHT_RIGHT,
            Maneuver.TURN_SHARP_RIGHT to ManeuverGlyph.TURN_SHARP_RIGHT,
            Maneuver.UTURN_RIGHT to ManeuverGlyph.UTURN_RIGHT,
            Maneuver.TURN_RIGHT to ManeuverGlyph.TURN_RIGHT,
            Maneuver.STRAIGHT to ManeuverGlyph.STRAIGHT,
            Maneuver.RAMP_LEFT to ManeuverGlyph.RAMP_LEFT,
            Maneuver.RAMP_RIGHT to ManeuverGlyph.RAMP_RIGHT,
            Maneuver.MERGE to ManeuverGlyph.MERGE,
            Maneuver.FORK_LEFT to ManeuverGlyph.FORK_LEFT,
            Maneuver.FORK_RIGHT to ManeuverGlyph.FORK_RIGHT,
            Maneuver.ROUNDABOUT_LEFT to ManeuverGlyph.ROUNDABOUT_LEFT,
            Maneuver.ROUNDABOUT_RIGHT to ManeuverGlyph.ROUNDABOUT_RIGHT,
            Maneuver.DEPART to ManeuverGlyph.STRAIGHT,
            Maneuver.NAME_CHANGE to ManeuverGlyph.STRAIGHT,
            Maneuver.WAIT to ManeuverGlyph.CLOCK,
            Maneuver.RIDE to ManeuverGlyph.BOOK,
            Maneuver.FERRY to null,
            Maneuver.FERRY_TRAIN to null,
            Maneuver.MANEUVER_UNSPECIFIED to null,
        )
        // Exhaustive over the enum: a maneuver the router adds later fails here until it gets
        // a row, rather than drawing nothing on device.
        assertEquals(
            Maneuver.entries.size,
            expected.size,
            "the table must cover every Maneuver",
        )
        for ((maneuver, glyph) in expected) {
            assertEquals(glyph, maneuver.glyph(), "$maneuver maps to $glyph")
        }
    }

    @Test
    fun left_and_right_uturns_are_distinct_glyphs() {
        // The reported bug, stated directly: both U-turns mapped to one glyph and every right
        // U-turn drew a left hook. The router distinguishes them; the icon table must too.
        val left = Maneuver.UTURN_LEFT.glyph()
        val right = Maneuver.UTURN_RIGHT.glyph()
        assertEquals(ManeuverGlyph.UTURN_LEFT, left)
        assertEquals(ManeuverGlyph.UTURN_RIGHT, right)
    }

    @Test
    fun every_covered_lane_combination_selects_its_combined_glyph() {
        val expected = mapOf(
            listOf(Maneuver.STRAIGHT, Maneuver.TURN_LEFT) to LaneComboGlyph.THROUGH_LEFT,
            listOf(Maneuver.STRAIGHT, Maneuver.TURN_RIGHT) to LaneComboGlyph.THROUGH_RIGHT,
            listOf(Maneuver.STRAIGHT, Maneuver.TURN_SLIGHT_LEFT) to
                LaneComboGlyph.THROUGH_SLIGHT_LEFT,
            listOf(Maneuver.STRAIGHT, Maneuver.TURN_SLIGHT_RIGHT) to
                LaneComboGlyph.THROUGH_SLIGHT_RIGHT,
            listOf(Maneuver.STRAIGHT, Maneuver.TURN_LEFT, Maneuver.TURN_RIGHT) to
                LaneComboGlyph.THROUGH_LEFT_RIGHT,
            listOf(Maneuver.TURN_LEFT, Maneuver.TURN_RIGHT) to LaneComboGlyph.LEFT_RIGHT,
        )
        for ((directions, glyph) in expected) {
            assertEquals(glyph, laneComboGlyph(directions), "$directions selects $glyph")
            // Order-insensitive: OSM lists a lane's movements in any order.
            assertEquals(glyph, laneComboGlyph(directions.reversed()), "$directions reversed")
        }
    }

    @Test
    fun uncovered_combinations_fall_back_to_a_single_arrow_explicitly() {
        // A single movement is not a combination.
        assertNull(laneComboGlyph(listOf(Maneuver.STRAIGHT)))
        assertNull(laneComboGlyph(listOf(Maneuver.TURN_LEFT)))
        // Covered movements in an uncovered arrangement: through + through is nonsense, and
        // sharp turns have no combined glyph — both must say so rather than pick a wrong one.
        assertNull(laneComboGlyph(listOf(Maneuver.STRAIGHT, Maneuver.STRAIGHT)))
        assertNull(
            laneComboGlyph(listOf(Maneuver.STRAIGHT, Maneuver.TURN_SHARP_LEFT)),
            "sharp turns have no combined glyph",
        )
        assertNull(
            laneComboGlyph(listOf(Maneuver.UTURN_LEFT, Maneuver.UTURN_RIGHT)),
            "U-turns have no combined glyph",
        )
    }
}
