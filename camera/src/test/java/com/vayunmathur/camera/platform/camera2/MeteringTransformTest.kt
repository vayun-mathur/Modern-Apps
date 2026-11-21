package com.vayunmathur.camera.platform.camera2

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class MeteringTransformTest {

    private val active = SensorRect(0, 0, 4000, 3000)

    @Test fun cropRegionForZoomCentersAndScales() {
        // 2x zoom: halved edges, centred in the active array.
        assertEquals(SensorRect(1000, 750, 3000, 2250),
            MeteringTransform.cropRegionForZoom(active, 2f))
        // 1x and below: the full array.
        assertEquals(active, MeteringTransform.cropRegionForZoom(active, 1f))
        assertEquals(active, MeteringTransform.cropRegionForZoom(active, 0.5f))
        // 4x zoom.
        assertEquals(SensorRect(1500, 1125, 2500, 1875),
            MeteringTransform.cropRegionForZoom(active, 4f))
    }

    @Test fun rotateViewfinderPointHandlesQuarterTurns() {
        // No rotation: identity (unmirrored).
        assertEquals(0.25f to 0.75f,
            MeteringTransform.rotateViewfinderPoint(0.25f, 0.75f, 0, mirrored = false))
        // 180: both axes flip.
        assertEquals(0.75f to 0.25f,
            MeteringTransform.rotateViewfinderPoint(0.25f, 0.75f, 180, mirrored = false))
        // 90 clockwise: (x,y) -> (1-y, x).
        assertEquals(0.25f to 0.25f,
            MeteringTransform.rotateViewfinderPoint(0.25f, 0.75f, 90, mirrored = false))
        // 270 clockwise: (x,y) -> (y, 1-x).
        assertEquals(0.75f to 0.75f,
            MeteringTransform.rotateViewfinderPoint(0.25f, 0.75f, 270, mirrored = false))
        // Non-quarter / negative inputs normalise instead of throwing.
        assertEquals(0.75f to 0.25f,
            MeteringTransform.rotateViewfinderPoint(0.25f, 0.75f, -180, mirrored = false))
    }

    @Test fun mirroredFrontFlipsXBeforeRotation() {
        // Mirror only: x flips.
        assertEquals(0.75f to 0.75f,
            MeteringTransform.rotateViewfinderPoint(0.25f, 0.75f, 0, mirrored = true))
        // Mirror + 180 ends up as a y-flip only.
        assertEquals(0.25f to 0.25f,
            MeteringTransform.rotateViewfinderPoint(0.25f, 0.75f, 180, mirrored = true))
    }

    @Test fun centreTapMapsToCropCentreAtAnyRotation() {
        val crop = MeteringTransform.cropRegionForZoom(active, 2f)
        for (rotation in listOf(0, 90, 180, 270)) {
            val (x, y) = MeteringTransform.viewfinderToSensorPoint(
                0.5f, 0.5f, crop,
                sensorOrientationDegrees = rotation, displayRotationDegrees = 0,
                mirrored = false,
            )
            assertEquals(crop.centerX, x, 0.6f, "rotation $rotation x")
            assertEquals(crop.centerY, y, 0.6f, "rotation $rotation y")
        }
    }

    @Test fun cornerTapsLandOnCropCorners() {
        val crop = MeteringTransform.cropRegionForZoom(active, 2f)
        // Back lens, sensor 90 / display 0: viewfinder top-left is the crop's
        // (right-1, top) corner in sensor space.
        val (x, y) = MeteringTransform.viewfinderToSensorPoint(
            0f, 0f, crop,
            sensorOrientationDegrees = 90, displayRotationDegrees = 0, mirrored = false,
        )
        assertEquals((crop.right - 1).toFloat(), x, 0.6f)
        assertEquals(crop.top.toFloat(), y, 0.6f)
    }

    @Test fun meteringRegionStaysInsideActiveArrayAtEdges() {
        val full = active
        for ((tx, ty) in listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f, 0.5f to 0f)) {
            val region = MeteringTransform.meteringRegionForTap(
                tx, ty, active, full,
                sensorOrientationDegrees = 90, displayRotationDegrees = 0, mirrored = false,
            )
            assertTrue(region.rect.left >= active.left, "left for $tx,$ty: ${region.rect}")
            assertTrue(region.rect.top >= active.top, "top for $tx,$ty: ${region.rect}")
            assertTrue(region.rect.right <= active.right, "right for $tx,$ty: ${region.rect}")
            assertTrue(region.rect.bottom <= active.bottom, "bottom for $tx,$ty: ${region.rect}")
            assertTrue(region.rect.width > 0 && region.rect.height > 0)
            assertEquals(MeteringRegion.DEFAULT_WEIGHT, region.weight)
        }
        // Centre tap: region centres on the (rotated) centre — same centre.
        val centre = MeteringTransform.meteringRegionForTap(
            0.5f, 0.5f, active, full,
            sensorOrientationDegrees = 0, displayRotationDegrees = 0, mirrored = false,
        )
        assertEquals(active.centerX, centre.rect.centerX, 1.5f)
        assertEquals(active.centerY, centre.rect.centerY, 1.5f)
    }

    @Test fun zoomedTapMapsIntoCropNotFullArray() {
        val crop = MeteringTransform.cropRegionForZoom(active, 2f)
        // A corner tap under 2x zoom centres on the crop corner (the region
        // may extend past the crop into the array — it is clamped to the
        // array, not the crop), never near the array's opposite corner.
        val corner = MeteringTransform.meteringRegionForTap(
            0f, 0f, active, crop,
            sensorOrientationDegrees = 0, displayRotationDegrees = 0, mirrored = false,
        )
        assertTrue(corner.rect.left >= active.left)
        assertEquals(crop.left.toFloat(), corner.rect.centerX, crop.width * 0.13f)
        assertEquals(crop.top.toFloat(), corner.rect.centerY, crop.height * 0.13f)
        assertTrue(corner.rect.centerX < active.centerX)
    }

    @Test fun displayRotationCancelsSensorOrientation() {
        // Sensor 90 with the display also rotated 90: relative rotation 0,
        // so the tap maps like an unrotated sensor.
        val (x, y) = MeteringTransform.viewfinderToSensorPoint(
            0.25f, 0.5f, active,
            sensorOrientationDegrees = 90, displayRotationDegrees = 90, mirrored = false,
        )
        assertEquals(1000f, x, 0.6f)
        assertEquals(1500f, y, 0.6f)
    }
}
