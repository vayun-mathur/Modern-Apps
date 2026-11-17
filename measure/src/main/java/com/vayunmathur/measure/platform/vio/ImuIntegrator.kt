package com.vayunmathur.measure.platform.vio

import com.vayunmathur.measure.domain.Vec3
import com.vayunmathur.measure.domain.rotate3

/** One IMU sample: accel in camera coordinates (m/s^2), timestamp in ns. */
private data class ImuSample(val tNs: Long, val ax: Double, val ay: Double, val az: Double)

/**
 * Short-window IMU displacement for visual-inertial scale alignment.
 *
 * This is deliberately not a full inertial navigator: gravity is
 * removed with the tracker's current world-up and orientation, accel
 * is rotated into the world frame, and displacement comes from double
 * integration over one keyframe window assuming near-zero velocity at
 * the window edges (the user is moving the phone by hand, pausing at
 * each keyframe beat). Windows are seconds at most, so drift stays
 * bounded; the scale estimator's confidence gate rejects windows where
 * the assumption breaks. Honest accuracy note: this is the pipeline's
 * weakest link, and the confidence threshold exists because of it.
 */
internal class ImuIntegrator {

    private val samples = ArrayList<ImuSample>()
    private var firstAccel: Vec3? = null

    /** Consume a packed batch: `[tNs, gx, gy, gz, ax, ay, az]` per sample. */
    fun push(packed: DoubleArray) {
        var i = 0
        while (i + STRIDE <= packed.size) {
            val t = packed[i].toLong()
            val ax = packed[i + AX_OFFSET]
            val ay = packed[i + AY_OFFSET]
            val az = packed[i + AZ_OFFSET]
            samples += ImuSample(t, ax, ay, az)
            if (firstAccel == null) firstAccel = Vec3(ax, ay, az)
            i += STRIDE
        }
        // Windows only ever look back a few seconds; cap the buffer.
        if (samples.size > MAX_SAMPLES) {
            samples.subList(0, samples.size - MAX_SAMPLES).clear()
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
     * Double-integrated displacement between [fromNs] and [toNs], in
     * world coordinates, or null when the window has too few samples.
     * [worldFromCamR] orients camera-frame accel into the world.
     */
    fun displacementBetween(
        fromNs: Long,
        toNs: Long,
        worldFromCamR: DoubleArray,
        worldUp: Vec3,
    ): Vec3? {
        if (toNs <= fromNs) return null
        val window = samples.filter { it.tNs in fromNs..toNs }
        if (window.size < MIN_WINDOW_SAMPLES) return null
        var vx = 0.0
        var vy = 0.0
        var vz = 0.0
        var px = 0.0
        var py = 0.0
        var pz = 0.0
        var prevT = window.first().tNs
        for (s in window) {
            val dt = (s.tNs - prevT) / NS_PER_SECOND
            prevT = s.tNs
            if (dt <= 0.0 || dt > MAX_STEP_S) continue
            val cam = Vec3(s.ax, s.ay, s.az)
            val world = rotate3(worldFromCamR, cam)
            // Remove gravity: accelerometer measures specific force, so
            // world accel already includes +g along up; subtract it.
            val ax = world.x - worldUp.x * GRAVITY
            val ay = world.y - worldUp.y * GRAVITY
            val az = world.z - worldUp.z * GRAVITY
            vx += ax * dt; vy += ay * dt; vz += az * dt
            px += vx * dt; py += vy * dt; pz += vz * dt
        }
        return Vec3(px, py, pz)
    }

    fun reset() {
        samples.clear()
        firstAccel = null
    }

    private companion object {
        const val GRAVITY = 9.81
        const val STRIDE = 7
        const val AX_OFFSET = 4
        const val AY_OFFSET = 5
        const val AZ_OFFSET = 6
        const val MAX_SAMPLES = 4000
        const val MIN_WINDOW_SAMPLES = 4
        const val NS_PER_SECOND = 1e9
        const val MAX_STEP_S = 0.1
    }
}
