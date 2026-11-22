package com.vayunmathur.camera.platform.camera2

import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.view.Surface
import com.vayunmathur.camera.domain.PlaneView
import com.vayunmathur.library.log.Log
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * One analysis frame as plane views. The buffers are borrowed: they are only
 * valid until [close], consumers must read them synchronously during the
 * delivery call and retain nothing. There is deliberately no bitmap accessor
 * on this path — no `ImageProxy.toBitmap()` equivalent exists here.
 */
interface AnalysisFrame : AutoCloseable {
    val width: Int
    val height: Int
    val timestampNs: Long

    /** Plane 0 (Y in YUV_420_888), the only plane the cheap consumers read. */
    val yPlane: PlaneView

    /** All planes, in format order. */
    val planes: List<PlaneView>
}

/** Synchronous plane consumer; see the lifetime contract on [AnalysisFrame]. */
fun interface AnalysisConsumer {
    fun onFrame(frame: AnalysisFrame)
}

/**
 * Testable handle over a YUV analysis output (an `ImageReader` in the real
 * driver). The session adds [surface] as a capture-session output alongside
 * preview/still and includes it in the repeating request targets; frames
 * arrive through the consumer given at creation. Mirrors [RingOutput].
 */
interface AnalysisOutput : AutoCloseable {
    val surface: Surface
    override fun close()
}

/**
 * The drop policy, isolated from Android so it is unit-testable: at most one
 * frame is in flight to the consumer. A frame that arrives while the consumer
 * is busy is dropped (and counted) instead of queued — analysis consumers
 * want the latest frame, never a backlog of stale ones. This mirrors
 * `acquireLatestImage()` semantics one level up, where the count is visible.
 */
class FrameDropGate {
    private val busy = AtomicBoolean(false)
    private val dropped = AtomicLong(0)
    private val delivered = AtomicLong(0)

    val droppedFrames: Long get() = dropped.get()
    val deliveredFrames: Long get() = delivered.get()
    val isBusy: Boolean get() = busy.get()

    /** Claims the single in-flight slot; false means this frame must drop. */
    fun tryBeginDelivery(): Boolean {
        if (!busy.compareAndSet(false, true)) {
            dropped.incrementAndGet()
            return false
        }
        delivered.incrementAndGet()
        return true
    }

    fun endDelivery() {
        busy.set(false)
    }

    /** Counts a frame dropped before delivery was even attempted. */
    fun recordDrop() {
        dropped.incrementAndGet()
    }
}

/**
 * Hands frames to [consumer] on [executor] under [gate]'s drop policy. The
 * frame stays open while the consumer runs and is closed on every path —
 * an unclosed ImageReader image exhausts the reader's image pool and stalls
 * the stream, the same failure mode as an unclosed CameraX proxy.
 */
class AnalysisFrameDispatcher(
    private val consumer: AnalysisConsumer,
    private val executor: Executor,
    val gate: FrameDropGate = FrameDropGate(),
) {
    /** Returns true when the frame was accepted for delivery. */
    fun dispatch(frame: AnalysisFrame): Boolean {
        if (!gate.tryBeginDelivery()) {
            closeQuietly(frame)
            return false
        }
        return try {
            executor.execute {
                try {
                    consumer.onFrame(frame)
                } catch (e: IllegalStateException) {
                    Log.error("AnalysisImageSource", "consumer threw", e)
                } catch (e: IllegalArgumentException) {
                    Log.error("AnalysisImageSource", "consumer threw", e)
                } finally {
                    closeQuietly(frame)
                    gate.endDelivery()
                }
            }
            true
        } catch (e: RejectedExecutionException) {
            gate.endDelivery()
            gate.recordDrop()
            closeQuietly(frame)
            false
        }
    }

    private fun closeQuietly(frame: AnalysisFrame) {
        try {
            frame.close()
        } catch (e: IllegalStateException) {
            Log.error("AnalysisImageSource", "frame close threw", e)
        }
    }
}

/** [AnalysisFrame] over a Camera2 [Image]; duplicates buffers so position state is private. */
private class ImageAnalysisFrame(private val image: Image) : AnalysisFrame {
    override val width: Int get() = image.width
    override val height: Int get() = image.height
    override val timestampNs: Long get() = image.timestamp

    override val planes: List<PlaneView> = image.planes.map { plane ->
        PlaneView(
            buffer = plane.buffer.duplicate(),
            rowStride = plane.rowStride,
            pixelStride = plane.pixelStride,
            width = image.width,
            height = image.height,
        )
    }

    override val yPlane: PlaneView get() = planes[0]

    override fun close() {
        image.close()
    }
}

/**
 * Camera2 migration Phase 3: an `ImageReader` (YUV_420_888) analysis source.
 *
 * The listener uses `acquireLatestImage()`: when the HAL delivers faster
 * than the consumer drains, intermediate images are discarded by the reader
 * itself, and any image that still arrives while [FrameDropGate] reports the
 * consumer busy is closed immediately and counted in [droppedFrames]. Frames
 * are never queued — a stale analysis frame has no value to luma or motion
 * consumers, and queueing would only add latency to the latest one.
 *
 * Ownership: whoever constructs this owns it and must call [close] when the
 * session ends. The reader's [surface] is the capture-session target.
 */
class AnalysisImageSource(
    width: Int,
    height: Int,
    maxImages: Int = DEFAULT_MAX_IMAGES,
    handler: Handler,
    executor: Executor,
    consumer: AnalysisConsumer,
) : AnalysisOutput {

    private val reader: ImageReader =
        ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, maxImages)
    private val dispatcher = AnalysisFrameDispatcher(consumer, executor)

    override val surface: Surface get() = reader.surface

    /** Frames dropped because the consumer was busy (see [FrameDropGate]). */
    val droppedFrames: Long get() = dispatcher.gate.droppedFrames
    val deliveredFrames: Long get() = dispatcher.gate.deliveredFrames

    init {
        reader.setOnImageAvailableListener({ r -> onImageAvailable(r) }, handler)
    }

    private fun onImageAvailable(reader: ImageReader) {
        val image = try {
            reader.acquireLatestImage()
        } catch (e: IllegalStateException) {
            Log.error("AnalysisImageSource", "acquireLatestImage threw", e)
            null
        } ?: return
        dispatcher.dispatch(ImageAnalysisFrame(image))
    }

    override fun close() {
        try {
            reader.setOnImageAvailableListener(null, null)
        } catch (e: IllegalStateException) {
            Log.error("AnalysisImageSource", "clearing listener threw", e)
        }
        reader.close()
    }

    companion object {
        const val DEFAULT_MAX_IMAGES: Int = 3
    }
}
