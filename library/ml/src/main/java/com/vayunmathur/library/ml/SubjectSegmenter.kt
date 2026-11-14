package com.vayunmathur.library.ml

import android.content.Context
import android.graphics.Bitmap

/**
 * General salient-object detection, for `:photos`'s auto-select-subject.
 *
 * **U²-Net portable** (Apache-2.0), run on Vulkan compute at 320×320 with fp16 weights.
 * Unlike [SelfieSegmenter] it is not person-specific: it predicts a per-pixel saliency map
 * and so picks out arbitrary subjects. See `photos/src/main/assets/README.md` for
 * provenance and `library/ml/src/main/rust/src/nets/u2netp.rs` for the forward pass.
 *
 * Replaces `com.vayunmathur.ncnn.Segmenter`. Same network, re-sourced from a licensed ONNX
 * export — the ncnn model's op inventory matched it exactly — so masks should look the
 * same as before, unlike `:camera`'s.
 *
 * Its activation arena is 76 MiB of device memory, so [close] it when the editor session
 * ends rather than holding it for the life of the process.
 *
 * GPU-only: if [isAvailable] is false the device has no Vulkan fp16 compute and [segment]
 * returns null, which the caller treats as "no subject found".
 *
 * Not thread-safe: [segment] and [close] must not overlap.
 *
 * @param context used only to read the asset; not retained.
 * @param assetName the `.maml` in the app's assets.
 */
class SubjectSegmenter(context: Context, assetName: String = DEFAULT_ASSET) : AutoCloseable {

    private val native =
        NativeSegmenter(context, assetName, "SubjectSegmenter", MlNative::createU2netp)

    /** Whether the network came up. False means auto-select-subject is off on this device. */
    val isAvailable: Boolean get() = native.isAvailable

    /**
     * Run the network over [bitmap] and return a 320×320 saliency map, or null on failure.
     *
     * [bitmap] may be any size and any config: it is resized and normalised natively.
     */
    fun segment(bitmap: Bitmap): SegmentationMask? = native.segment(bitmap)

    override fun close() = native.close()

    /** Bundled-asset constants for [SubjectSegmenter]. */
    companion object {
        /** What `:photos` ships. */
        const val DEFAULT_ASSET: String = "u2netp.maml"
    }
}
