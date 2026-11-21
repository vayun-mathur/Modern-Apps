package com.vayunmathur.measure.platform.vio

import com.vayunmathur.measure.data.model.TrackingQuality
import com.vayunmathur.measure.domain.FittedPlane
import com.vayunmathur.measure.domain.HitCandidate
import com.vayunmathur.measure.domain.HitKind
import com.vayunmathur.measure.domain.Pose3
import com.vayunmathur.measure.domain.Vec3
import com.vayunmathur.measure.domain.chainPose
import com.vayunmathur.measure.domain.estimateScale
import com.vayunmathur.measure.domain.gravityClass
import com.vayunmathur.measure.domain.PlaneClass
import com.vayunmathur.measure.domain.projectionMatrix
import com.vayunmathur.measure.domain.project
import com.vayunmathur.measure.domain.ransacPlane
import com.vayunmathur.measure.domain.reprojectionErrorPx
import com.vayunmathur.measure.domain.rotate3
import com.vayunmathur.measure.domain.solveCameraCentre
import com.vayunmathur.measure.domain.triangulate
import com.vayunmathur.measure.platform.Intrinsics
import com.vayunmathur.library.log.Log
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/** Per-frame result the analyzer forwards to the ViewModel. */
data class VioFrameResult(
    val quality: TrackingQuality,
    val hasPlane: Boolean,
    val trackedFeatures: Int,
    val landmarkCount: Int,
    val scaleConfidence: Double,
)

/** One triangulated landmark, in visual (scale-free) world units. */
private data class Landmark(
    val id: Long,
    val position: Vec3,
    var observations: Int,
    val birthFrame: Int = 0,
)

/**
 * Visual-inertial tracker behind the AR measurement flow.
 *
 * Pipeline per analysis frame (all on the camera executor thread): the
 * in-tree Rust CV core (`measure_cv`, see [MeasureCvNative]) detects
 * Shi-Tomasi corners, tracks them with pyramidal Lucas-Kanade plus a
 * forward-backward consistency check, and recovers frame-to-frame
 * motion from an essential matrix (normalised eight-point + RANSAC +
 * cheirality); that pose is chained into a world pose here. Landmarks
 * by DLT triangulation between keyframes (reprojection and parallax
 * filtered, math in `domain/VioMath`); the dominant surface by RANSAC
 * plane fit; and metric scale by aligning visual displacements with
 * double-integrated IMU displacement windows.
 *
 * Everything visual lives in scale-free units until the IMU scale
 * locks — projection is scale-invariant, so only quoted distances
 * wait for the lock, mirroring the old engine's "no metric scale, no
 * measurements" rule. A missing native library degrades [available]
 * to false and the UI falls back to the manual tools instead of
 * crashing.
 */
class VioEngine(
    private val intrinsics: Intrinsics,
    /** Actual analysis-frame size the intrinsics were resolved for (0 = unknown). */
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    /** Clockwise rotation of the analysis frames vs the display (from ImageProxy). */
    private val rotationDegrees: Int = 0,
) {

    /** Native-engine availability and per-frame tuning constants for [VioEngine]. */
    companion object {
        /** True when the in-tree Rust CV library loaded on this device/host. */
        val available: Boolean
            get() = MeasureCvNative.available

        private const val VALUES_PER_POINT = 3
        private const val MIN_TRACKED_FOR_POSE = 15
        private const val GOOD_TRACKED = 40
        private const val GOOD_LANDMARKS = 20
        private const val KEYFRAME_PARALLAX_PX = 14.0
        private const val MAX_REPROJECTION_PX = 2.0
        private const val SCALE_CONFIDENCE_MIN = 0.6
        private const val SCALE_WINDOWS_MIN = 4
        private const val MAX_LANDMARKS = 400
        private const val MIN_LANDMARKS_FOR_CENTRE = 12
        private const val LOST_AFTER_FAILURES = 3
        private const val PLANE_EXTENT_MARGIN_M = 0.5
        /** Landmarks closer than this (visual units) are the same point. */
        private const val MIN_LANDMARK_SEPARATION = 0.01
        /**
         * Scale-estimate windows kept for the visual/IMU alignment. Only
         * recent windows price the scale: the landmark map's gauge drifts
         * slowly as cohorts are re-triangulated, and a long memory
         * averages stale gauges into the estimate.
         */
        private const val MAX_SCALE_WINDOWS = 6
        /** Landmarks needed before a dominant plane is worth fitting. */
        private const val MIN_LANDMARKS_FOR_PLANE = 12
        const val TAG = "MeasureCv"
    }

    private val cameraMatrix = doubleArrayOf(
        intrinsics.fx, 0.0, intrinsics.cx,
        0.0, intrinsics.fy, intrinsics.cy,
        0.0, 0.0, 1.0,
    )

    /** Rust CV session handle; 0 when the native library is unavailable. */
    private val nativeHandle: Long =
        if (available) {
            MeasureCvNative.createSession(intrinsics.fx, intrinsics.fy, intrinsics.cx, intrinsics.cy)
        } else {
            0L
        }

    private var worldFromCam: Pose3 = Pose3.identity()
    private var keyframePose: Pose3? = null
    /** Keyframe points by stable feature id (see CvPair.id). */
    private var keyframePoints: Map<Long, Pair<Double, Double>> = emptyMap()
    private var keyframeTimeNs: Long = 0L

    private val landmarks = ArrayList<Landmark>()
    private val landmarkById = HashMap<Long, Landmark>()
    private var plane: FittedPlane? = null
    private var planeExtentM: Double = 0.0

    private var scale: Double? = null
    private var scaleConfidence: Double = 0.0
    private val visualWindows = ArrayList<Vec3>()
    private val imuWindows = ArrayList<Vec3>()
    private var lastKeyframeCentre: Vec3? = null

    /** Gravity-up in world coordinates, seeded from the first accelerometer window. */
    private var worldUp: Vec3 = Vec3(0.0, 1.0, 0.0)
    private var upSeeded = false

    private val imu = ImuIntegrator()
    private var consecutiveFailures = 0
    private var framesSeen = 0

    @Volatile private var lastQuality: TrackingQuality = TrackingQuality.Initialising

    /**
     * Device->camera rotation for the accel/gyro axes, as a row-major 3x3.
     *
     * SensorEvent values are in Android device axes (x right, y up, z out
     * of the screen); the visual pose lives in raw camera-image axes
     * (x = image right, y = image down, z into the scene). The fixed part
     * maps device->image axes at zero rotation, `(ax, -ay, -az)`; the
     * in-plane part then rotates by the frame rotation. Without this the
     * integrator's gravity subtraction uses a "camera frame" the camera
     * never had: on a still phone it double-integrated metres of phantom
     * displacement from un-cancelled gravity, the visual/IMU ratios
     * scattered, and scale confidence stayed at 0 forever (device logs,
     * 2026-11-17).
     */
    private val deviceToCameraR: DoubleArray = run {
        val theta = Math.toRadians(-rotationDegrees.toDouble())
        val c = kotlin.math.cos(theta)
        val s = kotlin.math.sin(theta)
        // Rz(-rotation) * diag(1, -1, -1), expanded.
        doubleArrayOf(c, s, 0.0, s, -c, 0.0, 0.0, 0.0, -1.0)
    }

    /** Feed the packed IMU batch (`[tNs, gx, gy, gz, ax, ay, az]` per sample). */
    fun pushImu(packed: DoubleArray) {
        // Re-express gyro+accel in camera axes before integration.
        val r = deviceToCameraR
        run {
            var i = 0
            while (i + 7 <= packed.size) {
                for (base in intArrayOf(i + 1, i + 4)) {
                    val x = packed[base]
                    val y = packed[base + 1]
                    val z = packed[base + 2]
                    packed[base] = r[0] * x + r[1] * y + r[2] * z
                    packed[base + 1] = r[3] * x + r[4] * y + r[5] * z
                    packed[base + 2] = r[6] * x + r[7] * y + r[8] * z
                }
                i += 7
            }
        }
        imu.push(packed)
        if (!upSeeded) {
            imu.meanAccelCam()?.let { a ->
                val len = sqrt(a.x * a.x + a.y * a.y + a.z * a.z)
                if (len > 1.0) {
                    // Accelerometer reads gravity reaction: up is -a, in the
                    // first camera frame, which is the world frame by fiat.
                    worldUp = Vec3(-a.x / len, -a.y / len, -a.z / len)
                    upSeeded = true
                }
            }
        }
    }

    fun processFrame(
        yPlane: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
        timestampNs: Long,
    ): VioFrameResult {
        framesSeen++
        val flow = MeasureCvNative.processFrame(nativeHandle, yPlane, width, height, rowStride)
        return processFlow(flow, width, height, rowStride, timestampNs)
    }

    /**
     * Direct-buffer overload: the CameraX Y plane is de-strided natively
     * from its own address, with no per-frame Kotlin copy of the plane.
     */
    fun processFrame(
        yPlane: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        timestampNs: Long,
    ): VioFrameResult {
        framesSeen++
        val flow = MeasureCvNative.processFrame(nativeHandle, yPlane, width, height, rowStride)
        return processFlow(flow, width, height, rowStride, timestampNs)
    }

    private fun processFlow(
        flowOrNull: CvFrameFlow?,
        width: Int,
        height: Int,
        rowStride: Int,
        timestampNs: Long,
    ): VioFrameResult {
        val flow = flowOrNull
            ?: run {
                if (framesSeen % 30 == 1) Log.dev(TAG, "frame=$framesSeen native=null handle=$nativeHandle avail=$available")
                return trackingFailure(0)
            }
        if (framesSeen % 30 == 1 || flow.firstFrame) {
            Log.dev(
                TAG,
                "frame=$framesSeen ${width}x$height stride=$rowStride first=${flow.firstFrame} " +
                    "seeded=${flow.seeded} cornersNext=${flow.pointCount} fwdOk=${flow.forwardOk} " +
                    "fb=${flow.fbSurvivors} pairs=${flow.pairs.size} ransac=${flow.ransacInliers} " +
                    "pose=${flow.pose != null} landmarks=${landmarks.size} scaleConf=$scaleConfidence scale=$scale",
            )
        }
        if (flow.firstFrame) {
            return result(TrackingQuality.Initialising, flow.pointCount)
        }
        if (flow.pairs.size < MIN_TRACKED_FOR_POSE) {
            return trackingFailure(flow.pairs.size)
        }
        val rel = flow.pose ?: return poseFailure(flow.pairs)
        consecutiveFailures = 0
        worldFromCam = chainPose(worldFromCam, rel)
        // Once a landmark map exists, replace the chained centre with a
        // centre solved against the map: chained centres accumulate one
        // *unit* translation per frame (essential poses have no scale),
        // which encodes frame count rather than distance and made every
        // IMU/visual scale ratio depend on walking speed. Rotation from
        // the chain is unaffected and is kept.
        if (landmarks.size >= MIN_LANDMARKS_FOR_CENTRE) {
            val matched = ArrayList<Pair<Landmark, CvPair>>()
            for (pair in flow.pairs) {
                val lm = landmarkById[pair.id] ?: continue
                matched += lm to pair
                lm.observations++
            }
            // Prefer well-observed landmarks: young ones were triangulated
            // from early poses and can sit in a distorted part of the map.
            val mature = matched.filter { it.first.observations >= 4 }
            val use = if (mature.size >= MIN_LANDMARKS_FOR_CENTRE) mature else matched
            val observations = use.map { (lm, pair) ->
                val bearingCam = Vec3(
                    (pair.currX - intrinsics.cx) / intrinsics.fx,
                    (pair.currY - intrinsics.cy) / intrinsics.fy,
                    1.0,
                )
                lm.position to rotate3(worldFromCam.r, bearingCam)
            }
            debugCentreAttempts++
            val solved = solveCameraCentre(observations)
            if (solved != null) {
                debugCentreSolves++
                if (centreSolveBirthFrame < 0) centreSolveBirthFrame = framesSeen
                // One-time map heal: landmarks triangulated before centre
                // solving existed were placed from unit-chained (time-
                // warped) poses, so each early cohort is a differently
                // warped copy of the scene and the solved centre jumps as
                // cohorts rotate. Once solving is established, drop the
                // pre-solve map; it rebuilds from solved poses only.
                if (!preSolveMapPurged && framesSeen - centreSolveBirthFrame > 20) {
                    preSolveMapPurged = true
                    val before = landmarks.size
                    landmarks.removeAll { it.birthFrame < centreSolveBirthFrame }
                    landmarkById.clear()
                    landmarks.forEach { landmarkById[it.id] = it }
                    // Scale windows spanning the warp are meaningless.
                    visualWindows.clear()
                    imuWindows.clear()
                    scale = null
                    scaleConfidence = 0.0
                    Log.dev(TAG, "map heal: purged ${before - landmarks.size} pre-solve landmarks")
                }
                if (framesSeen % 30 == 1) {
                    Log.dev(TAG, "centre solve obs=${observations.size} chained=${worldFromCam.t} solved=$solved")
                }
                worldFromCam = Pose3(worldFromCam.r, solved)
                // Keep the last few solved centres: their average poses
                // keyframes and scale windows. A single frame's solve
                // wanders by more than a window's real motion (cohort-
                // correlated landmark depth errors), and triangulating
                // from such a pose poisons the next cohort's depths.
            }
        }
        val currPts = flow.pairs.map { it.currX to it.currY }
        updateKeyframes(flow.pairs, timestampNs)
        val q = when {
            scale == null -> TrackingQuality.Initialising
            currPts.size >= GOOD_TRACKED && landmarks.size >= GOOD_LANDMARKS ->
                TrackingQuality.Good
            else -> TrackingQuality.Limited
        }
        return result(q, currPts.size)
    }

    private fun trackingFailure(trackedCount: Int): VioFrameResult {
        consecutiveFailures++
        val q = when {
            consecutiveFailures >= LOST_AFTER_FAILURES -> TrackingQuality.Lost
            framesSeen < 30 -> TrackingQuality.Initialising
            else -> TrackingQuality.Limited
        }
        return result(q, trackedCount)
    }

    private fun poseFailure(pairs: List<CvPair>): VioFrameResult {
        consecutiveFailures++
        return result(
            if (consecutiveFailures >= LOST_AFTER_FAILURES) {
                TrackingQuality.Lost
            } else {
                TrackingQuality.Limited
            },
            pairs.size,
        )
    }

    private fun updateKeyframes(pairs: List<CvPair>, timestampNs: Long) {
        val kf = keyframePose
        if (kf == null) {
            setKeyframe(pairs, timestampNs)
            return
        }
        // Parallax is accumulated keyframe->current flow for the *same*
        // features (matched by stable id), not one frame's flow: a slow
        // scanning pan moves only a few px/frame, so the old per-frame
        // 24px gate never fired and no keyframe after the first existed.
        val matched = pairs.filter { keyframePoints.containsKey(it.id) }
        if (matched.size < MIN_TRACKED_FOR_POSE) {
            // Keyframe features are gone; re-anchor on the current set.
            debugReanchors++
            Log.dev(TAG, "keyframe re-anchor: only ${matched.size} kf features survive")
            setKeyframe(pairs, timestampNs)
        } else if (meanKfFlow(matched) >= KEYFRAME_PARALLAX_PX) {
            debugKeyframes++
            onKeyframe(kf, matched, timestampNs)
            setKeyframe(pairs, timestampNs)
        }
    }

    private fun meanKfFlow(matched: List<CvPair>): Double {
        if (matched.isEmpty()) return 0.0
        var sum = 0.0
        for (p in matched) {
            val kfPt = keyframePoints[p.id] ?: continue
            sum += kfPt.distanceTo(p.currX to p.currY)
        }
        return sum / matched.size
    }

    private fun result(quality: TrackingQuality, tracked: Int): VioFrameResult {
        lastQuality = quality
        return VioFrameResult(
            quality = quality,
            hasPlane = plane != null,
            trackedFeatures = tracked,
            landmarkCount = landmarks.size,
            scaleConfidence = scaleConfidence,
        )
    }

    /** Centre-ray hit candidates: landmarks plus the fitted-plane intersection. */
    fun centreCandidates(width: Int, height: Int): List<HitCandidate> {
        val s = scale ?: return emptyList()
        val p = currentProjection()
        val centre = (width / 2.0) to (height / 2.0)
        val camCentre = worldFromCam.t
        val out = ArrayList<HitCandidate>()
        for (lm in landmarks) {
            val uv = project(p, lm.position) ?: continue
            val du = uv.first - centre.first
            val dv = uv.second - centre.second
            val angleDeg = Math.toDegrees(atan2(sqrt(du * du + dv * dv), intrinsics.fx))
            val distVisual = camCentre.distanceTo(lm.position)
            out += HitCandidate(
                kind = HitKind.FeaturePoint,
                position = lm.position * s,
                distanceM = distVisual * s,
                angularErrorDeg = angleDeg,
            )
        }
        plane?.let { pl ->
            val dir = centreRayWorld()
            val hit = pl.intersectRay(camCentre, dir) ?: return@let
            // Extent check: the infinite plane is not the surface. Only
            // hits near the observed inlier cloud may be placed on.
            if (hit.distanceTo(pl.point) <= planeExtentM) {
                out += HitCandidate(
                    kind = HitKind.Plane,
                    position = hit * s,
                    distanceM = camCentre.distanceTo(hit) * s,
                    angularErrorDeg = 0.0,
                    normalDotUp = abs(
                        pl.normal.x * worldUp.x + pl.normal.y * worldUp.y +
                            pl.normal.z * worldUp.z,
                    ),
                )
            }
        }
        return out
    }

    /**
     * Project metric world points to normalised screen coordinates
     * (`[nx, ny, visible]` per point), the contract the overlay has
     * always consumed. [width]/[height] are analysis-stream pixels.
     */
    fun projectPoints(world: DoubleArray, width: Double, height: Double): DoubleArray {
        if (world.isEmpty()) return DoubleArray(0)
        val s = scale ?: return DoubleArray(world.size / VALUES_PER_POINT * VALUES_PER_POINT)
        val p = currentProjection()
        val out = DoubleArray((world.size / VALUES_PER_POINT) * VALUES_PER_POINT)
        for (i in 0 until world.size / VALUES_PER_POINT) {
            val visual = Vec3(
                world[i * VALUES_PER_POINT] / s,
                world[i * VALUES_PER_POINT + 1] / s,
                world[i * VALUES_PER_POINT + 2] / s,
            )
            val uv = project(p, visual)
            out[i * VALUES_PER_POINT] = (uv?.first ?: 0.0) / width
            out[i * VALUES_PER_POINT + 1] = (uv?.second ?: 0.0) / height
            out[i * VALUES_PER_POINT + 2] = if (uv != null) 1.0 else 0.0
        }
        return out
    }

    /** Harness/diagnostics accessors: current world pose and locked scale. */
    internal fun debugPose(): Pose3 = worldFromCam
    internal fun debugScale(): Double? = scale

    internal var debugCentreSolves = 0
    internal var debugCentreAttempts = 0
    internal var debugKeyframes = 0
    internal var debugTriangulated = 0
    internal var debugReprojRejected = 0
    internal var debugReanchors = 0

    /** Frame of the first successful centre solve; -1 until then. */
    private var centreSolveBirthFrame = -1
    private var preSolveMapPurged = false

    /** Per-window (visualMag, imuMag) pairs feeding the scale estimator. */
    internal fun debugScaleWindows(): List<Pair<Double, Double>> =
        visualWindows.zip(imuWindows) { v, m ->
            val vm = sqrt(v.x * v.x + v.y * v.y + v.z * v.z)
            val im = sqrt(m.x * m.x + m.y * m.y + m.z * m.z)
            vm to im
        }

    fun reset() {
        MeasureCvNative.reset(nativeHandle)
        worldFromCam = Pose3.identity()
        keyframePose = null
        keyframePoints = emptyMap()
        landmarks.clear()
        landmarkById.clear()
        plane = null
        planeExtentM = 0.0
        scale = null
        scaleConfidence = 0.0
        visualWindows.clear()
        imuWindows.clear()
        lastKeyframeCentre = null
        imu.reset()
        consecutiveFailures = 0
        framesSeen = 0
        centreSolveBirthFrame = -1
        preSolveMapPurged = false
        debugCentreSolves = 0
        debugCentreAttempts = 0
        debugKeyframes = 0
        debugTriangulated = 0
        debugReprojRejected = 0
        debugReanchors = 0
        lastQuality = TrackingQuality.Initialising
    }

    fun close() {
        MeasureCvNative.destroySession(nativeHandle)
    }

    private fun onKeyframe(prevKfPose: Pose3, matched: List<CvPair>, timestampNs: Long) {
        val kfPoints = keyframePoints
        if (kfPoints.size >= MIN_TRACKED_FOR_POSE && matched.size >= MIN_TRACKED_FOR_POSE) {
            // Correspondences are matched by stable feature id: the same
            // scene point in the keyframe and in the current frame. (The
            // old code paired list indices, which name different points
            // as soon as LK/FB/RANSAC filtering drops or top-up inserts
            // any point, so every triangulation failed its reprojection
            // gate and no landmark was ever created.)
            // Keyframe-local refinement (a two-view bundle adjustment):
            // triangulate the matched bearings, re-solve both keyframe
            // centres against those points, and repeat. Poses and
            // landmarks become mutually consistent within the window
            // instead of each cohort inheriting the single-frame centre
            // noise (or smoothing lag) of the poses that created it —
            // the inconsistency that scattered scale ratios ~3x.
            var poseA = prevKfPose
            var poseB = worldFromCam
            var refinedWorld: List<Pair<CvPair, Vec3>> = emptyList()
            repeat(3) {
                val paR = projectionMatrix(cameraMatrix, poseA)
                val pbR = projectionMatrix(cameraMatrix, poseB)
                val tri = ArrayList<Pair<CvPair, Vec3>>()
                for (pair in matched) {
                    val a = kfPoints[pair.id] ?: continue
                    val b = pair.currX to pair.currY
                    val world = triangulate(paR, a, pbR, b) ?: continue
                    val errA = reprojectionErrorPx(paR, world, a)
                    val errB = reprojectionErrorPx(pbR, world, b)
                    if (errA > MAX_REPROJECTION_PX || errB > MAX_REPROJECTION_PX) continue
                    tri += pair to world
                }
                refinedWorld = tri
                if (tri.size < MIN_TRACKED_FOR_POSE) return@repeat
                val obsA = tri.map { (pair, world) ->
                    val a = kfPoints[pair.id]!!
                    val bearing = Vec3(
                        (a.first - intrinsics.cx) / intrinsics.fx,
                        (a.second - intrinsics.cy) / intrinsics.fy,
                        1.0,
                    )
                    world to rotate3(poseA.r, bearing)
                }
                val obsB = tri.map { (pair, world) ->
                    val bearing = Vec3(
                        (pair.currX - intrinsics.cx) / intrinsics.fx,
                        (pair.currY - intrinsics.cy) / intrinsics.fy,
                        1.0,
                    )
                    world to rotate3(poseB.r, bearing)
                }
                solveCameraCentre(obsA)?.let { poseA = Pose3(poseA.r, it) }
                solveCameraCentre(obsB)?.let { poseB = Pose3(poseB.r, it) }
            }
            worldFromCam = poseB
            var added = 0
            val reprojRejected = matched.size - refinedWorld.size
            debugReprojRejected += reprojRejected
            for ((pair, world) in refinedWorld) {
                val existing = landmarkById[pair.id]
                if (existing != null) {
                    // Re-triangulate with the current (map-solved) poses:
                    // landmarks born from early unit-chained poses carry
                    // that distortion until refreshed like this.
                    landmarks.remove(existing)
                    val refreshed = Landmark(pair.id, world, existing.observations + 1, framesSeen)
                    landmarks += refreshed
                    landmarkById[pair.id] = refreshed
                    continue
                }
                if (landmarks.any { it.position.distanceTo(world) < MIN_LANDMARK_SEPARATION }) continue
                val lm = Landmark(pair.id, world, observations = 2, birthFrame = framesSeen)
                landmarks += lm
                debugTriangulated++
                landmarkById[lm.id] = lm
                added++
            }
            if (landmarks.size > MAX_LANDMARKS) {
                // Evict the least-observed landmarks first: early,
                // pre-map triangulations are exactly the ones with few
                // confirming observations.
                landmarks.sortBy { it.observations }
                landmarks.subList(0, landmarks.size - MAX_LANDMARKS).clear()
                landmarkById.clear()
                landmarks.forEach { landmarkById[it.id] = it }
            }
            Log.dev(
                TAG,
                "keyframe kfPts=${kfPoints.size} matched=${matched.size} triangulated=$added " +
                    "reprojRejected=$reprojRejected landmarks=${landmarks.size} plane=${plane != null}",
            )
            if (added > 0) refitPlane()
            updateScaleWindows(timestampNs)
        }
    }

    private fun setKeyframe(points: List<CvPair>, timestampNs: Long) {
        keyframePose = worldFromCam
        keyframePoints = points.associate { it.id to (it.currX to it.currY) }
        keyframeTimeNs = timestampNs
        lastKeyframeCentre = worldFromCam.t
    }

    private fun updateScaleWindows(timestampNs: Long) {
        val centre = worldFromCam.t
        val last = lastKeyframeCentre ?: return
        val visual = centre - last
        val imuDisp = imu.displacementBetween(keyframeTimeNs, timestampNs)
        if (imuDisp != null) {
            visualWindows += visual
            imuWindows += imuDisp
            if (visualWindows.size > MAX_SCALE_WINDOWS) {
                visualWindows.removeAt(0)
                imuWindows.removeAt(0)
            }
            estimateScale(visualWindows, imuWindows)?.let { est ->
                scaleConfidence = est.confidence
                if (est.pairs >= SCALE_WINDOWS_MIN && est.confidence >= SCALE_CONFIDENCE_MIN) {
                    scale = est.scale
                }
                Log.dev(
                    TAG,
                    "scale windows=${visualWindows.size} pairs=${est.pairs} conf=${est.confidence} " +
                        "scale=${est.scale} locked=${scale != null} visual=$visual imu=$imuDisp",
                )
            } ?: Log.dev(TAG, "scale windows=${visualWindows.size} estimate=null imuDisp=$imuDisp")
        } else {
            Log.dev(TAG, "scale imuDisp=null window ${keyframeTimeNs}..$timestampNs (too few IMU samples?)")
        }
        lastKeyframeCentre = centre
    }

    private fun refitPlane() {
        if (landmarks.size < MIN_LANDMARKS_FOR_PLANE) return
        val fitted = ransacPlane(landmarks.map { it.position }) ?: return
        val (pl, inliers) = fitted
        if (gravityClass(pl.normal, worldUp) == PlaneClass.Slanted) return
        plane = pl
        // Observed extent: farthest inlier from the centroid, plus slack.
        planeExtentM = inliers.maxOfOrNull { it.distanceTo(pl.point) }
            ?.plus(PLANE_EXTENT_MARGIN_M) ?: 0.0
    }

    private fun currentProjection(): DoubleArray =
        projectionMatrix(cameraMatrix, worldFromCam)

    /** World-space direction of the centre-pixel ray. */
    private fun centreRayWorld(): Vec3 {
        // The centre pixel back-projects onto the optical axis, so the
        // camera-space ray is +Z; rotate it into the world frame.
        val camDir = Vec3(0.0, 0.0, 1.0)
        val worldDir = rotate3(worldFromCam.r, camDir)
        val len = sqrt(worldDir.x * worldDir.x + worldDir.y * worldDir.y + worldDir.z * worldDir.z)
        return Vec3(worldDir.x / len, worldDir.y / len, worldDir.z / len)
    }

}

private operator fun Vec3.times(s: Double): Vec3 = Vec3(x * s, y * s, z * s)
private fun Pair<Double, Double>.distanceTo(other: Pair<Double, Double>): Double {
    val dx = first - other.first
    val dy = second - other.second
    return sqrt(dx * dx + dy * dy)
}
