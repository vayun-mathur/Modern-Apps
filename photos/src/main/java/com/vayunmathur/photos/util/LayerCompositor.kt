package com.vayunmathur.photos.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.util.Log
import com.vayunmathur.photos.data.AdjustmentLayer
import com.vayunmathur.photos.data.DrawingLayer
import com.vayunmathur.photos.data.EditDocument
import com.vayunmathur.photos.data.Layer
import com.vayunmathur.photos.data.LayerMask
import com.vayunmathur.photos.data.LayerBlendMode
import com.vayunmathur.photos.data.LayerStyle
import com.vayunmathur.photos.data.PixelLayer
import com.vayunmathur.photos.data.TextLayer
import com.vayunmathur.photos.data.BitmapReference
import com.vayunmathur.photos.data.applyPerspectiveToBitmap
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Renders an [EditDocument] layer stack (bottom→top) to a single [Bitmap].
 *
 * Pixel work happens on raw IntArray buffers using [com.vayunmathur.photos.data.LayerBlendMode]
 * per-pixel math, so every blend mode and mask behaves identically across API levels.
 * During editing call [compositePreview] (small [maxDimension], reuses a cached render
 * of the layers *below* the active one). For export/merge call [composite] at full res.
 *
 * Instances hold a one-entry cache, so keep a single compositor per editor session and
 * call [invalidateCache] when needed (the cache also self-invalidates on content change).
 */
class LayerCompositor {

    private var belowKey: String? = null
    private var belowCache: IntArray? = null

    fun invalidateCache() {
        belowKey = null
        belowCache = null
    }

    /** Full render of all layers (no cache). Use for export, merge and flatten. */
    fun composite(document: EditDocument, maxDimension: Int = Int.MAX_VALUE): Bitmap {
        val (w, h) = targetSize(document, maxDimension)
        val backdrop = IntArray(w * h)
        renderLayersInto(backdrop, document.layers, document, w, h)
        return applyDocumentTransforms(bitmapFromInts(backdrop, w, h), document)
    }

    /** Cached preview render: layers below the active layer are cached and reused. */
    fun compositePreview(document: EditDocument, maxDimension: Int): Bitmap {
        val (w, h) = targetSize(document, maxDimension)
        val layers = document.layers

        // Groups composite as contiguous runs, so the below/rest split (which can
        // fall inside a run) would be wrong — render the whole stack instead.
        if (document.groups.isNotEmpty()) {
            val backdrop = IntArray(w * h)
            renderLayersInto(backdrop, layers, document, w, h)
            return applyDocumentTransforms(bitmapFromInts(backdrop, w, h), document)
        }

        val active = document.activeLayerIndex.coerceIn(0, layers.size)

        val below = if (active > 0) layers.subList(0, active) else emptyList()
        val key = cacheKey(below, w, h)
        if (key != belowKey || belowCache == null) {
            val base = IntArray(w * h)
            renderLayersInto(base, below, document, w, h)
            belowCache = base
            belowKey = key
        }

        val backdrop = belowCache!!.copyOf()
        val rest = if (active < layers.size) layers.subList(active, layers.size) else emptyList()
        renderLayersInto(backdrop, rest, document, w, h)
        return applyDocumentTransforms(bitmapFromInts(backdrop, w, h), document)
    }

    /** Merges the layer at [index] into the one directly below it, producing a pixel layer. */
    fun mergeDown(document: EditDocument, index: Int): EditDocument {
        if (index <= 0 || index !in document.layers.indices) return document
        val lower = index - 1
        val w = document.canvasWidth.coerceAtLeast(1)
        val h = document.canvasHeight.coerceAtLeast(1)
        val backdrop = IntArray(w * h)
        renderLayersInto(backdrop, listOf(document.layers[lower], document.layers[index]), document, w, h)
        val merged = PixelLayer(
            bitmapRef = BitmapReference(bitmapFromInts(backdrop, w, h)),
            name = document.layers[lower].name,
        )
        val newLayers = document.layers.toMutableList()
        newLayers[lower] = merged
        newLayers.removeAt(index)
        invalidateCache()
        return document.copy(layers = newLayers, activeLayerIndex = lower)
    }

    /** Collapses the whole stack into a single pixel layer (transforms preserved). */
    fun flatten(document: EditDocument): EditDocument {
        if (document.layers.isEmpty()) return document
        val w = document.canvasWidth.coerceAtLeast(1)
        val h = document.canvasHeight.coerceAtLeast(1)
        val backdrop = IntArray(w * h)
        renderLayersInto(backdrop, document.layers, document, w, h)
        val flat = PixelLayer(
            bitmapRef = BitmapReference(bitmapFromInts(backdrop, w, h)),
            name = "Flattened",
            locked = true,
        )
        invalidateCache()
        return document.copy(layers = listOf(flat), activeLayerIndex = 0)
    }

    // --- internal rendering ---------------------------------------------------

    /**
     * Splits [0,total) across CPU cores and runs [body] on each sub-range in
     * parallel. Callers must write disjoint indices per range (the blend loops
     * do: each pixel index is touched by exactly one range), so this is safe.
     */
    private inline fun parallelFor(total: Int, crossinline body: (start: Int, end: Int) -> Unit) {
        val cores = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        if (cores <= 1 || total < 120_000) { body(0, total); return }
        val chunk = (total + cores - 1) / cores
        val threads = ArrayList<Thread>(cores)
        var start = 0
        while (start < total) {
            val s = start
            val e = minOf(s + chunk, total)
            Thread { body(s, e) }.also { threads.add(it); it.start() }
            start = e
        }
        threads.forEach { it.join() }
    }

    private fun renderLayersInto(
        backdrop: IntArray,
        layers: List<Layer>,
        document: EditDocument,
        w: Int,
        h: Int,
    ) {
        var i = 0
        while (i < layers.size) {
            val gid = layers[i].groupId
            if (gid != null) {
                var j = i
                while (j < layers.size && layers[j].groupId == gid) j++
                val group = document.groupInfo(gid)
                if (group == null || group.visible) {
                    // Composite the group's contiguous run into a transparent
                    // sub-backdrop, then blend it in as a unit.
                    val sub = IntArray(w * h)
                    renderLayersPlain(sub, layers.subList(i, j), document, w, h)
                    val op = group?.opacity ?: 1f
                    val mode = group?.blendMode ?: LayerBlendMode.Normal
                    if (op > 0f) {
                        parallelFor(backdrop.size) { s, e ->
                            for (k in s until e) backdrop[k] = mode.blendPixel(backdrop[k], sub[k], op)
                        }
                    }
                }
                i = j
            } else {
                renderOneLayer(backdrop, layers, i, document, w, h)
                i++
            }
        }
    }

    private fun renderLayersPlain(
        backdrop: IntArray,
        layers: List<Layer>,
        document: EditDocument,
        w: Int,
        h: Int,
    ) {
        for (idx in layers.indices) renderOneLayer(backdrop, layers, idx, document, w, h)
    }

    private fun renderOneLayer(
        backdrop: IntArray,
        layers: List<Layer>,
        idx: Int,
        document: EditDocument,
        w: Int,
        h: Int,
    ) {
        val layer = layers[idx]
        if (!layer.visible || layer.opacity <= 0f) return
        val src = layerSourcePixels(layer, backdrop, document, w, h) ?: return
        val mask = layer.mask?.let { scaleMask(it, w, h) }
        // Clipping mask: limit this layer to the alpha shape of the base layer below.
        val clip = if (layer.clipped) clipBaseAlpha(layers, idx, document, w, h) else null
        if (!layer.style.isIdentity() && layer !is AdjustmentLayer) {
            renderLayerStyle(backdrop, src, layer.style, mask, layer.opacity, w, h)
        }
        val mode = layer.blendMode
        val opacity = layer.opacity
        parallelFor(backdrop.size) { s, e ->
            for (i in s until e) {
                var extra = opacity * (mask?.get(i) ?: 1f)
                if (clip != null) extra *= clip[i]
                if (extra <= 0f) continue
                backdrop[i] = mode.blendPixel(backdrop[i], src[i], extra)
            }
        }
    }

    /** Alpha coverage of the clip base (nearest non-clipped layer below [idx]). */
    private fun clipBaseAlpha(
        layers: List<Layer>,
        idx: Int,
        document: EditDocument,
        w: Int,
        h: Int,
    ): FloatArray? {
        var j = idx - 1
        while (j >= 0 && layers[j].clipped) j--
        if (j < 0) return null
        val base = layers[j]
        val px = when (base) {
            is PixelLayer -> pixelsOf(base.bitmapRef.bitmap, w, h)
            is TextLayer -> renderTextPixels(base, document, w, h)
            is DrawingLayer -> renderStrokePixels(base, w, h)
            is AdjustmentLayer -> return null
        }
        val baseMask = base.mask?.let { scaleMask(it, w, h) }
        return FloatArray(w * h) { i -> ((px[i] ushr 24) and 0xFF) / 255f * (baseMask?.get(i) ?: 1f) }
    }

    /** Renders drop-shadow / outer-glow / stroke from [src]'s alpha into [backdrop], beneath the layer. */
    private fun renderLayerStyle(
        backdrop: IntArray,
        src: IntArray,
        style: LayerStyle,
        mask: FloatArray?,
        opacity: Float,
        w: Int,
        h: Int,
    ) {
        val n = w * h
        val alpha = FloatArray(n) { ((src[it] ushr 24) and 0xFF) / 255f }
        val maxDim = maxOf(w, h)
        fun composite(coverage: FloatArray, color: Int) {
            val cr = color and 0x00FFFFFF or (0xFF shl 24)
            for (i in 0 until n) {
                val a = coverage[i] * opacity * (mask?.get(i) ?: 1f)
                if (a <= 0.001f) continue
                backdrop[i] = LayerBlendMode.Normal.blendPixel(backdrop[i], cr, a * ((color ushr 24) and 0xFF) / 255f)
            }
        }
        if (style.dropShadow) {
            val dx = (style.shadowDx * w).toInt()
            val dy = (style.shadowDy * h).toInt()
            val shifted = FloatArray(n)
            for (y in 0 until h) {
                val sy = y - dy
                if (sy !in 0 until h) continue
                for (x in 0 until w) {
                    val sx = x - dx
                    if (sx in 0 until w) shifted[y * w + x] = alpha[sy * w + sx]
                }
            }
            composite(boxBlur(shifted, w, h, (style.shadowBlur * maxDim).toInt()), style.shadowColor)
        }
        if (style.outerGlow) {
            val blurred = boxBlur(alpha, w, h, (style.glowRadius * maxDim).toInt())
            val glow = FloatArray(n) { (blurred[it] * (1f - alpha[it])).coerceIn(0f, 1f) }
            composite(glow, style.glowColor)
        }
        if (style.stroke) {
            val dil = boxBlur(alpha, w, h, (style.strokeWidth * maxDim).toInt().coerceAtLeast(1))
            val edge = FloatArray(n) { ((dil[it] * 2f).coerceAtMost(1f) - alpha[it]).coerceIn(0f, 1f) }
            composite(edge, style.strokeColor)
        }
    }

    /** Separable box blur on a normalized alpha buffer. */
    private fun boxBlur(data: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        if (radius <= 0) return data
        val tmp = FloatArray(w * h)
        val out = FloatArray(w * h)
        val win = (2 * radius + 1).toFloat()
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var sum = 0f
                for (k in -radius..radius) sum += data[row + (x + k).coerceIn(0, w - 1)]
                tmp[row + x] = sum / win
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0f
                for (k in -radius..radius) sum += tmp[(y + k).coerceIn(0, h - 1) * w + x]
                out[y * w + x] = sum / win
            }
        }
        return out
    }

    /** Returns the layer's own pixels at target size, or null if it contributes nothing. */
    private fun layerSourcePixels(
        layer: Layer,
        backdrop: IntArray,
        document: EditDocument,
        w: Int,
        h: Int,
    ): IntArray? = when (layer) {
        is PixelLayer -> pixelsOf(layer.bitmapRef.bitmap, w, h)
        is AdjustmentLayer -> {
            if (layer.adjustment.isIdentity()) {
                null
            } else {
                val source = bitmapFromInts(backdrop, w, h)
                val adjusted = layer.adjustment.applyToBitmap(source)
                val out = IntArray(w * h)
                adjusted.getPixels(out, 0, w, 0, 0, w, h)
                if (adjusted !== source) adjusted.recycleSafely()
                source.recycleSafely()
                out
            }
        }
        is TextLayer -> renderTextPixels(layer, document, w, h)
        is DrawingLayer -> renderStrokePixels(layer, w, h)
    }

    private fun renderTextPixels(layer: TextLayer, document: EditDocument, w: Int, h: Int): IntArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val refWidth = document.canvasWidth.takeIf { it > 0 }?.toFloat() ?: w.toFloat()
        canvas.drawTextElement(layer.textElement, w, h, refWidth)
        val out = IntArray(w * h)
        bmp.getPixels(out, 0, w, 0, 0, w, h)
        bmp.recycleSafely()
        return out
    }

    private fun renderStrokePixels(layer: DrawingLayer, w: Int, h: Int): IntArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawSerializedStrokes(layer.strokes, layer.sourceWidth, layer.sourceHeight, w, h)
        val out = IntArray(w * h)
        bmp.getPixels(out, 0, w, 0, 0, w, h)
        bmp.recycleSafely()
        return out
    }

    private fun applyDocumentTransforms(bitmap: Bitmap, document: EditDocument): Bitmap {
        var result = bitmap

        if (document.rotation != 0f) {
            val matrix = Matrix().apply { postRotate(document.rotation) }
            val rotated = Bitmap.createBitmap(result, 0, 0, result.width, result.height, matrix, true)
            if (rotated !== result) result.recycleSafely()
            result = rotated
        }

        val crop = document.cropRect
        if (crop != EditDocument.FULL_CROP) {
            val left = (crop.left * result.width).roundToInt().coerceIn(0, result.width - 1)
            val top = (crop.top * result.height).roundToInt().coerceIn(0, result.height - 1)
            val width = ((crop.right - crop.left) * result.width).roundToInt()
                .coerceAtMost(result.width - left)
            val height = ((crop.bottom - crop.top) * result.height).roundToInt()
                .coerceAtMost(result.height - top)
            if (width > 0 && height > 0) {
                val cropped = Bitmap.createBitmap(result, left, top, width, height)
                if (cropped !== result) result.recycleSafely()
                result = cropped
            }
        }

        if (!document.perspectiveCorners.isIdentity()) {
            val warped = document.perspectiveCorners.applyPerspectiveToBitmap(result)
            if (warped !== result) result.recycleSafely()
            result = warped
        }

        return result
    }

    // --- helpers --------------------------------------------------------------

    private fun targetSize(document: EditDocument, maxDimension: Int): Pair<Int, Int> {
        val cw = document.canvasWidth
        val ch = document.canvasHeight
        if (cw <= 0 || ch <= 0) return 1 to 1
        val scale = min(maxDimension.toFloat() / cw, maxDimension.toFloat() / ch).coerceAtMost(1f)
        val w = (cw * scale).roundToInt().coerceAtLeast(1)
        val h = (ch * scale).roundToInt().coerceAtLeast(1)
        return w to h
    }

    private fun pixelsOf(bitmap: Bitmap, w: Int, h: Int): IntArray {
        val arr = IntArray(w * h)
        if (bitmap.width == w && bitmap.height == h) {
            bitmap.getPixels(arr, 0, w, 0, 0, w, h)
        } else {
            val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
            scaled.getPixels(arr, 0, w, 0, 0, w, h)
            if (scaled !== bitmap) scaled.recycleSafely()
        }
        return arr
    }

    private fun scaleMask(mask: LayerMask, w: Int, h: Int): FloatArray {
        if (mask.width == w && mask.height == h) return mask.alphaData
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

    private fun bitmapFromInts(pixels: IntArray, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp
    }

    private fun cacheKey(layers: List<Layer>, w: Int, h: Int): String =
        layers.joinToString(separator = ",") { it.hashCode().toString() } + "@${w}x$h"

    private fun Bitmap.recycleSafely() {
        try {
            if (!isRecycled) recycle()
        } catch (e: Exception) {
            Log.w("LayerCompositor", "Failed to recycle bitmap", e)
        }
    }
}
