package com.vayunmathur.camera.platform.camera2

import android.graphics.ImageFormat
import android.hardware.camera2.CameraExtensionCharacteristics
import android.hardware.camera2.CameraMetadata
import android.util.Range
import android.util.Size
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase-6 probe tests: mapping from fake extension characteristics into the
 * per-lens availability + session-config contract that mirrors the CameraX
 * probe (`isExtensionAvailable` + `isSessionConfigSupported(Preview +
 * ImageCapture)`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Camera2ExtensionProbeTest {

    private val night = CameraExtensionCharacteristics.EXTENSION_NIGHT
    private val hdr = CameraExtensionCharacteristics.EXTENSION_HDR

    private fun snapshot(
        modes: List<Int> = listOf(night),
        support: Map<Int, ExtensionModeSupport> = mapOf(
            night to ExtensionModeSupport(
                mode = night,
                jpegSizes = listOf(Size(1920, 1080), Size(4032, 3024)),
                previewSizes = listOf(Size(1280, 720), Size(1920, 1080)),
                estimatedCaptureLatencyRangeMillisByJpegSize = mapOf(
                    Size(4032, 3024) to Range(800L, 1500L),
                    Size(1920, 1080) to Range(300L, 700L),
                ),
            ),
        ),
    ): LensCharacteristicsSnapshot = CharacteristicsFacade.fromFakeMap(
        cameraId = "0",
        lensFacingRaw = CameraMetadata.LENS_FACING_BACK,
        sizesByFormat = mapOf(ImageFormat.JPEG to listOf(Size(4032, 3024))),
        availableExtensionModes = modes,
        extensionSupport = support,
    ).snapshot

    @Test fun nightUsableWhenModeAndSizePairAdvertised() {
        val result = Camera2ExtensionProbe.probeNight(snapshot())
        assertTrue(result.available)
        assertTrue(result.sessionConfigSupported)
        assertTrue(result.usable)
        assertNull(result.rejectionReason)
        // Largest JPEG is the still; largest preview is the preview selection.
        assertEquals(FrameSize(4032, 3024), result.stillSize)
        assertEquals(FrameSize(1920, 1080), result.previewSize)
        // Latency follows the selected still size, never gates support.
        assertEquals(Range(800L, 1500L), result.estimatedCaptureLatencyRangeMillis)
    }

    @Test fun modeAbsentFromSupportedExtensionsIsUnavailable() {
        val result = Camera2ExtensionProbe.probeNight(snapshot(modes = listOf(hdr)))
        assertFalse(result.available)
        assertFalse(result.sessionConfigSupported)
        assertFalse(result.usable)
        assertTrue(result.rejectionReason!!.contains("not in supportedExtensions"))
    }

    @Test fun listedModeWithoutProbedSupportIsNotSessionSupported() {
        // Mode listed, but no sizes were ever read for it: probe must not guess.
        val result = Camera2ExtensionProbe.probeNight(snapshot(support = emptyMap()))
        assertTrue(result.available)
        assertFalse(result.sessionConfigSupported)
        assertFalse(result.usable)
    }

    @Test fun missingPreviewOrJpegPairIsNotSessionSupported() {
        val noJpeg = Camera2ExtensionProbe.probeNight(
            snapshot(
                support = mapOf(
                    night to ExtensionModeSupport(
                        mode = night,
                        jpegSizes = emptyList(),
                        previewSizes = listOf(Size(1280, 720)),
                    ),
                ),
            ),
        )
        assertFalse(noJpeg.sessionConfigSupported)
        val noPreview = Camera2ExtensionProbe.probeNight(
            snapshot(
                support = mapOf(
                    night to ExtensionModeSupport(
                        mode = night,
                        jpegSizes = listOf(Size(4032, 3024)),
                        previewSizes = emptyList(),
                    ),
                ),
            ),
        )
        assertFalse(noPreview.sessionConfigSupported)
    }

    @Test fun probeIsPerModeAndPerLensSnapshot() {
        // HDR support must not make NIGHT usable, and vice versa.
        val snap = snapshot(
            modes = listOf(night, hdr),
            support = mapOf(
                hdr to ExtensionModeSupport(
                    mode = hdr,
                    jpegSizes = listOf(Size(4032, 3024)),
                    previewSizes = listOf(Size(1920, 1080)),
                ),
            ),
        )
        assertFalse(Camera2ExtensionProbe.probeNight(snap).usable)
        assertTrue(Camera2ExtensionProbe.probe(snap, hdr).usable)
        assertEquals(night, Camera2ExtensionProbe.NIGHT_MODE)
    }

    @Test fun missingLatencyEstimateDoesNotGateSupport() {
        val result = Camera2ExtensionProbe.probeNight(
            snapshot(
                support = mapOf(
                    night to ExtensionModeSupport(
                        mode = night,
                        jpegSizes = listOf(Size(4032, 3024)),
                        previewSizes = listOf(Size(1920, 1080)),
                    ),
                ),
            ),
        )
        assertTrue(result.usable)
        assertNull(result.estimatedCaptureLatencyRangeMillis)
    }

    @Test fun facadeSnapshotCarriesExtensionSupportEntry() {
        val snap = snapshot()
        assertTrue(snap.hasExtension(night))
        assertEquals(
            listOf(Size(1920, 1080), Size(4032, 3024)),
            snap.extensionSupportFor(night)?.jpegSizes,
        )
        assertEquals(
            Range(300L, 700L),
            snap.extensionSupportFor(night)?.estimatedLatencyFor(Size(1920, 1080)),
        )
        assertNull(snap.extensionSupportFor(hdr))
    }
}
