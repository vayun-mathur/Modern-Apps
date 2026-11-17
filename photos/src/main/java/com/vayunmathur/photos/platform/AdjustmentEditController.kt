package com.vayunmathur.photos.platform

import com.vayunmathur.photos.data.AdjustmentLayer
import com.vayunmathur.photos.data.LayerAdjustment

/**
 * Adjustment-layer operations for the photo editor, split out of
 * [PhotoEditViewModel] so no class exceeds the function-count cap.
 * All mutations funnel through [PhotoEditViewModel.updateDocument], so undo
 * and preview stay in one place.
 */
class AdjustmentEditController(private val vm: PhotoEditViewModel) {

    /** Ensures the active layer is an [AdjustmentLayer] whose adjustment satisfies [match]. */
    fun ensureAdjustment(match: (LayerAdjustment) -> Boolean, create: () -> LayerAdjustment) {
        vm.updateDocument(pushUndo = false) { doc ->
            val idx = doc.layers.indexOfLast { it is AdjustmentLayer && match(it.adjustment) }
            if (idx >= 0) doc.setActiveLayer(idx)
            else doc.addLayer(vm.layers.withActiveSelectionMask(AdjustmentLayer(create())))
        }
    }

    fun updateActiveAdjustment(adjustment: LayerAdjustment) {
        vm.updateDocument { doc ->
            val i = doc.activeLayerIndex
            if (doc.layers.getOrNull(i) is AdjustmentLayer) {
                doc.updateLayer(i) { (it as AdjustmentLayer).copy(adjustment = adjustment) }
            } else doc
        }
    }

    fun addAdjustmentLayer(adjustment: LayerAdjustment) =
        vm.updateDocument { it.addLayer(vm.layers.withActiveSelectionMask(AdjustmentLayer(adjustment))) }

    /**
     * If a selection is active, returns [layer] masked to it so the edit only
     * affects the selected area (Photoshop-style). Otherwise returns [layer].
     */
    private fun withActiveSelectionMask(layer: com.vayunmathur.photos.data.Layer): com.vayunmathur.photos.data.Layer {
        val sel = vm.selection.value?.takeIf { !it.isEmpty() } ?: return layer
        return layer.copyBase(mask = sel.toLayerMask())
    }
}
