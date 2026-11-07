package com.vayunmathur.camera.util

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure-math coverage for the timelapse remux helpers. The muxer/extractor
 * path itself needs native codecs and cannot run under Robolectric, so the
 * timestamp remap and buffer sizing are kept pure and verified here.
 */
class VideoProcessorTest {

    @Test fun remappedPtsScalesBySpeedFactor() {
        // 30fps capture (33_333us/frame) sped up 8x plays 8x shorter.
        assertEquals(0L, VideoProcessor.remappedPtsUs(0L, 8f))
        assertEquals(4_166L, VideoProcessor.remappedPtsUs(33_333L, 8f))
        assertEquals(125_000L, VideoProcessor.remappedPtsUs(1_000_000L, 8f))
    }

    @Test fun remappedPtsStaysMonotonic() {
        var prev = -1L
        for (frame in 0..240) {
            val pts = VideoProcessor.remappedPtsUs(frame * 33_333L, 8f)
            assertTrue(pts > prev, "pts $pts not > $prev at frame $frame")
            prev = pts
        }
    }

    @Test fun remappedPtsIdentityAtUnitSpeed() {
        assertEquals(123_456L, VideoProcessor.remappedPtsUs(123_456L, 1f))
    }

    @Test fun sampleBufferSizeHonoursFloorAndEncoderMax() {
        val floor = 2 * 1024 * 1024
        assertEquals(floor, VideoProcessor.sampleBufferSize(0))
        assertEquals(floor, VideoProcessor.sampleBufferSize(512 * 1024))
        assertEquals(4 * 1024 * 1024, VideoProcessor.sampleBufferSize(4 * 1024 * 1024))
    }
}
