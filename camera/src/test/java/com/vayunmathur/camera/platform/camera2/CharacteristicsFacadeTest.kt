package com.vayunmathur.camera.platform.camera2

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.StreamConfigurationMap
import android.util.Range
import android.util.Size
import com.vayunmathur.camera.domain.LensFacing
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowCameraCharacteristics

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CharacteristicsFacadeTest {

    private fun characteristics(
        vararg entries: Pair<CameraCharacteristics.Key<*>, Any?>,
    ): CameraCharacteristics {
        val chars = ShadowCameraCharacteristics.newCameraCharacteristics()
        val shadow = Shadows.shadowOf(chars)
        for ((key, value) in entries) {
            @Suppress("UNCHECKED_CAST")
            shadow.set(key as CameraCharacteristics.Key<Any?>, value)
        }
        return chars
    }

    @Test fun mapsEveryProbeFieldFromRealCharacteristics() {
        val chars = characteristics(
            CameraCharacteristics.LENS_FACING to CameraMetadata.LENS_FACING_BACK,
            CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE to Range(0.5f, 10f),
            CameraCharacteristics.FLASH_INFO_AVAILABLE to true,
            CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE to Range(1_000L, 1_000_000_000L),
            CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE to Range(100, 6400),
            CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE to Range(-24, 24),
            CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP to android.util.Rational(1, 6),
            CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES to
                intArrayOf(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON),
            CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION to
                intArrayOf(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON),
            CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES to
                arrayOf(Range(30, 30), Range(15, 30)),
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL to
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL,
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES to intArrayOf(
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW,
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING,
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA,
            ),
            CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS to floatArrayOf(4.3f),
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT to
                CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB,
        )
        val facade = CharacteristicsFacade.fromCharacteristics(
            cameraId = "0",
            chars = chars,
            availableExtensionModes = listOf(1, 4),
        )
        val s = facade.snapshot
        assertEquals("0", facade.cameraId)
        assertEquals(LensFacing.BACK, s.lensFacing)
        assertEquals(Range(0.5f, 10f), s.zoomRatioRange)
        assertEquals(0.5f, s.minZoomRatio)
        assertEquals(10f, s.maxZoomRatio)
        assertTrue(s.hasFlashUnit)
        assertEquals(Range(1_000L, 1_000_000_000L), s.exposureTimeRangeNs)
        assertEquals(Range(100, 6400), s.sensitivityRange)
        // ±24 steps at 1/6 EV per step = ±4 EV.
        assertEquals(Range(-4f, 4f), s.exposureCompensationEvRange)
        assertEquals(listOf(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON), s.videoStabilizationModes)
        assertEquals(listOf(Range(30, 30), Range(15, 30)), s.aeTargetFpsRanges)
        assertTrue(s.supportsRaw)
        assertTrue(s.supportsReprocess)
        assertTrue(s.isLogicalMultiCamera)
        assertFalse(s.supportsManualSensor)
        assertTrue(s.hasExtension(4))
        assertFalse(s.hasExtension(2))
        assertEquals(listOf(4.3f), s.focalLengthsMm)
    }

    @Test fun missingKeysDegradeToEmptyAndDefaults() {
        val facade = CharacteristicsFacade.fromCharacteristics("1", characteristics())
        val s = facade.snapshot
        assertNull(s.lensFacing)
        assertNull(s.zoomRatioRange)
        assertEquals(1f, s.minZoomRatio)
        assertEquals(1f, s.maxZoomRatio)
        assertFalse(s.hasFlashUnit)
        assertNull(s.exposureTimeRangeNs)
        assertTrue(s.aeTargetFpsRanges.isEmpty())
        assertEquals(StreamConfigSummary.EMPTY, s.streamConfig)
        assertTrue(s.availableExtensionModes.isEmpty())
    }

    @Test fun digitalZoomFallbackWhenZoomRatioRangeAbsent() {
        // CONTROL_ZOOM_RATIO_RANGE is left unset; the shadow returns null, so
        // the facade must fall back to SCALER_AVAILABLE_MAX_DIGITAL_ZOOM.
        val facade = CharacteristicsFacade.fromCharacteristics(
            "0",
            characteristics(
                CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM to 8f,
            ),
        )
        assertEquals(Range(1f, 8f), facade.snapshot.zoomRatioRange)
    }

    @Test fun fakeMapBuildsSnapshotWithoutCharacteristics() {
        val facade = CharacteristicsFacade.fromFakeMap(
            cameraId = "2",
            lensFacingRaw = CameraMetadata.LENS_FACING_FRONT,
            physicalCameraIds = setOf("3", "4"),
            zoomRatioRange = Range(1f, 5f),
            hasFlashUnit = false,
            sizesByFormat = mapOf(
                ImageFormat.JPEG to listOf(Size(4032, 3024), Size(1920, 1080)),
                ImageFormat.RAW_SENSOR to listOf(Size(4032, 3024)),
            ),
            availableCapabilities = setOf(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW),
        )
        val s = facade.snapshot
        assertEquals(LensFacing.FRONT, s.lensFacing)
        assertEquals(setOf("3", "4"), s.physicalCameraIds)
        assertTrue(s.streamConfig.supportsRawSensor)
        assertEquals(Size(4032, 3024), s.streamConfig.maxSizeFor(ImageFormat.JPEG))
        assertEquals(2, s.streamConfig.formats.getValue(ImageFormat.JPEG).sizeCount)
        assertTrue(s.supportsRaw)
    }

    @Test fun streamConfigSummaryFromSizesPicksLargestArea() {
        val summary = StreamConfigSummary.fromSizes(
            mapOf(ImageFormat.YUV_420_888 to listOf(Size(640, 480), Size(1280, 720), Size(320, 240))),
        )
        assertEquals(Size(1280, 720), summary.maxSizeFor(ImageFormat.YUV_420_888))
        assertEquals(emptyList(), summary.sizesFor(ImageFormat.JPEG))
        assertFalse(summary.supportsRawSensor)
    }

    @Test fun streamConfigSummaryFromNullMapIsEmpty() {
        assertEquals(StreamConfigSummary.EMPTY, StreamConfigSummary.fromMap(null as StreamConfigurationMap?))
    }
}
