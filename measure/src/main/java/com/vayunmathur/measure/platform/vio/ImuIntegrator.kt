package com.vayunmathur.measure.platform.vio

import com.vayunmathur.measure.domain.Vec3
import com.vayunmathur.measure.domain.multiply3
import com.vayunmathur.measure.domain.rotate3
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** One IMU sample: accel+gyro in camera coordinates (m/s^2, rad/s), timestamp in ns. */
private data class ImuSample(
    val tNs: Long,
    val ax: Double,
    val ay: Double,
    val az: Double,
    val gx: Double,
    val gy: Double,
    val gz: Double,
)

/** Rotation matrix for a small rotation vector (Rodrigues' formula). */
private fun rodrigues(rx: Double, ry: Double, rz: Double): DoubleArray {
    val theta = sqrt(rx * rx + ry * ry + rz * rz)
    if (theta < 1e-12) {
        return doubleArrayOf(1.0, -rz, ry, rz, 1.0, -rx, -ry, rx, 1.0)
    }
    val (kx, ky, kz) = Triple(rx / theta, ry / theta, rz / theta)
    val c = cos(theta)
    val s = sin(theta)
    val v = 1.0 - c
    return doubleArrayOf(
        c + kx * kx * v, kx * ky * v - kz * s, kx * kz * v + ky * s,
        ky * kx * v + kz * s, c + ky * ky * v, ky * kz * v - kx * s,
        kz * kx * v - ky * s, kz * ky * v + kx * s, c + kz * kz * v,
    )
}

/**
 * Continuous short-horizon IMU integration for visual-inertial scale.
 *
 * Samples are integrated once, on arrival: orientation by gyro
 * integration (world frame = first camera frame, matching the visual
 * tracker's fiat), gravity removed with that orientation, velocity and
 * position carried continuously. A scale window's displacement is then
 * just the difference of integrated positions at its endpoints.
 *
 * The previous design re-integrated each window from scratch with two
 * fatal assumptions: zero velocity at the window start (false during a
 * continuous walk — it throws away `v0 * T`, the same order as the
 * window's own displacement) and one orientation for the whole window
 * (gravity leaks whenever the phone turns mid-window). Both scattered
 * the visual/IMU displacement ratios so the scale confidence never
 * converged, on device or in the synthetic harness.
 *
 * This is still not a full inertial navigator: gyro bias and accel bias
 * are not estimated, so integrated positions are only trusted over a
 * few seconds — which is all a scale window spans.
 */
internal class ImuIntegrator {

    private val samples = ArrayList<ImuSample>()
    private var firstAccel: Vec3? = null

    // Continuous integration state, advanced as samples arrive.
    private var processed = 0
    private var orientR = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
    private var vel = Vec3(0.0, 0.0, 0.0)
    private var pos = Vec3(0.0, 0.0, 0.0)
    private var lastTNs = 0L
    private var upWorld: Vec3? = null
    /** Accel bias in camera axes, estimated from the initial still period. */
    private var accelBias: Vec3? = null
    /** Integrated position at each processed sample, parallel to sample times. */
    private val track = ArrayList<Pair<Long, Vec3>>()

    /** Consume a packed batch: `[tNs, gx, gy, gz, ax, ay, az]` per sample. */
    fun push(packed: DoubleArray) {
        var i = 0
        while (i + STRIDE <= packed.size) {
            val t = packed[i].toLong()
            samples += ImuSample(
                t,
                packed[i + AX_OFFSET], packed[i + AY_OFFSET], packed[i + AZ_OFFSET],
                packed[i + 1], packed[i + 2], packed[i + 3],
            )
            if (firstAccel == null) {
                firstAccel = Vec3(packed[i + AX_OFFSET], packed[i + AY_OFFSET], packed[i + AZ_OFFSET])
            }
            i += STRIDE
        }
        integrateNewSamples()
        // Windows only ever look back a few seconds; cap the buffers.
        if (samples.size > MAX_SAMPLES) {
            val drop = samples.size - MAX_SAMPLES
            samples.subList(0, drop).clear()
            track.subList(0, minOf(drop, track.size)).clear()
            processed = maxOf(0, processed - drop)
        }
    }

    private fun integrateNewSamples() {
        // Calibration from the initial still period (the user has just
        // opened the AR screen): gravity-up from the mean accel direction,
        // and accel bias as the mean's deviation from exactly g along up.
        // Uncalibrated accel bias (~0.02 m/s^2) otherwise double-integrates
        // into centimetres of phantom displacement within seconds.
        if (accelBias == null) {
            if (samples.size < CALIBRATION_SAMPLES) return
            val head = samples.subList(0, CALIBRATION_SAMPLES)
            val mean = Vec3(
                head.sumOf { it.ax } / head.size,
                head.sumOf { it.ay } / head.size,
                head.sumOf { it.az } / head.size,
            )
            val len = sqrt(mean.x * mean.x + mean.y * mean.y + mean.z * mean.z)
            if (len <= 1.0) return
            val up = Vec3(mean.x / len, mean.y / len, mean.z / len)
            upWorld = up
            accelBias = Vec3(
                mean.x - up.x * GRAVITY,
                mean.y - up.y * GRAVITY,
                mean.z - up.z * GRAVITY,
            )
        }
        val up = upWorld ?: return
        val bias = accelBias ?: return
        while (processed < samples.size) {
            val s = samples[processed]
            if (processed == 0 || lastTNs == 0L) {
                lastTNs = s.tNs
                track += s.tNs to pos
                processed++
                continue
            }
            val dt = (s.tNs - lastTNs) / NS_PER_SECOND
            lastTNs = s.tNs
            processed++
            if (dt <= 0.0 || dt > MAX_STEP_S) {
                track += s.tNs to pos
                continue
            }
            val worldAccel = rotate3(orientR, Vec3(s.ax - bias.x, s.ay - bias.y, s.az - bias.z))
            val ax = worldAccel.x - up.x * GRAVITY
            val ay = worldAccel.y - up.y * GRAVITY
            val az = worldAccel.z - up.z * GRAVITY
            vel = Vec3(vel.x + ax * dt, vel.y + ay * dt, vel.z + az * dt)
            pos = Vec3(pos.x + vel.x * dt, pos.y + vel.y * dt, pos.z + vel.z * dt)
            orientR = multiply3(orientR, rodrigues(s.gx * dt, s.gy * dt, s.gz * dt))
            track += s.tNs to pos
        }
    }

    /** Mean accelerometer reading (camera frame), for gravity-up seeding. */
    fun meanAccelCam(): Vec3? {
        if (samples.isEmpty()) return firstAccel
        val n = samples.size
        return Vec3(
            samples.sumOf { it.ax } / n,
            samples.sumOf { it.ay } / n,
            samples.sumOf { it.az } / n,
        )
    }

    /**
     * Integrated displacement between [fromNs] and [toNs] in world
     * coordinates, or null when the track does not cover the window.
     */
    fun displacementBetween(fromNs: Long, toNs: Long): Vec3? {
        if (toNs <= fromNs || track.size < MIN_WINDOW_SAMPLES) return null
        val a = positionAt(fromNs) ?: return null
        val b = positionAt(toNs) ?: return null
        return b - a
    }

    private fun positionAt(tNs: Long): Vec3? {
        // Latest integrated position at or before tNs (windows end on a
        // frame timestamp, which always follows the newest IMU sample).
        var found: Vec3? = null
        for ((t, p) in track) {
            if (t > tNs) break
            found = p
        }
        return found
    }

    fun reset() {
        samples.clear()
        track.clear()
        firstAccel = null
        processed = 0
        orientR = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        vel = Vec3(0.0, 0.0, 0.0)
        pos = Vec3(0.0, 0.0, 0.0)
        lastTNs = 0L
        upWorld = null
        accelBias = null
    }

    private companion object {
        const val GRAVITY = 9.81
        const val STRIDE = 7
        const val AX_OFFSET = 4
        const val AY_OFFSET = 5
        const val AZ_OFFSET = 6
        const val MAX_SAMPLES = 4000
        /**
         * ~0.2 s at 200 Hz: the initial-still calibration window. Kept
         * short on purpose: a longer window risks overlapping the user's
         * first motion, and the motion's mean accel then lands in the
         * "bias" estimate and cancels real signal (harness: a 0.5 s
         * window ate ~0.19 m/s^2 and halved integrated displacement).
         */
        const val CALIBRATION_SAMPLES = 40
        const val MIN_WINDOW_SAMPLES = 4
        const val NS_PER_SECOND = 1e9
        const val MAX_STEP_S = 0.1
    }
}
