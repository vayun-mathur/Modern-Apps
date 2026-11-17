package com.vayunmathur.measure.domain

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Pure visual-inertial geometry behind the Rust CV tracker.
 *
 * The Rust core (in `platform/vio/`, crate `measure_cv`) does the
 * per-frame image work — Shi-Tomasi corners, Lucas-Kanade flow,
 * essential-matrix recovery. The
 * bookkeeping that decides whether a measurement may be trusted lives
 * here instead: pose chaining, triangulation, plane fitting and the
 * IMU/visual scale alignment. Keeping it free of native types makes
 * every one of those decisions unit-testable with synthetic ground
 * truth, which is exactly what the home-grown Rust engine never had.
 *
 * Matrices are row-major `DoubleArray(9)` rotations; projection
 * matrices are row-major `DoubleArray(12)` (3x4).
 */

/** A rigid transform: `world = r * local + t`. */
class Pose3(val r: DoubleArray, val t: Vec3) {
    fun toWorld(p: Vec3): Vec3 {
        val x = r[0] * p.x + r[1] * p.y + r[2] * p.z + t.x
        val y = r[3] * p.x + r[4] * p.y + r[5] * p.z + t.y
        val z = r[6] * p.x + r[7] * p.y + r[8] * p.z + t.z
        return Vec3(x, y, z)
    }

    /** This pose expressed in the parent frame: points map local -> parent. */
    fun inverse(): Pose3 {
        val rt = transpose3(r)
        val it = Vec3(
            -(rt[0] * t.x + rt[1] * t.y + rt[2] * t.z),
            -(rt[3] * t.x + rt[4] * t.y + rt[5] * t.z),
            -(rt[6] * t.x + rt[7] * t.y + rt[8] * t.z),
        )
        return Pose3(rt, it)
    }

    /** Factory for [Pose3] values, including the identity transform. */
    companion object {
        fun identity(): Pose3 = Pose3(
            doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0),
            Vec3(0.0, 0.0, 0.0),
        )
    }
}

fun transpose3(m: DoubleArray): DoubleArray = doubleArrayOf(
    m[0], m[3], m[6],
    m[1], m[4], m[7],
    m[2], m[5], m[8],
)

fun multiply3(a: DoubleArray, b: DoubleArray): DoubleArray {
    val out = DoubleArray(9)
    for (row in 0..2) {
        for (col in 0..2) {
            out[row * 3 + col] =
                a[row * 3] * b[col] + a[row * 3 + 1] * b[3 + col] + a[row * 3 + 2] * b[6 + col]
        }
    }
    return out
}

fun rotate3(m: DoubleArray, p: Vec3): Vec3 = Vec3(
    m[0] * p.x + m[1] * p.y + m[2] * p.z,
    m[3] * p.x + m[4] * p.y + m[5] * p.z,
    m[6] * p.x + m[7] * p.y + m[8] * p.z,
)

/**
 * Chain a world-from-A camera pose with a relative A->B transform (the
 * shape `recoverPose` returns: points written in A coordinates come out
 * in B coordinates) into the world-from-B pose.
 */
fun chainPose(worldFromA: Pose3, relAtoB: Pose3): Pose3 {
    val rwb = multiply3(worldFromA.r, transpose3(relAtoB.r))
    val t = rotate3(rwb, relAtoB.t)
    return Pose3(rwb, Vec3(worldFromA.t.x - t.x, worldFromA.t.y - t.y, worldFromA.t.z - t.z))
}

/** 3x4 projection matrix `K * [R | t]` for a camera with world pose [worldFromCam]. */
fun projectionMatrix(k: DoubleArray, worldFromCam: Pose3): DoubleArray {
    val inv = worldFromCam.inverse()
    val rt = DoubleArray(12)
    for (row in 0..2) {
        for (col in 0..2) rt[row * 4 + col] = inv.r[row * 3 + col]
        rt[row * 4 + 3] = inv.t.let { if (row == 0) it.x else if (row == 1) it.y else it.z }
    }
    val out = DoubleArray(12)
    for (row in 0..2) {
        for (col in 0..3) {
            out[row * 4 + col] =
                k[row * 3] * rt[col] + k[row * 3 + 1] * rt[4 + col] + k[row * 3 + 2] * rt[8 + col]
        }
    }
    return out
}

/** Project a world point to pixels through [p]; null when behind the camera. */
fun project(p: DoubleArray, world: Vec3): Pair<Double, Double>? {
    val w = p[8] * world.x + p[9] * world.y + p[10] * world.z + p[11]
    if (w <= 1e-9) return null
    val u = (p[0] * world.x + p[1] * world.y + p[2] * world.z + p[3]) / w
    val v = (p[4] * world.x + p[5] * world.y + p[6] * world.z + p[7]) / w
    return u to v
}

/**
 * Linear (DLT) triangulation of one point seen at pixels [a] and [b]
 * through projection matrices [pa] and [pb]. Solved as the smallest
 * eigenvector of the 4x4 normal matrix via Jacobi iteration — no SVD
 * dependency, and exact enough behind the reprojection-error filter
 * the caller applies.
 */
fun triangulate(pa: DoubleArray, a: Pair<Double, Double>, pb: DoubleArray, b: Pair<Double, Double>): Vec3? {
    val rows = arrayOf(
        doubleArrayOf(
            a.first * pa[8] - pa[0], a.first * pa[9] - pa[1],
            a.first * pa[10] - pa[2], a.first * pa[11] - pa[3],
        ),
        doubleArrayOf(
            a.second * pa[8] - pa[4], a.second * pa[9] - pa[5],
            a.second * pa[10] - pa[6], a.second * pa[11] - pa[7],
        ),
        doubleArrayOf(
            b.first * pb[8] - pb[0], b.first * pb[9] - pb[1],
            b.first * pb[10] - pb[2], b.first * pb[11] - pb[3],
        ),
        doubleArrayOf(
            b.second * pb[8] - pb[4], b.second * pb[9] - pb[5],
            b.second * pb[10] - pb[6], b.second * pb[11] - pb[7],
        ),
    )
    val ata = DoubleArray(16)
    for (i in 0..3) {
        for (j in 0..3) {
            ata[i * 4 + j] = rows.sumOf { row -> row[i] * row[j] }
        }
    }
    val x = smallestEigenvectorSymmetric(ata, 4) ?: return null
    if (abs(x[3]) < 1e-12) return null
    val out = Vec3(x[0] / x[3], x[1] / x[3], x[2] / x[3])
    return out.takeIf { it.isFinite() }
}

/** Reprojection error in pixels of [world] seen through [p] at pixel [px]. */
fun reprojectionErrorPx(p: DoubleArray, world: Vec3, px: Pair<Double, Double>): Double {
    val uv = project(p, world) ?: return Double.POSITIVE_INFINITY
    val du = uv.first - px.first
    val dv = uv.second - px.second
    return sqrt(du * du + dv * dv)
}

/** Smallest-eigenvalue eigenvector of a symmetric [n]x[n] matrix (Jacobi sweeps). */
internal fun smallestEigenvectorSymmetric(input: DoubleArray, n: Int): DoubleArray? {
    val a = input.copyOf()
    val v = DoubleArray(n * n) { i -> if (i % (n + 1) == 0) 1.0 else 0.0 }
    repeat(100) {
        var off = 0.0
        for (i in 0 until n) for (j in i + 1 until n) off += a[i * n + j] * a[i * n + j]
        if (off < 1e-24) return@repeat
        for (p in 0 until n - 1) {
            for (q in p + 1 until n) jacobiRotate(a, v, n, p, q)
        }
    }
    var minIdx = 0
    for (i in 1 until n) if (a[i * n + i] < a[minIdx * n + minIdx]) minIdx = i
    val out = DoubleArray(n) { row -> v[row * n + minIdx] }
    val norm = sqrt(out.sumOf { it * it })
    if (norm < 1e-12) return null
    return DoubleArray(n) { out[it] / norm }
}

/** One Jacobi rotation zeroing `a[p][q]` in the eigensolver above. */
private fun jacobiRotate(a: DoubleArray, v: DoubleArray, n: Int, p: Int, q: Int) {
    val apq = a[p * n + q]
    if (abs(apq) < 1e-15) return
    val app = a[p * n + p]
    val aqq = a[q * n + q]
    val phi = 0.5 * kotlin.math.atan2(2 * apq, aqq - app + 1e-300)
    val c = kotlin.math.cos(phi)
    val s = kotlin.math.sin(phi)
    for (k in 0 until n) {
        val akp = a[k * n + p]
        val akq = a[k * n + q]
        a[k * n + p] = c * akp - s * akq
        a[k * n + q] = s * akp + c * akq
    }
    for (k in 0 until n) {
        val apk = a[p * n + k]
        val aqk = a[q * n + k]
        a[p * n + k] = c * apk - s * aqk
        a[q * n + k] = s * apk + c * aqk
    }
    for (k in 0 until n) {
        val vkp = v[k * n + p]
        val vkq = v[k * n + q]
        v[k * n + p] = c * vkp - s * vkq
        v[k * n + q] = s * vkp + c * vkq
    }
}

/**
 * Camera centre from bearing observations of known landmarks: each
 * observation says the centre lies on the ray `X - mu * d` (world ray
 * direction [d] through landmark [X]), i.e. `(I - d d^T)(C - X) = 0`.
 * Least squares over all observations (3x3 normal equations).
 *
 * This is what makes the visual trajectory metrically *consistent* (one
 * global scale): pairwise essential poses only give unit translations,
 * so a chained trajectory encodes frame count, not distance, and no
 * IMU/visual window ratio can ever converge on it. Solving the centre
 * against the triangulated map puts every frame in map units instead.
 */
fun solveCameraCentre(
    observations: List<Pair<Vec3, Vec3>>,
): Vec3? {
    if (observations.size < 4) return null
    val first = solveCentreLeastSquares(observations) ?: return null
    // Two-pass outlier rejection: landmarks triangulated from early,
    // pre-map poses can be badly placed; one inconsistent subset must
    // not drag the centre. Residual = point-to-ray distance at the solve.
    val residuals = observations.map { (x, d) ->
        val rel = centreResidual(first, x, d)
        rel
    }
    val median = residuals.sorted()[residuals.size / 2]
    val keep = observations.filterIndexed { i, _ -> residuals[i] <= maxOf(3 * median, 0.02) }
    if (keep.size >= 4 && keep.size < observations.size) {
        return solveCentreLeastSquares(keep) ?: first
    }
    return first
}

/** Perpendicular distance from landmark [x] to the ray centre->landmark direction [dRaw]. */
private fun centreResidual(centre: Vec3, x: Vec3, dRaw: Vec3): Double {
    val len = sqrt(dRaw.x * dRaw.x + dRaw.y * dRaw.y + dRaw.z * dRaw.z)
    if (len < 1e-12) return Double.MAX_VALUE
    val rel = x - centre
    val along = (rel.x * dRaw.x + rel.y * dRaw.y + rel.z * dRaw.z) / len
    val perp = Vec3(rel.x - dRaw.x / len * along, rel.y - dRaw.y / len * along, rel.z - dRaw.z / len * along)
    return sqrt(perp.x * perp.x + perp.y * perp.y + perp.z * perp.z)
}

private fun solveCentreLeastSquares(
    observations: List<Pair<Vec3, Vec3>>,
): Vec3? {
    if (observations.size < 4) return null
    val ata = DoubleArray(9)
    val atb = DoubleArray(3)
    for ((x, dRaw) in observations) {
        val len = sqrt(dRaw.x * dRaw.x + dRaw.y * dRaw.y + dRaw.z * dRaw.z)
        if (len < 1e-12) continue
        val d = doubleArrayOf(dRaw.x / len, dRaw.y / len, dRaw.z / len)
        // A = I - d d^T; accumulate A^T A (=A, symmetric idempotent-ish) and A^T (A X) = A X.
        val a = DoubleArray(9) { i ->
            val rc = i / 3
            val cc = i % 3
            (if (rc == cc) 1.0 else 0.0) - d[rc] * d[cc]
        }
        val ax = Vec3(
            a[0] * x.x + a[1] * x.y + a[2] * x.z,
            a[3] * x.x + a[4] * x.y + a[5] * x.z,
            a[6] * x.x + a[7] * x.y + a[8] * x.z,
        )
        for (i in 0..8) ata[i] += a[i]
        atb[0] += ax.x; atb[1] += ax.y; atb[2] += ax.z
    }
    // Solve ata * C = atb by Gaussian elimination with partial pivoting.
    val m = Array(3) { r -> doubleArrayOf(ata[r * 3], ata[r * 3 + 1], ata[r * 3 + 2], atb[r]) }
    for (col in 0..2) {
        var pivot = col
        for (r in col + 1..2) if (abs(m[r][col]) > abs(m[pivot][col])) pivot = r
        if (abs(m[pivot][col]) < 1e-12) return null
        val tmp = m[col]; m[col] = m[pivot]; m[pivot] = tmp
        for (r in col + 1..2) {
            val f = m[r][col] / m[col][col]
            for (c in col..3) m[r][c] -= f * m[col][c]
        }
    }
    val out = DoubleArray(3)
    for (r in 2 downTo 0) {
        var s = m[r][3]
        for (c in r + 1..2) s -= m[r][c] * out[c]
        out[r] = s / m[r][r]
    }
    val centre = Vec3(out[0], out[1], out[2])
    return centre.takeIf { it.isFinite() }
}

/** A fitted plane: a point on it plus a unit normal. */
data class FittedPlane(val point: Vec3, val normal: Vec3) {
    fun distanceTo(p: Vec3): Double {
        val d = (p - point)
        return abs(d.x * normal.x + d.y * normal.y + d.z * normal.z)
    }

    /** Intersect the ray `origin + s * dir` (s > 0) with the plane; null when parallel/behind. */
    fun intersectRay(origin: Vec3, dir: Vec3): Vec3? {
        val denom = dir.x * normal.x + dir.y * normal.y + dir.z * normal.z
        if (abs(denom) < 1e-9) return null
        val toPlane = point - origin
        val s = (toPlane.x * normal.x + toPlane.y * normal.y + toPlane.z * normal.z) / denom
        if (s <= 0.0 || !s.isFinite()) return null
        return Vec3(origin.x + dir.x * s, origin.y + dir.y * s, origin.z + dir.z * s)
    }
}

/** Least-squares plane through [points] (needs 3+ non-collinear points). */
fun fitPlane(points: List<Vec3>): FittedPlane? {
    if (points.size < 3) return null
    val n = points.size
    val c = Vec3(
        points.sumOf { it.x } / n,
        points.sumOf { it.y } / n,
        points.sumOf { it.z } / n,
    )
    val cov = DoubleArray(9)
    for (p in points) {
        val d = p - c
        cov[0] += d.x * d.x; cov[1] += d.x * d.y; cov[2] += d.x * d.z
        cov[3] += d.y * d.x; cov[4] += d.y * d.y; cov[5] += d.y * d.z
        cov[6] += d.z * d.x; cov[7] += d.z * d.y; cov[8] += d.z * d.z
    }
    val normal = smallestEigenvectorSymmetric(cov, 3) ?: return null
    var nv = Vec3(normal[0], normal[1], normal[2])
    val len = sqrt(nv.x * nv.x + nv.y * nv.y + nv.z * nv.z)
    if (len < 1e-12) return null
    nv = Vec3(nv.x / len, nv.y / len, nv.z / len)
    return FittedPlane(c, nv)
}

/**
 * RANSAC dominant plane over [points]: the plane with the most inliers
 * within [inlierThresholdM]. Deterministic for a given [seed] so tests
 * and diagnostics are reproducible. Returns the refitted inlier plane
 * plus its inlier set.
 */
fun ransacPlane(
    points: List<Vec3>,
    inlierThresholdM: Double = 0.02,
    iterations: Int = 128,
    seed: Long = 42L,
): Pair<FittedPlane, List<Vec3>>? {
    if (points.size < 3) return null
    val rng = Random(seed)
    var best: List<Vec3> = emptyList()
    repeat(iterations) {
        val sample = points.shuffled(rng).take(3)
        val plane = fitPlane(sample) ?: return@repeat
        val inliers = points.filter { plane.distanceTo(it) <= inlierThresholdM }
        if (inliers.size > best.size) best = inliers
    }
    if (best.size < 3) return null
    val fitted = fitPlane(best) ?: return null
    val inliers = points.filter { fitted.distanceTo(it) <= inlierThresholdM }
    return fitted to inliers
}

/**
 * Snap a plane normal to gravity when it is close to horizontal or
 * vertical: measuring apps quote floor/wall planes, and a 45-degree
 * RANSAC fit through clutter is a tracking artefact, not a surface.
 * [up] is the unit gravity-up vector in world coordinates.
 */
fun gravityClass(normal: Vec3, up: Vec3): PlaneClass {
    val d = abs(normal.x * up.x + normal.y * up.y + normal.z * up.z)
    return when {
        d > 0.85 -> PlaneClass.Horizontal
        d < 0.15 -> PlaneClass.Vertical
        else -> PlaneClass.Slanted
    }
}

/** Classification of a fitted plane against gravity: horizontal, vertical, or slanted (rejected for measuring). */
enum class PlaneClass { Horizontal, Vertical, Slanted }

/**
 * Metric scale from paired visual/IMU displacements over the same
 * windows: `scale = median(|imu| / |visual|)`. Monocular vision is
 * scale-free, so this ratio — with a spread-based confidence — is the
 * number that decides whether distances may be shown at all.
 */
data class ScaleEstimate(val scale: Double, val confidence: Double, val pairs: Int)

fun estimateScale(visual: List<Vec3>, imu: List<Vec3>): ScaleEstimate? {
    require(visual.size == imu.size) { "paired windows" }
    val ratios = ArrayList<Double>()
    for (i in visual.indices) {
        val v = visual[i]
        val vm = sqrt(v.x * v.x + v.y * v.y + v.z * v.z)
        if (vm < 0.01) continue
        val m = imu[i]
        val im = sqrt(m.x * m.x + m.y * m.y + m.z * m.z)
        if (im < 0.005) continue
        // Projected ratio: scale along the visual displacement direction.
        // A magnitude ratio |imu|/|visual| counts perpendicular drift
        // (visual centre wander, IMU residue) as if it were motion along
        // the window, which scattered ratios ~2x in the synthetic harness
        // even with centimetre-accurate IMU windows. Windows whose IMU
        // displacement points against the visual one are not scale
        // evidence at all and are dropped.
        val dot = m.x * v.x + m.y * v.y + m.z * v.z
        if (dot <= 0.0) continue
        ratios += dot / (vm * vm)
    }
    if (ratios.size < 3) return null
    val sorted = ratios.sorted()
    val median = sorted[sorted.size / 2]
    if (!median.isFinite() || median <= 0.0) return null
    val q1 = sorted[sorted.size / 4]
    val q3 = sorted[(3 * sorted.size) / 4]
    val spread = if (median > 0) (q3 - q1) / median else 1.0
    val confidence = (1.0 - spread).coerceIn(0.0, 1.0)
    return ScaleEstimate(median, confidence, ratios.size)
}
