package com.vayunmathur.photos.platform

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.vayunmathur.photos.data.BitmapReference
import com.vayunmathur.photos.data.PerspectiveCorners
import com.vayunmathur.photos.data.PixelLayer
import com.vayunmathur.photos.data.Selection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pixel and geometry operations for the photo editor, split out of
 * [PhotoEditViewModel] so neither class exceeds the function-count cap.
 * All mutations funnel through [PhotoEditViewModel.updateDocument], so undo
 * and preview stay in one place.
 */
class PixelEditController(private val vm: PhotoEditViewModel) {

    /**
     * Applies [transform] to the active pixel layer's bitmap on a background thread.
     * If a [selection] is present, the result is constrained to the selected area.
     */
    fun applyToActivePixelLayer(transform: (Bitmap) -> Bitmap) {
        val doc = vm.document.value
        val index = doc.activeLayerIndex
        val layer = doc.layers.getOrNull(index) as? PixelLayer ?: return
        val sel = vm.selection.value
        vm.editScope.launch(Dispatchers.Default) {
            val src = layer.bitmapRef.bitmap
            val edited = transform(src)
            val finalBmp = if (sel != null && !sel.isEmpty()) {
                blendWithSelection(src, edited, sel)
            } else edited
            withContext(Dispatchers.Main) {
                vm.updateDocument {
                    it.updateLayer(index) { l ->
                        // Layer may have been replaced while the transform ran off-thread.
                        (l as? PixelLayer)?.copy(bitmapRef = BitmapReference(finalBmp)) ?: l
                    }
                }
            }
        }
    }

    /** Remove the current selection's contents via content-aware fill (inpaint). */
    fun contentAwareFillSelection() {
        val sel = vm.selection.value ?: return
        if (sel.isEmpty()) return
        val doc = vm.document.value
        val index = doc.activeLayerIndex
        val layer = doc.layers.getOrNull(index) as? PixelLayer ?: return
        vm.editScope.launch(Dispatchers.Default) {
            val filled = com.vayunmathur.photos.data.inpaintBitmap(
                layer.bitmapRef.bitmap, sel.mask, sel.width, sel.height,
            )
            withContext(Dispatchers.Main) {
                vm.updateDocument {
                    it.updateLayer(index) { l -> (l as? PixelLayer)?.copy(bitmapRef = BitmapReference(filled)) ?: l }
                }
            }
        }
    }

    fun rotate(delta: Float) = vm.updateDocument { it.copy(rotation = it.rotation + delta) }

    fun setRotation(angle: Float) = vm.updateDocument { it.copy(rotation = angle) }

    fun setCropRect(rect: androidx.compose.ui.geometry.Rect) =
        vm.updateDocument { it.copy(cropRect = rect) }

    fun setCroppingPreview(cropping: Boolean) {
        vm.croppingPreview = cropping
        vm.requestPreviewUpdate(immediate = true)
    }

    fun setPerspective(corners: PerspectiveCorners) =
        vm.updateDocument { it.copy(perspectiveCorners = corners) }

    /** Flip the active pixel layer horizontally or vertically. */
    fun flipActiveLayer(horizontal: Boolean) {
        val doc = vm.document.value
        val index = doc.activeLayerIndex
        val layer = doc.layers.getOrNull(index) as? PixelLayer ?: return
        vm.editScope.launch(Dispatchers.Default) {
            val src = layer.bitmapRef.bitmap
            val m = Matrix().apply {
                if (horizontal) preScale(-1f, 1f, src.width / 2f, src.height / 2f)
                else preScale(1f, -1f, src.width / 2f, src.height / 2f)
            }
            val flipped = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
            withContext(Dispatchers.Main) {
                vm.updateDocument { doc ->
                    doc.updateLayer(index) { l -> (l as? PixelLayer)?.copy(bitmapRef = BitmapReference(flipped)) ?: l }
                }
            }
        }
    }

    /**
     * Free-transform (scale/rotate/skew/distort) the active pixel layer by warping
     * its content to [corners] within the same canvas bounds.
     */
    fun transformActiveLayer(corners: PerspectiveCorners) {
        if (corners.isIdentity()) return
        val doc = vm.document.value
        val index = doc.activeLayerIndex
        val layer = doc.layers.getOrNull(index) as? PixelLayer ?: return
        vm.editScope.launch(Dispatchers.Default) {
            val src = layer.bitmapRef.bitmap
            val out = createBitmap(src.width, src.height)
            val m = corners.toMatrix(src.width.toFloat(), src.height.toFloat())
            android.graphics.Canvas(out).drawBitmap(
                src,
                m,
                android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG),
            )
            withContext(Dispatchers.Main) {
                vm.updateDocument { doc ->
                    doc.updateLayer(index) { l ->
                        (l as? PixelLayer)?.copy(bitmapRef = BitmapReference(out)) ?: l
                    }
                }
            }
        }
    }

    private fun blendWithSelection(original: Bitmap, edited: Bitmap, sel: Selection): Bitmap {
        val w = edited.width
        val h = edited.height
        val out = edited.copy(Bitmap.Config.ARGB_8888, true)
        val orig = IntArray(w * h)
        val origScaled = if (original.width == w && original.height == h) original
        else original.scale(w, h)
        origScaled.getPixels(orig, 0, w, 0, 0, w, h)
        val edt = IntArray(w * h)
        out.getPixels(edt, 0, w, 0, 0, w, h)
        for (y in 0 until h) {
            val sy = (y * sel.height / h).coerceIn(0, sel.height - 1)
            for (x in 0 until w) {
                val sx = (x * sel.width / w).coerceIn(0, sel.width - 1)
                val m = sel.mask[sy * sel.width + sx]
                if (m >= 1f) continue
                val i = y * w + x
                val o = orig[i]; val e = edt[i]
                val a = lerpCh(o ushr 24, e ushr 24, m)
                val r = lerpCh(o ushr 16, e ushr 16, m)
                val g = lerpCh(o ushr 8, e ushr 8, m)
                val b = lerpCh(o, e, m)
                edt[i] = (a shl ALPHA_SHIFT) or (r shl RED_SHIFT) or (g shl GREEN_SHIFT) or b
            }
        }
        out.setPixels(edt, 0, w, 0, 0, w, h)
        return out
    }

    private fun lerpCh(a: Int, b: Int, t: Float): Int {
        val av = a and 0xFF; val bv = b and 0xFF
        return (av + (bv - av) * t).toInt().coerceIn(0, CHANNEL_MAX)
    }

    /** Shared constants and helpers for PixelEditController. */
    companion object {
        private const val ALPHA_SHIFT = 24
        private const val RED_SHIFT = 16
        private const val GREEN_SHIFT = 8
        private const val CHANNEL_MAX = 255
    }
}
