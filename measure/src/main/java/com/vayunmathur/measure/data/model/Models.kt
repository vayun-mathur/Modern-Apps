package com.vayunmathur.measure.data.model

/** Preferred unit system for displayed measurements: metric or imperial. */
enum class UnitSystem { Metric, Imperial }

/** How far the visual-inertial estimator has got, and therefore what the UI may show. */
enum class TrackingQuality {
    /**
     * No metric scale yet. Monocular VIO needs genuine translation to triangulate; pure
     * rotation leaves the reconstruction scale-free, so measurements are withheld here.
     */
    Initialising,

    /** Tracking, but with few landmarks or weak parallax — distances may drift. */
    Limited,

    Good,

    /** Tracking lost entirely; anchors placed before this point are no longer trustworthy. */
    Lost,
}

/** A 3D point in the tracker's metric world frame, in metres, gravity-aligned (+Y up). */
data class Anchor(
    val id: Long,
    val x: Double,
    val y: Double,
    val z: Double,
    /** True when the point was snapped to the fitted plane rather than free-placed. */
    val onPlane: Boolean = false,
)

fun Anchor.distanceTo(other: Anchor): Double {
    val dx = x - other.x
    val dy = y - other.y
    val dz = z - other.z
    return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
}

/** Kind of a saved measurement, which determines the unit of its stored SI value. */
enum class MeasurementKind { Distance, Area, Perimeter, Angle, Height }

/** A measurement saved by the user, stored in SI units with its label, kind, and recording time. */
data class SavedMeasurement(
    val id: Long,
    val label: String,
    val kind: MeasurementKind,
    /** Metres, square metres, or degrees depending on [kind]. Always stored in SI. */
    val value: Double,
    val recordedAtEpochMs: Long,
    val framePath: String? = null,
)
