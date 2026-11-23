package com.vayunmathur.camera.platform.camera2

import android.hardware.camera2.CameraMetadata
import android.util.Range
import com.vayunmathur.camera.platform.toCustomRecordingConfig
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
 * Phase-5 planner tests over fake characteristics: size/range selection,
 * the fixed-FPS request resolution, and the encoder/timestamp contract.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HighSpeedCaptureTest {

    private fun snapshot(
        capabilities: Set<Int> = setOf(
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO,
        ),
        sizes: List<Size> = listOf(Size(1920, 1080), Size(1280, 720)),
        ranges: Map<Size, List<Range<Int>>> = mapOf(
            Size(1920, 1080) to listOf(Range(120, 120), Range(60, 60)),
            Size(1280, 720) to listOf(Range(240, 240), Range(120, 120)),
        ),
    ): LensCharacteristicsSnapshot = CharacteristicsFacade.fromFakeMap(
        cameraId = "0",
        lensFacingRaw = CameraMetadata.LENS_FACING_BACK,
        availableCapabilities = capabilities,
        highSpeedVideoSizes = sizes,
        highSpeedFpsRangesBySize = ranges,
    ).snapshot

    @Test fun facadeSnapshotCarriesHighSpeedTables() {
        val s = snapshot()
        assertTrue(s.supportsConstrainedHighSpeedVideo)
        assertEquals(listOf(Size(1920, 1080), Size(1280, 720)), s.highSpeedVideoSizes)
        assertEquals(
            listOf(Range(240, 240), Range(120, 120)),
            s.highSpeedFpsRangesBySize[Size(1280, 720)],
        )
    }

    @Test fun selectsHighestFpsThenLargestSize() {
        val selected = HighSpeedPlanner.select(snapshot())
        assertEquals(240, selected?.captureFps)
        assertEquals(FrameSize(1280, 720), selected?.size)

        // Without a 240 point, 120 at the larger size wins over 120 small.
        val no240 = snapshot(
            ranges = mapOf(
                Size(1920, 1080) to listOf(Range(120, 120)),
                Size(1280, 720) to listOf(Range(120, 120), Range(60, 60)),
            ),
        )
        val selected120 = HighSpeedPlanner.select(no240)
        assertEquals(120, selected120?.captureFps)
        assertEquals(FrameSize(1920, 1080), selected120?.size)
    }

    @Test fun selectionIsDeterministicRegardlessOfAdvertisedOrder() {
        val shuffled = snapshot(
            sizes = listOf(Size(1280, 720), Size(1920, 1080)),
            ranges = mapOf(
                Size(1280, 720) to listOf(Range(120, 120), Range(240, 240)),
                Size(1920, 1080) to listOf(Range(60, 60), Range(120, 120)),
            ),
        )
        assertEquals(HighSpeedPlanner.select(snapshot()), HighSpeedPlanner.select(shuffled))
    }

    @Test fun noCapabilityOrNoFixedRangeMeansNoSelection() {
        assertNull(
            HighSpeedPlanner.select(snapshot(capabilities = emptySet())),
            "without CONSTRAINED_HIGH_SPEED_VIDEO there is no slo-mo point",
        )
        assertNull(
            HighSpeedPlanner.select(
                snapshot(ranges = mapOf(Size(1920, 1080) to listOf(Range(30, 120)))),
            ),
            "a variable range must not be normalised into a pinned one",
        )
        assertNull(
            HighSpeedPlanner.select(
                snapshot(ranges = mapOf(Size(1920, 1080) to listOf(Range(30, 30)))),
            ),
            "30 fps is not slo-mo",
        )
        assertNull(HighSpeedPlanner.select(snapshot(sizes = emptyList(), ranges = emptyMap())))
    }

    @Test fun requestIsTemplateRecordShapedAndFixedRange() {
        val selection = HighSpeedPlanner.select(snapshot())!!
        val params = HighSpeedPlanner.resolveRequest(selection)
        // Exact fixed range: lower == upper == capture fps, no AE wandering.
        assertEquals(Range(240, 240), params.aeTargetFpsRange)
        assertEquals(params.aeTargetFpsRange.lower, params.aeTargetFpsRange.upper)
        assertEquals(240, params.captureFps)
        assertEquals(HighSpeedPlanner.PLAYBACK_FPS, params.playbackFps)
        assertEquals(CameraMetadata.CONTROL_AE_MODE_ON, params.aeMode)
        assertEquals(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO, params.afMode)
        assertEquals(CameraMetadata.FLASH_MODE_OFF, params.flashMode)
    }

    @Test fun encoderPlanAndTimestampStretchMatchPlaybackContract() {
        val selection = HighSpeedPlanner.select(snapshot())!!
        assertEquals(HighSpeedOutput.ENCODER_SURFACE, selection.output)
        val plan = selection.encoderPlan
        assertEquals(1280, plan.width)
        assertEquals(720, plan.height)
        // Captured at 240, played at 30: KEY_CAPTURE_RATE vs KEY_FRAME_RATE.
        assertEquals(240, plan.captureRateFps)
        assertEquals(30, plan.frameRateFps)
        assertEquals(8f, selection.slowdownFactor)
        // Two realtime seconds become sixteen seconds of playback.
        assertEquals(16_000_000L, selection.stretchedPtsUs(2_000_000L))
        assertEquals(0L, selection.stretchedPtsUs(0L))
    }

    @Test fun selectionMapsOntoPhase1EncoderConfig() {
        val selection = HighSpeedPlanner.select(snapshot())!!
        val config = selection.toCustomRecordingConfig()
        assertEquals(1280, config.width)
        assertEquals(720, config.height)
        assertEquals(30, config.fps)
        assertEquals(240, config.captureFps)
        assertEquals(android.media.MediaFormat.MIMETYPE_VIDEO_AVC, config.videoMime)
        // Slo-mo stays no-audio under the Opus-only regime (piece 5).
        assertFalse(config.audioEnabled)
    }

    @Test fun sixtyFpsIsTheSlowestHonestPoint() {
        val only60 = snapshot(
            ranges = mapOf(Size(1280, 720) to listOf(Range(60, 60))),
        )
        val selection = HighSpeedPlanner.select(only60)
        assertEquals(60, selection?.captureFps)
        assertEquals(2f, selection?.slowdownFactor)
    }
}
