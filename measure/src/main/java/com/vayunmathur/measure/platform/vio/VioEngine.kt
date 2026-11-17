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
import com.vayunmathur.measure.domain.triangulate
import com.vayunmathur.measure.platform.Intrinsics
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
private data class Landmark(val position: Vec3, var observations: Int)

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
class VioEngine(private val intrinsics: Intrinsics) {

    /** Native-engine availability and per-frame tuning constants for [VioEngine]. */
    companion object {
        /** True when the in-tree Rust CV library loaded on this device/host. */
        val available: Boolean
            get() = MeasureCvNative.available

        private const val VALUES_PER_POINT = 3
        private const val MIN_TRACKED_FOR_POSE = 15
        private const val GOOD_TRACKED = 40
        private const val GOOD_LANDMARKS = 20
        private const val KEYFRAME_PARALLAX_PX = 24.0
        private const val MAX_REPROJECTION_PX = 2.0
        private const val SCALE_CONFIDENCE_MIN = 0.6
        private const val SCALE_WINDOWS_MIN = 4
        private const val MAX_LANDMARKS = 400
        private const val LOST_AFTER_FAILURES = 3
        private const val PLANE_EXTENT_MARGIN_M = 0.5
        /** Landmarks closer than this (visual units) are the same point. */
        private const val MIN_LANDMARK_SEPARATION = 0.01
        /** Scale-estimate windows kept for the visual/IMU alignment. */
        private const val MAX_SCALE_WINDOWS = 12
        /** Landmarks needed before a dominant plane is worth fitting. */
        private const val MIN_LANDMARKS_FOR_PLANE = 12
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
    private var keyframePoints: List<Pair<Double, Double>> = emptyList()
    private var keyframeTimeNs: Long = 0L

    private val landmarks = ArrayList<Landmark>()
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

    /** Feed the packed IMU batch (`[tNs, gx, gy, gz, ax, ay, az]` per sample). */
    fun pushImu(packed: DoubleArray) {
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
            ?: return trackingFailure(0)
        if (flow.firstFrame) {
            return result(TrackingQuality.Initialising, flow.pointCount)
        }
        if (flow.pairs.size < MIN_TRACKED_FOR_POSE) {
            return trackingFailure(flow.pairs.size)
        }
        val rel = flow.pose ?: return poseFailure(flow.pairs)
        consecutiveFailures = 0
        worldFromCam = chainPose(worldFromCam, rel)
        val prevPts = flow.pairs.map { it.prevX to it.prevY }
        val currPts = flow.pairs.map { it.currX to it.currY }
        updateKeyframes(prevPts, currPts, timestampNs)
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

    private fun updateKeyframes(
        prevPts: List<Pair<Double, Double>>,
        currPts: List<Pair<Double, Double>>,
        timestampNs: Long,
    ) {
        val kf = keyframePose
        if (kf == null) {
            setKeyframe(currPts, timestampNs)
        } else if (meanFlow(prevPts, currPts) >= KEYFRAME_PARALLAX_PX) {
            onKeyframe(kf, currPts, timestampNs)
            setKeyframe(currPts, timestampNs)
        }
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

    fun reset() {
        MeasureCvNative.reset(nativeHandle)
        worldFromCam = Pose3.identity()
        keyframePose = null
        keyframePoints = emptyList()
        landmarks.clear()
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
        lastQuality = TrackingQuality.Initialising
    }

    fun close() {
        MeasureCvNative.destroySession(nativeHandle)
    }

    private fun onKeyframe(prevKfPose: Pose3, currPts: List<Pair<Double, Double>>, timestampNs: Long) {
        val kfPoints = keyframePoints
        if (kfPoints.size >= MIN_TRACKED_FOR_POSE && currPts.size >= MIN_TRACKED_FOR_POSE) {
            // Correspondences survived from the keyframe are the pairs LK
            // has carried; indices align because keyframe points were the
            // previous frame's tracked set when the baseline opened.
            val pa = projectionMatrix(cameraMatrix, prevKfPose)
            val pb = projectionMatrix(cameraMatrix, worldFromCam)
            val pairs = minOf(kfPoints.size, currPts.size)
            var added = 0
            for (i in 0 until pairs) {
                val a = kfPoints[i]
                val b = currPts[i]
                val world = triangulate(pa, a, pb, b)
                if (world != null) {
                    val errA = reprojectionErrorPx(pa, world, a)
                    val errB = reprojectionErrorPx(pb, world, b)
                    val isNewLandmark = landmarks.none {
                        it.position.distanceTo(world) < MIN_LANDMARK_SEPARATION
                    }
                    if (errA <= MAX_REPROJECTION_PX && errB <= MAX_REPROJECTION_PX && isNewLandmark) {
                        landmarks += Landmark(world, observations = 2)
                        added++
                    }
                }
            }
            if (landmarks.size > MAX_LANDMARKS) {
                landmarks.subList(0, landmarks.size - MAX_LANDMARKS).clear()
            }
            if (added > 0) refitPlane()
            updateScaleWindows(timestampNs)
        }
    }

    private fun setKeyframe(points: List<Pair<Double, Double>>, timestampNs: Long) {
        keyframePose = worldFromCam
        keyframePoints = points
        keyframeTimeNs = timestampNs
        lastKeyframeCentre = worldFromCam.t
    }

    private fun updateScaleWindows(timestampNs: Long) {
        val centre = worldFromCam.t
        val last = lastKeyframeCentre ?: return
        val visual = centre - last
        val imuDisp = imu.displacementBetween(
            keyframeTimeNs, timestampNs, worldFromCam.r, worldUp,
        )
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
            }
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

    private fun meanFlow(a: List<Pair<Double, Double>>, b: List<Pair<Double, Double>>): Double {
        if (a.isEmpty()) return 0.0
        val n = minOf(a.size, b.size)
        var sum = 0.0
        for (i in 0 until n) sum += a[i].distanceTo(b[i])
        return sum / n
    }
}

private operator fun Vec3.times(s: Double): Vec3 = Vec3(x * s, y * s, z * s)
private fun Pair<Double, Double>.distanceTo(other: Pair<Double, Double>): Double {
    val dx = first - other.first
    val dy = second - other.second
    return sqrt(dx * dx + dy * dy)
}
