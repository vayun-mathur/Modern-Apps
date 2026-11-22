package com.vayunmathur.camera.platform.camera2

import android.graphics.ImageFormat
import android.util.Size
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StreamSelectionTest {

    private val supported = listOf(
        FrameSize(1920, 1080),
        FrameSize(1440, 1080),
        FrameSize(1280, 720),
        FrameSize(640, 480),
    )

    @Test fun exactMatchWins() {
        assertEquals(FrameSize(1280, 720),
            StreamSelection.selectPreviewSize(FrameSize(1280, 720), supported))
        assertTrue(StreamSelection.isSupported(FrameSize(1280, 720), supported))
        assertFalse(StreamSelection.isSupported(FrameSize(1280, 721), supported))
    }

    @Test fun sameAspectPicksSmallestCoveringThenLargest() {
        // 1600x900 is 16:9 like 1920x1080 and 1280x720: smallest covering is 1920x1080.
        assertEquals(FrameSize(1920, 1080),
            StreamSelection.selectPreviewSize(FrameSize(1600, 900), supported))
        // 2560x1440 (16:9) is covered by nothing: largest 16:9 wins.
        assertEquals(FrameSize(1920, 1080),
            StreamSelection.selectPreviewSize(FrameSize(2560, 1440), supported))
        // 960x720 is 4:3 like 1440x1080 and 640x480: smallest covering is 1440x1080.
        assertEquals(FrameSize(1440, 1080),
            StreamSelection.selectPreviewSize(FrameSize(960, 720), supported))
    }

    @Test fun noSameAspectFallsBackToClosestShapeDeterministically() {
        // Square request: 1440x1080 and 640x480 are both 4:3 (aspect 1.333,
        // distance 0.333); 1280x720 is 16:9 (distance 0.778) — a 4:3 size wins,
        // and between the two the closer area (1440x1080) wins.
        assertEquals(FrameSize(1440, 1080),
            StreamSelection.selectPreviewSize(FrameSize(1000, 1000), supported))
        // Selection is order-independent.
        assertEquals(
            StreamSelection.selectPreviewSize(FrameSize(1000, 1000), supported),
            StreamSelection.selectPreviewSize(FrameSize(1000, 1000), supported.reversed()),
        )
    }

    @Test fun emptySupportedSelectsNothing() {
        assertNull(StreamSelection.selectPreviewSize(FrameSize(1280, 720), emptyList()))
        assertEquals(PreviewSizeRejection.NoSupportedSizes,
            StreamSelection.rejectionFor(FrameSize(1280, 720), emptyList()))
        assertTrue(StreamSelection.validatePreviewSize(FrameSize(1280, 720), emptyList()).isFailure)
    }

    @Test fun validateReturnsSelectedSize() {
        val result = StreamSelection.validatePreviewSize(FrameSize(1600, 900), supported)
        assertTrue(result.isSuccess)
        assertEquals(FrameSize(1920, 1080), result.getOrThrow())
        assertNull(StreamSelection.rejectionFor(FrameSize(1600, 900), supported))
    }

    @Test fun analysisSizeCapsAtPhotoAnalysisPixels() {
        val snapshot = CharacteristicsFacade.fromFakeMap(
            cameraId = "0",
            sizesByFormat = mapOf(
                ImageFormat.YUV_420_888 to listOf(
                    Size(1920, 1080), Size(1280, 960), Size(1280, 720), Size(640, 480),
                ),
            ),
        ).snapshot
        // Largest at/under ~1.2 MP wins (1280x960), never sensor resolution.
        assertEquals(FrameSize(1280, 960), StreamSelection.selectAnalysisSize(snapshot))
    }

    @Test fun analysisSizeOverCapPicksSmallestAndEmptyPicksNull() {
        val overCap = CharacteristicsFacade.fromFakeMap(
            cameraId = "0",
            sizesByFormat = mapOf(
                ImageFormat.YUV_420_888 to listOf(Size(4000, 3000), Size(1920, 1080)),
            ),
        ).snapshot
        assertEquals(FrameSize(1920, 1080), StreamSelection.selectAnalysisSize(overCap))
        val noYuv = CharacteristicsFacade.fromFakeMap(
            cameraId = "0",
            sizesByFormat = mapOf(ImageFormat.PRIVATE to listOf(Size(640, 480))),
        ).snapshot
        assertNull(StreamSelection.selectAnalysisSize(noYuv))
    }

    @Test fun supportedSizesPreferSurfaceTextureThenPrivate() {
        val withTexture = CharacteristicsFacade.fromFakeMap(
            cameraId = "0",
            sizesByFormat = mapOf(ImageFormat.PRIVATE to listOf(Size(640, 480))),
            surfaceTextureSizes = listOf(Size(1920, 1080), Size(1280, 720)),
        )
        assertEquals(
            listOf(FrameSize(1920, 1080), FrameSize(1280, 720)),
            StreamSelection.supportedPreviewSizes(withTexture.snapshot),
        )
        val privateOnly = CharacteristicsFacade.fromFakeMap(
            cameraId = "0",
            sizesByFormat = mapOf(ImageFormat.PRIVATE to listOf(Size(640, 480))),
        )
        assertEquals(
            listOf(FrameSize(640, 480)),
            StreamSelection.supportedPreviewSizes(privateOnly.snapshot),
        )
    }
}
