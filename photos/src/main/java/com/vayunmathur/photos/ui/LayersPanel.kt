package com.vayunmathur.photos.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.library.ui.IconVisible
import com.vayunmathur.photos.data.AdjustmentLayer
import com.vayunmathur.photos.data.BasicAdjustment
import com.vayunmathur.photos.data.BlackAndWhiteAdj
import com.vayunmathur.photos.data.BlackAndWhiteAdjustment
import com.vayunmathur.photos.data.ChannelMixerAdj
import com.vayunmathur.photos.data.ColorBalanceAdj
import com.vayunmathur.photos.data.CurvesAdj
import com.vayunmathur.photos.data.DrawingLayer
import com.vayunmathur.photos.data.EditDocument
import com.vayunmathur.photos.data.GradientMapAdj
import com.vayunmathur.photos.data.GradientMapAdjustment
import com.vayunmathur.photos.data.GradientStop
import com.vayunmathur.photos.data.HslAdj
import com.vayunmathur.photos.data.Layer
import com.vayunmathur.photos.data.LayerAdjustment
import com.vayunmathur.photos.data.LayerBlendMode
import com.vayunmathur.photos.data.GroupInfo
import com.vayunmathur.photos.data.LevelsAdj
import com.vayunmathur.photos.data.PixelLayer
import com.vayunmathur.photos.data.TextLayer
import kotlin.math.roundToInt

private val adjustmentFactories: List<Pair<String, () -> LayerAdjustment>> = listOf(
    "Brightness / Contrast" to { BasicAdjustment() },
    "Curves" to { CurvesAdj() },
    "HSL" to { HslAdj() },
    "Levels" to { LevelsAdj() },
    "Color Balance" to { ColorBalanceAdj() },
    "Channel Mixer" to { ChannelMixerAdj() },
    "Black & White" to { BlackAndWhiteAdj(BlackAndWhiteAdjustment(enabled = true)) },
    "Gradient Map" to {
        GradientMapAdj(
            GradientMapAdjustment(
                listOf(
                    GradientStop(0f, 0xFF000000.toInt()),
                    GradientStop(1f, 0xFFFFFFFF.toInt()),
                )
            )
        )
    },
    "Vibrance" to { com.vayunmathur.photos.data.VibranceAdj() },
    "Photo Filter" to { com.vayunmathur.photos.data.PhotoFilterAdj() },
    "Selective Color" to { com.vayunmathur.photos.data.SelectiveColorAdj() },
    "Posterize" to { com.vayunmathur.photos.data.PosterizeAdj() },
    "Threshold" to { com.vayunmathur.photos.data.ThresholdAdj() },
    "Invert" to { com.vayunmathur.photos.data.InvertAdj() },
)

@Composable
fun LayersPanel(
    document: EditDocument,
    hasSelection: Boolean,
    onSelectLayer: (Int) -> Unit,
    onToggleVisibility: (Int, Boolean) -> Unit,
    onOpacityChange: (Int, Float) -> Unit,
    onBlendModeChange: (Int, LayerBlendMode) -> Unit,
    onAddAdjustment: (LayerAdjustment) -> Unit,
    onAddPixelLayer: () -> Unit,
    onDuplicate: (Int) -> Unit,
    onMergeDown: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    onFlatten: () -> Unit,
    onAddMaskFromSelection: (Int) -> Unit,
    onDeleteMask: (Int) -> Unit,
    onInvertMask: (Int) -> Unit,
    onToggleClip: (Int, Boolean) -> Unit,
    onSetStyle: (Int, com.vayunmathur.photos.data.LayerStyle) -> Unit,
    onGroupActive: () -> Unit,
    onUngroup: () -> Unit,
    onUpdateGroup: (GroupInfo) -> Unit,
    onMoveLayer: (Int, Int) -> Unit,
    onEditText: (Int) -> Unit,
    onResumeDrawing: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(8.dp),
    ) {
        // Action bar
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            var showAdjMenu by remember { mutableStateOf(false) }
            Box {
                ActionChip("+ Adjustment") { showAdjMenu = true }
                DropdownMenu(expanded = showAdjMenu, onDismissRequest = { showAdjMenu = false }) {
                    adjustmentFactories.forEach { (label, factory) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = { showAdjMenu = false; onAddAdjustment(factory()) },
                        )
                    }
                }
            }
            ActionChip("+ Layer") { onAddPixelLayer() }
            ActionChip("Duplicate") { onDuplicate(document.activeLayerIndex) }
            ActionChip("Merge ↓") { onMergeDown(document.activeLayerIndex) }
            ActionChip("Group ↓") { onGroupActive() }
            if (document.activeLayer?.groupId != null) ActionChip("Ungroup") { onUngroup() }
            ActionChip("Flatten") { onFlatten() }
            ActionChip("Delete") { onDelete(document.activeLayerIndex) }
        }

        Spacer(Modifier.size(6.dp))

        // Active-layer controls
        val active = document.activeLayer
        if (active != null) {
            var showBlend by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box {
                    ActionChip(active.blendMode.label) { showBlend = true }
                    DropdownMenu(expanded = showBlend, onDismissRequest = { showBlend = false }) {
                        LayerBlendMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(mode.label) },
                                onClick = {
                                    showBlend = false
                                    onBlendModeChange(document.activeLayerIndex, mode)
                                },
                            )
                        }
                    }
                }
                Text("Opacity", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(
                    value = active.opacity,
                    onValueChange = { onOpacityChange(document.activeLayerIndex, it) },
                    valueRange = 0f..1f,
                    modifier = Modifier.weight(1f),
                )
                Text("${(active.opacity * 100).roundToInt()}", fontSize = 11.sp, modifier = Modifier.width(28.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val ai = document.activeLayerIndex
                if (ai < document.layers.size - 1) ActionChip("Move ↑") { onMoveLayer(ai, ai + 1) }
                if (ai > 0) ActionChip("Move ↓") { onMoveLayer(ai, ai - 1) }
                when (active) {
                    is TextLayer -> ActionChip("Edit Text") { onEditText(ai) }
                    is DrawingLayer -> ActionChip("Edit Strokes") { onResumeDrawing(ai) }
                    else -> {}
                }
                if (active.mask == null) {
                    ActionChip("Add Mask", enabled = hasSelection) {
                        if (hasSelection) onAddMaskFromSelection(document.activeLayerIndex)
                    }
                } else {
                    ActionChip("Invert Mask") { onInvertMask(document.activeLayerIndex) }
                    ActionChip("Delete Mask") { onDeleteMask(document.activeLayerIndex) }
                }
                if (document.activeLayerIndex > 0) {
                    ActionChip(if (active.clipped) "✓ Clip to below" else "Clip to below") {
                        onToggleClip(document.activeLayerIndex, !active.clipped)
                    }
                }
                val st = active.style
                ActionChip(if (st.dropShadow) "✓ Shadow" else "Shadow") {
                    onSetStyle(document.activeLayerIndex, st.copy(dropShadow = !st.dropShadow))
                }
                ActionChip(if (st.stroke) "✓ Stroke" else "Stroke") {
                    onSetStyle(document.activeLayerIndex, st.copy(stroke = !st.stroke))
                }
                ActionChip(if (st.outerGlow) "✓ Glow" else "Glow") {
                    onSetStyle(document.activeLayerIndex, st.copy(outerGlow = !st.outerGlow))
                }
            }
        }

        Spacer(Modifier.size(6.dp))

        // Layer list (top layer first)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            var i = document.layers.size - 1
            while (i >= 0) {
                val gid = document.layers[i].groupId
                if (gid != null) {
                    val group = document.groupInfo(gid)
                    if (group != null) GroupHeader(group, onUpdateGroup)
                    var j = i
                    while (j >= 0 && document.layers[j].groupId == gid) {
                        val idx = j
                        if (group == null || !group.collapsed) {
                            Row(modifier = Modifier.padding(start = 16.dp)) {
                                LayerRow(
                                    layer = document.layers[idx],
                                    isActive = idx == document.activeLayerIndex,
                                    onSelect = { onSelectLayer(idx) },
                                    onToggleVisibility = { onToggleVisibility(idx, it) },
                                )
                            }
                        }
                        j--
                    }
                    i = j
                } else {
                    val idx = i
                    LayerRow(
                        layer = document.layers[idx],
                        isActive = idx == document.activeLayerIndex,
                        onSelect = { onSelectLayer(idx) },
                        onToggleVisibility = { onToggleVisibility(idx, it) },
                    )
                    i--
                }
            }
        }
    }
}

@Composable
private fun LayerRow(
    layer: Layer,
    isActive: Boolean,
    onSelect: () -> Unit,
    onToggleVisibility: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { onSelect() },
        shape = RoundedCornerShape(6.dp),
        color = if (isActive) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = { onToggleVisibility(!layer.visible) }, modifier = Modifier.size(28.dp)) {
                if (layer.visible) IconVisible()
                else Text("–", fontSize = 16.sp)
            }
            LayerThumbnail(layer)
            Column(modifier = Modifier.weight(1f)) {
                Text(layer.name, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                Text(
                    "${layer.blendMode.label} · ${(layer.opacity * 100).roundToInt()}%",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (layer.mask != null) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color.White)
                        .border(1.dp, Color.Black, RoundedCornerShape(3.dp)),
                )
            }
        }
    }
}

@Composable
private fun LayerThumbnail(layer: Layer) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        when (layer) {
            is PixelLayer -> {
                val bmp = layer.bitmapRef.bitmap
                if (!bmp.isRecycled) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
            is AdjustmentLayer -> Text("ƒ", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            is TextLayer -> Text("T", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            is DrawingLayer -> Text("✎", fontSize = 16.sp)
        }
    }
}

@Composable
private fun ActionChip(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable(enabled = enabled) { onClick() },
        shape = RoundedCornerShape(6.dp),
        color = if (enabled) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    ) {
        Text(
            label,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            color = if (enabled) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun GroupHeader(group: GroupInfo, onUpdate: (GroupInfo) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (group.collapsed) "\u25B8" else "\u25BE",
                fontSize = 14.sp,
                modifier = Modifier.clickable { onUpdate(group.copy(collapsed = !group.collapsed)) }.padding(4.dp),
            )
            Text("\uD83D\uDCC1 ${group.name}", fontSize = 12.sp, modifier = Modifier.weight(1f))
            var showBlend by remember { mutableStateOf(false) }
            Box {
                ActionChip(group.blendMode.label) { showBlend = true }
                DropdownMenu(expanded = showBlend, onDismissRequest = { showBlend = false }) {
                    LayerBlendMode.entries.forEach { mode ->
                        DropdownMenuItem(text = { Text(mode.label) }, onClick = {
                            showBlend = false; onUpdate(group.copy(blendMode = mode))
                        })
                    }
                }
            }
            Text(
                if (group.visible) "\uD83D\uDC41" else "\u2298",
                fontSize = 14.sp,
                modifier = Modifier.clickable { onUpdate(group.copy(visible = !group.visible)) }.padding(4.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Opacity", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = group.opacity,
                onValueChange = { onUpdate(group.copy(opacity = it)) },
                valueRange = 0f..1f,
                modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
            )
        }
    }
}
