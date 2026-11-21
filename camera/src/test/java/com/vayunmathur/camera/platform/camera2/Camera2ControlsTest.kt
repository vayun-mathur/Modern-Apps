package com.vayunmathur.camera.platform.camera2

import android.hardware.camera2.CameraMetadata
import android.util.Range
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Camera2ControlsTest {

    /** Snapshot with zoom-ratio support, flash, ±4 EV in 1/6 steps, regions. */
    private fun snapshot(
        zoomRatioRange: Range<Float>? = Range(1f, 10f),
        hasFlashUnit: Boolean = true,
        maxAfRegions: Int = 1,
        maxAeRegions: Int = 1,
        supportsZoomRatioKey: Boolean = true,
    ): LensCharacteristicsSnapshot {
        val facade = CharacteristicsFacade.fromFakeMap(
            cameraId = "0",
            zoomRatioRange = zoomRatioRange,
            hasFlashUnit = hasFlashUnit,
            supportsZoomRatioKey = supportsZoomRatioKey,
            exposureCompensationEvRange = Range(-4f, 4f),
            activeArrayWidth = 4000,
            activeArrayHeight = 3000,
            exposureCompensationStepEv = 1f / 6f,
            maxAfRegions = maxAfRegions,
            maxAeRegions = maxAeRegions,
        )
        return facade.snapshot
    }

    @Test fun zoomRatioPathWhenSupported() {
        val params = Camera2Controls.resolve(
            Camera2ControlState(zoomRatio = 3f), snapshot(),
        )
        assertEquals(3f, params.zoomRatio)
        assertNull(params.cropRegion)
    }

    @Test fun zoomClampsToLensRange() {
        val params = Camera2Controls.resolve(
            Camera2ControlState(zoomRatio = 99f), snapshot(),
        )
        assertEquals(10f, params.zoomRatio)
        val low = Camera2Controls.resolve(
            Camera2ControlState(zoomRatio = 0.1f), snapshot(),
        )
        assertEquals(1f, low.zoomRatio)
    }

    @Test fun cropRegionFallbackWhenZoomRatioUnsupported() {
        // Range known (from SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) but the
        // CONTROL_ZOOM_RATIO key is not honoured: crop region carries zoom.
        val params = Camera2Controls.resolve(
            Camera2ControlState(zoomRatio = 2f),
            snapshot(zoomRatioRange = Range(1f, 8f), supportsZoomRatioKey = false),
        )
        assertNull(params.zoomRatio)
        assertEquals(SensorRect(1000, 750, 3000, 2250), params.cropRegion)
    }

    @Test fun torchRequiresFlashUnit() {
        val on = Camera2Controls.resolve(
            Camera2ControlState(torchOn = true), snapshot(),
        )
        assertEquals(CameraMetadata.FLASH_MODE_TORCH, on.flashMode)
        val noUnit = Camera2Controls.resolve(
            Camera2ControlState(torchOn = true), snapshot(hasFlashUnit = false),
        )
        assertEquals(CameraMetadata.FLASH_MODE_OFF, noUnit.flashMode)
        val off = Camera2Controls.resolve(Camera2ControlState.DEFAULT, snapshot())
        assertEquals(CameraMetadata.FLASH_MODE_OFF, off.flashMode)
    }

    @Test fun evConvertsToStepsAndClamps() {
        // 1/6 EV steps: +1 EV = 6 steps, -2 EV = -12 steps.
        assertEquals(6, Camera2Controls.evToSteps(1f, snapshot()))
        assertEquals(-12, Camera2Controls.evToSteps(-2f, snapshot()))
        assertEquals(0, Camera2Controls.evToSteps(0f, snapshot()))
        // Clamped to the ±4 EV range.
        assertEquals(24, Camera2Controls.evToSteps(9f, snapshot()))
        assertEquals(-24, Camera2Controls.evToSteps(-9f, snapshot()))
        // Resolved params carry the same conversion.
        val params = Camera2Controls.resolve(
            Camera2ControlState(exposureCompEv = 0.5f), snapshot(),
        )
        assertEquals(3, params.aeExposureCompensationSteps)
    }

    @Test fun meteringRegionsGatedOnHalRegionCounts() {
        val region = MeteringRegion(SensorRect(100, 100, 300, 300))
        val withRegions = Camera2Controls.resolve(
            Camera2ControlState(afRegion = region, aeRegion = region), snapshot(),
        )
        assertEquals(listOf(region), withRegions.afRegions)
        assertEquals(listOf(region), withRegions.aeRegions)
        assertTrue(withRegions.meteringActive)

        val noRegions = Camera2Controls.resolve(
            Camera2ControlState(afRegion = region, aeRegion = region),
            snapshot(maxAfRegions = 0, maxAeRegions = 0),
        )
        assertTrue(noRegions.afRegions.isEmpty())
        assertTrue(noRegions.aeRegions.isEmpty())

        val defaults = Camera2Controls.resolve(Camera2ControlState.DEFAULT, snapshot())
        assertTrue(defaults.afRegions.isEmpty())
        assertTrue(!defaults.meteringActive)
    }
}
