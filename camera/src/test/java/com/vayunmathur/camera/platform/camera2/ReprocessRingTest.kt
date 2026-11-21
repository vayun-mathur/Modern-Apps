package com.vayunmathur.camera.platform.camera2

import com.vayunmathur.camera.domain.PlaneView
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReprocessRingTest {

    private fun meta(ts: Long, exposureNs: Long? = 10L, format: RingFormat = RingFormat.YUV_420_888) =
        RingFrameMeta(sensorTimestampNs = ts, exposureTimeNs = exposureNs, format = format)

    // -- eviction -------------------------------------------------------------

    @Test fun offerEvictsOldestAndCounts() {
        val ring = ReprocessRing<String>(capacity = 3)
        assertNull(ring.offer(meta(1), "a"))
        assertNull(ring.offer(meta(2), "b"))
        assertNull(ring.offer(meta(3), "c"))
        assertEquals("a", ring.offer(meta(4), "d"))
        assertEquals(3, ring.size)
        assertEquals(1, ring.evictedFrames)
        assertEquals(4, ring.offeredFrames)
        assertEquals(listOf(2L, 3L, 4L), ring.metas().map { it.sensorTimestampNs })
    }

    @Test fun defaultCapacityIsEight() {
        val ring = ReprocessRing<Int>()
        repeat(8) { assertNull(ring.offer(meta(it.toLong()), it)) }
        assertEquals(0, ring.evictedFrames)
        assertEquals(0, ring.offer(meta(8), 8))
        assertEquals(8, ring.size)
    }

    @Test fun drainAndRemoveReturnPayloads() {
        val ring = ReprocessRing<String>(capacity = 4)
        ring.offer(meta(1), "a"); ring.offer(meta(2), "b"); ring.offer(meta(3), "c")
        assertEquals("b", ring.remove(2))
        assertNull(ring.remove(99))
        assertEquals(listOf("a", "c"), ring.drainAll())
        assertTrue(ring.isEmpty())
    }

    // -- selection ------------------------------------------------------------

    @Test fun selectPicksNearestExposureCompleteFrame() {
        val ring = ReprocessRing<String>(capacity = 8)
        ring.offer(meta(100), "a"); ring.offer(meta(200), "b"); ring.offer(meta(300), "c")
        // The 300 frame ends at 310 (still exposing at 260); nearest
        // exposure-complete frame is 200.
        val hit = ring.select(260) as RingSelection.Hit
        assertEquals("b", hit.frame)
    }

    @Test fun selectSkipsFrameStillExposingAtShutter() {
        val ring = ReprocessRing<String>(capacity = 8)
        ring.offer(meta(100, exposureNs = 10), "done-long-ago")
        ring.offer(meta(200, exposureNs = 10), "done")
        ring.offer(meta(250, exposureNs = 100), "still-exposing") // ends 350 > 260
        val hit = ring.select(260) as RingSelection.Hit
        assertEquals("done", hit.frame)
        assertEquals(60, hit.ageAtShutterNs)
    }

    @Test fun selectTieGoesToLaterFrame() {
        // Only a duplicate timestamp can tie (a frame starting after the
        // shutter is never exposure-complete at it); later arrival wins.
        val ring = ReprocessRing<String>(capacity = 8)
        ring.offer(meta(200, exposureNs = 10), "earlier")
        ring.offer(meta(200, exposureNs = 10), "later")
        val hit = ring.select(250) as RingSelection.Hit
        assertEquals("later", hit.frame)
    }

    @Test fun selectMissesWhenNothingFullyExposed() {
        val ring = ReprocessRing<String>(capacity = 8)
        ring.offer(meta(250, exposureNs = 100), "exposing")
        val miss = ring.select(260) as RingSelection.Miss
        assertEquals(RingMissReason.NO_EXPOSURE_COMPLETE_FRAME, miss.reason)
        val empty = ReprocessRing<String>().select(1) as RingSelection.Miss
        assertEquals(RingMissReason.EMPTY, empty.reason)
    }

    @Test fun unknownExposureCountsStartedFrameCompleteOnly() {
        val frame = RingFrameMeta(sensorTimestampNs = 100, exposureTimeNs = null)
        assertTrue(frame.isExposureComplete(100))
        assertFalse(frame.isExposureComplete(99))
        assertEquals(110L, RingFrameMeta(100, 10).exposureEndNs)
        assertNull(RingFrameMeta(100, null).exposureEndNs)
    }

    @Test fun latestCompleteAndMetadataPatch() {
        val ring = ReprocessRing<String>(capacity = 8)
        ring.offer(RingFrameMeta(sensorTimestampNs = 100), "a")
        ring.offer(RingFrameMeta(sensorTimestampNs = 200), "b")
        assertTrue(ring.attachMetadata(200, exposureTimeNs = 5, aeState = 2, iso = 400))
        assertFalse(ring.attachMetadata(999, exposureTimeNs = 5))
        assertEquals("b", ring.latestComplete(205)?.first)
        assertEquals(400, ring.latestComplete(205)?.second?.iso)
        // At 204 b is not yet fully exposed; a (unknown exposure, started) is.
        assertEquals("a", ring.latestComplete(204)?.first)
    }

    @Test fun rawFormatSlotHoldsAlongsideYuvShape() {
        // RAW is out of phase, but a second ring of RAW10 metas uses the
        // same eviction/selection math without changes to this class.
        val rawRing = ReprocessRing<String>(capacity = 2)
        rawRing.offer(meta(1, format = RingFormat.RAW10), "r1")
        rawRing.offer(meta(2, format = RingFormat.RAW10), "r2")
        assertEquals(RingFormat.RAW10, rawRing.metas().first().format)
        assertEquals("r2", (rawRing.select(12) as RingSelection.Hit).frame)
    }

    // -- latency model --------------------------------------------------------

    @Test fun ringLatencyHasNoAeWaitOrFreshExposure() {
        val conventional = StillLatencyModel.conventional(
            aeConvergenceWaitMs = 120, exposureMs = 33, readoutEncodeMs = 45,
        )
        assertEquals(198, conventional.totalMs)
        val reprocess = StillLatencyModel.ringReprocess(reprocessMs = 20, readoutEncodeMs = 45)
        assertEquals(0, reprocess.aeConvergenceWaitMs)
        assertEquals(0, reprocess.freshExposureMs)
        assertEquals(65, reprocess.totalMs)
        assertEquals(133, StillLatencyModel.savedMs(conventional, reprocess))
        val direct = StillLatencyModel.ringDirectEncode(readoutEncodeMs = 45)
        assertEquals(45, direct.totalMs)
        assertTrue(StillLatencyModel.savedMs(conventional, direct) > 0)
    }

    // -- NV21 conversion ------------------------------------------------------

    @Test fun nv21ConversionHonoursStridesAndInterleavesVu() {
        // 2x2: Y plane rowStride 3 (1 pad), chroma 1x1 pixelStride 1.
        val y = PlaneView(ByteBuffer.wrap(byteArrayOf(10, 20, 0, 30, 40, 0)), 3, 1, 2, 2)
        val u = PlaneView(ByteBuffer.wrap(byteArrayOf(100)), 1, 1, 1, 1)
        val v = PlaneView(ByteBuffer.wrap(byteArrayOf(110)), 1, 1, 1, 1)
        val nv21 = Yuv420ToNv21.convert(listOf(y, u, v), 2, 2)!!
        assertTrue(nv21.contentEquals(byteArrayOf(10, 20, 30, 40, 110, 100)))
        assertNull(Yuv420ToNv21.convert(listOf(y), 2, 2))
    }
}
