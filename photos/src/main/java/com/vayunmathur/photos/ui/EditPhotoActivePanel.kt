package com.vayunmathur.photos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.get
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.ink.brush.Brush
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconRotateRight
import com.vayunmathur.photos.R
import com.vayunmathur.photos.data.BasicAdjustment
import com.vayunmathur.photos.data.BlackAndWhiteAdj
import com.vayunmathur.photos.data.BlurAdj
import com.vayunmathur.photos.data.BlurParams
import com.vayunmathur.photos.data.ChannelMixerAdj
import com.vayunmathur.photos.data.ColorBalanceAdj
import com.vayunmathur.photos.data.CurveChannel
import com.vayunmathur.photos.data.CurvesAdj
import com.vayunmathur.photos.data.CurvesAdjustment
import com.vayunmathur.photos.data.DodgeBurnMode
import com.vayunmathur.photos.data.EditDocument
import com.vayunmathur.photos.data.GradientMapAdj
import com.vayunmathur.photos.data.HslAdj
import com.vayunmathur.photos.data.HslAdjustments
import com.vayunmathur.photos.data.HslColorRange
import com.vayunmathur.photos.data.ImageAdjustments
import com.vayunmathur.photos.data.LevelsAdj
import com.vayunmathur.photos.data.PhotoFilter
import com.vayunmathur.photos.data.PhotoFilterAdj
import com.vayunmathur.photos.data.PhotoFilters
import com.vayunmathur.photos.data.PosterizeAdj
import com.vayunmathur.photos.data.Selection
import com.vayunmathur.photos.data.SelectionCombine
import com.vayunmathur.photos.data.SelectiveAdj
import com.vayunmathur.photos.data.SelectiveColorAdj
import com.vayunmathur.photos.data.SelectiveEdits
import com.vayunmathur.photos.data.SelectiveMask
import com.vayunmathur.photos.data.ThresholdAdj
import com.vayunmathur.photos.data.VibranceAdj
import com.vayunmathur.photos.data.toColorMatrix
import com.vayunmathur.photos.platform.PhotoEditViewModel
import kotlin.math.roundToInt

@Composable
internal fun ActivePanel(
    editorMode: EditorMode,
    document: EditDocument,
    baseBitmap: android.graphics.Bitmap?,
    selection: Selection?,
    vm: PhotoEditViewModel,
    selectedAdjustment: AdjustmentType,
    onSelectAdjustment: (AdjustmentType) -> Unit,
    selectedCurveChannel: CurveChannel,
    onCurveChannel: (CurveChannel) -> Unit,
    selectedHslRange: HslColorRange,
    onHslRange: (HslColorRange) -> Unit,
    currentSelectiveMask: SelectiveMask,
    showSelectiveMask: Boolean,
    onSelectiveMask: (SelectiveMask) -> Unit,
    onShowSelectiveMask: (Boolean) -> Unit,
    healingBrushSize: Float,
    isSettingHealingSource: Boolean,
    onHealingBrushSize: (Float) -> Unit,
    onSetHealingSource: (Boolean) -> Unit,
    dodgeBurnMode: DodgeBurnMode,
    onDodgeBurnMode: (DodgeBurnMode) -> Unit,
    brushSize: Float,
    onBrushSize: (Float) -> Unit,
    selectionTool: SelectionTool,
    onSelectionTool: (SelectionTool) -> Unit,
    selectionCombine: SelectionCombine,
    onSelectionCombine: (SelectionCombine) -> Unit,
    selectionFeather: Float,
    onSelectionFeather: (Float) -> Unit,
    onSelectionFeatherCommit: () -> Unit,
    wandTolerance: Float,
    onWandTolerance: (Float) -> Unit,
    polygonPointCount: Int,
    onClosePolygon: () -> Unit,
    liquifyTool: com.vayunmathur.photos.data.LiquifyTool,
    onLiquifyTool: (com.vayunmathur.photos.data.LiquifyTool) -> Unit,
    liquifyStrength: Float,
    onLiquifyStrength: (Float) -> Unit,
    liquifyRadius: Float,
    onLiquifyRadius: (Float) -> Unit,
    onSelectionInvert: () -> Unit,
    onSelectionClear: () -> Unit,
    onSelectionDelete: () -> Unit,
    maskPaintReveal: Boolean,
    onMaskPaintReveal: (Boolean) -> Unit,
    maskBrushSize: Float,
    onMaskBrushSize: (Float) -> Unit,
    paintColor: Color,
    onPaintColor: (Color) -> Unit,
    recentColors: List<Color>,
    fillTolerance: Float,
    onFillTolerance: (Float) -> Unit,
    shapeStrokeWidth: Float,
    onShapeStrokeWidth: (Float) -> Unit,
    onEditTextLayer: (Int) -> Unit,
    onResumeDrawingLayer: (Int) -> Unit,
    onSelectSubject: () -> Unit,
) {
    when (editorMode) {
        EditorMode.Adjust -> {
            val basic = document.activeAdjustment<BasicAdjustment>()?.adjustments ?: ImageAdjustments()
            AdjustmentPanel(
                adjustments = basic,
                selectedAdjustment = selectedAdjustment,
                onSelectAdjustment = onSelectAdjustment,
                onUpdateAdjustment = { update -> vm.adjustments.updateActiveAdjustment(BasicAdjustment(update(basic))) },
                onReset = { vm.adjustments.updateActiveAdjustment(BasicAdjustment(ImageAdjustments())) },
            )
        }
        EditorMode.Filters -> {
            val basic = document.activeAdjustment<BasicAdjustment>()?.adjustments ?: ImageAdjustments()
            FilterPresetPanel(
                bitmap = baseBitmap,
                adjustments = basic,
                onSelectFilter = { filter -> vm.adjustments.updateActiveAdjustment(BasicAdjustment(filter.adjustments)) },
            )
        }
        EditorMode.Curves -> {
            val curves = document.activeAdjustment<CurvesAdj>()?.curves ?: CurvesAdjustment()
            CurvesPanel(curves, selectedCurveChannel, onCurveChannel) { vm.adjustments.updateActiveAdjustment(CurvesAdj(it)) }
        }
        EditorMode.HSL -> {
            val hsl = document.activeAdjustment<HslAdj>()?.hsl ?: HslAdjustments()
            HslPanel(hsl, selectedHslRange, onHslRange) { vm.adjustments.updateActiveAdjustment(HslAdj(it)) }
        }
        EditorMode.Levels -> {
            val levels = document.activeAdjustment<LevelsAdj>()?.levels ?: com.vayunmathur.photos.data.LevelsAdjustment()
            LevelsPanel(levels) { vm.adjustments.updateActiveAdjustment(LevelsAdj(it)) }
        }
        EditorMode.ColorBalance -> {
            val cb = document.activeAdjustment<ColorBalanceAdj>()?.balance ?: com.vayunmathur.photos.data.ColorBalanceAdjustment()
            ColorBalancePanel(cb) { vm.adjustments.updateActiveAdjustment(ColorBalanceAdj(it)) }
        }
        EditorMode.ChannelMixer -> {
            val mx = document.activeAdjustment<ChannelMixerAdj>()?.mixer ?: com.vayunmathur.photos.data.ChannelMixerAdjustment()
            ChannelMixerPanel(mx) { vm.adjustments.updateActiveAdjustment(ChannelMixerAdj(it)) }
        }
        EditorMode.BlackWhite -> {
            val bw = document.activeAdjustment<BlackAndWhiteAdj>()?.bw ?: com.vayunmathur.photos.data.BlackAndWhiteAdjustment(enabled = true)
            BlackWhitePanel(bw) { vm.adjustments.updateActiveAdjustment(BlackAndWhiteAdj(it.copy(enabled = true))) }
        }
        EditorMode.GradientMap -> {
            GradientMapPanel { stops -> vm.adjustments.updateActiveAdjustment(GradientMapAdj(com.vayunmathur.photos.data.GradientMapAdjustment(stops))) }
        }
        EditorMode.Vibrance -> {
            val v = document.activeAdjustment<VibranceAdj>()?.amount ?: 0f
            VibrancePanel(v) { vm.adjustments.updateActiveAdjustment(VibranceAdj(it)) }
        }
        EditorMode.PhotoFilter -> {
            val pf = document.activeAdjustment<PhotoFilterAdj>() ?: PhotoFilterAdj()
            PhotoFilterPanel(pf) { vm.adjustments.updateActiveAdjustment(it) }
        }
        EditorMode.SelectiveColor -> {
            val sc = document.activeAdjustment<SelectiveColorAdj>() ?: SelectiveColorAdj()
            SelectiveColorPanel(sc) { vm.adjustments.updateActiveAdjustment(it) }
        }
        EditorMode.Posterize -> {
            val p = document.activeAdjustment<PosterizeAdj>()?.levels ?: 4
            PosterizePanel(p) { vm.adjustments.updateActiveAdjustment(PosterizeAdj(it)) }
        }
        EditorMode.Threshold -> {
            val t = document.activeAdjustment<ThresholdAdj>()?.level ?: 128
            ThresholdPanel(t) { vm.adjustments.updateActiveAdjustment(ThresholdAdj(it)) }
        }
        EditorMode.Invert -> InvertPanel()
        EditorMode.LensBlur -> {
            val blur = document.activeAdjustment<BlurAdj>()?.blur ?: BlurParams()
            BlurPanel(blur) { vm.adjustments.updateActiveAdjustment(BlurAdj(it)) }
        }
        EditorMode.Selective -> {
            val sel = document.activeAdjustment<SelectiveAdj>()?.selective ?: SelectiveEdits()
            SelectiveEditPanel(
                mask = currentSelectiveMask,
                showMask = showSelectiveMask,
                onMaskChanged = onSelectiveMask,
                onShowMaskChanged = onShowSelectiveMask,
                onAddMask = {
                    vm.adjustments.updateActiveAdjustment(SelectiveAdj(sel.copy(masks = sel.masks + currentSelectiveMask)))
                    onSelectiveMask(SelectiveMask())
                },
            )
        }
        EditorMode.FilterFx -> FiltersPanel { adj -> vm.adjustments.addAdjustmentLayer(adj) }
        EditorMode.Liquify -> LiquifyPanel(liquifyTool, onLiquifyTool, liquifyStrength, onLiquifyStrength, liquifyRadius, onLiquifyRadius)
        EditorMode.Healing -> HealingPanel(healingBrushSize, isSettingHealingSource, onHealingBrushSize, onSetHealingSource)
        EditorMode.RedEye -> SimpleBrushPanel("Tap each eye. Brush", brushSize, onBrushSize)
        EditorMode.DodgeBurn -> DodgeBurnPanel(dodgeBurnMode, onDodgeBurnMode, brushSize, onBrushSize)
        EditorMode.Smudge -> SimpleBrushPanel("Drag to smudge. Brush", brushSize, onBrushSize)
        EditorMode.Selection -> SelectionPanel(
            tool = selectionTool, onTool = onSelectionTool,
            combine = selectionCombine, onCombine = onSelectionCombine,
            feather = selectionFeather, onFeather = onSelectionFeather,
            onFeatherCommit = onSelectionFeatherCommit,
            wandTolerance = wandTolerance, onWandTolerance = onWandTolerance,
            polygonPointCount = polygonPointCount, onClosePolygon = onClosePolygon,
            hasSelection = selection != null,
            onInvert = onSelectionInvert,
            onClear = onSelectionClear,
            onDelete = onSelectionDelete,
            onContentAwareFill = { vm.pixel.contentAwareFillSelection() },
            onSelectSubject = onSelectSubject,
        )
        EditorMode.Layers -> LayersPanel(
            document = document,
            hasSelection = selection != null,
            onSelectLayer = { vm.layers.setActiveLayer(it) },
            onToggleVisibility = { i, v -> vm.layers.setLayerVisibility(i, v) },
            onOpacityChange = { i, o -> vm.layers.setLayerOpacity(i, o) },
            onBlendModeChange = { i, m -> vm.layers.setLayerBlendMode(i, m) },
            onAddAdjustment = { vm.adjustments.addAdjustmentLayer(it) },
            onAddPixelLayer = { vm.layers.addEmptyPixelLayer() },
            onDuplicate = { vm.layers.duplicateLayer(it) },
            onMergeDown = { vm.layers.mergeDown(it) },
            onDelete = { vm.layers.removeLayer(it) },
            onFlatten = { vm.layers.flatten() },
            onAddMaskFromSelection = { vm.layers.selectionToActiveMask() },
            onDeleteMask = { vm.layers.deleteLayerMask(it) },
            onInvertMask = { vm.layers.invertLayerMask(it) },
            onToggleClip = { i, c -> vm.layers.setLayerClipped(i, c) },
            onSetStyle = { i, s -> vm.layers.setLayerStyle(i, s) },
            onGroupActive = { vm.layers.groupActiveWithBelow() },
            onUngroup = { vm.layers.ungroupActive() },
            onUpdateGroup = { vm.layers.updateGroup(it) },
            onMoveLayer = { from, to -> vm.layers.moveLayer(from, to) },
            onEditText = onEditTextLayer,
            onResumeDrawing = onResumeDrawingLayer,
        )
        EditorMode.MaskPaint -> MaskPaintPanel(
            reveal = maskPaintReveal, onReveal = onMaskPaintReveal,
            brushSize = maskBrushSize, onBrushSize = onMaskBrushSize,
        )
        EditorMode.Fill, EditorMode.GradientTool, EditorMode.ShapeRect,
        EditorMode.ShapeEllipse, EditorMode.ShapeLine, EditorMode.Eyedropper -> PaintPanel(
            mode = editorMode,
            color = paintColor, onColor = onPaintColor,
            recentColors = recentColors,
            fillTolerance = fillTolerance, onFillTolerance = onFillTolerance,
            strokeWidth = shapeStrokeWidth, onStrokeWidth = onShapeStrokeWidth,
        )
        EditorMode.Crop -> {}
        EditorMode.FreeTransform -> {}
        EditorMode.None -> {}
    }
}
