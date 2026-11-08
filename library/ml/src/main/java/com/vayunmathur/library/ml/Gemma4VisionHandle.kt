package com.vayunmathur.library.ml

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import com.vayunmathur.library.log.Log
import androidx.core.graphics.scale
import java.io.File

/**
 * Gemma 4's vision tower: an image in, the soft tokens the decoder reads in its place.
 *
 * Restores the image input that `com.google.ai.edge.litertlm` used to provide. [Gemma4Handle] is
 * text-only; this produces the `[n, 1536]` block that stands in for an image in its prompt, and
 * [Gemma4Handle.generate] splices it in.
 *
 * # A separate file and a separate handle
 *
 * The tower is its own `.maml` with its own graph id, downloaded separately. A device that never
 * sends an image never fetches it, and a tower that fails to load leaves the assistant answering
 * text rather than taking it down.
 *
 * # The resize is here, the size is not
 *
 * Bitmap work belongs on the platform, as it does for `ClipEmbedder`. But the target size is the
 * reference preprocessor's aspect-ratio-preserving fit to a patch budget, and being one 48-pixel
 * block out changes how many soft tokens come back - so [MlNative.gemma4VisionSize] decides and
 * [encode] obeys.
 *
 * # Threading
 *
 * Not thread-safe. A new aspect ratio re-records the plan, so two concurrent encodes would race
 * on the recording.
 */
class Gemma4VisionHandle private constructor(private val file: File) : AutoCloseable {

    private var handle: Long = if (MlNative.isAvailable) create(file) else 0L

    /** Whether the tower came up. False leaves the assistant text-only rather than crashing. */
    val isAvailable: Boolean
        get() = handle != 0L

    /**
     * Soft tokens for [image] as one flat `n * 1536` array, or null.
     *
     * [softTokens] is the patch budget, and the cost is quadratic in it: at [SOFT_TOKENS_DEFAULT]
     * a square image is a 48x48 patch grid whose two attention score maps alone are 254 MB of
     * scratch. The smaller budgets in [SOFT_TOKEN_BUDGETS] trade detail for that.
     *
     * Divide the length by [OUT_DIM] for the number of soft tokens, which varies with the
     * image's aspect ratio and is what [Gemma4Handle.generate] must reserve placeholders for.
     */
    fun encode(image: Bitmap, softTokens: Int = SOFT_TOKENS_DEFAULT): FloatArray? {
        if (handle == 0L) return null
        val size = MlNative.gemma4VisionSize(image.width, image.height, softTokens)
        if (size == null || size.size != 2) {
            Log.status(TAG, "no vision grid for ${image.width}x${image.height} at $softTokens")
            return null
        }
        val (width, height) = size
        // ARGB_8888 because `getPixels` needs a readable config, and a HARDWARE bitmap is not.
        val scaled = runCatching {
            image.scale(width, height).copy(Bitmap.Config.ARGB_8888, false)
        }.getOrElse {
            Log.status(TAG, "cannot resize to ${width}x$height: $it")
            return null
        }
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        if (scaled !== image) scaled.recycle()
        return MlNative.encodeImageGemma4(handle, pixels, width, height)
    }

    override fun close() {
        val live = handle
        handle = 0L
        if (live != 0L) MlNative.destroyGemma4Vision(live)
    }

    override fun toString(): String = "Gemma 4 vision tower at $file"

    companion object {
        private const val TAG = "Gemma4VisionHandle"

        /** The tower. Native checks its graph id, so a wrong file fails at load. */
        const val VISION = "gemma4_vision.maml"

        /** Channels per soft token, which is the decoder's `hidden_size`. */
        const val OUT_DIM = 1536

        /**
         * Patch budgets the reference processor accepts. Mirrors
         * `nets::gemma4_vision::SOFT_TOKEN_BUDGETS`.
         */
        val SOFT_TOKEN_BUDGETS = intArrayOf(70, 140, 280, 560, 1120)

        /**
         * The budget to use unless a caller says otherwise, which is the reference's own default.
         *
         * A square image becomes a 48x48 patch grid and 256 soft tokens. That is the faithful
         * choice and the expensive one - see [encode].
         */
        const val SOFT_TOKENS_DEFAULT = 280

        /** The tower in a folder on disk. Construction never throws. */
        fun inDirectory(directory: File): Gemma4VisionHandle =
            Gemma4VisionHandle(File(directory, VISION))

        /**
         * Open the graph and hand the descriptor over.
         *
         * Native adopts it and closes it on every path including failure, so [handed] guards only
         * the window between detaching and the call being made.
         */
        private fun create(file: File): Long {
            if (!file.isFile) {
                Log.status(TAG, "${file.name} is missing")
                return 0L
            }
            val fd = runCatching {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    .use { it.detachFd() }
            }.getOrElse {
                Log.status(TAG, "cannot open ${file.name}: $it")
                return 0L
            }
            var handed = false
            try {
                val live = MlNative.createGemma4Vision(fd, 0L, file.length())
                handed = true
                return live
            } finally {
                if (!handed) runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
            }
        }
    }
}
