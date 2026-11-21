package com.vayunmathur.camera.platform.camera2

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.view.Surface
import com.vayunmathur.camera.domain.PlaneView
import com.vayunmathur.library.log.Log
import java.io.ByteArrayOutputStream

/** Delivers one held ring frame; fires on the reader handler thread. */
fun interface RingFrameListener {
    fun onRingFrame(frame: RingHeldFrame)
}

/**
 * One frame held by the [ReprocessRing]. The owner must [close] it:
 * an unclosed image exhausts the reader pool and stalls the stream.
 * [encodeJpeg] is the direct-conversion fallback used when HAL
 * reprocessing is unavailable; it is valid until [close].
 */
interface RingHeldFrame : AutoCloseable {
    val meta: RingFrameMeta

    /** The underlying `Image` for the reprocess writer, or null in fakes. */
    val nativeImageForReprocess: Any? get() = null

    /** JPEG bytes from this frame's YUV planes, or null on failure. */
    fun encodeJpeg(quality: Int = DEFAULT_JPEG_QUALITY): ByteArray?

    override fun close()

    companion object {
        const val DEFAULT_JPEG_QUALITY: Int = 95
    }
}

/** Testable handle over the ring's YUV `ImageReader` (real driver below). */
interface RingOutput : AutoCloseable {
    val surface: Surface
    fun setListener(listener: RingFrameListener?)
    override fun close()
}

/** Pure YUV_420_888 -> NV21 assembly (the layout `YuvImage` consumes). */
object Yuv420ToNv21 {

    /** [planes] are Y, U, V in format order, any row/pixel stride. */
    fun convert(planes: List<PlaneView>, width: Int, height: Int): ByteArray? {
        if (planes.size < 3 || width <= 0 || height <= 0) return null
        val out = ByteArray(width * height + 2 * ((width + 1) / 2) * ((height + 1) / 2))
        val yPlane = planes[0]
        var offset = 0
        for (row in 0 until height) {
            for (col in 0 until width) {
                out[offset++] = sample(yPlane, col, row)
            }
        }
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val uPlane = planes[1]
        val vPlane = planes[2]
        for (row in 0 until chromaHeight) {
            for (col in 0 until chromaWidth) {
                // NV21 is interleaved V,U.
                out[offset++] = sample(vPlane, col, row)
                out[offset++] = sample(uPlane, col, row)
            }
        }
        return out
    }

    private fun sample(plane: PlaneView, x: Int, y: Int): Byte {
        val buffer = plane.buffer.duplicate()
        val index = y * plane.rowStride + x * plane.pixelStride
        if (index < 0 || index >= buffer.limit()) return 0
        return buffer.get(index)
    }
}

/** [RingHeldFrame] over a real Camera2 [Image]. */
class ImageRingHeldFrame(private val image: Image) : RingHeldFrame {
    override val meta: RingFrameMeta = RingFrameMeta(
        sensorTimestampNs = image.timestamp,
        width = image.width,
        height = image.height,
        format = RingFormat.YUV_420_888,
    )

    override val nativeImageForReprocess: Any get() = image

    @Volatile
    private var closed = false

    override fun encodeJpeg(quality: Int): ByteArray? {
        if (closed) return null
        return try {
            val planes = image.planes.map { plane ->
                PlaneView(
                    buffer = plane.buffer.duplicate(),
                    rowStride = plane.rowStride,
                    pixelStride = plane.pixelStride,
                    width = image.width,
                    height = image.height,
                )
            }
            val nv21 = Yuv420ToNv21.convert(planes, image.width, image.height) ?: return null
            val yuv = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
            ByteArrayOutputStream().use { out ->
                if (!yuv.compressToJpeg(Rect(0, 0, image.width, image.height), quality, out)) {
                    null
                } else {
                    out.toByteArray()
                }
            }
        } catch (e: IllegalStateException) {
            Log.error("YuvRingSource", "ring frame JPEG encode failed", e)
            null
        } catch (e: IllegalArgumentException) {
            Log.error("YuvRingSource", "ring frame JPEG encode failed", e)
            null
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            image.close()
        } catch (e: IllegalStateException) {
            Log.error("YuvRingSource", "ring image close threw", e)
        }
    }
}

/**
 * YUV `ImageReader` ring source. Uses `acquireLatestImage()` (Phase-3
 * semantics): when the HAL outruns the session, intermediate images are
 * discarded by the reader itself; every acquired image becomes a
 * [RingHeldFrame] the session's [ReprocessRing] evicts/closes.
 *
 * Ownership: whoever constructs this owns it and must call [close].
 */
class YuvRingOutput(
    width: Int,
    height: Int,
    maxImages: Int = ReprocessRingConfig.DEFAULT_CAPACITY,
    private val handler: Handler,
) : RingOutput {

    private val reader: ImageReader =
        ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, maxImages)

    override val surface: Surface get() = reader.surface

    @Volatile
    private var listener: RingFrameListener? = null

    init {
        reader.setOnImageAvailableListener({ onImageAvailable() }, handler)
    }

    override fun setListener(listener: RingFrameListener?) {
        this.listener = listener
    }

    private fun onImageAvailable() {
        val image = try {
            reader.acquireLatestImage()
        } catch (e: IllegalStateException) {
            Log.error("YuvRingSource", "acquireLatestImage threw", e)
            null
        } ?: return
        val frame = ImageRingHeldFrame(image)
        val current = listener
        if (current == null) {
            frame.close()
            return
        }
        current.onRingFrame(frame)
    }

    override fun close() {
        try {
            reader.setOnImageAvailableListener(null, null)
        } catch (e: IllegalStateException) {
            Log.error("YuvRingSource", "clearing listener threw", e)
        }
        reader.close()
    }
}
