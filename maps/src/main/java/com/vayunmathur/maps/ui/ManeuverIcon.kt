package com.vayunmathur.maps.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import com.vayunmathur.library.ui.Icon
import com.vayunmathur.library.ui.IconForkLeft
import com.vayunmathur.library.ui.IconForkRight
import com.vayunmathur.library.ui.IconMerge
import com.vayunmathur.library.ui.IconRampLeft
import com.vayunmathur.library.ui.IconRampRight
import com.vayunmathur.library.ui.IconRoundaboutLeft
import com.vayunmathur.library.ui.IconRoundaboutRight
import com.vayunmathur.library.ui.IconStraight
import com.vayunmathur.library.ui.IconTurnLeft
import com.vayunmathur.library.ui.IconTurnRight
import com.vayunmathur.library.ui.IconTurnSharpLeft
import com.vayunmathur.library.ui.IconTurnSharpRight
import com.vayunmathur.library.ui.IconTurnSlightLeft
import com.vayunmathur.library.ui.IconTurnSlightRight
import com.vayunmathur.library.ui.IconUTurn
import com.vayunmathur.library.ui.IconUTurnRight
import com.vayunmathur.maps.R
import com.vayunmathur.maps.domain.RouteService.API.Maneuver

/** Composable icon for a maneuver, or null when the maneuver has no icon. */
fun Maneuver.iconContent(): (@Composable (Modifier, Color) -> Unit)? {
    // Derived from [glyph], never authored twice: the right-U-turn fix (UTURN_RIGHT drew a left
    // hook because both U-turns mapped to one glyph) stays fixed because there is one table, and
    // [ManeuverGlyphTest] pins every row of it.
    return when (val g = glyph()) {
        null -> null
        ManeuverGlyph.TURN_SLIGHT_LEFT -> { m, t -> IconTurnSlightLeft(m, t) }
        ManeuverGlyph.TURN_SHARP_LEFT -> { m, t -> IconTurnSharpLeft(m, t) }
        ManeuverGlyph.UTURN_LEFT -> { m, t -> IconUTurn(m, t) }
        ManeuverGlyph.TURN_LEFT -> { m, t -> IconTurnLeft(m, t) }
        ManeuverGlyph.TURN_SLIGHT_RIGHT -> { m, t -> IconTurnSlightRight(m, t) }
        ManeuverGlyph.TURN_SHARP_RIGHT -> { m, t -> IconTurnSharpRight(m, t) }
        ManeuverGlyph.UTURN_RIGHT -> { m, t -> IconUTurnRight(m, t) }
        ManeuverGlyph.TURN_RIGHT -> { m, t -> IconTurnRight(m, t) }
        ManeuverGlyph.STRAIGHT -> { m, t -> IconStraight(m, t) }
        ManeuverGlyph.RAMP_LEFT -> { m, t -> IconRampLeft(m, t) }
        ManeuverGlyph.RAMP_RIGHT -> { m, t -> IconRampRight(m, t) }
        ManeuverGlyph.MERGE -> { m, t -> IconMerge(m, t) }
        ManeuverGlyph.FORK_LEFT -> { m, t -> IconForkLeft(m, t) }
        ManeuverGlyph.FORK_RIGHT -> { m, t -> IconForkRight(m, t) }
        ManeuverGlyph.ROUNDABOUT_LEFT -> { m, t -> IconRoundaboutLeft(m, t) }
        ManeuverGlyph.ROUNDABOUT_RIGHT -> { m, t -> IconRoundaboutRight(m, t) }
        ManeuverGlyph.CLOCK -> { m, t ->
            Icon(painterResource(R.drawable.outline_nest_clock_farsight_analog_24), null, m, t)
        }
        ManeuverGlyph.BOOK -> { m, t ->
            Icon(painterResource(R.drawable.outline_menu_book_24), null, m, t)
        }
    }
}

/**
 * Which glyph a maneuver draws, or null when it draws none.
 *
 * The single derivation behind [iconContent]: one row per [Maneuver], so the router's geometric
 * distinction between left and right U-turns cannot be collapsed back into one glyph without
 * failing [ManeuverGlyphTest]. The composable lambdas themselves are not comparable on the JVM,
 * which is why this enum — plain data — is what the test pins.
 *
 * Aliases are authored, not incidental: DEPART and NAME_CHANGE both draw STRAIGHT (a departure
 * is a straight-on), WAIT draws a clock and RIDE a book. FERRY, FERRY_TRAIN and
 * MANEUVER_UNSPECIFIED draw nothing.
 */
internal enum class ManeuverGlyph {
    TURN_SLIGHT_LEFT, TURN_SHARP_LEFT, UTURN_LEFT, TURN_LEFT,
    TURN_SLIGHT_RIGHT, TURN_SHARP_RIGHT, UTURN_RIGHT, TURN_RIGHT,
    STRAIGHT, RAMP_LEFT, RAMP_RIGHT, MERGE, FORK_LEFT, FORK_RIGHT,
    ROUNDABOUT_LEFT, ROUNDABOUT_RIGHT, CLOCK, BOOK,
}

internal fun Maneuver.glyph(): ManeuverGlyph? = when (this) {
    Maneuver.TURN_SLIGHT_LEFT -> ManeuverGlyph.TURN_SLIGHT_LEFT
    Maneuver.TURN_SHARP_LEFT -> ManeuverGlyph.TURN_SHARP_LEFT
    Maneuver.UTURN_LEFT -> ManeuverGlyph.UTURN_LEFT
    Maneuver.TURN_LEFT -> ManeuverGlyph.TURN_LEFT
    Maneuver.TURN_SLIGHT_RIGHT -> ManeuverGlyph.TURN_SLIGHT_RIGHT
    Maneuver.TURN_SHARP_RIGHT -> ManeuverGlyph.TURN_SHARP_RIGHT
    Maneuver.UTURN_RIGHT -> ManeuverGlyph.UTURN_RIGHT
    Maneuver.TURN_RIGHT -> ManeuverGlyph.TURN_RIGHT
    Maneuver.STRAIGHT -> ManeuverGlyph.STRAIGHT
    Maneuver.RAMP_LEFT -> ManeuverGlyph.RAMP_LEFT
    Maneuver.RAMP_RIGHT -> ManeuverGlyph.RAMP_RIGHT
    Maneuver.MERGE -> ManeuverGlyph.MERGE
    Maneuver.FORK_LEFT -> ManeuverGlyph.FORK_LEFT
    Maneuver.FORK_RIGHT -> ManeuverGlyph.FORK_RIGHT
    Maneuver.ROUNDABOUT_LEFT -> ManeuverGlyph.ROUNDABOUT_LEFT
    Maneuver.ROUNDABOUT_RIGHT -> ManeuverGlyph.ROUNDABOUT_RIGHT
    Maneuver.DEPART -> ManeuverGlyph.STRAIGHT
    Maneuver.NAME_CHANGE -> ManeuverGlyph.STRAIGHT
    Maneuver.WAIT -> ManeuverGlyph.CLOCK
    Maneuver.RIDE -> ManeuverGlyph.BOOK
    Maneuver.FERRY, Maneuver.FERRY_TRAIN, Maneuver.MANEUVER_UNSPECIFIED -> null
}
