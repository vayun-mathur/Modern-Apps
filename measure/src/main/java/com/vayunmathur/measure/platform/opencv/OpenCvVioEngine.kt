package com.vayunmathur.measure.platform.opencv

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
import org.opencv.android.OpenCVLoader
import org.opencv.calib3d.Calib3d
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.core.TermCriteria
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video

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
 * OpenCV visual-inertial tracker behind the AR measurement flow.
 *
 * Pipeline per analysis frame (all on the camera executor thread):
 * Shi-Tomasi corners (`goodFeaturesToTrack`) tracked with pyramidal
 * Lucas-Kanade (`calcOpticalFlowPyrLK`) and a forward-backward
 * consistency check; frame-to-frame motion from
 * `findEssentialMat` + `recoverPose`, chained into a world pose;
 * landmarks by DLT triangulation between keyframes (reprojection and
 * parallax filtered, math in `domain/VioMath`); the dominant surface
 * by RANSAC plane fit; and metric scale by aligning visual
 * displacements with double-integrated IMU displacement windows.
 *
 * Everything visual lives in scale-free units until the IMU scale
 * locks — projection is scale-invariant, so only quoted distances
 * wait for the lock, mirroring the old engine's "no metric scale, no
 * measurements" rule. All OpenCV handles are released per frame; a
 * missing native library degrades [available] to false and the UI
 * falls back to the manual tools instead of crashing.
 */
class OpenCvVioEngine(private val intrinsics: Intrinsics) {

    companion object {
        /** True when the OpenCV native library loaded on this device/host. */
        val available: Boolean by lazy {
            runCatching { OpenCVLoader.initDebug() }.getOrDefault(false)
        }

        private const val VALUES_PER_POINT = 3
        private const val MAX_CORNERS = 220
        private const val MIN_TRACKED_FOR_POSE = 15
        private const val GOOD_TRACKED = 40
        private const val GOOD_LANDMARKS = 20
        private const val KEYFRAME_PARALLAX_PX = 24.0
        private const val MAX_REPROJECTION_PX = 2.0
        private const val FB_ERROR_PX = 1.5
        private const val SCALE_CONFIDENCE_MIN = 0.6
        private const val SCALE_WINDOWS_MIN = 4
        private const val MAX_LANDMARKS = 400
        private const val LOST_AFTER_FAILURES = 3
        private const val PLANE_EXTENT_MARGIN_M = 0.5
    }

    private val cameraMatrix = doubleArrayOf(
        intrinsics.fx, 0.0, intrinsics.cx,
        0.0, intrinsics.fy, intrinsics.cy,
        0.0, 0.0, 1.0,
    )

    private var prevGray: Mat? = null
    private var prevPoints: List<Point> = emptyList()
    private var worldFromCam: Pose3 = Pose3.identity()
    private var keyframePose: Pose3? = null
    private var keyframePoints: List<Point> = emptyList()
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
        val gray = grayMat(yPlane, width, height, rowStride)
        try {
            val prev = prevGray ?: return firstFrame(gray)
            val tracked = track(prev, gray, prevPoints)
            if (tracked == null || tracked.first.size < MIN_TRACKED_FOR_POSE) {
                return trackingFailure(gray, tracked?.first?.size ?: 0)
            }
            val (prevPts, currPts) = tracked
            val rel = recoverRelative(prevPts, currPts)
            if (rel == null) return poseFailure(gray, currPts)
            consecutiveFailures = 0
            worldFromCam = chainPose(worldFromCam, rel)
            updateKeyframes(prevPts, currPts, timestampNs)
            // Top features back up so slow attrition never starves the tracker.
            prevPoints = if (currPts.size < MAX_CORNERS / 2) {
                mergePoints(currPts, detect(gray))
            } else {
                currPts
            }
            prevGray?.release()
            prevGray = gray.clone()
            val q = when {
                scale == null -> TrackingQuality.Initialising
                currPts.size >= GOOD_TRACKED && landmarks.size >= GOOD_LANDMARKS ->
                    TrackingQuality.Good
                else -> TrackingQuality.Limited
            }
            return result(q, currPts.size)
        } finally {
            gray.release()
        }
    }

    private fun firstFrame(gray: Mat): VioFrameResult {
        prevPoints = detect(gray)
        prevGray = gray.clone()
        return result(TrackingQuality.Initialising, prevPoints.size)
    }

    private fun trackingFailure(gray: Mat, trackedCount: Int): VioFrameResult {
        consecutiveFailures++
        prevPoints = detect(gray)
        prevGray?.release()
        prevGray = gray.clone()
        val q = when {
            consecutiveFailures >= LOST_AFTER_FAILURES -> TrackingQuality.Lost
            framesSeen < 30 -> TrackingQuality.Initialising
            else -> TrackingQuality.Limited
        }
        return result(q, trackedCount)
    }

    private fun poseFailure(gray: Mat, currPts: List<Point>): VioFrameResult {
        consecutiveFailures++
        prevPoints = currPts
        prevGray?.release()
        prevGray = gray.clone()
        return result(
            if (consecutiveFailures >= LOST_AFTER_FAILURES) {
                TrackingQuality.Lost
            } else {
                TrackingQuality.Limited
            },
            currPts.size,
        )
    }

    private fun updateKeyframes(prevPts: List<Point>, currPts: List<Point>, timestampNs: Long) {
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
        val s = scale ?: return DoubleArray(world.size / 3 * 3)
        val p = currentProjection()
        val out = DoubleArray((world.size / 3) * 3)
        for (i in 0 until world.size / 3) {
            val visual = Vec3(world[i * 3] / s, world[i * 3 + 1] / s, world[i * 3 + 2] / s)
            val uv = project(p, visual)
            out[i * 3] = (uv?.first ?: 0.0) / width
            out[i * 3 + 1] = (uv?.second ?: 0.0) / height
            out[i * 3 + 2] = if (uv != null) 1.0 else 0.0
        }
        return out
    }

    fun reset() {
        prevGray?.release()
        prevGray = null
        prevPoints = emptyList()
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
        prevGray?.release()
        prevGray = null
    }

    // -- OpenCV stages -------------------------------------------------

    private fun grayMat(yPlane: ByteArray, width: Int, height: Int, rowStride: Int): Mat {
        val mat = Mat(height, width, CvType.CV_8UC1)
        if (rowStride == width) {
            mat.put(0, 0, yPlane, 0, width * height)
        } else {
            val row = ByteArray(width)
            for (r in 0 until height) {
                System.arraycopy(yPlane, r * rowStride, row, 0, width)
                mat.put(r, 0, row)
            }
        }
        return mat
    }

    private fun detect(gray: Mat): List<Point> {
        val corners = org.opencv.core.MatOfPoint()
        try {
            Imgproc.goodFeaturesToTrack(
                gray, corners, MAX_CORNERS, 0.01, 10.0, Mat(), 3, false, 0.04,
            )
            val rough = corners.toList()
            if (rough.isEmpty()) return emptyList()
            // The Java binding returns integer-quantised corners; refine
            // to sub-pixel before LK seeds from them.
            val refined = MatOfPoint2f(*rough.toTypedArray())
            try {
                Imgproc.cornerSubPix(
                    gray, refined, Size(5.0, 5.0), Size(-1.0, -1.0),
                    TermCriteria(TermCriteria.EPS or TermCriteria.COUNT, 30, 0.01),
                )
                return refined.toList()
            } finally {
                refined.release()
            }
        } finally {
            corners.release()
        }
    }

    /** LK tracking with forward-backward check; returns (prev, curr) survivors. */
    private fun track(prev: Mat, curr: Mat, points: List<Point>): Pair<List<Point>, List<Point>>? {
        if (points.isEmpty()) return null
        val prevMat = MatOfPoint2f(*points.toTypedArray())
        val currMat = MatOfPoint2f()
        val backMat = MatOfPoint2f()
        val status = MatOfByte()
        val err = MatOfFloat()
        val backStatus = MatOfByte()
        val backErr = MatOfFloat()
        val criteria = TermCriteria(TermCriteria.EPS or TermCriteria.COUNT, 30, 0.01)
        try {
            Video.calcOpticalFlowPyrLK(
                prev, curr, prevMat, currMat, status, err, Size(21.0, 21.0), 3, criteria,
            )
            Video.calcOpticalFlowPyrLK(
                curr, prev, currMat, backMat, backStatus, backErr, Size(21.0, 21.0), 3, criteria,
            )
            val st = status.toArray()
            val bst = backStatus.toArray()
            val currList = currMat.toList()
            val backList = backMat.toList()
            val outPrev = ArrayList<Point>()
            val outCurr = ArrayList<Point>()
            for (i in points.indices) {
                if (st[i].toInt() == 0 || bst[i].toInt() == 0) continue
                val fb = points[i].distanceTo(backList[i])
                if (fb > FB_ERROR_PX) continue
                outPrev += points[i]
                outCurr += currList[i]
            }
            return outPrev to outCurr
        } catch (e: org.opencv.core.CvException) {
            return null
        } finally {
            prevMat.release(); currMat.release(); backMat.release()
            status.release(); err.release(); backStatus.release(); backErr.release()
        }
    }

    /** Essential-matrix relative pose (A -> B), or null when degenerate. */
    private fun recoverRelative(prevPts: List<Point>, currPts: List<Point>): Pose3? {
        val k = Mat(3, 3, CvType.CV_64F)
        val p1 = MatOfPoint2f(*prevPts.toTypedArray())
        val p2 = MatOfPoint2f(*currPts.toTypedArray())
        val r = Mat()
        val t = Mat()
        val mask = Mat()
        try {
            k.put(0, 0, *cameraMatrix)
            val e = Calib3d.findEssentialMat(p1, p2, k, Calib3d.RANSAC, 0.999, 1.0)
            if (e.empty()) {
                e.release()
                return null
            }
            val inliers = Calib3d.recoverPose(e, p1, p2, k, r, t, mask)
            e.release()
            if (inliers < MIN_TRACKED_FOR_POSE || r.empty() || t.empty()) return null
            val rd = DoubleArray(9)
            r.get(0, 0, rd)
            val td = DoubleArray(3)
            t.get(0, 0, td)
            val tn = sqrt(td[0] * td[0] + td[1] * td[1] + td[2] * td[2])
            if (tn < 1e-9) return null
            // recoverPose translation is unit-length by construction.
            return Pose3(rd, Vec3(td[0] / tn, td[1] / tn, td[2] / tn))
        } catch (e: org.opencv.core.CvException) {
            return null
        } finally {
            k.release(); p1.release(); p2.release(); r.release(); t.release(); mask.release()
        }
    }

    private fun onKeyframe(prevKfPose: Pose3, currPts: List<Point>, timestampNs: Long) {
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
                val a = kfPoints[i].x to kfPoints[i].y
                val b = currPts[i].x to currPts[i].y
                val world = triangulate(pa, a, pb, b) ?: continue
                val errA = reprojectionErrorPx(pa, world, a)
                val errB = reprojectionErrorPx(pb, world, b)
                if (errA > MAX_REPROJECTION_PX || errB > MAX_REPROJECTION_PX) continue
                if (landmarks.any { it.position.distanceTo(world) < 0.01 }) continue
                landmarks += Landmark(world, observations = 2)
                added++
            }
            if (landmarks.size > MAX_LANDMARKS) {
                landmarks.subList(0, landmarks.size - MAX_LANDMARKS).clear()
            }
            if (added > 0) refitPlane()
            updateScaleWindows(timestampNs)
        }
    }

    private fun setKeyframe(points: List<Point>, timestampNs: Long) {
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
            if (visualWindows.size > 12) {
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
        if (landmarks.size < 12) return
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

    private fun meanFlow(a: List<Point>, b: List<Point>): Double {
        if (a.isEmpty()) return 0.0
        val n = minOf(a.size, b.size)
        var sum = 0.0
        for (i in 0 until n) sum += a[i].distanceTo(b[i])
        return sum / n
    }

    private fun mergePoints(tracked: List<Point>, fresh: List<Point>): List<Point> {
        val out = ArrayList(tracked)
        for (p in fresh) {
            if (out.any { it.distanceTo(p) < 10.0 }) continue
            out += p
            if (out.size >= MAX_CORNERS) break
        }
        return out
    }
}

private operator fun Vec3.times(s: Double): Vec3 = Vec3(x * s, y * s, z * s)
private fun Point.distanceTo(other: Point): Double {
    val dx = x - other.x
    val dy = y - other.y
    return sqrt(dx * dx + dy * dy)
}
