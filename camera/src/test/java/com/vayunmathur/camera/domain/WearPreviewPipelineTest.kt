package com.vayunmathur.camera.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WearPreviewPipelineTest {

    /** Records every encode call so tests can pin size/ordering/quality. */
    private class RecordingEncoder(private val jpegSize: Int = 8) : PreviewJpegEncoder {
        val calls = mutableListOf<Triple<Int, Int, Int>>()

        override fun encode(argb: IntArray, width: Int, height: Int, quality: Int): ByteArray {
            calls += Triple(width, height, quality)
            // The pixels handed over must already be the downscaled frame.
            assertEquals(width * height, argb.size)
            return ByteArray(jpegSize) { 0xFF.toByte() }
        }
    }

    @Test
    fun dimensionsCapWidthAndNeverUpscale() {
        assertEquals(320 to 240, previewDimensions(1280, 960))
        assertEquals(320 to 180, previewDimensions(640, 360))
        assertEquals(320 to 320, previewDimensions(480, 480))
        assertEquals(240 to 180, previewDimensions(240, 180))
        assertEquals(1 to 1, previewDimensions(0, 0))
    }

    @Test
    fun encodeRunsOnceAtDownscaledSizeNeverFullSize() {
        val encoder = RecordingEncoder()
        val processor = PreviewFrameProcessor(encoder)
        val argb = IntArray(1280 * 960) { it }
        val encoded = processor.process(argb, 1280, 960)!!
        assertEquals(320, encoded.width)
        assertEquals(240, encoded.height)
        // Exactly one encode, at watch size — never a full-size encode first.
        assertEquals(listOf(Triple(320, 240, WearCameraProtocol.PREVIEW_JPEG_QUALITY)), encoder.calls)
        assertNull(processor.process(IntArray(3), 1280, 960))
        assertNull(processor.process(argb, 0, 960))
    }

    @Test
    fun downscaleSamplesNearestPixels() {
        // 4x2 -> 2x1 picks columns 0 and 2 of row 0.
        val argb = intArrayOf(10, 11, 12, 13, 20, 21, 22, 23)
        assertEquals(listOf(10, 12), downscaleArgb(argb, 4, 2, 2, 1).toList())
        assertEquals(argb.toList(), downscaleArgb(argb, 4, 2, 4, 2).toList())
    }

    @Test
    fun qualityAdaptsDownOnLargeFramesAndUpOnSmall() {
        val large = PreviewFrameProcessor(RecordingEncoder(jpegSize = WearCameraProtocol.PREVIEW_LARGE_FRAME_BYTES + 1))
        val argb = IntArray(320 * 240)
        large.process(argb, 320, 240)
        assertEquals(WearCameraProtocol.PREVIEW_JPEG_QUALITY - 5, large.nextQuality)

        val small = PreviewFrameProcessor(RecordingEncoder(jpegSize = 100))
        small.process(argb, 320, 240)
        assertEquals(WearCameraProtocol.PREVIEW_JPEG_QUALITY + 5, small.nextQuality)

        // Floor holds under repeated large frames.
        repeat(20) { large.process(argb, 320, 240) }
        assertEquals(WearCameraProtocol.PREVIEW_MIN_JPEG_QUALITY, large.nextQuality)
    }

    @Test
    fun latestOnlySlotDropsWhileOccupiedAndNeverQueues() {
        val slot = LatestOnlySlot<Int>()
        assertTrue(slot.offer(1))
        assertFalse(slot.offer(2))
        assertFalse(slot.offer(3))
        assertEquals(2L, slot.droppedCount)
        assertEquals(3L, slot.offeredCount)
        assertEquals(1, slot.take())
        assertNull(slot.take())
        assertTrue(slot.offer(4))
        assertEquals(4, slot.take())
    }

    @Test
    fun adaptiveRateBacksOffWhenSendExceedsIntervalAndRecovers() {
        val rate = AdaptivePreviewRate()
        assertEquals(WearCameraProtocol.PREVIEW_NOMINAL_FPS, rate.fps)
        // A send slower than the 200 ms interval at 5 fps steps down.
        rate.onFrameSent(durationMillis = 250L)
        assertEquals(4, rate.fps)
        rate.onSendFailed()
        assertEquals(3, rate.fps)
        // Sustained fast sends (under half the interval) recover one step
        // per streak of four, capped at the max.
        repeat(4) { rate.onFrameSent(durationMillis = 10L) }
        assertEquals(4, rate.fps)
        repeat(40) { rate.onFrameSent(durationMillis = 10L) }
        assertEquals(WearCameraProtocol.PREVIEW_MAX_FPS, rate.fps)
        // Floor holds.
        repeat(20) { rate.onSendFailed() }
        assertEquals(WearCameraProtocol.PREVIEW_MIN_FPS, rate.fps)
        assertEquals(500L, rate.intervalMillis)
    }
}
