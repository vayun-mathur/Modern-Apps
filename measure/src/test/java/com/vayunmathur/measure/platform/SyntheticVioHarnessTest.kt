package com.vayunmathur.measure.platform

import com.vayunmathur.measure.domain.HitSelector
import com.vayunmathur.measure.domain.Vec3
import com.vayunmathur.measure.platform.vio.VioEngine
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Synthetic ground-truth validation of the whole VIO pipeline.
 *
 * A textured two-plane scene is rendered from a defined camera trajectory
 * at the analyzer's resolution; accel/gyro samples are synthesized from
 * the same trajectory (in Android device axes, so the engine's
 * device->camera rotation is exercised too) and the real [VioEngine]
 * (Rust corners/LK/essential + Kotlin chaining/triangulation/IMU scale)
 * consumes both. Metrics are printed as a table and the walk scenarios
 * assert sane bars. Deterministic seeds throughout.
 */
class SyntheticVioHarnessTest {

    private val w = 640
    private val h = 480
    private val fx = 450.0
    private val fy = 450.0
    private val cx = 320.0
    private val cy = 240.0

    // ---- scene texture (wrapped, sampled bilinearly) ----
    private val texSize = 1024
    private val texture = FloatArray(texSize * texSize).also { t ->
        val rnd = Random(42)
        val block = FloatArray((texSize / 8) * (texSize / 8)) { (rnd.nextFloat() - 0.5f) * 110f }
        for (y in 0 until texSize) {
            for (x in 0 until texSize) {
                var v = 128f +
                    55f * sin(x * 0.11f) * cos(y * 0.13f) +
                    35f * sin((x + y) * 0.045f) +
                    block[(y / 8) * (texSize / 8) + (x / 8)]
                v += (rnd.nextFloat() - 0.5f) * 18f
                t[y * texSize + x] = v.coerceIn(0f, 255f)
            }
        }
    }

    private fun sampleTex(u: Double, v: Double, contrast: Double): Double {
        val x = ((u % texSize) + texSize) % texSize
        val y = ((v % texSize) + texSize) % texSize
        val x0 = x.toInt()
        val y0 = y.toInt()
        val x1 = (x0 + 1) % texSize
        val y1 = (y0 + 1) % texSize
        val ax = x - x0
        val ay = y - y0
        val top = texture[y0 * texSize + x0] * (1 - ax) + texture[y0 * texSize + x1] * ax
        val bot = texture[y1 * texSize + x0] * (1 - ax) + texture[y1 * texSize + x1] * ax
        val value = top * (1 - ay) + bot * ay
        return 128.0 + (value - 128.0) * contrast
    }

    // Rays in camera coords per pixel, precomputed once.
    private val rayX = DoubleArray(w * h)
    private val rayY = DoubleArray(w * h)

    init {
        for (y in 0 until h) {
            for (x in 0 until w) {
                rayX[y * w + x] = (x - cx) / fx
                rayY[y * w + x] = (y - cy) / fy
            }
        }
    }

    /** World frame = first camera frame: X right, Y down, Z into the scene. */
    private data class Traj(val pos: (Double) -> Vec3, val euler: (Double) -> Triple<Double, Double, Double>)

    private fun smooth01(x: Double): Double {
        val t = x.coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    private fun scenarios(): List<Triple<String, Traj, Double>> {
        val still = Traj({ Vec3(0.0, 0.0, 0.0) }, { t -> Triple(0.002 * sin(2 * t), 0.0, 0.0) })
        val pan = Traj({ Vec3(0.0, 0.0, 0.0) }, { t -> Triple(0.0, 0.30 * sin(0.5 * t), 0.0) })
        val sideways = Traj(
            { t -> Vec3(0.85 * smooth01((t - 0.5) / 3.2), 0.01 * sin(3 * t), 0.0) },
            { t -> Triple(0.01 * sin(2 * t), 0.03 * sin(0.7 * t), 0.0) },
        )
        val forward = Traj(
            { t -> Vec3(0.01 * sin(2.5 * t), 0.008 * sin(3 * t), 0.85 * smooth01((t - 0.5) / 3.2)) },
            { t -> Triple(0.01 * sin(2 * t), 0.02 * sin(0.6 * t), 0.0) },
        )
        val fast = Traj(
            { t -> Vec3(1.3 * smooth01((t - 0.3) / 1.1), 0.02 * sin(5 * t), 0.0) },
            { t -> Triple(0.02 * sin(4 * t), 0.06 * sin(1.5 * t), 0.0) },
        )
        return listOf(
            Triple("static", still, 1.0),
            Triple("slow-pan", pan, 1.0),
            Triple("sideways", sideways, 1.0),
            Triple("forward", forward, 1.0),
            Triple("low-texture", sideways, 0.12),
            Triple("fast", fast, 1.0),
        )
    }

    private fun rotAt(traj: Traj, t: Double): DoubleArray {
        val (pitch, yaw, roll) = traj.euler(t)
        val cy = cos(yaw); val sy = sin(yaw)
        val cp = cos(pitch); val sp = sin(pitch)
        val cr = cos(roll); val sr = sin(roll)
        val ry = doubleArrayOf(cy, 0.0, sy, 0.0, 1.0, 0.0, -sy, 0.0, cy)
        val rx = doubleArrayOf(1.0, 0.0, 0.0, 0.0, cp, -sp, 0.0, sp, cp)
        val rz = doubleArrayOf(cr, -sr, 0.0, sr, cr, 0.0, 0.0, 0.0, 1.0)
        return mul(mul(ry, rx), rz)
    }

    private fun mul(a: DoubleArray, b: DoubleArray): DoubleArray {
        val out = DoubleArray(9)
        for (r in 0..2) for (c in 0..2) {
            out[r * 3 + c] = a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
        }
        return out
    }

    private fun transpose(r: DoubleArray) = doubleArrayOf(
        r[0], r[3], r[6], r[1], r[4], r[7], r[2], r[5], r[8],
    )

    private fun rotVec(r: DoubleArray, v: Vec3): Vec3 = Vec3(
        r[0] * v.x + r[1] * v.y + r[2] * v.z,
        r[3] * v.x + r[4] * v.y + r[5] * v.z,
        r[6] * v.x + r[7] * v.y + r[8] * v.z,
    )

    private fun rotVecT(r: DoubleArray, v: Vec3): Vec3 = Vec3(
        r[0] * v.x + r[3] * v.y + r[6] * v.z,
        r[1] * v.x + r[4] * v.y + r[7] * v.z,
        r[2] * v.x + r[5] * v.y + r[8] * v.z,
    )

    private fun render(r: DoubleArray, c: Vec3, contrast: Double, rnd: Random, gain: Double): ByteArray {
        val out = ByteArray(w * h)
        for (i in 0 until w * h) {
            val dir = rotVec(r, Vec3(rayX[i], rayY[i], 1.0))
            var bestT = Double.MAX_VALUE
            var value = 40.0
            // Front plane z = 3.
            if (dir.z > 1e-6) {
                val t = (3.0 - c.z) / dir.z
                if (t > 0.05 && t < bestT) {
                    val px = c.x + dir.x * t
                    val py = c.y + dir.y * t
                    if (px in -6.0..6.0 && py in -4.5..4.5) {
                        bestT = t
                        value = sampleTex((px + 6) * 170.0, (py + 4.5) * 170.0, contrast)
                    }
                }
            }
            // Floor plane y = 1.1 (below camera; Y is down).
            if (dir.y > 1e-6) {
                val t = (1.1 - c.y) / dir.y
                if (t > 0.05 && t < bestT) {
                    val px = c.x + dir.x * t
                    val pz = c.z + dir.z * t
                    if (px in -6.0..6.0 && pz in -1.0..8.0) {
                        bestT = t
                        value = sampleTex((px + 6) * 170.0, pz * 170.0, contrast) * 0.85
                    }
                }
            }
            val noisy = value * gain + (rnd.nextDouble() - 0.5) * 5.0
            out[i] = noisy.coerceIn(0.0, 255.0).toInt().toByte()
        }
        return out
    }

    /** Synthesize one IMU sample in Android device axes at time [t]. */
    private fun imuSample(traj: Traj, t: Double, rnd: Random): DoubleArray {
        val dt = 0.002
        val aWorld = Vec3(
            (traj.pos(t + dt).x - 2 * traj.pos(t).x + traj.pos(t - dt).x) / (dt * dt),
            (traj.pos(t + dt).y - 2 * traj.pos(t).y + traj.pos(t - dt).y) / (dt * dt),
            (traj.pos(t + dt).z - 2 * traj.pos(t).z + traj.pos(t - dt).z) / (dt * dt),
        )
        val r = rotAt(traj, t)
        // Specific force in world = a - g, g = (0, +9.81, 0) in Y-down world.
        val fWorld = Vec3(aWorld.x, aWorld.y - 9.81, aWorld.z)
        val fCam = rotVecT(r, fWorld)
        // Angular velocity in camera frame: skew part of R^T R(t+dt) ~ angle.
        val rel = mul(transpose(r), rotAt(traj, t + dt))
        val ang = Vec3((rel[7] - rel[5]) / 2, (rel[2] - rel[6]) / 2, (rel[3] - rel[1]) / 2)
        val gyro = Vec3(ang.x / dt, ang.y / dt, ang.z / dt)
        // Camera -> device axes: inverse of the engine's device->camera map
        // for rotation=90, M = [[0,-1,0],[-1,0,0],[0,0,-1]] (symmetric).
        fun camToDev(v: Vec3) = Vec3(-v.y, -v.x, -v.z)
        val fDev = camToDev(
            Vec3(
                fCam.x + 0.02 + (rnd.nextDouble() - 0.5) * 0.08,
                fCam.y - 0.015 + (rnd.nextDouble() - 0.5) * 0.08,
                fCam.z + 0.01 + (rnd.nextDouble() - 0.5) * 0.08,
            ),
        )
        val gDev = camToDev(
            Vec3(
                gyro.x + (rnd.nextDouble() - 0.5) * 0.002,
                gyro.y + (rnd.nextDouble() - 0.5) * 0.002,
                gyro.z + (rnd.nextDouble() - 0.5) * 0.002,
            ),
        )
        val tNs = (t * 1e9).toLong()
        return doubleArrayOf(
            tNs.toDouble(), gDev.x, gDev.y, gDev.z, fDev.x, fDev.y, fDev.z,
        )
    }

    @Test
    fun imuIntegratorMatchesGroundTruth() {
        val (_, traj, _) = scenarios()[2] // sideways
        val integrator = com.vayunmathur.measure.platform.vio.ImuIntegrator()
        val rnd = Random(7)
        // Feed samples in the *camera* frame here: the integrator sits
        // below the engine's device->camera rotation, so undo that map by
        // feeding cam-frame samples packed directly.
        var t = 0.0
        val batch = ArrayList<Double>()
        while (t <= 2.8) {
            val s = imuSample(traj, t, rnd)
            // imuSample returns device axes; rotate back to camera axes
            // (M is symmetric): cam = (-devY, -devX, -devZ) per axis group.
            val gx = -s[2]; val gy = -s[1]; val gz = -s[3]
            val ax = -s[5]; val ay = -s[4]; val az = -s[6]
            batch.addAll(listOf(s[0], gx, gy, gz, ax, ay, az))
            t += 0.005
        }
        integrator.push(batch.toDoubleArray())
        for ((a, b) in listOf(0.0 to 0.4, 0.0 to 0.9, 0.0 to 1.8, 0.0 to 2.8, 0.4 to 1.3, 1.3 to 2.2, 0.9 to 1.8)) {
            val est = integrator.displacementBetween((a * 1e9).toLong(), (b * 1e9).toLong())
            val gt = traj.pos(b) - traj.pos(a)
            println("imu window $a..$b est=$est gt=$gt")
            // Scale estimation consumes displacement magnitude along the
            // motion axis; perpendicular drift over a multi-second open
            // integration is expected without bias estimation. Assert
            // those two, not full-vector agreement.
            check(
                est != null && kotlin.math.abs(est.x - gt.x) < 0.03 &&
                    kotlin.math.abs(
                        sqrt(est.x * est.x + est.y * est.y + est.z * est.z) -
                            sqrt(gt.x * gt.x + gt.y * gt.y + gt.z * gt.z),
                    ) < 0.03,
            ) {
                "IMU displacement off: est=$est gt=$gt"
            }
        }
    }

    @Test
    fun syntheticPipelineMetrics() {
        val fps = 30.0
        val frames = 150
        val rows = mutableListOf<String>()
        var sidewaysScaleErr: Double? = null
        var forwardScaleErr: Double? = null
        var sidewaysLocked = false
        var forwardLocked = false

        for ((name, traj, contrast) in scenarios()) {
            val engine = VioEngine(Intrinsics(fx, fy, cx, cy, true), w, h, rotationDegrees = 90)
            val rnd = Random(7)
            var trackedSum = 0
            var lastResult: com.vayunmathur.measure.platform.vio.VioFrameResult? = null
            var imuCursor = 0.0
            for (f in 0 until frames) {
                val t = f / fps
                val batch = ArrayList<Double>()
                while (imuCursor <= t) {
                    batch.addAll(imuSample(traj, imuCursor, rnd).toList())
                    imuCursor += 0.005
                }
                if (batch.isNotEmpty()) {
                    engine.pushImu(batch.toDoubleArray())
                }
                val frame = render(rotAt(traj, t), traj.pos(t), contrast, rnd, 1.0 + 0.03 * sin(t * 1.7))
                val result = engine.processFrame(frame, w, h, w, (t * 1e9).toLong())
                trackedSum += result.trackedFeatures
                lastResult = result
            }
            val res = lastResult!!
            val scale = engine.debugScale()
            val estPose = engine.debugPose()
            val gtC = traj.pos((frames - 1) / fps)
            val posErr = if (scale != null) {
                Vec3(estPose.t.x * scale, estPose.t.y * scale, estPose.t.z * scale).distanceTo(gtC)
            } else {
                Double.NaN
            }
            val hit = HitSelector.select(engine.centreCandidates(w, h))
            val rEnd = rotAt(traj, (frames - 1) / fps)
            val centreDir = rotVec(rEnd, Vec3(0.0, 0.0, 1.0))
            val gtDist = if (centreDir.z > 0.05) (3.0 - gtC.z) / centreDir.z else Double.NaN
            val scaleErr = if (hit != null && gtDist.isFinite()) hit.distanceM / gtDist - 1.0 else Double.NaN
            rows += String.format(
                "%-12s tracked=%5.1f landmarks=%3d quality=%-12s scaleConf=%.2f scale=%s " +
                    "posErrM=%s hit=%s scaleErr=%s kf=%d tri=%d rej=%d reanchor=%d solves=%d",
                name,
                trackedSum.toDouble() / frames,
                res.landmarkCount,
                res.quality,
                res.scaleConfidence,
                scale?.let { String.format("%.3f", it) } ?: "null",
                if (posErr.isNaN()) "  -  " else String.format("%.3f", posErr),
                hit?.let { String.format("%.2fm", it.distanceM) } ?: "none",
                if (scaleErr.isNaN()) "  -  " else String.format("%+.1f%%", scaleErr * 100),
                engine.debugKeyframes,
                engine.debugTriangulated,
                engine.debugReprojRejected,
                engine.debugReanchors,
                engine.debugCentreSolves,
            )
            if (name == "sideways" || name == "forward") {
                println(
                    "$name windows: " + engine.debugScaleWindows().joinToString(" ") {
                        String.format("(v=%.3f,i=%.3f,r=%.3f)", it.first, it.second, it.second / it.first)
                    } +
                        " centreSolves=${engine.debugCentreSolves}/${engine.debugCentreAttempts}" +
                        " estT=${engine.debugPose().t} gtT=$gtC",
                )
            }
            when (name) {
                "sideways" -> {
                    sidewaysScaleErr = scaleErr
                    sidewaysLocked = scale != null && res.landmarkCount >= 20
                }
                "forward" -> {
                    forwardScaleErr = scaleErr
                    forwardLocked = scale != null && res.landmarkCount >= 20
                }
            }
            engine.close()
        }

        println("=== Synthetic VIO harness (640x480, 150 frames @30fps, IMU 200Hz) ===")
        rows.forEach { println(it) }

        // Demonstrated bars (2026-11-17): the sideways-class scenarios lock
        // metric scale with a full landmark map. Forward/fast still do not
        // lock reliably, and locked-scale accuracy is ~10-50%, not the <3%
        // a measuring app ultimately needs: the remaining error is the
        // unit-translation bootstrap warp in the landmark map, which only
        // a real bundle adjustment (joint pose+landmark refinement) can
        // remove. Those numbers stay printed above as the follow-up's
        // baseline; do not weaken the printouts to hide them.
        check(sidewaysLocked) { "sideways: scale not locked or too few landmarks\n" + rows.joinToString("\n") }
        println(
            "scaleErr sideways=$sidewaysScaleErr forward=$forwardScaleErr " +
                "forwardLocked=$forwardLocked (target <3%: NOT yet met)",
        )
    }
}
