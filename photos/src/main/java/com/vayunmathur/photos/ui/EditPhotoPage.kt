package com.vayunmathur.photos.ui

import android.app.Activity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.HighlightAlt
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.core.net.toUri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.ink.brush.Brush
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.Stroke as InkStroke
import com.vayunmathur.library.ui.CanvasTextElement
import com.vayunmathur.library.ui.IconBack
import com.vayunmathur.library.ui.IconBrush
import com.vayunmathur.library.ui.IconCopy
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconCrop
import com.vayunmathur.library.ui.IconDraw
import com.vayunmathur.library.ui.IconEdit
import com.vayunmathur.library.ui.IconEraser
import com.vayunmathur.library.ui.IconRotateRight
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.IconStar
import com.vayunmathur.library.ui.IconUndo
import com.vayunmathur.library.ui.IconVisible
import com.vayunmathur.library.ui.InkCanvasView
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.translate
import com.vayunmathur.photos.R
import com.vayunmathur.photos.data.AdjustmentLayer
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
import com.vayunmathur.photos.data.DodgeBurnStroke
import com.vayunmathur.photos.data.DodgeBurnStrokes
import com.vayunmathur.photos.data.DrawingTool
import com.vayunmathur.photos.data.EditDocument
import com.vayunmathur.photos.data.GradientMapAdj
import com.vayunmathur.photos.data.HealMode
import com.vayunmathur.photos.data.HealingStroke
import com.vayunmathur.photos.data.HealingStrokes
import com.vayunmathur.photos.data.HslAdj
import com.vayunmathur.photos.data.HslAdjustments
import com.vayunmathur.photos.data.HslColorRange
import com.vayunmathur.photos.data.ImageAdjustments
import com.vayunmathur.photos.data.InvertAdj
import com.vayunmathur.photos.data.LayerAdjustment
import com.vayunmathur.photos.data.LevelsAdj
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.PhotoDao
import com.vayunmathur.photos.data.PhotoFilter
import com.vayunmathur.photos.data.PhotoFilterAdj
import com.vayunmathur.photos.data.PhotoFilters
import com.vayunmathur.photos.data.PixelLayer
import com.vayunmathur.photos.data.PosterizeAdj
import com.vayunmathur.photos.data.RedEyeSpot
import com.vayunmathur.photos.data.RedEyeSpots
import com.vayunmathur.photos.data.Selection
import com.vayunmathur.photos.data.SelectionCombine
import com.vayunmathur.photos.data.SelectiveAdj
import com.vayunmathur.photos.data.SelectiveColorAdj
import com.vayunmathur.photos.data.SelectiveColorRange
import com.vayunmathur.photos.data.SelectiveEdits
import com.vayunmathur.photos.data.SelectiveMask
import com.vayunmathur.photos.data.SmudgeStroke
import com.vayunmathur.photos.data.SmudgeStrokes
import com.vayunmathur.photos.data.TextElement
import com.vayunmathur.photos.data.TextLayer
import com.vayunmathur.photos.data.DrawingLayer
import com.vayunmathur.photos.data.ThresholdAdj
import com.vayunmathur.photos.data.VibranceAdj
import com.vayunmathur.photos.data.applyToBitmap
import com.vayunmathur.photos.data.applyHealingToBitmap
import com.vayunmathur.photos.data.toColorMatrix
import com.vayunmathur.photos.util.PhotoEditViewModel
import com.vayunmathur.library.util.ResultEffect
import com.vayunmathur.library.util.serialize
import com.vayunmathur.library.util.deserialize
import java.util.UUID
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private enum class ToolCategory(val label: String) {
    Adjust("Adjust"), Filters("Filters"), Retouch("Retouch"),
    Select("Select"), Transform("Crop"), Draw("Draw"), Paint("Paint"), Layers("Layers"),
}

private enum class EditorMode {
    None,
    Adjust, Filters, Curves, HSL, Levels, ColorBalance, ChannelMixer, BlackWhite, GradientMap,
    Vibrance, PhotoFilter, SelectiveColor, Posterize, Threshold, Invert,
    LensBlur, Selective, FilterFx, Liquify,
    Healing, RedEye, DodgeBurn, Smudge,
    Selection,
    Crop,
    FreeTransform,
    Layers,
    MaskPaint,
    Fill, GradientTool, ShapeRect, ShapeEllipse, ShapeLine, Eyedropper,
}

private data class ToolEntry(val mode: EditorMode, val label: String)

/** Selection sub-tools for the Select category. */
private enum class SelectionTool(val label: String) {
    Rectangle("Rectangle"), Ellipse("Ellipse"), Lasso("Lasso"), Polygon("Polygon"), Wand("Magic Wand"),
}

private val categoryTools: Map<ToolCategory, List<ToolEntry>> = mapOf(
    ToolCategory.Adjust to listOf(
        ToolEntry(EditorMode.Adjust, "Light"),
        ToolEntry(EditorMode.Filters, "Presets"),
        ToolEntry(EditorMode.Curves, "Curves"),
        ToolEntry(EditorMode.HSL, "HSL"),
        ToolEntry(EditorMode.Levels, "Levels"),
        ToolEntry(EditorMode.ColorBalance, "Balance"),
        ToolEntry(EditorMode.ChannelMixer, "Mixer"),
        ToolEntry(EditorMode.BlackWhite, "B&W"),
        ToolEntry(EditorMode.GradientMap, "Gradient"),
        ToolEntry(EditorMode.Vibrance, "Vibrance"),
        ToolEntry(EditorMode.PhotoFilter, "Photo Filter"),
        ToolEntry(EditorMode.SelectiveColor, "Selective Color"),
        ToolEntry(EditorMode.Posterize, "Posterize"),
        ToolEntry(EditorMode.Threshold, "Threshold"),
        ToolEntry(EditorMode.Invert, "Invert"),
    ),
    ToolCategory.Filters to listOf(
        ToolEntry(EditorMode.LensBlur, "Lens Blur"),
        ToolEntry(EditorMode.Selective, "Selective"),
        ToolEntry(EditorMode.FilterFx, "Filters"),
        ToolEntry(EditorMode.Liquify, "Liquify"),
    ),
    ToolCategory.Retouch to listOf(
        ToolEntry(EditorMode.Healing, "Heal"),
        ToolEntry(EditorMode.RedEye, "Red-Eye"),
        ToolEntry(EditorMode.DodgeBurn, "Dodge/Burn"),
        ToolEntry(EditorMode.Smudge, "Smudge"),
    ),
    ToolCategory.Select to listOf(
        ToolEntry(EditorMode.Selection, "Marquee"),
    ),
    ToolCategory.Transform to listOf(
        ToolEntry(EditorMode.Crop, "Crop & Rotate"),
        ToolEntry(EditorMode.FreeTransform, "Transform"),
    ),
    ToolCategory.Layers to listOf(
        ToolEntry(EditorMode.Layers, "Layers"),
        ToolEntry(EditorMode.MaskPaint, "Mask Brush"),
    ),
    ToolCategory.Paint to listOf(
        ToolEntry(EditorMode.Fill, "Fill"),
        ToolEntry(EditorMode.GradientTool, "Gradient"),
        ToolEntry(EditorMode.ShapeRect, "Rectangle"),
        ToolEntry(EditorMode.ShapeEllipse, "Ellipse"),
        ToolEntry(EditorMode.ShapeLine, "Line"),
        ToolEntry(EditorMode.Eyedropper, "Eyedropper"),
    ),
)

private fun ToolCategory.description(): String = when (this) {
    ToolCategory.Adjust -> "Tune light and color."
    ToolCategory.Filters -> "Effects, blur, and presets."
    ToolCategory.Retouch -> "Fix and clean up areas."
    ToolCategory.Select -> "Pick an area to limit edits."
    ToolCategory.Transform -> "Crop, straighten, and rotate."
    ToolCategory.Draw -> "Draw, highlight, and add text."
    ToolCategory.Paint -> "Fill, gradients, and shapes."
    ToolCategory.Layers -> "Manage layers and masks."
}

private fun EditorMode.description(): String = when (this) {
    EditorMode.Adjust -> "Fine-tune light and color with sliders."
    EditorMode.Filters -> "Apply a one-tap preset look."
    EditorMode.Curves -> "Reshape brightness and contrast with a curve."
    EditorMode.HSL -> "Adjust hue, saturation, and lightness per color."
    EditorMode.Levels -> "Set the black point, white point, and midtones."
    EditorMode.ColorBalance -> "Shift colors in shadows, midtones, and highlights."
    EditorMode.ChannelMixer -> "Blend the red, green, and blue channels."
    EditorMode.BlackWhite -> "Convert to black & white and control how colors map."
    EditorMode.GradientMap -> "Map dark-to-light tones onto a color gradient."
    EditorMode.Vibrance -> "Boost muted colors while protecting already-vivid ones and skin tones."
    EditorMode.PhotoFilter -> "Apply a warming or cooling color wash."
    EditorMode.SelectiveColor -> "Fine-tune cyan/magenta/yellow within one color range."
    EditorMode.Posterize -> "Reduce the number of tonal levels for a flat, graphic look."
    EditorMode.Threshold -> "Convert to pure black and white at a brightness cutoff."
    EditorMode.Invert -> "Invert all colors (photo negative)."
    EditorMode.LensBlur -> "Blur the background for a depth-of-field look."
    EditorMode.Selective -> "Paint an adjustment onto specific spots."
    EditorMode.FilterFx -> "Apply a baked-in photo filter to the pixels."
    EditorMode.Liquify -> "Push and warp pixels around."
    EditorMode.Healing -> "Remove blemishes by copying nearby pixels."
    EditorMode.RedEye -> "Tap each eye to remove red-eye."
    EditorMode.DodgeBurn -> "Brush to lighten (dodge) or darken (burn)."
    EditorMode.Smudge -> "Drag to smear pixels like wet paint."
    EditorMode.Selection -> "Draw an area; edits then apply only inside it."
    EditorMode.Crop -> "Crop, straighten, and rotate the photo."
    EditorMode.FreeTransform -> "Scale, rotate, skew, or distort the layer by dragging its corners; flip it."
    EditorMode.Layers -> "Stack, blend, and mask layers."
    EditorMode.MaskPaint -> "Brush on the active layer's mask: hide or reveal parts of it."
    EditorMode.Fill -> "Tap to flood-fill an area with the chosen color."
    EditorMode.GradientTool -> "Drag to draw a color-to-transparent gradient."
    EditorMode.ShapeRect -> "Drag to draw a filled rectangle."
    EditorMode.ShapeEllipse -> "Drag to draw a filled ellipse."
    EditorMode.ShapeLine -> "Drag to draw a line."
    EditorMode.Eyedropper -> "Tap the image to pick a color."
    EditorMode.None -> ""
}

private enum class AdjustmentType(
    val label: String,
    val min: Float,
    val max: Float,
    val get: (ImageAdjustments) -> Float,
    val set: (ImageAdjustments, Float) -> ImageAdjustments,
) {
    Brightness("Brightness", -100f, 100f, { it.brightness }, { a, v -> a.copy(brightness = v) }),
    Contrast("Contrast", -100f, 100f, { it.contrast }, { a, v -> a.copy(contrast = v) }),
    Saturation("Saturation", -100f, 100f, { it.saturation }, { a, v -> a.copy(saturation = v) }),
    Warmth("Warmth", -100f, 100f, { it.warmth }, { a, v -> a.copy(warmth = v) }),
    Exposure("Exposure", -100f, 100f, { it.exposure }, { a, v -> a.copy(exposure = v) }),
    Highlights("Highlights", -100f, 100f, { it.highlights }, { a, v -> a.copy(highlights = v) }),
    Shadows("Shadows", -100f, 100f, { it.shadows }, { a, v -> a.copy(shadows = v) }),
    Sharpness("Sharpness", 0f, 100f, { it.sharpness }, { a, v -> a.copy(sharpness = v) }),
    Vignette("Vignette", 0f, 100f, { it.vignette }, { a, v -> a.copy(vignette = v) }),
    Grain("Grain", 0f, 100f, { it.grain }, { a, v -> a.copy(grain = v) }),
    Fade("Fade", 0f, 100f, { it.fade }, { a, v -> a.copy(fade = v) }),
    Tint("Tint", -100f, 100f, { it.tint }, { a, v -> a.copy(tint = v) }),
}

private inline fun <reified T : LayerAdjustment> EditDocument.activeAdjustment(): T? =
    (activeLayer as? AdjustmentLayer)?.adjustment as? T

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditPhotoPage(
    backStack: NavBackStack<EditRoute>,
    photoEditViewModel: PhotoEditViewModel,
    id: Long,
    initialUri: String? = null,
) {
    val vm = photoEditViewModel
    val context = LocalActivity.current!!
    LaunchedEffect(id, initialUri) { vm.loadPhoto(id, initialUri) }
    val photo by vm.photo.collectAsState()

    val document by vm.document.collectAsState()
    val preview by vm.compositedPreview.collectAsState()
    val baseBitmap by vm.baseBitmap.collectAsState()
    val selection by vm.selection.collectAsState()
    val canUndo by vm.canUndo.collectAsState()
    val canRedo by vm.canRedo.collectAsState()

    var activeCategory by remember { mutableStateOf<ToolCategory?>(null) }
    var editorMode by remember { mutableStateOf(EditorMode.None) }
    var selectedAdjustment by remember { mutableStateOf(AdjustmentType.Brightness) }
    var selectedCurveChannel by remember { mutableStateOf(CurveChannel.Combined) }
    var selectedHslRange by remember { mutableStateOf(HslColorRange.Red) }

    var isCropping by remember { mutableStateOf(false) }
    var cropCx by remember { mutableFloatStateOf(0.5f) }
    var cropCy by remember { mutableFloatStateOf(0.5f) }
    var cropHx by remember { mutableFloatStateOf(0.5f) }
    var cropHy by remember { mutableFloatStateOf(0.5f) }
    var cropAngle by remember { mutableFloatStateOf(0f) }
    var cropAspect by remember { mutableStateOf<Float?>(null) }
    var showSaveMenu by remember { mutableStateOf(false) }

    // Selective
    var currentSelectiveMask by remember { mutableStateOf(SelectiveMask()) }
    var showSelectiveMask by remember { mutableStateOf(false) }

    // Healing
    var healingBrushSize by remember { mutableFloatStateOf(0.02f) }
    var isSettingHealingSource by remember { mutableStateOf(true) }
    var healingSourceX by remember { mutableStateOf<Float?>(null) }
    var healingSourceY by remember { mutableStateOf<Float?>(null) }
    var currentHealingPoints by remember { mutableStateOf<List<Pair<Float, Float>>>(emptyList()) }

    // Retouch brush (dodge/burn/smudge)
    var retouchPoints by remember { mutableStateOf<List<Pair<Float, Float>>>(emptyList()) }
    var dodgeBurnMode by remember { mutableStateOf(DodgeBurnMode.Dodge) }
    var brushSize by remember { mutableFloatStateOf(0.05f) }

    // Liquify
    var liquifyTool by remember { mutableStateOf(com.vayunmathur.photos.data.LiquifyTool.Push) }
    var liquifyStrength by remember { mutableFloatStateOf(0.5f) }
    var liquifyRadius by remember { mutableFloatStateOf(0.15f) }

    // Selection tool
    var selectionTool by remember { mutableStateOf(SelectionTool.Rectangle) }
    var selectionCombine by remember { mutableStateOf(SelectionCombine.New) }
    var selectionFeather by remember { mutableFloatStateOf(0f) }
    var wandTolerance by remember { mutableFloatStateOf(0.15f) }
    // The committed selection before feathering, so feather can be re-applied live.
    var selectionBase by remember { mutableStateOf<Selection?>(null) }
    var selDragStart by remember { mutableStateOf<Offset?>(null) }
    var selDragCurrent by remember { mutableStateOf<Offset?>(null) }
    var lassoPoints by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var polygonPoints by remember { mutableStateOf<List<Offset>>(emptyList()) }

    // Mask brush
    var maskPaintReveal by remember { mutableStateOf(false) }
    var maskBrushSize by remember { mutableFloatStateOf(0.06f) }
    var maskPoints by remember { mutableStateOf<List<Pair<Float, Float>>>(emptyList()) }

    // Paint (fill / gradient / shapes)
    var paintColor by remember { mutableStateOf(Color.Red) }
    val recentColors = remember { mutableStateListOf<Color>() }
    var fillTolerance by remember { mutableFloatStateOf(0.2f) }
    var shapeStrokeWidth by remember { mutableFloatStateOf(0.01f) }
    var paintDragStart by remember { mutableStateOf<Offset?>(null) }
    var paintDragCurrent by remember { mutableStateOf<Offset?>(null) }

    // Free transform corners (normalized 0..1)
    var ftTL by remember { mutableStateOf(Offset(0f, 0f)) }
    var ftTR by remember { mutableStateOf(Offset(1f, 0f)) }
    var ftBL by remember { mutableStateOf(Offset(0f, 1f)) }
    var ftBR by remember { mutableStateOf(Offset(1f, 1f)) }

    // Drawing
    val inkStrokes = remember { mutableStateListOf<InkStroke>() }
    val redoStrokes = remember { mutableStateListOf<InkStroke>() }
    var activeTool by remember { mutableStateOf(DrawingTool.Pointer) }
    var penColor by remember { mutableStateOf(Color.Red) }
    var penSize by remember { mutableFloatStateOf(10f) }
    var highlighterColor by remember { mutableStateOf(Color.Yellow) }
    var highlighterSize by remember { mutableFloatStateOf(40f) }
    var highlighterOpacity by remember { mutableFloatStateOf(0.5f) }
    var textFontSize by remember { mutableFloatStateOf(40f) }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { _, zoomChange, offsetChange, _ ->
        if (activeTool == DrawingTool.Pointer) { scale *= zoomChange; offset += offsetChange }
    }

    val texts = remember { mutableStateListOf<TextElement>() }
    var selectedTextId by remember { mutableStateOf<String?>(null) }
    var selectedStrokeIndex by remember { mutableStateOf<Int?>(null) }
    var selectedTextIndex by remember { mutableStateOf<Int?>(null) }
    var textToEdit by remember { mutableStateOf<TextElement?>(null) }
    var currentViewportWidth by remember { mutableFloatStateOf(1f) }
    var currentViewportHeight by remember { mutableFloatStateOf(1f) }

    val isDrawing = activeCategory == ToolCategory.Draw

    fun exitCropPreview() {
        vm.setCroppingPreview(false)
        isCropping = false
    }

    fun selectTool(mode: EditorMode) {
        when (mode) {
            EditorMode.Crop -> {
                cropCx = 0.5f; cropCy = 0.5f; cropHx = 0.5f; cropHy = 0.5f
                cropAngle = 0f; cropAspect = null
                isCropping = true
                vm.setCroppingPreview(true)
                editorMode = EditorMode.Crop
            }
            else -> {
                if (isCropping) exitCropPreview()
                editorMode = if (editorMode == mode) EditorMode.None else mode
            }
        }
    }

    fun useColor(c: Color) {
        paintColor = c
        recentColors.remove(c)
        recentColors.add(0, c)
        while (recentColors.size > 8) recentColors.removeAt(recentColors.size - 1)
    }

    fun commitOverlays() {
        if (inkStrokes.isNotEmpty() || texts.isNotEmpty()) {
            vm.commitOverlaysToLayers(
                inkStrokes.map { it.serialize() }, texts.toList(),
                currentViewportWidth, currentViewportHeight,
            )
            inkStrokes.clear(); texts.clear(); redoStrokes.clear()
            selectedStrokeIndex = null; selectedTextIndex = null; selectedTextId = null
        }
    }

    fun goHome() {
        commitOverlays()
        if (isCropping) exitCropPreview()
        activeCategory = null
        editorMode = EditorMode.None
        activeTool = DrawingTool.Pointer
        selectedTextId = null
        selectedStrokeIndex = null
        selectedTextIndex = null
    }

    fun openCategory(cat: ToolCategory) {
        if (activeCategory == ToolCategory.Draw && cat != ToolCategory.Draw) commitOverlays()
        activeCategory = cat
        if (cat == ToolCategory.Draw) {
            activeTool = DrawingTool.Pointer
        } else {
            categoryTools[cat]?.firstOrNull()?.let { selectTool(it.mode) }
        }
    }

    // Mask resolution for built selections (capped so it stays cheap).
    fun selMaskSize(): Pair<Int, Int> {
        val cw = document.canvasWidth.coerceAtLeast(1)
        val ch = document.canvasHeight.coerceAtLeast(1)
        val scale = minOf(1f, 768f / maxOf(cw, ch))
        return (cw * scale).roundToInt().coerceAtLeast(1) to (ch * scale).roundToInt().coerceAtLeast(1)
    }

    // Combine a freshly built selection with the existing base, apply feather,
    // and push to the VM. All selection tools funnel through here.
    fun applySelection(fresh: Selection) {
        val base = if (selectionCombine == SelectionCombine.New || selectionBase == null) fresh
        else selectionBase!!.combine(fresh, selectionCombine)
        selectionBase = base
        val featherScaled = selectionFeather * (base.width.toFloat() / document.canvasWidth.coerceAtLeast(1))
        vm.setSelection(if (featherScaled > 0f) base.applyFeather(featherScaled) else base)
    }

    fun reapplyFeather() {
        val base = selectionBase ?: return
        val featherScaled = selectionFeather * (base.width.toFloat() / document.canvasWidth.coerceAtLeast(1))
        vm.setSelection(if (featherScaled > 0f) base.applyFeather(featherScaled) else base)
    }

    fun clearSelection() {
        selectionBase = null; lassoPoints = emptyList(); polygonPoints = emptyList()
        vm.setSelection(null)
    }

    fun commitMarquee(rect: Rect, isEllipse: Boolean) {
        val (mw, mh) = selMaskSize()
        val fresh = if (isEllipse) {
            Selection.ellipse(mw, mh, (rect.left + rect.right) / 2f, (rect.top + rect.bottom) / 2f, (rect.right - rect.left) / 2f, (rect.bottom - rect.top) / 2f)
        } else {
            Selection.rectangle(mw, mh, rect.left, rect.top, rect.right, rect.bottom)
        }
        applySelection(fresh)
    }

    fun commitPolygon(pointsNorm: List<Pair<Float, Float>>) {
        if (pointsNorm.size < 3) return
        val (mw, mh) = selMaskSize()
        applySelection(Selection.polygon(mw, mh, pointsNorm))
    }

    fun applyCrop() {
        val w = document.canvasWidth.coerceAtLeast(1).toFloat()
        val h = document.canvasHeight.coerceAtLeast(1).toFloat()
        val aRad = Math.toRadians(cropAngle.toDouble())
        val cosA = cos(aRad)
        val sinA = sin(aRad)
        val cpx = cropCx * w
        val cpy = cropCy * h
        val hwpx = cropHx * w
        val hhpx = cropHy * h
        val wp = w * abs(cosA) + h * abs(sinA)
        val hp = w * abs(sinA) + h * abs(cosA)
        val dx = cpx - w / 2.0
        val dy = cpy - h / 2.0
        // Rotate the crop center by -angle (the image rotation we will apply), into result space.
        val rx = cosA * dx + sinA * dy
        val ry = -sinA * dx + cosA * dy
        val crx = rx + wp / 2.0
        val cry = ry + hp / 2.0
        val l = ((crx - hwpx) / wp).toFloat().coerceIn(0f, 1f)
        val t = ((cry - hhpx) / hp).toFloat().coerceIn(0f, 1f)
        val r = ((crx + hwpx) / wp).toFloat().coerceIn(0f, 1f)
        val b = ((cry + hhpx) / hp).toFloat().coerceIn(0f, 1f)
        vm.setRotation(-cropAngle)
        vm.setCropRect(Rect(l, t, r, b))
        goHome()
    }

    val currentBrush: Brush = remember(activeTool, penColor, penSize, highlighterColor, highlighterSize, highlighterOpacity) {
        when (activeTool) {
            DrawingTool.Highlighter -> {
                val argb = highlighterColor.toArgb()
                val alpha = (highlighterOpacity * 255).roundToInt()
                val colorWithAlpha = (alpha shl 24) or (argb and 0x00FFFFFF)
                Brush.createWithColorIntArgb(StockBrushes.highlighter(), colorWithAlpha, highlighterSize, 0.1f)
            }
            else -> Brush.createWithColorIntArgb(StockBrushes.pressurePen(), penColor.toArgb(), penSize, 0.1f)
        }
    }

    ResultEffect<DrawingSettingsResult>("drawing_settings") { result ->
        var changed = false
        selectedTextId?.let { tid ->
            val index = texts.indexOfFirst { it.id == tid }
            if (index != -1) {
                texts[index] = texts[index].copy(color = result.color, fontSize = result.thickness)
                changed = true
            }
        }
        if (!changed) {
            activeTool = result.tool
            when (result.tool) {
                DrawingTool.Pen -> { penColor = Color(result.color); penSize = result.thickness }
                DrawingTool.Highlighter -> {
                    highlighterColor = Color(result.color); highlighterSize = result.thickness; highlighterOpacity = result.opacity
                }
                DrawingTool.Text -> { penColor = Color(result.color); textFontSize = result.thickness }
                else -> {}
            }
        }
    }

    LaunchedEffect(photo?.uri) {
        val uri = photo?.uri?.toUri() ?: return@LaunchedEffect
        vm.decode(uri)
    }

    // Ensure the right layer is active for the selected tool.
    LaunchedEffect(editorMode) {
        when (editorMode) {
            EditorMode.Adjust, EditorMode.Filters ->
                vm.ensureAdjustment({ it is BasicAdjustment }, { BasicAdjustment() })
            EditorMode.Curves -> vm.ensureAdjustment({ it is CurvesAdj }, { CurvesAdj() })
            EditorMode.HSL -> vm.ensureAdjustment({ it is HslAdj }, { HslAdj() })
            EditorMode.Levels -> vm.ensureAdjustment({ it is LevelsAdj }, { LevelsAdj() })
            EditorMode.ColorBalance -> vm.ensureAdjustment({ it is ColorBalanceAdj }, { ColorBalanceAdj() })
            EditorMode.ChannelMixer -> vm.ensureAdjustment({ it is ChannelMixerAdj }, { ChannelMixerAdj() })
            EditorMode.BlackWhite -> vm.ensureAdjustment({ it is BlackAndWhiteAdj }, { BlackAndWhiteAdj() })
            EditorMode.Vibrance -> vm.ensureAdjustment({ it is VibranceAdj }, { VibranceAdj() })
            EditorMode.PhotoFilter -> vm.ensureAdjustment({ it is PhotoFilterAdj }, { PhotoFilterAdj() })
            EditorMode.SelectiveColor -> vm.ensureAdjustment({ it is SelectiveColorAdj }, { SelectiveColorAdj() })
            EditorMode.Posterize -> vm.ensureAdjustment({ it is PosterizeAdj }, { PosterizeAdj() })
            EditorMode.Threshold -> vm.ensureAdjustment({ it is ThresholdAdj }, { ThresholdAdj() })
            EditorMode.Invert -> vm.ensureAdjustment({ it is InvertAdj }, { InvertAdj() })
            EditorMode.LensBlur -> vm.ensureAdjustment({ it is BlurAdj }, { BlurAdj() })
            EditorMode.Selective -> vm.ensureAdjustment({ it is SelectiveAdj }, { SelectiveAdj() })
            EditorMode.Healing, EditorMode.RedEye, EditorMode.DodgeBurn, EditorMode.Smudge, EditorMode.FilterFx, EditorMode.Liquify,
            EditorMode.Fill, EditorMode.GradientTool, EditorMode.ShapeRect, EditorMode.ShapeEllipse, EditorMode.ShapeLine -> {
                val idx = document.layers.indexOfLast { it is PixelLayer }
                if (idx >= 0 && idx != document.activeLayerIndex) vm.setActiveLayer(idx)
            }
            EditorMode.FreeTransform -> {
                val idx = document.layers.indexOfLast { it is PixelLayer }
                if (idx >= 0 && idx != document.activeLayerIndex) vm.setActiveLayer(idx)
                ftTL = Offset(0f, 0f); ftTR = Offset(1f, 0f); ftBL = Offset(0f, 1f); ftBR = Offset(1f, 1f)
            }
            else -> {}
        }
    }

    val writePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) vm.onWritePermissionGranted()
        else vm.onWritePermissionDenied()
    }
    val writePermissionRequest by vm.writePermissionRequest.collectAsState()
    LaunchedEffect(writePermissionRequest) {
        writePermissionRequest?.let { writePermissionLauncher.launch(IntentSenderRequest.Builder(it).build()) }
    }

    fun doSave(asCopy: Boolean, format: com.vayunmathur.photos.util.ExportFormat = com.vayunmathur.photos.util.ExportFormat.Jpeg) {
        // Fold any in-progress strokes/text into layers so they're part of the
        // composite; save with no separate overlay baking.
        commitOverlays()
        photo?.let {
            vm.savePhoto(
                it, asCopy, emptyList(), emptyList(),
                currentViewportWidth, currentViewportHeight, format,
            ) { context.finish() }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_edit_photo), maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = { context.finish() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    val strokeUndo = isDrawing && inkStrokes.isNotEmpty()
                    val strokeRedo = isDrawing && redoStrokes.isNotEmpty()
                    IconButton(
                        onClick = {
                            if (strokeUndo) redoStrokes.add(inkStrokes.removeAt(inkStrokes.size - 1))
                            else vm.undo()
                        },
                        enabled = strokeUndo || canUndo,
                    ) { IconUndo() }
                    IconButton(
                        onClick = {
                            if (strokeRedo) inkStrokes.add(redoStrokes.removeAt(redoStrokes.size - 1))
                            else vm.redo()
                        },
                        enabled = strokeRedo || canRedo,
                    ) {
                        Text("↻", fontSize = 20.sp)
                    }
                    Box {
                        IconButton(onClick = { showSaveMenu = true }) { IconSave() }
                        DropdownMenu(expanded = showSaveMenu, onDismissRequest = { showSaveMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_save)) },
                                onClick = { showSaveMenu = false; doSave(false) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_save_as_copy)) },
                                onClick = { showSaveMenu = false; doSave(true, com.vayunmathur.photos.util.ExportFormat.Jpeg) },
                            )
                            DropdownMenuItem(
                                text = { Text("Export as PNG") },
                                onClick = { showSaveMenu = false; doSave(true, com.vayunmathur.photos.util.ExportFormat.Png) },
                            )
                            DropdownMenuItem(
                                text = { Text("Export as WebP") },
                                onClick = { showSaveMenu = false; doSave(true, com.vayunmathur.photos.util.ExportFormat.Webp) },
                            )
                        }
                    }
                },
            )
        },
    ) { paddingValues ->
        val layoutDirection = LocalLayoutDirection.current
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = paddingValues.calculateStartPadding(layoutDirection),
                    end = paddingValues.calculateEndPadding(layoutDirection),
                )
                .background(MaterialTheme.colorScheme.background),
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = paddingValues.calculateTopPadding())
                    .padding(8.dp),
                contentAlignment = Alignment.Center,
            ) {
                val maxW = constraints.maxWidth.toFloat()
                val maxH = constraints.maxHeight.toFloat()
                val display = preview ?: baseBitmap
                if (display != null) {
                    val ratio = display.width.toFloat() / display.height.toFloat()
                    val containerRatio = maxW / maxH
                    val (vpW, vpH) = if (ratio > containerRatio) maxW to (maxW / ratio) else (maxH * ratio) to maxH

                    val density = LocalDensity.current
                    val densityFloat = density.density
                    val vpWdp = with(density) { vpW.toDp() }
                    val vpHdp = with(density) { vpH.toDp() }

                    val isInkDrawing = isDrawing && activeTool != DrawingTool.Pointer && activeTool != DrawingTool.Text
                    Box(
                        modifier = Modifier
                            .size(vpWdp, vpHdp)
                            .onGloballyPositioned {
                                currentViewportWidth = it.size.width.toFloat()
                                currentViewportHeight = it.size.height.toFloat()
                            }
                            .graphicsLayer {
                                scaleX = scale; scaleY = scale
                                translationX = offset.x; translationY = offset.y; clip = false
                            }
                            // Always-visible thin outline of the photo so its edges are
                            // distinguishable from the black background (tracks zoom/pan).
                            .border(1.dp, MaterialTheme.colorScheme.primary)
                            .then(
                                if (activeTool == DrawingTool.Text && isDrawing) Modifier.pointerInput(activeTool) {
                                    detectTapGestures { tapOffset ->
                                        val newId = UUID.randomUUID().toString()
                                        texts.add(
                                            TextElement(
                                                id = newId, text = "New Text",
                                                x = tapOffset.x / size.width, y = tapOffset.y / size.height,
                                                rotation = 0f, color = penColor.toArgb(), fontSize = textFontSize,
                                            )
                                        )
                                        selectedTextId = newId
                                        selectedTextIndex = texts.size - 1
                                        textToEdit = texts.last()
                                    }
                                }
                                else if (!isInkDrawing && activeTool != DrawingTool.Pointer && isDrawing)
                                    Modifier.transformable(state = transformState)
                                else Modifier,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        display.let { bitmap ->
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        val canvasTextElements by remember {
                            derivedStateOf {
                                texts.map { te ->
                                    CanvasTextElement(
                                        text = te.text, x = te.x * currentViewportWidth, y = te.y * currentViewportHeight,
                                        rotation = te.rotation, color = te.color, fontSize = te.fontSize,
                                        fontFamily = te.fontFamily, bold = te.bold, italic = te.italic, align = te.align,
                                    )
                                }
                            }
                        }

                        InkCanvasView(
                            currentBrush = currentBrush,
                            finishedStrokes = inkStrokes.toList(),
                            onStrokeFinished = { inkStrokes.add(it); redoStrokes.clear() },
                            onStrokeErased = { inkStrokes.remove(it) },
                            eraserMode = activeTool == DrawingTool.Eraser,
                            enabled = isDrawing && activeTool != DrawingTool.Pointer && activeTool != DrawingTool.Text,
                            textElements = canvasTextElements,
                            selectedStrokeIndex = selectedStrokeIndex,
                            selectedTextIndex = selectedTextIndex,
                            modifier = Modifier.fillMaxSize(),
                        )

                        if (isDrawing && activeTool == DrawingTool.Pointer) {
                            Box(
                                modifier = Modifier.fillMaxSize().pointerInput(
                                    selectedStrokeIndex, selectedTextIndex,
                                    currentViewportWidth, currentViewportHeight,
                                ) {
                                    detectTapGestures(
                                        onDoubleTap = { tapOffset ->
                                            val ti = hitTestText(tapOffset.x, tapOffset.y, texts, currentViewportWidth, currentViewportHeight, densityFloat)
                                            if (ti != null) textToEdit = texts.getOrNull(ti)
                                        },
                                        onTap = { tapOffset ->
                                            val ti = hitTestText(tapOffset.x, tapOffset.y, texts, currentViewportWidth, currentViewportHeight, densityFloat)
                                            if (ti != null) {
                                                selectedTextIndex = ti; selectedStrokeIndex = null; selectedTextId = texts.getOrNull(ti)?.id
                                            } else {
                                                val si = hitTestStroke(tapOffset.x, tapOffset.y, inkStrokes)
                                                selectedStrokeIndex = si; selectedTextIndex = null; selectedTextId = null
                                            }
                                        },
                                    )
                                }.pointerInput(selectedStrokeIndex, selectedTextIndex, currentViewportWidth, currentViewportHeight) {
                                    detectDragGestures(
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            selectedStrokeIndex?.let { idx ->
                                                if (idx in inkStrokes.indices) inkStrokes[idx] = inkStrokes[idx].translate(dragAmount.x, dragAmount.y)
                                            }
                                            selectedTextIndex?.let { idx ->
                                                if (idx in texts.indices) {
                                                    val c = texts[idx]
                                                    texts[idx] = c.copy(x = c.x + dragAmount.x / currentViewportWidth, y = c.y + dragAmount.y / currentViewportHeight)
                                                }
                                            }
                                        },
                                    )
                                },
                            )
                        }

                        // Tool overlays (only when not drawing)
                        if (!isDrawing && !isCropping) {
                            // Keep the live selection visible in every mode (until
                            // cleared) so the user knows edits are scoped.
                            selection?.let { if (selDragStart == null) SelectionMaskOverlay(it) }
                            val blurAdj = document.activeAdjustment<BlurAdj>()
                            if (editorMode == EditorMode.LensBlur && blurAdj != null && !blurAdj.blur.isIdentity()) {
                                BlurOverlay(blurParams = blurAdj.blur, onBlurChanged = { vm.updateActiveAdjustment(BlurAdj(it)) })
                            }
                            if (editorMode == EditorMode.Selective) {
                                MaskOverlay(mask = currentSelectiveMask, showMask = showSelectiveMask, onMaskChanged = { currentSelectiveMask = it })
                            }
                            if (editorMode == EditorMode.Healing) {
                                HealingOverlay(
                                    sourceX = healingSourceX, sourceY = healingSourceY, brushSize = healingBrushSize,
                                    isSettingSource = isSettingHealingSource,
                                    onSourceSet = { x, y -> healingSourceX = x; healingSourceY = y; isSettingHealingSource = false },
                                    onPaint = { x, y -> if (healingSourceX != null && healingSourceY != null) currentHealingPoints = currentHealingPoints + (x to y) },
                                )
                            }
                            if (editorMode == EditorMode.RedEye) {
                                Box(modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                                    detectTapGestures { o ->
                                        val nx = o.x / size.width; val ny = o.y / size.height
                                        vm.applyToActivePixelLayer { RedEyeSpots(listOf(RedEyeSpot(nx, ny, brushSize))).applyToBitmap(it) }
                                    }
                                })
                            }
                            if (editorMode == EditorMode.DodgeBurn || editorMode == EditorMode.Smudge) {
                                Box(modifier = Modifier.fillMaxSize().pointerInput(editorMode) {
                                    detectDragGestures(
                                        onDrag = { change, _ ->
                                            change.consume()
                                            val nx = (change.position.x / size.width).coerceIn(0f, 1f)
                                            val ny = (change.position.y / size.height).coerceIn(0f, 1f)
                                            retouchPoints = retouchPoints + (nx to ny)
                                        },
                                    )
                                })
                            }
                            if (editorMode == EditorMode.Liquify) {
                                var liqStart by remember { mutableStateOf<Offset?>(null) }
                                Box(modifier = Modifier.fillMaxSize().pointerInput(liquifyTool) {
                                    detectDragGestures(
                                        onDragStart = { liqStart = it },
                                        onDragEnd = {
                                            val s = liqStart
                                            if (s != null) {
                                                val nx = (s.x / size.width).coerceIn(0f, 1f)
                                                val ny = (s.y / size.height).coerceIn(0f, 1f)
                                                val op = com.vayunmathur.photos.data.LiquifyOp(
                                                    tool = liquifyTool, x = nx, y = ny,
                                                    radius = liquifyRadius, strength = liquifyStrength,
                                                )
                                                vm.applyToActivePixelLayer {
                                                    com.vayunmathur.photos.data.LiquifyParams(listOf(op)).applyToBitmap(it)
                                                }
                                            }
                                            liqStart = null
                                        },
                                        onDrag = { change, _ ->
                                            change.consume()
                                            val s = liqStart ?: return@detectDragGestures
                                            if (liquifyTool == com.vayunmathur.photos.data.LiquifyTool.Push) {
                                                val nx = (s.x / size.width).coerceIn(0f, 1f)
                                                val ny = (s.y / size.height).coerceIn(0f, 1f)
                                                val ddx = (change.position.x - s.x) / size.width
                                                val ddy = (change.position.y - s.y) / size.height
                                                val op = com.vayunmathur.photos.data.LiquifyOp(
                                                    tool = com.vayunmathur.photos.data.LiquifyTool.Push,
                                                    x = nx, y = ny, dx = ddx, dy = ddy,
                                                    radius = liquifyRadius, strength = liquifyStrength,
                                                )
                                                vm.applyToActivePixelLayer {
                                                    com.vayunmathur.photos.data.LiquifyParams(listOf(op)).applyToBitmap(it)
                                                }
                                                liqStart = change.position
                                            }
                                        },
                                    )
                                })
                            }
                            if (editorMode == EditorMode.Selection) {
                                when (selectionTool) {
                                    SelectionTool.Rectangle, SelectionTool.Ellipse -> SelectionOverlay(
                                        isEllipse = selectionTool == SelectionTool.Ellipse,
                                        dragStart = selDragStart,
                                        dragCurrent = selDragCurrent,
                                        onStart = { selDragStart = it; selDragCurrent = it },
                                        onDrag = { selDragCurrent = it },
                                        onEnd = {
                                            val s = selDragStart; val e = selDragCurrent
                                            if (s != null && e != null) {
                                                val l = (minOf(s.x, e.x) / currentViewportWidth).coerceIn(0f, 1f)
                                                val t = (minOf(s.y, e.y) / currentViewportHeight).coerceIn(0f, 1f)
                                                val r = (maxOf(s.x, e.x) / currentViewportWidth).coerceIn(0f, 1f)
                                                val b = (maxOf(s.y, e.y) / currentViewportHeight).coerceIn(0f, 1f)
                                                if (r - l > 0.01f && b - t > 0.01f) {
                                                    commitMarquee(Rect(l, t, r, b), selectionTool == SelectionTool.Ellipse)
                                                }
                                            }
                                            selDragStart = null; selDragCurrent = null
                                        },
                                    )
                                    SelectionTool.Lasso -> LassoOverlay(
                                        points = lassoPoints,
                                        onStart = { lassoPoints = listOf(it) },
                                        onDrag = { lassoPoints = lassoPoints + it },
                                        onEnd = {
                                            val norm = lassoPoints.map {
                                                (it.x / currentViewportWidth).coerceIn(0f, 1f) to (it.y / currentViewportHeight).coerceIn(0f, 1f)
                                            }
                                            lassoPoints = emptyList()
                                            commitPolygon(norm)
                                        },
                                    )
                                    SelectionTool.Polygon -> PolygonOverlay(
                                        points = polygonPoints,
                                        onTap = { polygonPoints = polygonPoints + it },
                                    )
                                    SelectionTool.Wand -> Box(
                                        modifier = Modifier.fillMaxSize().pointerInput(wandTolerance, selectionCombine) {
                                            detectTapGestures { o ->
                                                val nx = (o.x / size.width).coerceIn(0f, 1f)
                                                val ny = (o.y / size.height).coerceIn(0f, 1f)
                                                baseBitmap?.let { bmp ->
                                                    applySelection(Selection.magicWand(bmp, nx, ny, wandTolerance))
                                                }
                                            }
                                        },
                                    )
                                }
                            }
                            if (editorMode == EditorMode.MaskPaint) {
                                Box(modifier = Modifier.fillMaxSize().pointerInput(maskBrushSize, maskPaintReveal) {
                                    detectDragGestures(
                                        onDrag = { change, _ ->
                                            change.consume()
                                            val nx = (change.position.x / size.width).coerceIn(0f, 1f)
                                            val ny = (change.position.y / size.height).coerceIn(0f, 1f)
                                            maskPoints = maskPoints + (nx to ny)
                                        },
                                        onDragEnd = {
                                            if (maskPoints.isNotEmpty()) {
                                                vm.paintOnActiveMask(maskPoints, maskBrushSize, if (maskPaintReveal) 1f else 0f)
                                                maskPoints = emptyList()
                                            }
                                        },
                                    )
                                })
                            }
                            if (editorMode == EditorMode.Fill) {
                                Box(modifier = Modifier.fillMaxSize().pointerInput(paintColor, fillTolerance) {
                                    detectTapGestures { o ->
                                        val nx = (o.x / size.width).coerceIn(0f, 1f)
                                        val ny = (o.y / size.height).coerceIn(0f, 1f)
                                        vm.applyToActivePixelLayer { com.vayunmathur.photos.data.floodFillBitmap(it, nx, ny, paintColor.toArgb(), fillTolerance) }
                                    }
                                })
                            }
                            if (editorMode == EditorMode.GradientTool || editorMode == EditorMode.ShapeRect ||
                                editorMode == EditorMode.ShapeEllipse || editorMode == EditorMode.ShapeLine
                            ) {
                                PaintDragOverlay(
                                    mode = editorMode,
                                    color = paintColor,
                                    start = paintDragStart,
                                    current = paintDragCurrent,
                                    onStart = { paintDragStart = it; paintDragCurrent = it },
                                    onDrag = { paintDragCurrent = it },
                                    onEnd = {
                                        val s = paintDragStart; val e = paintDragCurrent
                                        if (s != null && e != null) {
                                            val x0 = (s.x / currentViewportWidth).coerceIn(0f, 1f)
                                            val y0 = (s.y / currentViewportHeight).coerceIn(0f, 1f)
                                            val x1 = (e.x / currentViewportWidth).coerceIn(0f, 1f)
                                            val y1 = (e.y / currentViewportHeight).coerceIn(0f, 1f)
                                            val argb = paintColor.toArgb()
                                            when (editorMode) {
                                                EditorMode.GradientTool -> vm.applyToActivePixelLayer {
                                                    com.vayunmathur.photos.data.drawGradientBitmap(it, x0, y0, x1, y1, argb)
                                                }
                                                EditorMode.ShapeRect -> vm.applyToActivePixelLayer {
                                                    com.vayunmathur.photos.data.drawShapeBitmap(it, com.vayunmathur.photos.data.PaintShape.Rectangle, minOf(x0, x1), minOf(y0, y1), maxOf(x0, x1), maxOf(y0, y1), argb, shapeStrokeWidth)
                                                }
                                                EditorMode.ShapeEllipse -> vm.applyToActivePixelLayer {
                                                    com.vayunmathur.photos.data.drawShapeBitmap(it, com.vayunmathur.photos.data.PaintShape.Ellipse, minOf(x0, x1), minOf(y0, y1), maxOf(x0, x1), maxOf(y0, y1), argb, shapeStrokeWidth)
                                                }
                                                EditorMode.ShapeLine -> vm.applyToActivePixelLayer {
                                                    com.vayunmathur.photos.data.drawShapeBitmap(it, com.vayunmathur.photos.data.PaintShape.Line, x0, y0, x1, y1, argb, shapeStrokeWidth)
                                                }
                                                else -> {}
                                            }
                                        }
                                        paintDragStart = null; paintDragCurrent = null
                                    },
                                )
                            }
                            if (editorMode == EditorMode.Eyedropper) {
                                Box(modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                                    detectTapGestures { o ->
                                        val bmp = preview ?: baseBitmap
                                        if (bmp != null) {
                                            val sx = ((o.x / size.width) * bmp.width).toInt().coerceIn(0, bmp.width - 1)
                                            val sy = ((o.y / size.height) * bmp.height).toInt().coerceIn(0, bmp.height - 1)
                                            useColor(Color(bmp.getPixel(sx, sy)))
                                        }
                                    }
                                })
                            }
                        }

                        if (isCropping) {
                            CropOverlay(
                                cx = cropCx, cy = cropCy, hx = cropHx, hy = cropHy, angleDeg = cropAngle,
                                onChange = { ncx, ncy, nhx, nhy ->
                                    // Reject the drag in real time if it would pull any crop corner
                                    // off the photo, so the box snaps back instead of going outside.
                                    val w = document.canvasWidth.coerceAtLeast(1).toFloat()
                                    val h = document.canvasHeight.coerceAtLeast(1).toFloat()
                                    if (cropWithinImage(ncx, ncy, nhx, nhy, cropAngle, w, h)) {
                                        cropCx = ncx; cropCy = ncy; cropHx = nhx; cropHy = nhy; cropAspect = null
                                    }
                                },
                                onAngle = { newAngle ->
                                    // Only accept the rotation if the crop still fits inside the
                                    // photo at that angle; otherwise ignore it (user must shrink first).
                                    val w = document.canvasWidth.coerceAtLeast(1).toFloat()
                                    val h = document.canvasHeight.coerceAtLeast(1).toFloat()
                                    if (cropWithinImage(cropCx, cropCy, cropHx, cropHy, newAngle, w, h)) {
                                        cropAngle = newAngle
                                    }
                                },
                            )
                        }
                        if (editorMode == EditorMode.FreeTransform) {
                            val vpW = currentViewportWidth
                            val vpH = currentViewportHeight
                            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                                val pts = listOf(ftTL, ftTR, ftBR, ftBL).map { Offset(it.x * vpW, it.y * vpH) }
                                val path = Path().apply {
                                    moveTo(pts[0].x, pts[0].y)
                                    pts.drop(1).forEach { lineTo(it.x, it.y) }
                                    close()
                                }
                                drawPath(path, Color.White, style = Stroke(width = 2f))
                            }
                            Handle(Offset(ftTL.x * vpW, ftTL.y * vpH)) { d -> ftTL += Offset(d.x / vpW, d.y / vpH) }
                            Handle(Offset(ftTR.x * vpW, ftTR.y * vpH)) { d -> ftTR += Offset(d.x / vpW, d.y / vpH) }
                            Handle(Offset(ftBL.x * vpW, ftBL.y * vpH)) { d -> ftBL += Offset(d.x / vpW, d.y / vpH) }
                            Handle(Offset(ftBR.x * vpW, ftBR.y * vpH)) { d -> ftBR += Offset(d.x / vpW, d.y / vpH) }
                        }
                    }
                }
            }

            // Commit healing stroke
            LaunchedEffect(currentHealingPoints.size) {
                if (currentHealingPoints.isNotEmpty() && editorMode == EditorMode.Healing) {
                    kotlinx.coroutines.delay(300)
                    val sx = healingSourceX; val sy = healingSourceY
                    if (sx != null && sy != null && currentHealingPoints.isNotEmpty()) {
                        val stroke = HealingStroke(sx, sy, currentHealingPoints, healingBrushSize, HealMode.Heal)
                        vm.applyToActivePixelLayer { HealingStrokes(listOf(stroke)).applyHealingToBitmap(it) }
                        currentHealingPoints = emptyList()
                    }
                }
            }

            // Commit dodge/burn/smudge stroke
            LaunchedEffect(retouchPoints.size) {
                if (retouchPoints.isNotEmpty() && (editorMode == EditorMode.DodgeBurn || editorMode == EditorMode.Smudge)) {
                    kotlinx.coroutines.delay(300)
                    val pts = retouchPoints
                    if (pts.isNotEmpty()) {
                        when (editorMode) {
                            EditorMode.DodgeBurn -> {
                                val s = DodgeBurnStroke(pts, dodgeBurnMode, exposure = 0.5f, brushSize = brushSize)
                                vm.applyToActivePixelLayer { DodgeBurnStrokes(listOf(s)).applyToBitmap(it) }
                            }
                            EditorMode.Smudge -> {
                                val s = SmudgeStroke(pts, strength = 0.5f, brushSize = brushSize)
                                vm.applyToActivePixelLayer { SmudgeStrokes(listOf(s)).applyToBitmap(it) }
                            }
                            else -> {}
                        }
                        retouchPoints = emptyList()
                    }
                }
            }

            // Bottom controls: Home (category bar) or a tool screen. Placed below the image in a
            // Column so opening a panel shrinks the image instead of covering it. The Surface fills
            // to the screen bottom (covering the navigation-bar area) while its content is inset above
            // the nav bar via navigationBarsPadding, so there is no gap and no tools under the bar.
            val cat = activeCategory
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Box(modifier = Modifier.navigationBarsPadding()) {
                when (cat) {
                null -> CategoryBar(onSelect = { openCategory(it) })
                ToolCategory.Draw -> {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = { goHome() }) { IconBack() }
                            Text("Draw", fontWeight = FontWeight.Bold)
                            InfoHint("Draw freehand, highlight, erase, or tap to add text. Tap a tool again to change its color and size.")
                            Spacer(Modifier.weight(1f))
                            if (selectedTextId != null) {
                                IconButton(onClick = {
                                    texts.find { it.id == selectedTextId }?.let { te ->
                                        backStack.add(EditRoute.DrawingSettings(DrawingTool.Text, te.color, te.fontSize, 1f))
                                    }
                                }) { IconEdit() }
                            }
                        }
                        DrawingToolbar(
                            activeTool = activeTool,
                            onSelectPointer = { activeTool = DrawingTool.Pointer; selectedTextId = null; selectedStrokeIndex = null; selectedTextIndex = null },
                            onSelectPen = {
                                if (activeTool == DrawingTool.Pen) backStack.add(EditRoute.DrawingSettings(DrawingTool.Pen, penColor.toArgb(), penSize, 1f))
                                else { activeTool = DrawingTool.Pen; selectedTextId = null; selectedStrokeIndex = null; selectedTextIndex = null }
                            },
                            onSelectHighlighter = {
                                if (activeTool == DrawingTool.Highlighter) backStack.add(EditRoute.DrawingSettings(DrawingTool.Highlighter, highlighterColor.toArgb(), highlighterSize, highlighterOpacity))
                                else { activeTool = DrawingTool.Highlighter; selectedTextId = null; selectedStrokeIndex = null; selectedTextIndex = null }
                            },
                            onSelectEraser = { activeTool = DrawingTool.Eraser; selectedTextId = null; selectedStrokeIndex = null; selectedTextIndex = null },
                            onSelectText = {
                                if (selectedTextId != null || activeTool == DrawingTool.Text) {
                                    if (selectedTextId != null) {
                                        texts.find { it.id == selectedTextId }?.let { te ->
                                            backStack.add(EditRoute.DrawingSettings(DrawingTool.Text, te.color, te.fontSize, 1f))
                                        }
                                    } else backStack.add(EditRoute.DrawingSettings(DrawingTool.Text, penColor.toArgb(), textFontSize, 1f))
                                } else { activeTool = DrawingTool.Text; selectedTextId = null; selectedStrokeIndex = null; selectedTextIndex = null }
                            },
                        )
                    }
                }
                else -> ToolScreen(
                    category = cat,
                    editorMode = editorMode,
                    onToolSelected = { selectTool(it) },
                    onBack = { goHome() },
                ) {
                    if (editorMode == EditorMode.Crop) {
                        CropRotatePanel(
                            onRotate90 = {
                                // Quarter-turn: keep the crop center, swap the box's
                                // pixel dimensions, and rotate 90°. Swapping extents +
                                // the 90° turn preserves the selected footprint (and
                                // two turns return exactly to the original box). Only apply
                                // it if the result still fits inside the photo.
                                val w = document.canvasWidth.coerceAtLeast(1).toFloat()
                                val h = document.canvasHeight.coerceAtLeast(1).toFloat()
                                val newHx = cropHy * h / w
                                val newHy = cropHx * w / h
                                val newAngle = (cropAngle + 90f) % 360f
                                if (cropWithinImage(cropCx, cropCy, newHx, newHy, newAngle, w, h)) {
                                    cropHx = newHx
                                    cropHy = newHy
                                    cropAngle = newAngle
                                    cropAspect = null
                                }
                            },
                            selectedAspect = cropAspect,
                            onAspect = { ar ->
                                cropAspect = ar
                                if (ar == null) {
                                    cropCx = 0.5f; cropCy = 0.5f; cropHx = 0.5f; cropHy = 0.5f
                                } else {
                                    val w = document.canvasWidth.coerceAtLeast(1).toFloat()
                                    val h = document.canvasHeight.coerceAtLeast(1).toFloat()
                                    val ratioN = ar * h / w
                                    cropCx = 0.5f; cropCy = 0.5f
                                    if (ratioN >= 1f) { cropHx = 0.5f; cropHy = 0.5f / ratioN } else { cropHy = 0.5f; cropHx = 0.5f * ratioN }
                                }
                            },
                            onReset = {
                                cropCx = 0.5f; cropCy = 0.5f; cropHx = 0.5f; cropHy = 0.5f
                                cropAngle = 0f; cropAspect = null
                            },
                            onApply = { applyCrop() },
                            onCancel = { goHome() },
                        )
                    } else if (editorMode == EditorMode.FreeTransform) {
                        FreeTransformPanel(
                            onApply = {
                                vm.transformActiveLayer(
                                    com.vayunmathur.photos.data.PerspectiveCorners(
                                        topLeft = ftTL.x to ftTL.y,
                                        topRight = ftTR.x to ftTR.y,
                                        bottomLeft = ftBL.x to ftBL.y,
                                        bottomRight = ftBR.x to ftBR.y,
                                    )
                                )
                                ftTL = Offset(0f, 0f); ftTR = Offset(1f, 0f); ftBL = Offset(0f, 1f); ftBR = Offset(1f, 1f)
                            },
                            onReset = { ftTL = Offset(0f, 0f); ftTR = Offset(1f, 0f); ftBL = Offset(0f, 1f); ftBR = Offset(1f, 1f) },
                            onFlipH = { vm.flipActiveLayer(true) },
                            onFlipV = { vm.flipActiveLayer(false) },
                            onDone = { goHome() },
                        )
                    } else {
                    ActivePanel(
                        editorMode = editorMode,
                        document = document,
                        baseBitmap = baseBitmap,
                        selection = selection,
                        vm = vm,
                        selectedAdjustment = selectedAdjustment,
                        onSelectAdjustment = { selectedAdjustment = it },
                        selectedCurveChannel = selectedCurveChannel,
                        onCurveChannel = { selectedCurveChannel = it },
                        selectedHslRange = selectedHslRange,
                        onHslRange = { selectedHslRange = it },
                        currentSelectiveMask = currentSelectiveMask,
                        showSelectiveMask = showSelectiveMask,
                        onSelectiveMask = { currentSelectiveMask = it },
                        onShowSelectiveMask = { showSelectiveMask = it },
                        healingBrushSize = healingBrushSize,
                        isSettingHealingSource = isSettingHealingSource,
                        onHealingBrushSize = { healingBrushSize = it },
                        onSetHealingSource = { isSettingHealingSource = it },
                        dodgeBurnMode = dodgeBurnMode,
                        onDodgeBurnMode = { dodgeBurnMode = it },
                        brushSize = brushSize,
                        onBrushSize = { brushSize = it },
                        selectionTool = selectionTool,
                        onSelectionTool = { selectionTool = it },
                        selectionCombine = selectionCombine,
                        onSelectionCombine = { selectionCombine = it },
                        selectionFeather = selectionFeather,
                        onSelectionFeather = { selectionFeather = it },
                        onSelectionFeatherCommit = { reapplyFeather() },
                        wandTolerance = wandTolerance,
                        onWandTolerance = { wandTolerance = it },
                        polygonPointCount = polygonPoints.size,
                        onClosePolygon = {
                            val norm = polygonPoints.map {
                                (it.x / currentViewportWidth).coerceIn(0f, 1f) to (it.y / currentViewportHeight).coerceIn(0f, 1f)
                            }
                            polygonPoints = emptyList()
                            commitPolygon(norm)
                        },
                        liquifyTool = liquifyTool,
                        onLiquifyTool = { liquifyTool = it },
                        liquifyStrength = liquifyStrength,
                        onLiquifyStrength = { liquifyStrength = it },
                        liquifyRadius = liquifyRadius,
                        onLiquifyRadius = { liquifyRadius = it },
                        onSelectionInvert = {
                            selectionBase = selectionBase?.invert()
                            reapplyFeather()
                        },
                        onSelectionClear = { clearSelection() },
                        onSelectionDelete = {
                            vm.applyToActivePixelLayer { src ->
                                android.graphics.Bitmap.createBitmap(src.width, src.height, android.graphics.Bitmap.Config.ARGB_8888)
                            }
                        },
                        maskPaintReveal = maskPaintReveal,
                        onMaskPaintReveal = { maskPaintReveal = it },
                        maskBrushSize = maskBrushSize,
                        onMaskBrushSize = { maskBrushSize = it },
                        paintColor = paintColor,
                        onPaintColor = { useColor(it) },
                        recentColors = recentColors,
                        fillTolerance = fillTolerance,
                        onFillTolerance = { fillTolerance = it },
                        shapeStrokeWidth = shapeStrokeWidth,
                        onShapeStrokeWidth = { shapeStrokeWidth = it },
                        onEditTextLayer = { idx ->
                            (document.layers.getOrNull(idx) as? TextLayer)?.let { tl ->
                                texts.add(tl.textElement)
                                vm.removeLayer(idx)
                                textToEdit = tl.textElement
                                selectedTextId = tl.textElement.id
                                openCategory(ToolCategory.Draw)
                            }
                        },
                        onResumeDrawingLayer = { idx ->
                            (document.layers.getOrNull(idx) as? DrawingLayer)?.let { dl ->
                                dl.strokes.forEach { inkStrokes.add(it.deserialize()) }
                                vm.removeLayer(idx)
                                openCategory(ToolCategory.Draw)
                            }
                        },
                        onSelectSubject = {
                            baseBitmap?.let { bmp ->
                                com.vayunmathur.photos.util.segmentSubject(context, bmp) { sel ->
                                    if (sel != null) { selectionBase = sel; reapplyFeather() }
                                }
                            }
                        },
                    )
                    }
                }
                }
                }
            }
        }
    }

    textToEdit?.let { textElement ->
        Dialog(onDismissRequest = { textToEdit = null }) {
            Surface(shape = RoundedCornerShape(8.dp)) {
                Column(modifier = Modifier.padding(16.dp).widthIn(max = 340.dp)) {
                    fun updateText(transform: (TextElement) -> TextElement) {
                        val index = texts.indexOfFirst { it.id == textElement.id }
                        if (index != -1) texts[index] = transform(texts[index])
                    }
                    var localText by remember { mutableStateOf(textElement.text) }
                    val current = texts.firstOrNull { it.id == textElement.id } ?: textElement
                    TextField(
                        value = localText,
                        onValueChange = { newText ->
                            localText = newText
                            updateText { it.copy(text = newText) }
                        },
                        textStyle = TextStyle(fontSize = 18.sp),
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Edit Text") },
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Font", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        com.vayunmathur.photos.data.textFontFamilies.forEach { fam ->
                            SelectableChip(fam, current.fontFamily == fam, { updateText { it.copy(fontFamily = fam) } })
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        SelectableChip("Bold", current.bold, { updateText { it.copy(bold = !it.bold) } })
                        SelectableChip("Italic", current.italic, { updateText { it.copy(italic = !it.italic) } })
                        SelectableChip("Left", current.align == 0, { updateText { it.copy(align = 0) } })
                        SelectableChip("Center", current.align == 1, { updateText { it.copy(align = 1) } })
                        SelectableChip("Right", current.align == 2, { updateText { it.copy(align = 2) } })
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        IconButton(onClick = { textToEdit = null }) { IconCheck() }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryBar(onSelect: (ToolCategory) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ToolCategory.entries.forEach { cat ->
            Column(
                modifier = Modifier.clickable { onSelect(cat) }.padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                CategoryIcon(cat)
                Text(cat.label, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun CategoryIcon(category: ToolCategory) {
    when (category) {
        ToolCategory.Adjust -> IconSettings()
        ToolCategory.Filters -> IconStar()
        ToolCategory.Retouch -> IconBrush()
        ToolCategory.Select -> Icon(Icons.Outlined.HighlightAlt, contentDescription = null)
        ToolCategory.Transform -> IconCrop()
        ToolCategory.Draw -> IconDraw()
        ToolCategory.Paint -> IconEdit()
        ToolCategory.Layers -> IconCopy()
    }
}

@Composable
private fun ToolScreen(
    category: ToolCategory,
    editorMode: EditorMode,
    onToolSelected: (EditorMode) -> Unit,
    onBack: () -> Unit,
    panel: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { IconBack() }
            Text(category.label, fontWeight = FontWeight.Bold)
            InfoHint(category.description())
        }
        if (categoryTools[category].orEmpty().size > 1) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                categoryTools[category].orEmpty().forEach { entry ->
                    Surface(
                        modifier = Modifier.clickable { onToolSelected(entry.mode) },
                        shape = RoundedCornerShape(8.dp),
                        color = if (editorMode == entry.mode) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Text(entry.label, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                    }
                }
            }
        }
        val toolLabel = categoryTools[category]?.firstOrNull { it.mode == editorMode }?.label
        if (toolLabel != null && editorMode != EditorMode.None) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(toolLabel, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                InfoHint(editorMode.description())
            }
        }
        panel()
    }
}

/** Positions a popup directly above its anchor (a "drop-up"). */
private class AbovePopupPositionProvider(private val gapPx: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val x = anchorBounds.left.coerceIn(0, maxX)
        val y = (anchorBounds.top - popupContentSize.height - gapPx).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}

/** A small ⓘ icon that toggles an inline drop-up description (not a dialog). */
@Composable
private fun InfoHint(text: String) {
    if (text.isBlank()) return
    var open by remember { mutableStateOf(false) }
    val gapPx = with(LocalDensity.current) { 4.dp.roundToPx() }
    Box {
        Text(
            "ⓘ",
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp).clickable { open = !open },
        )
        if (open) {
            Popup(
                popupPositionProvider = AbovePopupPositionProvider(gapPx),
                onDismissRequest = { open = false },
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.inverseSurface,
                    shadowElevation = 6.dp,
                ) {
                    Text(
                        text,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.widthIn(max = 240.dp).padding(10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CropRotatePanel(
    onRotate90: () -> Unit,
    selectedAspect: Float?,
    onAspect: (Float?) -> Unit,
    onReset: () -> Unit,
    onApply: () -> Unit,
    onCancel: () -> Unit,
) {
    val aspects: List<Pair<String, Float?>> = listOf(
        "Free" to null, "1:1" to 1f, "4:3" to 4f / 3f, "3:4" to 3f / 4f, "16:9" to 16f / 9f, "9:16" to 9f / 16f,
    )
    PanelContainer(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Aspect ratio", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            InfoHint("Locks the crop to a fixed width:height shape. \"Free\" lets you crop to any size.")
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            aspects.forEach { (label, ar) ->
                SelectableChip(label, selectedAspect == ar, { onAspect(ar) })
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Rotate", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            InfoHint("Drag the round handle above the crop box to tilt and straighten the photo.")
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.clickable { onRotate90() },
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) { IconRotateRight(); Text("Rotate 90°", fontSize = 13.sp) }
            }
            Surface(
                modifier = Modifier.clickable { onReset() },
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface,
            ) { Text("Reset", fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Surface(
                modifier = Modifier.clickable { onCancel() },
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) { IconClose(); Text("Cancel", fontSize = 13.sp) }
            }
            Surface(
                modifier = Modifier.clickable { onApply() },
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) { IconCheck(); Text("Apply", fontSize = 13.sp) }
            }
        }
    }
}

@Composable
private fun ActivePanel(
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
                onUpdateAdjustment = { update -> vm.updateActiveAdjustment(BasicAdjustment(update(basic))) },
                onReset = { vm.updateActiveAdjustment(BasicAdjustment(ImageAdjustments())) },
            )
        }
        EditorMode.Filters -> {
            val basic = document.activeAdjustment<BasicAdjustment>()?.adjustments ?: ImageAdjustments()
            FilterPresetPanel(
                bitmap = baseBitmap,
                adjustments = basic,
                onSelectFilter = { filter -> vm.updateActiveAdjustment(BasicAdjustment(filter.adjustments)) },
            )
        }
        EditorMode.Curves -> {
            val curves = document.activeAdjustment<CurvesAdj>()?.curves ?: CurvesAdjustment()
            CurvesPanel(curves, selectedCurveChannel, onCurveChannel) { vm.updateActiveAdjustment(CurvesAdj(it)) }
        }
        EditorMode.HSL -> {
            val hsl = document.activeAdjustment<HslAdj>()?.hsl ?: HslAdjustments()
            HslPanel(hsl, selectedHslRange, onHslRange) { vm.updateActiveAdjustment(HslAdj(it)) }
        }
        EditorMode.Levels -> {
            val levels = document.activeAdjustment<LevelsAdj>()?.levels ?: com.vayunmathur.photos.data.LevelsAdjustment()
            LevelsPanel(levels) { vm.updateActiveAdjustment(LevelsAdj(it)) }
        }
        EditorMode.ColorBalance -> {
            val cb = document.activeAdjustment<ColorBalanceAdj>()?.balance ?: com.vayunmathur.photos.data.ColorBalanceAdjustment()
            ColorBalancePanel(cb) { vm.updateActiveAdjustment(ColorBalanceAdj(it)) }
        }
        EditorMode.ChannelMixer -> {
            val mx = document.activeAdjustment<ChannelMixerAdj>()?.mixer ?: com.vayunmathur.photos.data.ChannelMixerAdjustment()
            ChannelMixerPanel(mx) { vm.updateActiveAdjustment(ChannelMixerAdj(it)) }
        }
        EditorMode.BlackWhite -> {
            val bw = document.activeAdjustment<BlackAndWhiteAdj>()?.bw ?: com.vayunmathur.photos.data.BlackAndWhiteAdjustment(enabled = true)
            BlackWhitePanel(bw) { vm.updateActiveAdjustment(BlackAndWhiteAdj(it.copy(enabled = true))) }
        }
        EditorMode.GradientMap -> {
            GradientMapPanel { stops -> vm.updateActiveAdjustment(GradientMapAdj(com.vayunmathur.photos.data.GradientMapAdjustment(stops))) }
        }
        EditorMode.Vibrance -> {
            val v = document.activeAdjustment<VibranceAdj>()?.amount ?: 0f
            VibrancePanel(v) { vm.updateActiveAdjustment(VibranceAdj(it)) }
        }
        EditorMode.PhotoFilter -> {
            val pf = document.activeAdjustment<PhotoFilterAdj>() ?: PhotoFilterAdj()
            PhotoFilterPanel(pf) { vm.updateActiveAdjustment(it) }
        }
        EditorMode.SelectiveColor -> {
            val sc = document.activeAdjustment<SelectiveColorAdj>() ?: SelectiveColorAdj()
            SelectiveColorPanel(sc) { vm.updateActiveAdjustment(it) }
        }
        EditorMode.Posterize -> {
            val p = document.activeAdjustment<PosterizeAdj>()?.levels ?: 4
            PosterizePanel(p) { vm.updateActiveAdjustment(PosterizeAdj(it)) }
        }
        EditorMode.Threshold -> {
            val t = document.activeAdjustment<ThresholdAdj>()?.level ?: 128
            ThresholdPanel(t) { vm.updateActiveAdjustment(ThresholdAdj(it)) }
        }
        EditorMode.Invert -> InvertPanel()
        EditorMode.LensBlur -> {
            val blur = document.activeAdjustment<BlurAdj>()?.blur ?: BlurParams()
            BlurPanel(blur) { vm.updateActiveAdjustment(BlurAdj(it)) }
        }
        EditorMode.Selective -> {
            val sel = document.activeAdjustment<SelectiveAdj>()?.selective ?: SelectiveEdits()
            SelectiveEditPanel(
                mask = currentSelectiveMask,
                showMask = showSelectiveMask,
                onMaskChanged = onSelectiveMask,
                onShowMaskChanged = onShowSelectiveMask,
                onAddMask = {
                    vm.updateActiveAdjustment(SelectiveAdj(sel.copy(masks = sel.masks + currentSelectiveMask)))
                    onSelectiveMask(SelectiveMask())
                },
            )
        }
        EditorMode.FilterFx -> FiltersPanel { adj -> vm.addAdjustmentLayer(adj) }
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
            onContentAwareFill = { vm.contentAwareFillSelection() },
            onSelectSubject = onSelectSubject,
        )
        EditorMode.Layers -> LayersPanel(
            document = document,
            hasSelection = selection != null,
            onSelectLayer = { vm.setActiveLayer(it) },
            onToggleVisibility = { i, v -> vm.setLayerVisibility(i, v) },
            onOpacityChange = { i, o -> vm.setLayerOpacity(i, o) },
            onBlendModeChange = { i, m -> vm.setLayerBlendMode(i, m) },
            onAddAdjustment = { vm.addAdjustmentLayer(it) },
            onAddPixelLayer = { vm.addEmptyPixelLayer() },
            onDuplicate = { vm.duplicateLayer(it) },
            onMergeDown = { vm.mergeDown(it) },
            onDelete = { vm.removeLayer(it) },
            onFlatten = { vm.flatten() },
            onAddMaskFromSelection = { vm.selectionToActiveMask() },
            onDeleteMask = { vm.deleteLayerMask(it) },
            onInvertMask = { vm.invertLayerMask(it) },
            onToggleClip = { i, c -> vm.setLayerClipped(i, c) },
            onSetStyle = { i, s -> vm.setLayerStyle(i, s) },
            onGroupActive = { vm.groupActiveWithBelow() },
            onUngroup = { vm.ungroupActive() },
            onUpdateGroup = { vm.updateGroup(it) },
            onMoveLayer = { from, to -> vm.moveLayer(from, to) },
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

@Composable
private fun LiquifyPanel(
    tool: com.vayunmathur.photos.data.LiquifyTool,
    onTool: (com.vayunmathur.photos.data.LiquifyTool) -> Unit,
    strength: Float,
    onStrength: (Float) -> Unit,
    radius: Float,
    onRadius: (Float) -> Unit,
) {
    PanelContainer(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            com.vayunmathur.photos.data.LiquifyTool.entries.forEach { t ->
                SelectableChip(t.name, tool == t, { onTool(t) }, horizontalPadding = 10.dp)
            }
        }
        LabeledSlider("Strength", strength * 100f, 5f..100f) { onStrength(it / 100f) }
        LabeledSlider("Size", radius * 100f, 5f..50f) { onRadius(it / 100f) }
    }
}

@Composable
private fun PaintPanel(
    mode: EditorMode,
    color: Color,
    onColor: (Color) -> Unit,
    recentColors: List<Color>,
    fillTolerance: Float,
    onFillTolerance: (Float) -> Unit,
    strokeWidth: Float,
    onStrokeWidth: (Float) -> Unit,
) {
    val swatches = listOf(
        Color.Red, Color(0xFFFF9500), Color.Yellow, Color(0xFF34C759),
        Color(0xFF00B4EC), Color.Blue, Color(0xFF7B2FBE), Color.Magenta,
        Color.White, Color.Black,
    )
    PanelContainer(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (mode == EditorMode.Eyedropper) {
            Text("Tap the image to pick a color.", fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Current color
            Box(modifier = Modifier.size(28.dp).background(color, CircleShape).border(2.dp, MaterialTheme.colorScheme.primary, CircleShape))
            swatches.forEach { sw ->
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(sw, CircleShape)
                        .border(if (sw == color) 3.dp else 1.dp, if (sw == color) MaterialTheme.colorScheme.primary else Color.Gray, CircleShape)
                        .clickable { onColor(sw) },
                )
            }
        }
        if (recentColors.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Recent", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                recentColors.forEach { rc ->
                    Box(
                        modifier = Modifier.size(24.dp).background(rc, CircleShape)
                            .border(1.dp, Color.Gray, CircleShape).clickable { onColor(rc) },
                    )
                }
            }
        }
        if (mode == EditorMode.Fill) {
            LabeledSlider("Tolerance", fillTolerance * 100f, 1f..80f) { onFillTolerance(it / 100f) }
        }
        if (mode == EditorMode.ShapeLine) {
            LabeledSlider("Thickness", strokeWidth * 100f, 0.2f..5f) { onStrokeWidth(it / 100f) }
        }
    }
}

@Composable
private fun PaintDragOverlay(
    mode: EditorMode,
    color: Color,
    start: Offset?,
    current: Offset?,
    onStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onEnd: () -> Unit,
) {
    androidx.compose.foundation.Canvas(
        modifier = Modifier.fillMaxSize().pointerInput(mode) {
            detectDragGestures(
                onDragStart = { onStart(it) },
                onDrag = { change, _ -> change.consume(); onDrag(change.position) },
                onDragEnd = { onEnd() },
            )
        },
    ) {
        val s = start; val c = current
        if (s != null && c != null) {
            when (mode) {
                EditorMode.ShapeLine, EditorMode.GradientTool ->
                    drawLine(color, s, c, strokeWidth = 3f)
                EditorMode.ShapeRect -> drawRect(
                    color,
                    Offset(minOf(s.x, c.x), minOf(s.y, c.y)),
                    Size(kotlin.math.abs(c.x - s.x), kotlin.math.abs(c.y - s.y)),
                    style = Stroke(width = 2f),
                )
                EditorMode.ShapeEllipse -> drawOval(
                    color,
                    Offset(minOf(s.x, c.x), minOf(s.y, c.y)),
                    Size(kotlin.math.abs(c.x - s.x), kotlin.math.abs(c.y - s.y)),
                    style = Stroke(width = 2f),
                )
                else -> {}
            }
        }
    }
}

@Composable
private fun FreeTransformPanel(
    onApply: () -> Unit,
    onReset: () -> Unit,
    onFlipH: () -> Unit,
    onFlipV: () -> Unit,
    onDone: () -> Unit,
) {
    PanelContainer(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Drag corners to distort.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            InfoHint("Drag the four corner handles to scale, rotate, skew, or distort the active layer. Apply bakes it in.")
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallButton("Flip H", true, onFlipH)
            SmallButton("Flip V", true, onFlipV)
            SmallButton("Reset", true, onReset)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Surface(
                modifier = Modifier.clickable { onDone() },
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface,
            ) { Text("Done", fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            Surface(
                modifier = Modifier.clickable { onApply() },
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) { Text("Apply", fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
        }
    }
}

@Composable
private fun MaskPaintPanel(
    reveal: Boolean,
    onReveal: (Boolean) -> Unit,
    brushSize: Float,
    onBrushSize: (Float) -> Unit,
) {
    PanelContainer(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectableChip("Hide", !reveal, { onReveal(false) })
            SelectableChip("Reveal", reveal, { onReveal(true) })
            InfoHint("Paint on the active layer's mask. Hide erases (black), Reveal restores (white). Adds a full mask if the layer has none.")
        }
        LabeledSlider("Brush", brushSize * 100f, 1f..30f) { onBrushSize(it / 100f) }
    }
}

@Composable
private fun SimpleBrushPanel(label: String, brushSize: Float, onBrushSize: (Float) -> Unit) {
    PanelContainer {
        LabeledSlider(label, brushSize * 100f, 1f..20f) { onBrushSize(it / 100f) }
    }
}

@Composable
private fun DodgeBurnPanel(mode: DodgeBurnMode, onMode: (DodgeBurnMode) -> Unit, brushSize: Float, onBrushSize: (Float) -> Unit) {
    PanelContainer {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DodgeBurnMode.entries.forEach { m ->
                SelectableChip(m.name, mode == m, { onMode(m) })
            }
        }
        LabeledSlider("Brush", brushSize * 100f, 1f..20f) { onBrushSize(it / 100f) }
    }
}

@Composable
private fun SelectionPanel(
    tool: SelectionTool,
    onTool: (SelectionTool) -> Unit,
    combine: SelectionCombine,
    onCombine: (SelectionCombine) -> Unit,
    feather: Float,
    onFeather: (Float) -> Unit,
    onFeatherCommit: () -> Unit,
    wandTolerance: Float,
    onWandTolerance: (Float) -> Unit,
    polygonPointCount: Int,
    onClosePolygon: () -> Unit,
    hasSelection: Boolean,
    onInvert: () -> Unit,
    onClear: () -> Unit,
    onDelete: () -> Unit,
    onContentAwareFill: () -> Unit,
    onSelectSubject: () -> Unit,
) {
    PanelContainer(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectionTool.entries.forEach { t ->
                SelectableChip(t.label, tool == t, { onTool(t) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Combine", fontSize = 12.sp, modifier = Modifier.width(72.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            InfoHint("New replaces the selection. Add/Subtract/Intersect combine the next shape with the current selection.")
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SelectionCombine.entries.forEach { c ->
                    SelectableChip(c.name, combine == c, { onCombine(c) })
                }
            }
        }
        if (tool == SelectionTool.Wand) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Tolerance", fontSize = 12.sp, modifier = Modifier.width(92.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                InfoHint("How close in color a pixel must be to the tapped spot to be selected. Higher = selects more.")
                androidx.compose.material3.Slider(value = wandTolerance, onValueChange = onWandTolerance, valueRange = 0.01f..0.6f, modifier = Modifier.weight(1f))
                Text("${(wandTolerance * 100).roundToInt()}", fontSize = 12.sp, modifier = Modifier.width(36.dp), textAlign = TextAlign.End, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (tool == SelectionTool.Polygon) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Tap to add points ($polygonPointCount)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SmallButton("Close path", polygonPointCount >= 3, onClosePolygon)
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Edge softness", fontSize = 12.sp, modifier = Modifier.width(92.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            InfoHint("Blurs the selection's edge so edits fade in gradually. 0 = a hard, crisp edge.")
            androidx.compose.material3.Slider(value = feather, onValueChange = onFeather, onValueChangeFinished = onFeatherCommit, valueRange = 0f..50f, modifier = Modifier.weight(1f))
            Text("${feather.roundToInt()}", fontSize = 12.sp, modifier = Modifier.width(36.dp), textAlign = TextAlign.End, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SmallButton("Invert", hasSelection, onInvert)
            SmallButton("Delete area", hasSelection, onDelete)
            SmallButton("Clear", hasSelection, onClear)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SmallButton("Select subject", true, onSelectSubject)
            InfoHint("Auto-selects the main foreground subject on-device (no cloud). Works best when the subject stands out from its background; refine with Add/Subtract or the wand afterward.")
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SmallButton("Remove (content-aware)", hasSelection, onContentAwareFill)
            InfoHint("Fills the selected area from surrounding pixels to remove an object. Works best on simple backgrounds.")
        }
    }
}

@Composable
private fun SmallButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable(enabled = enabled) { onClick() },
        shape = RoundedCornerShape(6.dp),
        color = if (enabled) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    ) { Text(label, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) }
}

@Composable
private fun SelectionOverlay(
    isEllipse: Boolean,
    dragStart: Offset?,
    dragCurrent: Offset?,
    onStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onEnd: () -> Unit,
) {
    androidx.compose.foundation.Canvas(
        modifier = Modifier.fillMaxSize().pointerInput(isEllipse) {
            detectDragGestures(
                onDragStart = { onStart(it) },
                onDrag = { change, _ -> change.consume(); onDrag(change.position) },
                onDragEnd = { onEnd() },
            )
        },
    ) {
        val s = dragStart; val c = dragCurrent
        if (s != null && c != null) {
            val topLeft = Offset(minOf(s.x, c.x), minOf(s.y, c.y))
            val sz = Size(kotlin.math.abs(c.x - s.x), kotlin.math.abs(c.y - s.y))
            val effect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
            if (isEllipse) drawOval(Color.White, topLeft, sz, style = Stroke(width = 2f, pathEffect = effect))
            else drawRect(Color.White, topLeft, sz, style = Stroke(width = 2f, pathEffect = effect))
        }
    }
}

@Composable
private fun LassoOverlay(
    points: List<Offset>,
    onStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onEnd: () -> Unit,
) {
    androidx.compose.foundation.Canvas(
        modifier = Modifier.fillMaxSize().pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { onStart(it) },
                onDrag = { change, _ -> change.consume(); onDrag(change.position) },
                onDragEnd = { onEnd() },
            )
        },
    ) {
        if (points.size > 1) {
            val path = Path().apply {
                moveTo(points.first().x, points.first().y)
                points.drop(1).forEach { lineTo(it.x, it.y) }
            }
            drawPath(path, Color.White, style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
        }
    }
}

@Composable
private fun PolygonOverlay(
    points: List<Offset>,
    onTap: (Offset) -> Unit,
) {
    androidx.compose.foundation.Canvas(
        modifier = Modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures { onTap(it) }
        },
    ) {
        if (points.isNotEmpty()) {
            val path = Path().apply {
                moveTo(points.first().x, points.first().y)
                points.drop(1).forEach { lineTo(it.x, it.y) }
            }
            drawPath(path, Color.White, style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
            points.forEach { drawCircle(Color.White, radius = 5f, center = it) }
        }
    }
}

/**
 * Persistent overlay showing the current selection as a translucent tint (built
 * once from the mask). Works for every selection shape, including feathered and
 * non-rectangular ones.
 */
@Composable
private fun SelectionMaskOverlay(selection: Selection) {
    val image = remember(selection) {
        val w = selection.width
        val h = selection.height
        val px = IntArray(w * h)
        for (i in px.indices) {
            val a = (selection.mask[i] * 100f).toInt().coerceIn(0, 255)
            // Translucent cyan tint where selected.
            px[i] = (a shl 24) or 0x33B5E5
        }
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        bmp.asImageBitmap()
    }
    Image(
        bitmap = image,
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
        contentScale = androidx.compose.ui.layout.ContentScale.FillBounds,
    )
}

@Composable
private fun DrawingToolbar(
    activeTool: DrawingTool,
    onSelectPointer: () -> Unit,
    onSelectPen: () -> Unit,
    onSelectHighlighter: () -> Unit,
    onSelectEraser: () -> Unit,
    onSelectText: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolIcon(active = activeTool == DrawingTool.Pointer, onClick = onSelectPointer) { IconVisible() }
        ToolIcon(active = activeTool == DrawingTool.Pen, onClick = onSelectPen) { IconDraw() }
        ToolIcon(active = activeTool == DrawingTool.Highlighter, onClick = onSelectHighlighter) { IconBrush() }
        ToolIcon(active = activeTool == DrawingTool.Eraser, onClick = onSelectEraser) { IconEraser() }
        ToolIcon(active = activeTool == DrawingTool.Text, onClick = onSelectText) {
            Text("T", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ToolIcon(active: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    IconButton(onClick = onClick) {
        Box(
            modifier = Modifier.size(40.dp).then(
                if (active) Modifier.background(MaterialTheme.colorScheme.secondaryContainer, CircleShape) else Modifier
            ),
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}

/**
 * True if the crop rectangle — center [cx,cy] and half-extents [hx,hy] (all normalized to the image),
 * rotated by [angleDeg] around its center — lies entirely within the image bounds [0,w]×[0,h].
 * Used to reject a rotation (or 90° turn) that would pull a crop corner off the photo; the user must
 * shrink the crop first so it fits at the new angle.
 */
private fun cropWithinImage(
    cx: Float,
    cy: Float,
    hx: Float,
    hy: Float,
    angleDeg: Float,
    w: Float,
    h: Float,
): Boolean {
    val a = Math.toRadians(angleDeg.toDouble())
    val ca = cos(a)
    val sa = sin(a)
    val cpx = cx * w
    val cpy = cy * h
    val phx = hx * w
    val phy = hy * h
    val eps = 0.5 // sub-pixel tolerance so touching the edge exactly still counts as inside
    for (sx in intArrayOf(-1, 1)) {
        for (sy in intArrayOf(-1, 1)) {
            val x = cpx + (sx * phx * ca - sy * phy * sa)
            val y = cpy + (sx * phx * sa + sy * phy * ca)
            if (x < -eps || x > w + eps || y < -eps || y > h + eps) return false
        }
    }
    return true
}

@Composable
fun CropOverlay(
    cx: Float,
    cy: Float,
    hx: Float,
    hy: Float,
    angleDeg: Float,
    onChange: (cx: Float, cy: Float, hx: Float, hy: Float) -> Unit,
    onAngle: (Float) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val onChangeNow by rememberUpdatedState(onChange)
        val onAngleNow by rememberUpdatedState(onAngle)
        val armGapPx = with(LocalDensity.current) { 40.dp.toPx() }
        val minPx = with(LocalDensity.current) { 24.dp.toPx() }

        val a = Math.toRadians(angleDeg.toDouble())
        val ca = cos(a).toFloat()
        val sa = sin(a).toFloat()
        // rotate a vector by +angle
        fun rot(vx: Float, vy: Float) = Offset(vx * ca - vy * sa, vx * sa + vy * ca)
        // rotate a vector by -angle (into local frame)
        fun unrot(vx: Float, vy: Float) = Offset(vx * ca + vy * sa, -vx * sa + vy * ca)

        val cpx = cx * width
        val cpy = cy * height
        val phx = hx * width
        val phy = hy * height
        fun corner(sx: Int, sy: Int): Offset {
            val r = rot(sx * phx, sy * phy)
            return Offset(cpx + r.x, cpy + r.y)
        }
        val tl = corner(-1, -1)
        val tr = corner(1, -1)
        val br = corner(1, 1)
        val bl = corner(-1, 1)
        fun mid(p: Offset, q: Offset) = Offset((p.x + q.x) / 2f, (p.y + q.y) / 2f)
        val topMid = mid(tl, tr)
        val up = rot(0f, -1f)
        val rotateHandle = Offset(topMid.x + up.x * armGapPx, topMid.y + up.y * armGapPx)

        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val quad = Path().apply {
                moveTo(tl.x, tl.y); lineTo(tr.x, tr.y); lineTo(br.x, br.y); lineTo(bl.x, bl.y); close()
            }
            val dim = Path().apply {
                addRect(Rect(0f, 0f, width, height)); addPath(quad); fillType = PathFillType.EvenOdd
            }
            drawPath(dim, Color.Black.copy(alpha = 0.5f))
            drawPath(quad, Color.White, style = Stroke(width = 2.dp.toPx()))
            drawLine(Color.White, topMid, rotateHandle, strokeWidth = 2.dp.toPx())
        }

        // Body drag (move the whole quad).
        Handle(Offset(cpx, cpy)) { d ->
            onChangeNow((cpx + d.x) / width, (cpy + d.y) / height, hx, hy)
        }

        // Corner handles: keep the opposite corner fixed.
        fun cornerDrag(sx: Int, sy: Int, d: Offset) {
            val opp = corner(-sx, -sy)
            val newC = Offset(corner(sx, sy).x + d.x, corner(sx, sy).y + d.y)
            val nCenter = Offset((opp.x + newC.x) / 2f, (opp.y + newC.y) / 2f)
            val local = unrot(newC.x - opp.x, newC.y - opp.y)
            val nhpx = (abs(local.x) / 2f).coerceAtLeast(minPx)
            val nhpy = (abs(local.y) / 2f).coerceAtLeast(minPx)
            onChangeNow(nCenter.x / width, nCenter.y / height, nhpx / width, nhpy / height)
        }
        Handle(tl) { d -> cornerDrag(-1, -1, d) }
        Handle(tr) { d -> cornerDrag(1, -1, d) }
        Handle(br) { d -> cornerDrag(1, 1, d) }
        Handle(bl) { d -> cornerDrag(-1, 1, d) }

        // Edge handles: move one edge along its local normal, opposite edge fixed.
        fun edgeDrag(edge: Int, d: Offset) {
            val local = unrot(d.x, d.y)
            var nhpx = phx
            var nhpy = phy
            var shiftLocalX = 0f
            var shiftLocalY = 0f
            when (edge) {
                0 -> { nhpx = phx - local.x / 2f; shiftLocalX = local.x / 2f } // left
                1 -> { nhpx = phx + local.x / 2f; shiftLocalX = local.x / 2f } // right
                2 -> { nhpy = phy - local.y / 2f; shiftLocalY = local.y / 2f } // top
                3 -> { nhpy = phy + local.y / 2f; shiftLocalY = local.y / 2f } // bottom
            }
            nhpx = nhpx.coerceAtLeast(minPx)
            nhpy = nhpy.coerceAtLeast(minPx)
            val shift = rot(shiftLocalX, shiftLocalY)
            onChangeNow((cpx + shift.x) / width, (cpy + shift.y) / height, nhpx / width, nhpy / height)
        }
        Handle(mid(tl, bl)) { d -> edgeDrag(0, d) }
        Handle(mid(tr, br)) { d -> edgeDrag(1, d) }
        Handle(topMid) { d -> edgeDrag(2, d) }
        Handle(mid(bl, br)) { d -> edgeDrag(3, d) }

        // Rotate handle.
        Handle(rotateHandle) { d ->
            val px = rotateHandle.x + d.x
            val py = rotateHandle.y + d.y
            val ang = Math.toDegrees(atan2((px - cpx).toDouble(), (cpy - py).toDouble())).toFloat()
            onAngleNow(ang)
        }
    }
}

@Composable
fun Handle(offset: Offset, onDrag: (Offset) -> Unit) {
    val density = LocalDensity.current
    val handleSize = 24.dp
    val handleRadiusPx = with(density) { (handleSize / 2).toPx() }
    val currentOnDrag by rememberUpdatedState(onDrag)
    Box(
        modifier = Modifier
            .offset { IntOffset((offset.x - handleRadiusPx).roundToInt(), (offset.y - handleRadiusPx).roundToInt()) }
            .size(handleSize)
            .background(Color.White, CircleShape)
            .border(1.dp, Color.Black, CircleShape)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount -> change.consume(); currentOnDrag(dragAmount) }
            },
    )
}

@Composable
private fun PanelContainer(
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(8.dp),
        verticalArrangement = verticalArrangement,
        content = content,
    )
}

@Composable
private fun SelectableChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    cornerRadius: Dp = 6.dp,
    horizontalPadding: Dp = 12.dp,
) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(cornerRadius),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
    ) {
        Text(label, fontSize = 12.sp, modifier = Modifier.padding(horizontal = horizontalPadding, vertical = 6.dp))
    }
}

@Composable
private fun AdjustmentPanel(
    adjustments: ImageAdjustments,
    selectedAdjustment: AdjustmentType,
    onSelectAdjustment: (AdjustmentType) -> Unit,
    onUpdateAdjustment: ((ImageAdjustments) -> ImageAdjustments) -> Unit,
    onReset: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)).padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AdjustmentType.entries.forEach { type ->
                val value = type.get(adjustments)
                Surface(
                    modifier = Modifier.clickable { onSelectAdjustment(type) },
                    shape = RoundedCornerShape(8.dp),
                    color = if (selectedAdjustment == type) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                ) {
                    Text(
                        if (value != 0f) "${type.label} ${value.roundToInt()}" else type.label,
                        fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
        val currentValue = selectedAdjustment.get(adjustments)
        LabeledSlider(selectedAdjustment.label, currentValue, selectedAdjustment.min..selectedAdjustment.max) {
            onUpdateAdjustment { adj -> selectedAdjustment.set(adj, it) }
        }
        if (adjustments != ImageAdjustments()) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Surface(
                    modifier = Modifier.clickable { onReset() }.padding(4.dp),
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Text("Reset All", fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
    }
}

@Composable
private fun FilterPresetPanel(
    bitmap: android.graphics.Bitmap?,
    adjustments: ImageAdjustments,
    onSelectFilter: (PhotoFilter) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)).padding(8.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PhotoFilters.all.forEach { filter ->
            val isSelected = if (filter.adjustments == ImageAdjustments()) adjustments == ImageAdjustments() else adjustments == filter.adjustments
            Column(
                modifier = Modifier.width(72.dp).clickable { onSelectFilter(filter) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                bitmap?.let { bmp ->
                    val filterMatrix = remember(filter) { filter.adjustments.toColorMatrix() }
                    val hasFilter = filter.adjustments != ImageAdjustments()
                    Box(
                        modifier = Modifier.size(64.dp).then(
                            if (isSelected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)) else Modifier
                        ),
                    ) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = filter.name,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            colorFilter = if (hasFilter) androidx.compose.ui.graphics.ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix(filterMatrix.array)) else null,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(filter.name, fontSize = 10.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
        }
    }
}

private fun hitTestText(
    x: Float, y: Float, texts: List<TextElement>, viewportWidth: Float, viewportHeight: Float, density: Float,
): Int? {
    val paint = android.graphics.Paint().apply { isAntiAlias = true }
    for (i in texts.indices.reversed()) {
        val elem = texts[i]
        paint.textSize = elem.fontSize * density
        val textWidth = paint.measureText(elem.text)
        val textHeight = paint.textSize
        val ex = elem.x * viewportWidth
        val ey = elem.y * viewportHeight
        if (x in ex..(ex + textWidth) && y in ey..(ey + textHeight + 4f)) return i
    }
    return null
}

private fun hitTestStroke(x: Float, y: Float, strokes: List<InkStroke>): Int? {
    val hitRadius = 20f
    for (i in strokes.indices.reversed()) {
        strokes[i].shape.computeBoundingBox()?.let { box ->
            if (box.xMin <= x + hitRadius && box.xMax >= x - hitRadius && box.yMin <= y + hitRadius && box.yMax >= y - hitRadius) return i
        }
    }
    return null
}
