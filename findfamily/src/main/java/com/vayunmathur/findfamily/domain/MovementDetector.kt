package com.vayunmathur.findfamily.domain

/**
 * One location fix, reduced to what movement detection needs.
 *
 * Pure on purpose (no `android.location` types) so the state machine below is
 * unit-testable without a device. [accuracyMeters], [speedMps] and
 * [speedAccuracyMps] are null when the provider did not report them.
 */
data class FixSample(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double?,
    val speedMps: Double?,
    val speedAccuracyMps: Double?,
    val elapsedMs: Long,
)

/** What a [MovementDetector] input changed, so the caller knows which radios to switch. */
enum class MotionTransition { NONE, BECAME_MOVING, BECAME_STILL }

/**
 * Decides whether the sending phone is moving (GitHub #895).
 *
 * The old rule was acceleration alone: 60 s of linear acceleration under
 * [ACCELERATION_THRESHOLD] meant "still", and only the one-shot
 * significant-motion sensor could undo it. A car, bus or tram at steady speed
 * sits under that threshold, and the significant-motion sensor is step-driven
 * on many phones, so a departure by vehicle was never noticed and the phone
 * stopped producing fixes for hours.
 *
 * Now any of three kinds of evidence keeps or makes the phone "moving":
 * - acceleration above [ACCELERATION_THRESHOLD] (unchanged),
 * - a fix whose provider-reported speed is clearly above walking pace,
 * - a fix displaced from the last reference point ([anchor]) by more than
 *   [DISPLACEMENT_THRESHOLD_METERS] AND by more than [ERROR_RADIUS_SCALE]
 *   times both fixes' error radii combined, so network jitter at home does
 *   not count but a coarse fix far from home does.
 *
 * Fixes of any accuracy feed this detector, including ones the display gate
 * ([NoShowPolicy.NO_SHOW_MAX_ACCURACY_METERS]) drops: a 600 m network fix
 * two kilometres from home cannot say where the person is, but it does prove
 * they left.
 *
 * "Still" now needs both signals quiet: no acceleration for
 * [ACCELERATION_QUIET_MS] AND no fix-based movement for [FIX_QUIET_MS].
 *
 * Times are monotonic milliseconds (elapsedRealtime) supplied by the caller.
 */
class MovementDetector {

    /** True while active (10 s network / GPS) updates should run. */
    var isMoving: Boolean = false
        private set

    /** Last time acceleration (or a wake-up such as significant motion) showed movement. */
    var lastAccelerationMovementMs: Long = 0L
        private set

    /** Last time a fix (speed or displacement) showed movement. */
    var lastFixMovementMs: Long = 0L
        private set

    /** Reference point displacement is measured from; moves only on movement or refinement. */
    var anchor: FixSample? = null
        private set

    /**
     * Something outside the fix stream says "moving": the significant-motion
     * trigger, a fresh service start with no fix, or the pre-unlock path.
     * Both quiet windows restart, so the phone gets a full window of active
     * updates to gather evidence before it may count as still again.
     */
    fun markMoving(nowMs: Long): MotionTransition {
        lastAccelerationMovementMs = nowMs
        lastFixMovementMs = nowMs
        return becomeMoving()
    }

    /** Feed one linear-acceleration magnitude (m/s²). */
    fun onAcceleration(magnitude: Float, nowMs: Long): MotionTransition {
        if (magnitude > ACCELERATION_THRESHOLD) {
            lastAccelerationMovementMs = nowMs
            return becomeMoving()
        }
        return evaluateStillness(nowMs)
    }

    /**
     * Feed one fix, whatever its accuracy. Fixes older than [MAX_SAMPLE_AGE_MS]
     * at [nowMs] (a cached fix replayed by a provider) are ignored entirely.
     */
    fun onFix(sample: FixSample, nowMs: Long): MotionTransition {
        if (nowMs - sample.elapsedMs > MAX_SAMPLE_AGE_MS) return evaluateStillness(nowMs)
        val moved = isFastEnough(sample) || isDisplaced(sample)
        val held = anchor
        if (held == null || moved || accuracyOf(sample) < accuracyOf(held)) {
            // Movement moves the reference point; a consistent but sharper fix
            // refines it (otherwise a coarse first anchor would hide a slow walk).
            anchor = sample
        }
        if (moved) {
            lastFixMovementMs = nowMs
            return becomeMoving()
        }
        return evaluateStillness(nowMs)
    }

    /** Re-check stillness with no new input (heartbeat tick, devices without an accelerometer). */
    fun onTick(nowMs: Long): MotionTransition = evaluateStillness(nowMs)

    private fun becomeMoving(): MotionTransition {
        if (isMoving) return MotionTransition.NONE
        isMoving = true
        return MotionTransition.BECAME_MOVING
    }

    private fun evaluateStillness(nowMs: Long): MotionTransition {
        if (!isMoving) return MotionTransition.NONE
        val accelerationQuiet = nowMs - lastAccelerationMovementMs > ACCELERATION_QUIET_MS
        val fixesQuiet = nowMs - lastFixMovementMs > FIX_QUIET_MS
        if (!accelerationQuiet || !fixesQuiet) return MotionTransition.NONE
        isMoving = false
        return MotionTransition.BECAME_STILL
    }

    private fun isFastEnough(sample: FixSample): Boolean {
        val speed = sample.speedMps ?: return false
        if (!speed.isFinite()) return false
        val uncertainty = sample.speedAccuracyMps?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
        return speed - uncertainty > WALKING_SPEED_MPS
    }

    private fun isDisplaced(sample: FixSample): Boolean {
        val held = anchor ?: return false
        val distance = NoShowPolicy.haversineMeters(
            held.latitude, held.longitude, sample.latitude, sample.longitude
        )
        return distance > DISPLACEMENT_THRESHOLD_METERS &&
            distance > ERROR_RADIUS_SCALE * (accuracyOf(held) + accuracyOf(sample))
    }

    private fun accuracyOf(sample: FixSample): Double =
        sample.accuracyMeters?.takeIf { it.isFinite() && it >= 0.0 } ?: UNKNOWN_ACCURACY_METERS

    /** Tuning constants; see the class KDoc for how they combine. */
    companion object {
        /** Linear-acceleration magnitude that counts as movement (unchanged from before #895). */
        const val ACCELERATION_THRESHOLD = 0.5f

        /** How long acceleration must stay under the threshold before "still" is possible. */
        const val ACCELERATION_QUIET_MS = 60_000L

        /**
         * How long fixes must show no movement before "still" is possible.
         *
         * Longer than the power-save GPS interval (180 s) so a vehicle tracked
         * only by GPS speed in power-save does not flap between modes.
         */
        const val FIX_QUIET_MS = 4 * 60_000L

        /** Brisk walking is ~1.7 m/s; anything clearly above 2 m/s is a vehicle or a run. */
        const val WALKING_SPEED_MPS = 2.0

        /** Minimum displacement that can count as movement, on top of the error-radius test. */
        const val DISPLACEMENT_THRESHOLD_METERS = 75.0

        /**
         * Android accuracy is a ~68 % radius; doubling it gives roughly a 95 %
         * radius, so two fixes only disagree when their 95 % circles do not
         * overlap. Keeps the at-home network jitter from issue #895 from
         * waking active updates over and over.
         */
        const val ERROR_RADIUS_SCALE = 2.0

        /** Error radius assumed for a fix that reports none: treat it as coarse. */
        const val UNKNOWN_ACCURACY_METERS = 1_000.0

        /** Fixes older than this when they arrive say nothing about movement now. */
        const val MAX_SAMPLE_AGE_MS = 2 * 60_000L
    }
}
