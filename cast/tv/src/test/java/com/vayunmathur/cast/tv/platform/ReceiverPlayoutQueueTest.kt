package com.vayunmathur.cast.tv.platform

import com.vayunmathur.cast.protocol.DecodableFrame
import com.vayunmathur.cast.protocol.FrameId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The receiver's playout queue, apart from any TV framework.
 *
 * Scheduling is by RTP-timestamp delta from the first frame, so playout follows capture
 * spacing rather than arrival spacing, and a mapping that has drifted a full buffer off
 * the target delay is re-established on the newest frame.
 */
class ReceiverPlayoutQueueTest {

    private fun frame(id: Long, rtpTimestamp: Long) = DecodableFrame(
        frameId = FrameId(id),
        rtpTimestamp = rtpTimestamp,
        isKeyFrame = id == 0L,
        payload = byteArrayOf(id.toByte()),
    )

    @Test
    fun `an empty queue has nothing due`() {
        assertNull(PlayoutQueue(targetDelayMs = 150, timebase = 1_000).due(1_000))
    }

    @Test
    fun `the first frame is due one target delay after it arrives`() {
        val queue = PlayoutQueue(targetDelayMs = 150, timebase = 1_000)
        queue.add(frame(0, 0), 1_000)
        assertNull(queue.due(1_000))
        assertEquals(0L, queue.due(1_150)?.frameId?.value)
        assertNull(queue.due(1_150))
    }

    @Test
    fun `frames come out in order at their capture spacing`() {
        val queue = PlayoutQueue(targetDelayMs = 150, timebase = 1_000)
        queue.add(frame(0, 0), 1_000)
        queue.add(frame(1, 30), 1_000)
        assertEquals(0L, queue.due(1_150)?.frameId?.value)
        assertNull(queue.due(1_150))
        assertEquals(1, queue.depth)
        assertEquals(1L, queue.due(1_180)?.frameId?.value)
    }

    @Test
    fun `a mapping that drifted far ahead is re-established on the newest frame`() {
        val queue = PlayoutQueue(targetDelayMs = 150, timebase = 1_000)
        queue.add(frame(0, 0), 0)
        queue.add(frame(1, 100_000), 10)
        assertEquals(1, queue.rebases)
    }

    @Test
    fun `a mapping that drifted far behind is re-established too`() {
        val queue = PlayoutQueue(targetDelayMs = 150, timebase = 1_000)
        queue.add(frame(0, 0), 0)
        queue.add(frame(1, 0), 1_000)
        assertEquals(1, queue.rebases)
    }

    @Test
    fun `frames straddling the 32-bit rtp wrap still play out in order`() {
        // RTP timestamps are 32 bits on the wire: at a 1 kHz timebase the
        // counter wraps at 4294967296. Frame 0 sits 6 ticks before the wrap
        // and frame 1 four ticks after it, i.e. 10 ms later, not ~49 days
        // earlier. A plain subtraction rebased onto frame 1 and left frame 0
        // scheduled one full wrap in the future, freezing playout for good.
        val queue = PlayoutQueue(targetDelayMs = 150, timebase = 1_000)
        queue.add(frame(0, 4_294_967_290L), 1_000)
        queue.add(frame(1, 4L), 1_010)
        assertEquals(0, queue.rebases)
        assertNull(queue.due(1_149))
        assertEquals(0L, queue.due(1_150)?.frameId?.value)
        assertNull(queue.due(1_159))
        assertEquals(1L, queue.due(1_160)?.frameId?.value)
    }

    @Test
    fun `clearing drops everything and re-establishes the mapping on the next frame`() {
        val queue = PlayoutQueue(targetDelayMs = 150, timebase = 1_000)
        queue.add(frame(0, 0), 1_000)
        queue.clear()
        assertEquals(0, queue.depth)
        assertNull(queue.due(10_000))
        queue.add(frame(5, 5_000), 2_000)
        assertNull(queue.due(2_000))
        assertEquals(5L, queue.due(2_150)?.frameId?.value)
    }
}
