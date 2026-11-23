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
 * Static per-source metadata an [AnalysisImageSource] needs to stamp
 * each frame with its display rotation: the lens's sensor orientation
 * and facing (from the characteristics snapshot) plus a live supplier
 * of the current display rotation in degrees (0/90/180/270), read per
 * frame because the display rotates while a session lives.
 */
data class AnalysisFrameMetadata(
    val sensorOrientationDegrees: Int = 0,
    val lensFacing: com.vayunmathur.camera.domain.LensFacing? = null,
    val displayRotationDegrees: () -> Int = { 0 },
)

/**
 * One analysis frame as plane views. The buffers are borrowed: they are only
 * valid until [close], consumers must read them synchronously during the
 * delivery call and retain nothing. A sensor-oriented bitmap is available
 * via [toBitmapFromPlanes].
 *
 * [rotationDegrees] is the CameraX `imageInfo.rotationDegrees`
 * equivalent, computed from the source's [AnalysisFrameMetadata] via
 * [com.vayunmathur.camera.domain.AnalysisRotation]; [cropRect] is the
 * full frame (the analysis stream applies no scaler crop of its own),
 * the honest equivalent of a full-frame `ImageProxy.cropRect`.
 */
interface AnalysisFrame : AutoCloseable {
    val width: Int
    val height: Int
    val timestampNs: Long

    /** Clockwise degrees to rotate this sensor frame to display-upright. */
    val rotationDegrees: Int

    /** Crop of this frame in its own coordinates (full frame today). */
    val cropRect: com.vayunmathur.camera.domain.FrameCrop

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
 * Converts this frame to a sensor-oriented `Bitmap` via the pure
 * [com.vayunmathur.camera.domain.Yuv420ToArgb] math (BT.601 limited
 * range, strides honoured, no rotation, no crop — the same orientation
 * contract `ImageProxy.toBitmap()` hands the bitmap consumers). Must be
 * called synchronously while the frame is open; the caller owns the
 * returned bitmap. Null when the planes cannot describe a full frame.
 * Rotation/crop travel separately on the frame ([AnalysisFrame.rotationDegrees],
 * [AnalysisFrame.cropRect]), exactly as CameraX carries them on
 * `ImageProxy.imageInfo`/`cropRect`.
 */
fun AnalysisFrame.toBitmapFromPlanes(): android.graphics.Bitmap? {
    val argb = com.vayunmathur.camera.domain.Yuv420ToArgb.convert(planes, width, height)
        ?: return null
    return android.graphics.Bitmap.createBitmap(
        argb, width, height, android.graphics.Bitmap.Config.ARGB_8888,
    )
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
private class ImageAnalysisFrame(
    private val image: Image,
    private val metadata: AnalysisFrameMetadata,
) : AnalysisFrame {
    override val width: Int get() = image.width
    override val height: Int get() = image.height
    override val timestampNs: Long get() = image.timestamp

    override val rotationDegrees: Int
        get() = com.vayunmathur.camera.domain.AnalysisRotation.rotationDegrees(
            metadata.sensorOrientationDegrees,
            metadata.displayRotationDegrees(),
            metadata.lensFacing,
        )

    override val cropRect: com.vayunmathur.camera.domain.FrameCrop
        get() = com.vayunmathur.camera.domain.FrameCrop.fullFrame(image.width, image.height)

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
    private val metadata: AnalysisFrameMetadata = AnalysisFrameMetadata(),
    /** Executor this source owns (shut down by [close]); null when borrowed. */
    private val ownedExecutor: java.util.concurrent.ExecutorService? = null,
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
        dispatcher.dispatch(ImageAnalysisFrame(image, metadata))
    }

    override fun close() {
        try {
            reader.setOnImageAvailableListener(null, null)
        } catch (e: IllegalStateException) {
            Log.error("AnalysisImageSource", "clearing listener threw", e)
        }
        reader.close()
        ownedExecutor?.shutdown()
    }

    companion object {
        const val DEFAULT_MAX_IMAGES: Int = 3
    }
}
