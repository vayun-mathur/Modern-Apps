package com.vayunmathur.camera.platform.camera2

import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.view.Surface
import com.vayunmathur.library.log.Log

/** Delivers one held RAW ring frame; fires on the reader handler thread. */
fun interface RawRingFrameListener {
    fun onRawRingFrame(frame: RawHeldFrame)
}

/** Maps a ring format to its `ImageFormat` stream format. */
fun RingFormat.toImageFormat(): Int = when (this) {
    RingFormat.YUV_420_888 -> ImageFormat.YUV_420_888
    RingFormat.PRIVATE -> ImageFormat.PRIVATE
    RingFormat.RAW10 -> ImageFormat.RAW10
    RingFormat.RAW_SENSOR -> ImageFormat.RAW_SENSOR
}

/** Bit depth a [RawHeldFrame.copyRawBytes] payload carries to Rust. */
fun RingFormat.rawBitDepth(): Int = when (this) {
    RingFormat.RAW10 -> 10
    else -> 16
}

/**
 * One RAW frame held by the RAW [ReprocessRing]. The owner must [close]
 * it: an unclosed image exhausts the reader pool and stalls the stream.
 * [copyRawBytes] copies the single plane out (RAW is one plane) and is
 * valid until [close]; the copy, not the image, crosses into Rust.
 */
interface RawHeldFrame : AutoCloseable {
    val meta: RingFrameMeta
    val format: RingFormat

    /** Packed plane bytes (RAW10 packed, or RAW_SENSOR u16 LE rows incl. row-stride padding), or null. */
    fun copyRawBytes(): ByteArray?

    /** Row stride of the copied plane in bytes (Rust unpack assumes tight rows; see glue). */
    val rowStrideBytes: Int get() = 0

    override fun close()
}

/** Testable handle over the ring's RAW `ImageReader` (real driver below). */
interface RawRingOutput : AutoCloseable {
    val surface: Surface
    fun setListener(listener: RawRingFrameListener?)
    override fun close()
}

/** [RawHeldFrame] over a real Camera2 RAW [Image]. */
class ImageRawHeldFrame(
    private val image: Image,
    override val format: RingFormat,
) : RawHeldFrame {
    override val meta: RingFrameMeta = RingFrameMeta(
        sensorTimestampNs = image.timestamp,
        width = image.width,
        height = image.height,
        format = format,
    )

    override val rowStrideBytes: Int
        get() = try {
            image.planes.firstOrNull()?.rowStride ?: 0
        } catch (e: IllegalStateException) {
            0
        }

    @Volatile
    private var closed = false

    override fun copyRawBytes(): ByteArray? {
        if (closed) return null
        return try {
            val plane = image.planes.firstOrNull() ?: return null
            val buffer = plane.buffer.duplicate()
            ByteArray(buffer.remaining()).also { buffer.get(it) }
        } catch (e: IllegalStateException) {
            Log.error("RawRingSource", "RAW plane copy failed", e)
            null
        } catch (e: IllegalArgumentException) {
            Log.error("RawRingSource", "RAW plane copy failed", e)
            null
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            image.close()
        } catch (e: IllegalStateException) {
            Log.error("RawRingSource", "RAW image close threw", e)
        }
    }
}

/**
 * RAW `ImageReader` ring source. Mirrors [YuvRingOutput]: `acquireLatest`
 * semantics, every acquired image becomes a [RawHeldFrame] the session's
 * RAW [ReprocessRing] evicts/closes. RAW stream combinations vary wildly
 * per HAL, so construction failure is expected on many devices and the
 * driver degrades to no RAW ring (policy then excludes the RAW path).
 *
 * Ownership: whoever constructs this owns it and must call [close].
 */
class RawImageReaderOutput(
    width: Int,
    height: Int,
    private val format: RingFormat,
    maxImages: Int = RawRingConfig.DEFAULT_CAPACITY,
    private val handler: Handler,
) : RawRingOutput {

    private val reader: ImageReader =
        ImageReader.newInstance(width, height, format.toImageFormat(), maxImages)

    override val surface: Surface get() = reader.surface

    @Volatile
    private var listener: RawRingFrameListener? = null

    init {
        reader.setOnImageAvailableListener({ onImageAvailable() }, handler)
    }

    override fun setListener(listener: RawRingFrameListener?) {
        this.listener = listener
    }

    private fun onImageAvailable() {
        val image = try {
            reader.acquireLatestImage()
        } catch (e: IllegalStateException) {
            Log.error("RawRingSource", "acquireLatestImage threw", e)
            null
        } ?: return
        val frame = ImageRawHeldFrame(image, format)
        val current = listener
        if (current == null) {
            frame.close()
            return
        }
        current.onRawRingFrame(frame)
    }

    override fun close() {
        try {
            reader.setOnImageAvailableListener(null, null)
        } catch (e: IllegalStateException) {
            Log.error("RawRingSource", "clearing listener threw", e)
        }
        reader.close()
    }
}

/** Gate for the RAW night path. Default OFF, mirroring [ReprocessRingGate]. */
object RawRingGate {
    const val ENABLED: Boolean = false
}

/** What the RAW half of the dual ring holds. */
data class RawRingConfig(
    val enabled: Boolean = false,
    val capacity: Int = DEFAULT_CAPACITY,
) {
    init {
        require(capacity > 0) { "raw ring capacity must be positive" }
    }

    companion object {
        const val DEFAULT_CAPACITY: Int = 6
        val DISABLED = RawRingConfig(enabled = false)
    }
}
