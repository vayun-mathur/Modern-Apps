package com.vayunmathur.camera.platform.camera2

import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.view.Surface
import com.vayunmathur.library.log.Log

/** Delivers one JPEG frame from a [StillOutput]; fires on the reader handler thread. */
fun interface JpegImageListener {
    fun onJpeg(jpegBytes: ByteArray, width: Int, height: Int, timestampNs: Long)
}

/**
 * Testable handle over a JPEG still output (an `ImageReader` in the real
 * driver). The session adds [surface] as its second capture-session output
 * alongside preview; JPEG bytes arrive through the listener.
 */
interface StillOutput : AutoCloseable {
    val surface: Surface
    fun setListener(listener: JpegImageListener?)
    override fun close()
}

/**
 * Camera2 migration Phase 4a: a JPEG `ImageReader` still source.
 *
 * Uses `acquireNextImage()` (not latest): a still must never be dropped in
 * favour of a newer frame — Phase 4a allows one capture in flight, so at
 * most one image is expected. The image is always closed; an unclosed
 * image exhausts the reader pool and stalls the stream.
 *
 * Ownership: whoever constructs this owns it and must call [close].
 */
class JpegStillOutput(
    width: Int,
    height: Int,
    maxImages: Int = DEFAULT_MAX_IMAGES,
    private val handler: Handler,
) : StillOutput {

    private val reader: ImageReader =
        ImageReader.newInstance(width, height, ImageFormat.JPEG, maxImages)

    override val surface: Surface get() = reader.surface

    @Volatile
    private var listener: JpegImageListener? = null

    init {
        reader.setOnImageAvailableListener({ onImageAvailable() }, handler)
    }

    override fun setListener(listener: JpegImageListener?) {
        this.listener = listener
    }

    private fun onImageAvailable() {
        val image = try {
            reader.acquireNextImage()
        } catch (e: IllegalStateException) {
            Log.error("StillImageSource", "acquireNextImage threw", e)
            null
        } ?: return
        try {
            val plane = image.planes.firstOrNull() ?: return
            val buffer = plane.buffer
            val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
            listener?.onJpeg(bytes, image.width, image.height, image.timestamp)
        } catch (e: IllegalStateException) {
            Log.error("StillImageSource", "reading JPEG image threw", e)
        } finally {
            try {
                image.close()
            } catch (e: IllegalStateException) {
                Log.error("StillImageSource", "image close threw", e)
            }
        }
    }

    override fun close() {
        try {
            reader.setOnImageAvailableListener(null, null)
        } catch (e: IllegalStateException) {
            Log.error("StillImageSource", "clearing listener threw", e)
        }
        reader.close()
    }

    companion object {
        const val DEFAULT_MAX_IMAGES: Int = 2
    }
}
