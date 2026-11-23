package com.vayunmathur.camera.platform.camera2

import com.vayunmathur.camera.domain.PlaneView
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AnalysisImageSourceTest {

    private class FakeFrame : AnalysisFrame {
        val closed = AtomicInteger(0)
        override val width = 4
        override val height = 3
        override val timestampNs = 42L
        override val rotationDegrees = 90
        override val cropRect = com.vayunmathur.camera.domain.FrameCrop.fullFrame(4, 3)
        override val yPlane = PlaneView(ByteBuffer.wrap(ByteArray(12) { 9 }), 4, 1, 4, 3)
        override val planes = listOf(yPlane)
        override fun close() {
            closed.incrementAndGet()
        }
    }

    @Test fun gateAdmitsOneInFlightAndCountsDrops() {
        val gate = FrameDropGate()
        assertTrue(gate.tryBeginDelivery())
        assertFalse(gate.tryBeginDelivery())
        assertFalse(gate.tryBeginDelivery())
        assertEquals(2, gate.droppedFrames)
        assertEquals(1, gate.deliveredFrames)
        gate.endDelivery()
        assertTrue(gate.tryBeginDelivery())
    }

    @Test fun dispatchDeliversAndClosesFrame() {
        val seen = AtomicInteger(0)
        val dispatcher = AnalysisFrameDispatcher(
            consumer = { frame ->
                assertEquals(42L, frame.timestampNs)
                seen.incrementAndGet()
            },
            executor = { it.run() },
        )
        val frame = FakeFrame()
        assertTrue(dispatcher.dispatch(frame))
        assertEquals(1, seen.get())
        assertEquals(1, frame.closed.get())
        assertFalse(dispatcher.gate.isBusy)
    }

    @Test fun busyConsumerDropsNewFramesAndClosesThem() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val dispatcher = AnalysisFrameDispatcher(
                consumer = {
                    entered.countDown()
                    release.await(5, TimeUnit.SECONDS)
                },
                executor = executor,
            )
            val first = FakeFrame()
            assertTrue(dispatcher.dispatch(first))
            assertTrue(entered.await(5, TimeUnit.SECONDS))

            // Consumer busy on the executor: both of these must drop.
            val droppedA = FakeFrame()
            val droppedB = FakeFrame()
            assertFalse(dispatcher.dispatch(droppedA))
            assertFalse(dispatcher.dispatch(droppedB))
            assertEquals(2, dispatcher.gate.droppedFrames)
            assertEquals(1, droppedA.closed.get())
            assertEquals(1, droppedB.closed.get())

            release.countDown()
            executor.submit { }.get(5, TimeUnit.SECONDS)
            assertEquals(1, first.closed.get())
            assertFalse(dispatcher.gate.isBusy)

            val after = FakeFrame()
            assertTrue(dispatcher.dispatch(after))
            executor.submit { }.get(5, TimeUnit.SECONDS)
            assertEquals(1, after.closed.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun throwingConsumerStillClosesAndFreesGate() {
        val dispatcher = AnalysisFrameDispatcher(
            consumer = { throw IllegalStateException("consumer gone") },
            executor = { it.run() },
        )
        val frame = FakeFrame()
        assertTrue(dispatcher.dispatch(frame))
        assertEquals(1, frame.closed.get())
        assertFalse(dispatcher.gate.isBusy)
        assertTrue(dispatcher.dispatch(FakeFrame()))
    }
}
