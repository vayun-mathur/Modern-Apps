package com.vayunmathur.photos.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ink.deserialize
import com.vayunmathur.library.ui.IconBack
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconEdit
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.photos.R
import com.vayunmathur.photos.data.DrawingLayer
import com.vayunmathur.photos.data.DrawingTool
import com.vayunmathur.photos.data.TextLayer
import com.vayunmathur.photos.platform.segmentSubject

// Bottom controls: Home (category bar) or a tool screen. Placed below the image in a
// Column so opening a panel shrinks the image instead of covering it. The Surface fills
// to the screen bottom (covering the navigation-bar area) while its content is inset above
// the nav bar via navigationBarsPadding, so there is no gap and no tools under the bar.
@Composable
internal fun EditPhotoBottomControls(state: EditPhotoEditorState, backStack: NavBackStack<EditRoute>) {
    val cat = state.activeCategory
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Box(modifier = Modifier.navigationBarsPadding()) {
            when (cat) {
                null -> CategoryBar(onSelect = { state.openCategory(it) })
                ToolCategory.Draw -> DrawControls(state, backStack)
                else -> ToolScreen(
                    category = cat,
                    editorMode = state.editorMode,
                    onToolSelected = { state.selectTool(it) },
                    onBack = { state.goHome() },
                ) {
                    when (state.editorMode) {
                        EditorMode.Crop -> CropControls(state)
                        EditorMode.FreeTransform -> FreeTransformControls(state)
                        else -> EditorPanelHost(state)
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawControls(state: EditPhotoEditorState, backStack: NavBackStack<EditRoute>) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { state.goHome() }) { IconBack() }
            Text(stringResource(R.string.tool_cat_draw), fontWeight = FontWeight.Bold)
            InfoHint(stringResource(R.string.draw_freehand_highlight_erase_or_tap_to))
            Spacer(Modifier.weight(1f))
            if (state.selectedTextId != null) {
                IconButton(onClick = {
                    state.texts.find { it.id == state.selectedTextId }?.let { te ->
                        backStack.add(EditRoute.DrawingSettings(DrawingTool.Text, te.color, te.fontSize, 1f))
                    }
                }) { IconEdit() }
            }
        }
        DrawingToolbar(
            activeTool = state.activeTool,
            onSelectPointer = { state.activeTool = DrawingTool.Pointer; state.selectedTextId = null; state.selectedStrokeIndex = null; state.selectedTextIndex = null },
            onSelectPen = {
                if (state.activeTool == DrawingTool.Pen) backStack.add(EditRoute.DrawingSettings(DrawingTool.Pen, state.penColor.toArgb(), state.penSize, 1f))
                else { state.activeTool = DrawingTool.Pen; state.selectedTextId = null; state.selectedStrokeIndex = null; state.selectedTextIndex = null }
            },
            onSelectHighlighter = {
                if (state.activeTool == DrawingTool.Highlighter) backStack.add(EditRoute.DrawingSettings(DrawingTool.Highlighter, state.highlighterColor.toArgb(), state.highlighterSize, state.highlighterOpacity))
                else { state.activeTool = DrawingTool.Highlighter; state.selectedTextId = null; state.selectedStrokeIndex = null; state.selectedTextIndex = null }
            },
            onSelectEraser = { state.activeTool = DrawingTool.Eraser; state.selectedTextId = null; state.selectedStrokeIndex = null; state.selectedTextIndex = null },
            onSelectText = {
                if (state.selectedTextId != null || state.activeTool == DrawingTool.Text) {
                    if (state.selectedTextId != null) {
                        state.texts.find { it.id == state.selectedTextId }?.let { te ->
                            backStack.add(EditRoute.DrawingSettings(DrawingTool.Text, te.color, te.fontSize, 1f))
                        }
                    } else backStack.add(EditRoute.DrawingSettings(DrawingTool.Text, state.penColor.toArgb(), state.textFontSize, 1f))
                } else { state.activeTool = DrawingTool.Text; state.selectedTextId = null; state.selectedStrokeIndex = null; state.selectedTextIndex = null }
            },
        )
    }
}

@Composable
private fun CropControls(state: EditPhotoEditorState) {
    CropRotatePanel(
        onRotate90 = {
            // Quarter-turn: keep the crop center, swap the box's
            // pixel dimensions, and rotate 90°. Swapping extents +
            // the 90° turn preserves the selected footprint (and
            // two turns return exactly to the original box). Only apply
            // it if the result still fits inside the photo.
            val w = state.document.canvasWidth.coerceAtLeast(1).toFloat()
            val h = state.document.canvasHeight.coerceAtLeast(1).toFloat()
            val newHx = state.cropHy * h / w
            val newHy = state.cropHx * w / h
            val newAngle = (state.cropAngle + 90f) % 360f
            if (cropWithinImage(state.cropCx, state.cropCy, newHx, newHy, newAngle, w, h)) {
                state.cropHx = newHx
                state.cropHy = newHy
                state.cropAngle = newAngle
                state.cropAspect = null
            }
        },
        selectedAspect = state.cropAspect,
        onAspect = { ar ->
            state.cropAspect = ar
            if (ar == null) {
                state.cropCx = 0.5f; state.cropCy = 0.5f; state.cropHx = 0.5f; state.cropHy = 0.5f
            } else {
                val w = state.document.canvasWidth.coerceAtLeast(1).toFloat()
                val h = state.document.canvasHeight.coerceAtLeast(1).toFloat()
                val ratioN = ar * h / w
                state.cropCx = 0.5f; state.cropCy = 0.5f
                if (ratioN >= 1f) { state.cropHx = 0.5f; state.cropHy = 0.5f / ratioN } else { state.cropHy = 0.5f; state.cropHx = 0.5f * ratioN }
            }
        },
        onReset = {
            state.cropCx = 0.5f; state.cropCy = 0.5f; state.cropHx = 0.5f; state.cropHy = 0.5f
            state.cropAngle = 0f; state.cropAspect = null
        },
        onApply = { state.applyCrop() },
        onCancel = { state.goHome() },
    )
}

@Composable
private fun FreeTransformControls(state: EditPhotoEditorState) {
    FreeTransformPanel(
        onApply = {
            state.vm.pixel.transformActiveLayer(
                com.vayunmathur.photos.data.PerspectiveCorners(
                    topLeft = state.ftTL.x to state.ftTL.y,
                    topRight = state.ftTR.x to state.ftTR.y,
                    bottomLeft = state.ftBL.x to state.ftBL.y,
                    bottomRight = state.ftBR.x to state.ftBR.y,
                )
            )
            state.ftTL = androidx.compose.ui.geometry.Offset(0f, 0f); state.ftTR = androidx.compose.ui.geometry.Offset(1f, 0f); state.ftBL = androidx.compose.ui.geometry.Offset(0f, 1f); state.ftBR = androidx.compose.ui.geometry.Offset(1f, 1f)
        },
        onReset = { state.ftTL = androidx.compose.ui.geometry.Offset(0f, 0f); state.ftTR = androidx.compose.ui.geometry.Offset(1f, 0f); state.ftBL = androidx.compose.ui.geometry.Offset(0f, 1f); state.ftBR = androidx.compose.ui.geometry.Offset(1f, 1f) },
        onFlipH = { state.vm.pixel.flipActiveLayer(true) },
        onFlipV = { state.vm.pixel.flipActiveLayer(false) },
        onDone = { state.goHome() },
    )
}

/** Wires the shared ActivePanel to the editor state (selection, paint, layers, subject). */
@Composable
internal fun EditorPanelHost(state: EditPhotoEditorState) {
    ActivePanel(
        editorMode = state.editorMode,
        document = state.document,
        baseBitmap = state.baseBitmap,
        selection = state.selection,
        vm = state.vm,
        selectedAdjustment = state.selectedAdjustment,
        onSelectAdjustment = { state.selectedAdjustment = it },
        selectedCurveChannel = state.selectedCurveChannel,
        onCurveChannel = { state.selectedCurveChannel = it },
        selectedHslRange = state.selectedHslRange,
        onHslRange = { state.selectedHslRange = it },
        currentSelectiveMask = state.currentSelectiveMask,
        showSelectiveMask = state.showSelectiveMask,
        onSelectiveMask = { state.currentSelectiveMask = it },
        onShowSelectiveMask = { state.showSelectiveMask = it },
        healingBrushSize = state.healingBrushSize,
        isSettingHealingSource = state.isSettingHealingSource,
        onHealingBrushSize = { state.healingBrushSize = it },
        onSetHealingSource = { state.isSettingHealingSource = it },
        dodgeBurnMode = state.dodgeBurnMode,
        onDodgeBurnMode = { state.dodgeBurnMode = it },
        brushSize = state.brushSize,
        onBrushSize = { state.brushSize = it },
        selectionTool = state.selectionTool,
        onSelectionTool = { state.selectionTool = it },
        selectionCombine = state.selectionCombine,
        onSelectionCombine = { state.selectionCombine = it },
        selectionFeather = state.selectionFeather,
        onSelectionFeather = { state.selectionFeather = it },
        onSelectionFeatherCommit = { state.reapplyFeather() },
        wandTolerance = state.wandTolerance,
        onWandTolerance = { state.wandTolerance = it },
        polygonPointCount = state.polygonPoints.size,
        onClosePolygon = {
            val norm = state.polygonPoints.map {
                (it.x / state.currentViewportWidth).coerceIn(0f, 1f) to (it.y / state.currentViewportHeight).coerceIn(0f, 1f)
            }
            state.polygonPoints = emptyList()
            state.commitPolygon(norm)
        },
        liquifyTool = state.liquifyTool,
        onLiquifyTool = { state.liquifyTool = it },
        liquifyStrength = state.liquifyStrength,
        onLiquifyStrength = { state.liquifyStrength = it },
        liquifyRadius = state.liquifyRadius,
        onLiquifyRadius = { state.liquifyRadius = it },
        onSelectionInvert = {
            state.selectionBase = state.selectionBase?.invert()
            state.reapplyFeather()
        },
        onSelectionClear = { state.clearSelection() },
        onSelectionDelete = {
            state.vm.pixel.applyToActivePixelLayer { src ->
                androidx.core.graphics.createBitmap(src.width, src.height)
            }
        },
        maskPaintReveal = state.maskPaintReveal,
        onMaskPaintReveal = { state.maskPaintReveal = it },
        maskBrushSize = state.maskBrushSize,
        onMaskBrushSize = { state.maskBrushSize = it },
        paintColor = state.paintColor,
        onPaintColor = { state.useColor(it) },
        recentColors = state.recentColors,
        fillTolerance = state.fillTolerance,
        onFillTolerance = { state.fillTolerance = it },
        shapeStrokeWidth = state.shapeStrokeWidth,
        onShapeStrokeWidth = { state.shapeStrokeWidth = it },
        onEditTextLayer = { idx ->
            (state.document.layers.getOrNull(idx) as? TextLayer)?.let { tl ->
                state.texts.add(tl.textElement)
                state.vm.layers.removeLayer(idx)
                state.textToEdit = tl.textElement
                state.selectedTextId = tl.textElement.id
                state.openCategory(ToolCategory.Draw)
            }
        },
        onResumeDrawingLayer = { idx ->
            (state.document.layers.getOrNull(idx) as? DrawingLayer)?.let { dl ->
                dl.strokes.forEach { state.inkStrokes.add(it.deserialize()) }
                state.vm.layers.removeLayer(idx)
                state.openCategory(ToolCategory.Draw)
            }
        },
        onSelectSubject = {
            state.baseBitmap?.let { bmp ->
                segmentSubject(state.context, bmp) { sel ->
                    if (sel != null) { state.selectionBase = sel; state.reapplyFeather() }
                }
            }
        },
    )
}
