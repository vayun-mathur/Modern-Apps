package com.vayunmathur.measure.domain

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Synthetic-ground-truth tests for the pure VIO geometry: the numbers
 * the OpenCV tracker quotes are only trustworthy if chaining,
 * triangulation, plane fitting and scale alignment recover known
 * scenes exactly. These run without a device, a camera, or OpenCV.
 */
class VioMathTest {

    private val k = doubleArrayOf(
        500.0, 0.0, 320.0,
        0.0, 500.0, 240.0,
        0.0, 0.0, 1.0,
    )

    private fun assertVecClose(expected: Vec3, actual: Vec3, tol: Double = 1e-6) {
        assertTrue(abs(expected.x - actual.x) < tol, "x: ${expected.x} vs ${actual.x}")
        assertTrue(abs(expected.y - actual.y) < tol, "y: ${expected.y} vs ${actual.y}")
        assertTrue(abs(expected.z - actual.z) < tol, "z: ${expected.z} vs ${actual.z}")
    }

    @Test fun poseInverseRoundTrips() {
        val pose = Pose3(
            doubleArrayOf(0.0, -1.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0),
            Vec3(1.0, 2.0, 3.0),
        )
        val p = Vec3(0.5, -1.5, 2.0)
        assertVecClose(p, pose.inverse().toWorld(pose.toWorld(p)), 1e-9)
    }

    @Test fun chainingComposesLikeDirectTransform() {
        // Camera A at world origin; B related by (R, t) mapping A-coords
        // to B-coords. A point written in B must land identically via the
        // chained world pose and via A + the relative transform.
        val worldFromA = Pose3.identity()
        val rel = Pose3(
            doubleArrayOf(0.0, -1.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0),
            Vec3(1.0, 0.0, 0.0),
        )
        val worldFromB = chainPose(worldFromA, rel)
        val inB = Vec3(0.3, 0.7, -1.0)
        val world = worldFromB.toWorld(inB)
        val inA = worldFromA.inverse().toWorld(world)
        assertVecClose(rel.toWorld(inA), inB, 1e-9)
    }

    @Test fun triangulationRecoversKnownPoint() {
        val worldPoint = Vec3(0.4, -0.2, 3.0)
        val camA = Pose3.identity()
        val camB = Pose3(
            doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0),
            Vec3(0.5, 0.0, 0.0),
        )
        val pa = projectionMatrix(k, camA)
        val pb = projectionMatrix(k, camB)
        val pxA = project(pa, worldPoint)
        val pxB = project(pb, worldPoint)
        assertNotNull(pxA)
        assertNotNull(pxB)
        val recovered = triangulate(pa, pxA, pb, pxB)
        assertNotNull(recovered)
        assertVecClose(worldPoint, recovered, 1e-6)
        assertTrue(reprojectionErrorPx(pa, recovered, pxA) < 1e-6)
    }

    @Test fun triangulationRejectsPointBehindCamera() {
        val behind = Vec3(0.0, 0.0, -2.0)
        val pa = projectionMatrix(k, Pose3.identity())
        assertNull(project(pa, behind))
    }

    @Test fun planeFitRecoversFloorAndRayHitsIt() {
        val points = listOf(
            Vec3(0.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0), Vec3(0.0, 0.0, 1.0),
            Vec3(1.0, 0.0, 1.0), Vec3(0.5, 0.0, 0.25),
        )
        val plane = fitPlane(points)
        assertNotNull(plane)
        assertTrue(abs(abs(plane.normal.y) - 1.0) < 1e-9)
        assertEquals(PlaneClass.Horizontal, gravityClass(plane.normal, Vec3(0.0, 1.0, 0.0)))
        val hit = plane.intersectRay(Vec3(0.5, 1.0, 0.5), Vec3(0.0, -1.0, 0.0))
        assertNotNull(hit)
        assertVecClose(Vec3(0.5, 0.0, 0.5), hit, 1e-9)
        assertNull(plane.intersectRay(Vec3(0.5, 1.0, 0.5), Vec3(0.0, 1.0, 0.0)))
    }

    @Test fun ransacFindsDominantPlaneThroughClutter() {
        val floor = (0..20).map { Vec3(it * 0.1, 0.0, (it % 5) * 0.2) }
        val clutter = listOf(Vec3(0.3, 0.5, 0.3), Vec3(0.8, -0.4, 0.9), Vec3(1.5, 0.7, 0.1))
        val result = ransacPlane(floor + clutter, seed = 7L)
        assertNotNull(result)
        val (plane, inliers) = result
        assertTrue(inliers.size >= floor.size - 2, "inliers=${inliers.size}")
        assertTrue(abs(abs(plane.normal.y) - 1.0) < 1e-6)
    }

    @Test fun slantedPlaneIsClassifiedHonestly() {
        val n = Vec3(0.0, 0.7071, 0.7071)
        assertEquals(PlaneClass.Slanted, gravityClass(n, Vec3(0.0, 1.0, 0.0)))
        assertEquals(PlaneClass.Vertical, gravityClass(Vec3(1.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0)))
    }

    @Test fun scaleEstimateRecoversKnownScale() {
        // Visual windows of unit-ish size; IMU windows exactly 2x.
        val visual = listOf(
            Vec3(0.5, 0.0, 0.0), Vec3(0.0, 0.4, 0.0), Vec3(0.3, 0.3, 0.0), Vec3(0.0, 0.0, 0.6),
        )
        val imu = visual.map { Vec3(it.x * 2.0, it.y * 2.0, it.z * 2.0) }
        val est = estimateScale(visual, imu)
        assertNotNull(est)
        assertEquals(2.0, est.scale, 1e-9)
        assertTrue(est.confidence > 0.99)
        assertEquals(4, est.pairs)
    }

    @Test fun scaleEstimateRefusesThinData() {
        assertNull(estimateScale(listOf(Vec3(1.0, 0.0, 0.0)), listOf(Vec3(1.0, 0.0, 0.0))))
        // Rotation-only windows carry no translation: no scale may emerge.
        val still = List(4) { Vec3(0.0, 0.0, 0.0) }
        assertNull(estimateScale(still, still))
    }
}
