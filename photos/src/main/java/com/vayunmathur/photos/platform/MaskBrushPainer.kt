package com.vayunmathur.photos.platform
import com.vayunmathur.photos.domain.MaskDab

import com.vayunmathur.photos.data.EditDocument
import com.vayunmathur.photos.data.Layer
import com.vayunmathur.photos.data.LayerMask
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Brush painting onto a layer mask at preview resolution, split out of
 * [LayerEditController] so no class exceeds the function-count cap.
 */
internal class MaskBrushPainer {

    fun paintOnActiveMask(
        vm: PhotoEditViewModel,
        points: List<Pair<Float, Float>>,
        radius: Float,
        paintValue: Float,
    ) {
        if (points.isEmpty()) return
        val doc = vm.document.value
        val index = doc.activeLayerIndex
        val layer = doc.layers.getOrNull(index) ?: return
        vm.editScope.launch(Dispatchers.Default) {
            val mask = buildMaskStroke(doc, layer, points, radius, paintValue)
            withContext(Dispatchers.Main) {
                vm.updateDocument { it.updateLayer(index) { l -> l.copyBase(mask = mask) } }
            }
        }
    }

    private fun buildMaskStroke(
        doc: EditDocument,
        layer: Layer,
        points: List<Pair<Float, Float>>,
        radius: Float,
        paintValue: Float,
    ): LayerMask {
        val cw = doc.canvasWidth.coerceAtLeast(1)
        val ch = doc.canvasHeight.coerceAtLeast(1)
        val scale = minOf(1f, MASK_PREVIEW_MAX_DIM.toFloat() / maxOf(cw, ch))
        val mw = (cw * scale).roundToInt().coerceAtLeast(1)
        val mh = (ch * scale).roundToInt().coerceAtLeast(1)
        val data = resolveMaskData(layer.mask, mw, mh)
        val r = (radius * maxOf(mw, mh)).roundToInt().coerceAtLeast(1)
        for ((nx, ny) in points) {
            paintMaskDab(data, mw, mh, r, nx, ny, paintValue)
        }
        return LayerMask(data, mw, mh)
    }

    private fun resolveMaskData(existing: LayerMask?, mw: Int, mh: Int): FloatArray = when {
        existing != null && existing.width == mw && existing.height == mh -> existing.alphaData.copyOf()
        existing != null -> scaleMaskData(existing, mw, mh)
        else -> FloatArray(mw * mh) { 1f }
    }

    private fun paintMaskDab(
        data: FloatArray,
        mw: Int,
        mh: Int,
        r: Int,
        nx: Float,
        ny: Float,
        paintValue: Float,
    ) {
        val cx = (nx * mw).roundToInt()
        val cy = (ny * mh).roundToInt()
        val dab = MaskDab(data, mw, mh, r, paintValue)
        for (dy in -r..r) {
            dab.paintRow(cy + dy, cy, cx, dxRange(r, dy))
        }
    }

    private fun dxRange(r: Int, dy: Int): IntRange {
        val dx = kotlin.math.sqrt((r * r - dy * dy).toFloat()).toInt()
        return -dx..dx
    }

    private fun scaleMaskData(mask: LayerMask, w: Int, h: Int): FloatArray {
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val sy = (y * mask.height / h).coerceIn(0, mask.height - 1)
            for (x in 0 until w) {
                val sx = (x * mask.width / w).coerceIn(0, mask.width - 1)
                out[y * w + x] = mask.alphaData[sy * mask.width + sx]
            }
        }
        return out
    }

    companion object {
        private const val MASK_PREVIEW_MAX_DIM = 1024
    }
}
