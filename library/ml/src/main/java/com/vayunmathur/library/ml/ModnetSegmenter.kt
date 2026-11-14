package com.vayunmathur.library.ml

import android.content.Context
import android.graphics.Bitmap

/**
 * Portrait alpha matting, for `:camera`'s still-capture portrait bokeh.
 *
 * **MODNet photographic-portrait-matting** (Apache-2.0), run on Vulkan compute at 512×512 with
 * fp16 weights. Unlike [SelfieSegmenter] it predicts a true alpha matte with strand-level
 * detail rather than a binary person mask. See `camera/src/main/assets/README.md` for
 * provenance and `library/ml/src/main/rust/src/nets/modnet.rs` for the forward pass.
 *
 * Still-capture only: the 15 fps preview keeps [SelfieSegmenter], whose 256×256 pass is
 * cheaper per frame. This runs once per shutter, so its larger arena is acceptable —
 * the same tradeoff [SubjectSegmenter] documents.
 *
 * GPU-only: if [isAvailable] is false the device has no Vulkan fp16 compute and [segment]
 * returns null, which the caller treats as "no mask" and keeps the sharp frame.
 *
 * Not thread-safe: [segment] and [close] must not overlap.
 *
 * @param context used only to read the asset; not retained.
 * @param assetName the `.maml` in the app's assets.
 */
class ModnetSegmenter(context: Context, assetName: String = DEFAULT_ASSET) : AutoCloseable {

    private val native =
        NativeSegmenter(context, assetName, "ModnetSegmenter", MlNative::createModnet)

    /** Whether the network came up. False means still-capture bokeh is off on this device. */
    val isAvailable: Boolean get() = native.isAvailable

    /**
     * Run the network over [bitmap] and return a 512×512 matte, or null on failure.
     *
     * [bitmap] may be any size and any config: it is resized and normalised natively.
     */
    fun segment(bitmap: Bitmap): SegmentationMask? = native.segment(bitmap)

    override fun close() = native.close()

    /** Bundled-asset constants for [ModnetSegmenter]. */
    companion object {
        /** What `:camera` ships for still capture. */
        const val DEFAULT_ASSET: String = "modnet.maml"
    }
}
