package com.vayunmathur.maps.domain
import com.vayunmathur.maps.util.OfflineRouter

import kotlin.math.ceil
import kotlin.time.Duration

/**
 * How a transit itinerary is worded: a walk leg's duration, and the "Leave at" /
 * "Arrive at" bookends.
 *
 * Split out of [OfflineRouter] because that object's initializer loads the native
 * routing library, which puts everything on it out of reach of a JVM unit test.
 * None of this needs the router — it reads the legs the router already produced.
 */

/**
 * Stand-in instruction text for an itinerary's walk leg, replaced by
 * [phraseWalkLegs] once coalescing has settled the leg's duration. Never shown: it
 * exists so adjacent walk legs still compare equal and merge.
 */
internal const val WALK_LEG_PLACEHOLDER = "\u0000walk"

/**
 * Replace each walk leg's placeholder instruction with its duration, via [label].
 *
 * Done after coalescing, not while the step is built: the merge groups steps by
 * identical instruction text, so a duration embedded earlier would stop two adjacent
 * walk legs merging and split them into two rows. By this point the duration is the
 * merged one, which is what the row should show.
 *
 * [label] rather than a `Context` so the rule about *which* steps get rephrased —
 * the part that interacts with coalescing — is testable off-device.
 */
internal fun phraseWalkLegs(
    steps: List<RouteService.Step>,
    mode: RouteService.TravelMode,
    label: (minutes: Int) -> String,
): List<RouteService.Step> {
    if (mode != RouteService.TravelMode.TRANSIT) return steps
    return steps.map { step ->
        if (step.navInstruction.instructions != WALK_LEG_PLACEHOLDER) step
        else step.copy(
            navInstruction = step.navInstruction.copy(
                instructions = label(walkLegMinutes(step.staticDuration))
            )
        )
    }
}

/** A walk leg's duration in whole minutes, rounded up and floored at one: every other
 * time the route sheet shows drops seconds, and "0 min" reads as no walk at all. */
internal fun walkLegMinutes(duration: Duration): Int =
    ceil(duration.inWholeSeconds / SECONDS_PER_MINUTE).toInt().coerceAtLeast(1)

/**
 * When to set off: the first ride's departure less the walk that leads to it.
 *
 * Not the itinerary's own start time. A wait leg between the walk and the ride is
 * slack the user does not need to spend at the stop, so only the walk is subtracted —
 * that is the number that answers "when do I leave?". Null when there is no
 * timetabled ride, i.e. a walk-only itinerary.
 */
internal fun leaveAt(rawSteps: Array<OfflineRouter.RawStep>): String? {
    val firstRide = rawSteps.indexOfFirst { it.isTransit && it.depSecs > 0 }
    if (firstRide < 0) return null
    return formatServiceTime(rawSteps[firstRide].depSecs - walkSecs(rawSteps.take(firstRide)))
}

/** When the itinerary lands: the last ride's arrival plus the walk off the end. */
internal fun arriveAt(rawSteps: Array<OfflineRouter.RawStep>): String? {
    val lastRide = rawSteps.indexOfLast { it.isTransit && it.arrSecs > 0 }
    if (lastRide < 0) return null
    return formatServiceTime(rawSteps[lastRide].arrSecs + walkSecs(rawSteps.drop(lastRide + 1)))
}

/** Walking seconds across [legs], counting neither rides nor waiting at a stop. */
private fun walkSecs(legs: List<OfflineRouter.RawStep>): Int =
    legs.filter { !it.isTransit && it.maneuverId != RouteService.API.Maneuver.WAIT.ordinal }
        .sumOf { it.duration10ms / CENTISEC_PER_SECOND }
        .toInt()

/** Seconds per minute, for the walk-leg wording. */
private const val SECONDS_PER_MINUTE = 60.0
/** Native durations are centiseconds (10 ms units). */
private const val CENTISEC_PER_SECOND = 100
