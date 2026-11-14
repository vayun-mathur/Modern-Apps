package com.vayunmathur.library.ml

import android.content.Context
import android.graphics.Bitmap

/**
 * Person-versus-background segmentation, for `:camera`'s portrait bokeh.
 *
 * **MediaPipe Selfie Segmentation** (Apache-2.0), run on Vulkan compute at 256×256 with
 * fp16 weights. See `camera/src/main/assets/README.md` for provenance and
 * `library/ml/src/main/rust/src/nets/selfie.rs` for the forward pass.
 *
 * Replaces `com.vayunmathur.ncnn.PortraitSegmenter` and its `erdnet` model, which shipped
 * with no upstream URL, license or conversion recipe. This is a different model rather
 * than a port of that one, so its masks differ.
 *
 * GPU-only: if [isAvailable] is false the device has no Vulkan fp16 compute and [segment]
 * returns null, which callers treat as "no mask" rather than as an error.
 *
 * Not thread-safe: [segment] and [close] must not overlap.
 *
 * @param context used only to read the asset; not retained.
 * @param assetName the `.maml` in the app's assets.
 */
class SelfieSegmenter(context: Context, assetName: String = DEFAULT_ASSET) : AutoCloseable {

    private val native =
        NativeSegmenter(context, assetName, "SelfieSegmenter", MlNative::createSelfie)

    /** Whether the network came up. False means portrait bokeh is off on this device. */
    val isAvailable: Boolean get() = native.isAvailable

    /**
     * Run the network over [bitmap] and return a 256×256 mask, or null on failure.
     *
     * [bitmap] may be any size and any config: it is resized and normalised natively.
     */
    fun segment(bitmap: Bitmap): SegmentationMask? = native.segment(bitmap)

    override fun close() = native.close()

    /** Bundled-asset constants for [SelfieSegmenter]. */
    companion object {
        /** What `:camera` ships. */
        const val DEFAULT_ASSET: String = "selfie_segmentation.maml"
    }
}
