package com.vayunmathur.photos.ui

import android.app.Activity
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.ink.strokes.Stroke as InkStroke
import com.vayunmathur.library.ink.serialize
import com.vayunmathur.photos.data.DodgeBurnMode
import com.vayunmathur.photos.data.DrawingTool
import com.vayunmathur.photos.data.EditDocument
import com.vayunmathur.photos.data.HslColorRange
import com.vayunmathur.photos.data.CurveChannel
import com.vayunmathur.photos.data.LiquifyTool
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.Selection
import com.vayunmathur.photos.data.SelectionCombine
import com.vayunmathur.photos.data.SelectiveMask
import com.vayunmathur.photos.data.TextElement
import com.vayunmathur.photos.platform.ExportFormat
import com.vayunmathur.photos.platform.PhotoEditViewModel
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

internal class EditPhotoEditorState(
    val vm: PhotoEditViewModel,
    internal val context: Activity,
    private val documentState: State<EditDocument>,
    private val photoState: State<Photo?>,
    internal val baseBitmapState: State<Bitmap?>,
    internal val selectionState: State<Selection?>,
) {
    internal val document: EditDocument get() = documentState.value
    internal val photo: Photo? get() = photoState.value
    internal val baseBitmap: Bitmap? get() = baseBitmapState.value
    internal val selection: Selection? get() = selectionState.value

    var activeCategory by mutableStateOf<ToolCategory?>(null)
    var editorMode by mutableStateOf(EditorMode.None)
    var selectedAdjustment by mutableStateOf(AdjustmentType.Brightness)
    var selectedCurveChannel by mutableStateOf(CurveChannel.Combined)
    var selectedHslRange by mutableStateOf(HslColorRange.Red)

    var isCropping by mutableStateOf(false)
    /** True while the Draw category is active (ink, highlighter, eraser, text, pointer). */
    val isDrawing: Boolean get() = activeCategory == ToolCategory.Draw
    var cropCx by mutableFloatStateOf(0.5f)
    var cropCy by mutableFloatStateOf(0.5f)
    var cropHx by mutableFloatStateOf(0.5f)
    var cropHy by mutableFloatStateOf(0.5f)
    var cropAngle by mutableFloatStateOf(0f)
    var cropAspect by mutableStateOf<Float?>(null)
    var showSaveMenu by mutableStateOf(false)

    // Selective
    var currentSelectiveMask by mutableStateOf(SelectiveMask())
    var showSelectiveMask by mutableStateOf(false)

    // Healing
    var healingBrushSize by mutableFloatStateOf(0.02f)
    var isSettingHealingSource by mutableStateOf(true)
    var healingSourceX by mutableStateOf<Float?>(null)
    var healingSourceY by mutableStateOf<Float?>(null)
    var currentHealingPoints by mutableStateOf<List<Pair<Float, Float>>>(emptyList())

    // Retouch brush (dodge/burn/smudge)
    var retouchPoints by mutableStateOf<List<Pair<Float, Float>>>(emptyList())
    var dodgeBurnMode by mutableStateOf(DodgeBurnMode.Dodge)
    var brushSize by mutableFloatStateOf(0.05f)

    // Liquify
    var liquifyTool by mutableStateOf(LiquifyTool.Push)
    var liquifyStrength by mutableFloatStateOf(0.5f)
    var liquifyRadius by mutableFloatStateOf(0.15f)

    // Selection tool
    var selectionTool by mutableStateOf(SelectionTool.Rectangle)
    var selectionCombine by mutableStateOf(SelectionCombine.New)
    var selectionFeather by mutableFloatStateOf(0f)
    var wandTolerance by mutableFloatStateOf(0.15f)
    // The committed selection before feathering, so feather can be re-applied live.
    var selectionBase by mutableStateOf<Selection?>(null)
    var selDragStart by mutableStateOf<Offset?>(null)
    var selDragCurrent by mutableStateOf<Offset?>(null)
    var lassoPoints by mutableStateOf<List<Offset>>(emptyList())
    var polygonPoints by mutableStateOf<List<Offset>>(emptyList())

    // Mask brush
    var maskPaintReveal by mutableStateOf(false)
    var maskBrushSize by mutableFloatStateOf(0.06f)
    var maskPoints by mutableStateOf<List<Pair<Float, Float>>>(emptyList())

    // Paint (fill / gradient / shapes)
    var paintColor by mutableStateOf(Color.Red)
    val recentColors = mutableStateListOf<Color>()
    var fillTolerance by mutableFloatStateOf(0.2f)
    var shapeStrokeWidth by mutableFloatStateOf(0.01f)
    var paintDragStart by mutableStateOf<Offset?>(null)
    var paintDragCurrent by mutableStateOf<Offset?>(null)

    // Free transform corners (normalized 0..1)
    var ftTL by mutableStateOf(Offset(0f, 0f))
    var ftTR by mutableStateOf(Offset(1f, 0f))
    var ftBL by mutableStateOf(Offset(0f, 1f))
    var ftBR by mutableStateOf(Offset(1f, 1f))

    // Drawing
    val inkStrokes = mutableStateListOf<InkStroke>()
    val redoStrokes = mutableStateListOf<InkStroke>()
    var activeTool by mutableStateOf(DrawingTool.Pointer)
    var penColor by mutableStateOf(Color.Red)
    var penSize by mutableFloatStateOf(10f)
    var highlighterColor by mutableStateOf(Color.Yellow)
    var highlighterSize by mutableFloatStateOf(40f)
    var highlighterOpacity by mutableFloatStateOf(0.5f)
    var textFontSize by mutableFloatStateOf(40f)

    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)

    val texts = mutableStateListOf<TextElement>()
    var selectedTextId by mutableStateOf<String?>(null)
    var selectedStrokeIndex by mutableStateOf<Int?>(null)
    var selectedTextIndex by mutableStateOf<Int?>(null)
    var textToEdit by mutableStateOf<TextElement?>(null)
    var currentViewportWidth by mutableFloatStateOf(1f)
    var currentViewportHeight by mutableFloatStateOf(1f)

    fun exitCropPreview() {
        vm.pixel.setCroppingPreview(false)
        isCropping = false
    }

    fun selectTool(mode: EditorMode) {
        when (mode) {
            EditorMode.Crop -> {
                cropCx = 0.5f; cropCy = 0.5f; cropHx = 0.5f; cropHy = 0.5f
                cropAngle = 0f; cropAspect = null
                isCropping = true
                vm.pixel.setCroppingPreview(true)
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
            vm.layers.commitOverlaysToLayers(
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
        vm.pixel.setRotation(-cropAngle)
        vm.pixel.setCropRect(Rect(l, t, r, b))
        goHome()
    }

    fun doSave(asCopy: Boolean, format: ExportFormat = ExportFormat.Jpeg) {
        // Fold any in-progress strokes/text into layers so they're part of the
        // composite; save with no separate overlay baking.
        commitOverlays()
        photo?.let {
            vm.saver.savePhoto(
                it, asCopy, emptyList(), emptyList(),
                currentViewportWidth, currentViewportHeight, format,
            ) { context.finish() }
        }
    }
}

@Composable
internal fun rememberEditPhotoEditorState(vm: PhotoEditViewModel, context: Activity): EditPhotoEditorState {
    val document = vm.document.collectAsState()
    val photo = vm.photo.collectAsState()
    val baseBitmap = vm.baseBitmap.collectAsState()
    val selection = vm.selection.collectAsState()
    return remember(vm, context) {
        EditPhotoEditorState(vm, context, document, photo, baseBitmap, selection)
    }
}
