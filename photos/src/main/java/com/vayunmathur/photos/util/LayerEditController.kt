package com.vayunmathur.photos.util

import androidx.core.graphics.createBitmap
import com.vayunmathur.library.ink.SerializedStroke
import com.vayunmathur.photos.data.BitmapReference
import com.vayunmathur.photos.data.DrawingLayer
import com.vayunmathur.photos.data.LayerBlendMode
import com.vayunmathur.photos.data.LayerMask
import com.vayunmathur.photos.data.LayerStyle
import com.vayunmathur.photos.data.PixelLayer
import com.vayunmathur.photos.data.TextElement
import com.vayunmathur.photos.data.TextLayer
import com.vayunmathur.photos.data.GroupInfo
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Layer-stack operations for the photo editor, split out of
 * [PhotoEditViewModel] so neither class exceeds the function-count cap.
 * All mutations funnel through [PhotoEditViewModel.updateDocument], so undo
 * and preview stay in one place.
 */
class LayerEditController(private val vm: PhotoEditViewModel) {

    fun setActiveLayer(index: Int) = vm.updateDocument(pushUndo = false) { it.setActiveLayer(index) }
    fun moveLayer(from: Int, to: Int) = vm.updateDocument { it.moveLayer(from, to) }
    fun removeLayer(index: Int) = vm.updateDocument { it.removeLayer(index) }
    fun duplicateLayer(index: Int) = vm.updateDocument { it.duplicateLayer(index) }
    fun mergeDown(index: Int) = vm.updateDocument { vm.compositor.mergeDown(it, index) }
    fun flatten() = vm.updateDocument { vm.compositor.flatten(it) }

    fun setLayerVisibility(index: Int, visible: Boolean) =
        vm.updateDocument { it.updateLayer(index) { l -> l.copyBase(visible = visible) } }

    fun setLayerOpacity(index: Int, opacity: Float) =
        vm.updateDocument { it.updateLayer(index) { l -> l.copyBase(opacity = opacity.coerceIn(0f, 1f)) } }

    fun setLayerBlendMode(index: Int, mode: LayerBlendMode) =
        vm.updateDocument { it.updateLayer(index) { l -> l.copyBase(blendMode = mode) } }

    fun renameLayer(index: Int, name: String) =
        vm.updateDocument { it.updateLayer(index) { l -> l.copyBase(name = name) } }

    /** Commit in-progress ink strokes and text overlays into the document as real,
     *  undoable DrawingLayer/TextLayers (one undo step). */
    fun commitOverlaysToLayers(
        strokes: List<SerializedStroke>,
        texts: List<TextElement>,
        sourceWidth: Float,
        sourceHeight: Float,
    ) {
        if (strokes.isEmpty() && texts.isEmpty()) return
        vm.updateDocument { doc ->
            var d = doc
            if (strokes.isNotEmpty()) {
                d = d.addLayer(
                    DrawingLayer(
                        strokes = strokes,
                        sourceWidth = sourceWidth,
                        sourceHeight = sourceHeight,
                        name = "Drawing",
                    )
                )
            }
            texts.forEach { t ->
                val layerName = t.text.take(TEXT_LAYER_NAME_LENGTH).ifBlank { "Text" }
                d = d.addLayer(TextLayer(textElement = t, name = layerName))
            }
            d
        }
    }

    fun addEmptyPixelLayer() {
        val doc = vm.document.value
        val w = doc.canvasWidth.coerceAtLeast(1)
        val h = doc.canvasHeight.coerceAtLeast(1)
        val bmp = createBitmap(w, h)
        vm.updateDocument { it.addLayer(PixelLayer(BitmapReference(bmp), name = "Layer")) }
    }

    fun deleteLayerMask(index: Int) =
        vm.updateDocument { it.updateLayer(index) { l -> l.copyBase(mask = null) } }

    fun invertLayerMask(index: Int) =
        vm.updateDocument {
            it.updateLayer(index) { l -> l.copyBase(mask = l.mask?.invert() ?: l.mask) }
        }

    fun setLayerMask(index: Int, mask: LayerMask) =
        vm.updateDocument { it.updateLayer(index) { l -> l.copyBase(mask = mask) } }

    fun setLayerClipped(index: Int, clipped: Boolean) =
        vm.updateDocument { it.updateLayer(index) { l -> l.copyBase(clipped = clipped) } }

    fun setLayerStyle(index: Int, style: LayerStyle) =
        vm.updateDocument { it.updateLayer(index) { l -> l.copyBase(style = style) } }

    fun groupActiveWithBelow() = vm.updateDocument { it.groupActiveWithBelow() }
    fun ungroupActive() = vm.updateDocument { it.ungroupActive() }
    fun updateGroup(info: GroupInfo) =
        vm.updateDocument(pushUndo = false) { it.updateGroup(info) }

    fun selectionToActiveMask() {
        val sel = vm.selection.value ?: return
        val index = vm.document.value.activeLayerIndex
        setLayerMask(index, sel.toLayerMask())
    }

    /** Brush painting onto the active layer's mask (delegated to stay under the function cap). */
    private val maskBrush = MaskBrushPainer()
    /** Paint onto the active layer's mask - see [MaskBrushPainer]. */
    fun paintOnActiveMask(points: List<Pair<Float, Float>>, radius: Float, paintValue: Float) =
        maskBrush.paintOnActiveMask(vm, points, radius, paintValue)

    internal fun withActiveSelectionMask(layer: com.vayunmathur.photos.data.Layer): com.vayunmathur.photos.data.Layer {
        val sel = vm.selection.value?.takeIf { !it.isEmpty() } ?: return layer
        return layer.copyBase(mask = sel.toLayerMask())
    }

    /** Shared constants and helpers for LayerEditController. */
    companion object {
        private const val TEXT_LAYER_NAME_LENGTH = 16
    }
}
