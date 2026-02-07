package com.vayunmathur.maps

import com.vayunmathur.maps.data.SpecificFeature
import org.maplibre.spatialk.geojson.Position
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

data class TransitRoute(val steps: List<Step>, override val duration: Duration = steps.fold(0.seconds, {a, b -> a + b.duration}), override val distanceMeters: Double = steps.sumOf { it.distanceMeters }): RouteService.RouteType {
    sealed interface Step {
        val polyline: List<Position>
        val duration: Duration
        val distanceMeters: Double
        data class WalkStep(override val duration: Duration, override val distanceMeters: Double, override val polyline: List<Position>) : Step
        data class TransitStep(override val duration: Duration, override val distanceMeters: Double, override val polyline: List<Position>,
            val departureStation: String, val arrivalStation: String, val lineColor: String, val lineName: String, val lineDirection: String,
            val departureTime: Instant, val arrivalTime: Instant
            ) : Step
    }

    fun startTime(): Instant {
        if(steps.size == 1) {
            return when(val step = steps.first()) {
                is Step.WalkStep -> Clock.System.now()
                is Step.TransitStep -> step.departureTime
            }
        } else if(steps.first() is Step.WalkStep) {
            val firstStep = steps.first() as Step.WalkStep
            val secondStep = steps[1] as Step.TransitStep
            return secondStep.departureTime - firstStep.duration
        } else {
            val firstStep = steps.first() as Step.TransitStep
            return firstStep.departureTime
        }
    }

    fun endTime(): Instant {
        if(steps.size == 1) {
            return when(val step = steps.first()) {
                is Step.WalkStep -> Clock.System.now() + step.duration
                is Step.TransitStep -> step.arrivalTime
            }
        } else if(steps.last() is Step.WalkStep) {
            val lastStep = steps.last() as Step.WalkStep
            val secondLastStep = steps[steps.size - 2] as Step.TransitStep
            return secondLastStep.arrivalTime + lastStep.duration
        } else {
            val lastStep = steps.last() as Step.TransitStep
            return lastStep.arrivalTime
        }
    }

    companion object {
        suspend fun computeRoute(features: SpecificFeature.Route,
                                 userPosition: Position): TransitRoute? {
            val res = RouteService.computeRoute(features, userPosition, RouteService.TravelMode.TRANSIT) ?: return null
            val steps = res.step.map {
                if(it.travelMode == RouteService.TravelMode.WALK) {
                    Step.WalkStep(it.staticDuration, it.distanceMeters, it.polyline)
                } else if(it.travelMode == RouteService.TravelMode.TRANSIT) {
                    it.transitDetails ?: throw Exception("Transit details not found")
                    Step.TransitStep(
                        it.staticDuration,
                        it.distanceMeters,
                        it.polyline,
                        it.transitDetails.stopDetails.departureStop.name,
                        it.transitDetails.stopDetails.arrivalStop.name,
                        it.transitDetails.transitLine.color,
                        it.transitDetails.transitLine.nameShort ?: it.transitDetails.transitLine.name,
                        it.transitDetails.headsign,
                        Instant.parse(it.transitDetails.stopDetails.departureTime),
                        Instant.parse(it.transitDetails.stopDetails.arrivalTime)
                    )
                } else throw Exception("Unknown travel mode")
            }
            return TransitRoute(steps.combineAdjacent({a, b -> a is Step.WalkStep && b is Step.WalkStep}, {a, b ->
                Step.WalkStep((a as Step.WalkStep).duration + (b as Step.WalkStep).duration, a.distanceMeters + b.distanceMeters, a.polyline + b.polyline)
            }))
        }
    }
}

/**
 * Combines adjacent elements in a list if they satisfy the [predicate].
 * [transform] defines how two elements should be merged into one.
 */
fun <T> List<T>.combineAdjacent(
    predicate: (T, T) -> Boolean,
    transform: (T, T) -> T
): List<T> {
    if (this.isEmpty()) return emptyList()

    val result = mutableListOf<T>()
    var current = this[0]

    for (i in 1 until size) {
        val next = this[i]
        if (predicate(current, next)) {
            // Merge them and keep the result as the 'current' for the next check
            current = transform(current, next)
        } else {
            // Condition not met, save 'current' and move on
            result.add(current)
            current = next
        }
    }
    result.add(current)
    return result
}