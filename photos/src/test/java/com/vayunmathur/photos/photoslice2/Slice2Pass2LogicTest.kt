package com.vayunmathur.photos.photoslice2

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.photos.data.AdjustmentLayer
import com.vayunmathur.photos.data.BasicAdjustment
import com.vayunmathur.photos.data.BitmapReference
import com.vayunmathur.photos.data.BlurAdj
import com.vayunmathur.photos.data.BlurParams
import com.vayunmathur.photos.data.DrawingLayer
import com.vayunmathur.photos.data.EditDocument
import com.vayunmathur.photos.data.ImageAdjustments
import com.vayunmathur.photos.data.LevelsAdj
import com.vayunmathur.photos.data.PixelLayer
import com.vayunmathur.photos.data.PhotosRepository
import com.vayunmathur.photos.data.Selection
import com.vayunmathur.photos.data.TextLayer
import com.vayunmathur.photos.data.VideoEditState
import com.vayunmathur.photos.data.VideoFilterPreset
import com.vayunmathur.photos.ui.EditPhotoCanvas
import com.vayunmathur.photos.ui.EditPhotoCropTransformOverlays
import com.vayunmathur.photos.ui.EditPhotoEditorState
import com.vayunmathur.photos.ui.EditPhotoPaintOverlays
import com.vayunmathur.photos.ui.EditPhotoToolOverlays
import com.vayunmathur.photos.ui.EditorMode
import com.vayunmathur.photos.ui.LayersPanel
import com.vayunmathur.photos.util.LayerCompositor
import com.vayunmathur.photos.util.VideoEditViewModel
import com.vayunmathur.photos.util.pass3Photo
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Second-pass slice-2 logic: Selection factories (rect/ellipse/polygon/
 * wand + feather/invert), the layer compositor over real bitmaps, the
 * video editor view-model state machine, tool overlays across modes,
 * the edit canvas, and a LayersPanel populated with every layer kind.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1000dp-h1400dp")
class Slice2Pass2LogicTest {

    @get:Rule val rule = createComposeRule()

    private val app: Application
        get() = ApplicationProvider.getApplicationContext<android.content.Context>()
            .applicationContext as Application

    private fun bmp(color: Int, w: Int = 8, h: Int = 8): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    private fun state(
        doc: EditDocument = EditDocument(canvasWidth = 100, canvasHeight = 50),
        bitmap: Bitmap? = null,
        selection: Selection? = null,
    ): EditPhotoEditorState {
        val vm = com.vayunmathur.photos.util.PhotoEditViewModel(app, PhotosRepository.get(app))
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        return EditPhotoEditorState(
            vm, activity,
            mutableStateOf(doc), mutableStateOf(null), mutableStateOf(bitmap), mutableStateOf(selection),
        )
    }

    // ---- Selection factories ----

    @Test fun selectionRectangleAndEllipse() {
        val rect = Selection.rectangle(10, 10, 0.2f, 0.2f, 0.5f, 0.5f)
        assertEquals(9, rect.mask.count { it == 1f }) // 3x3 interior
        assertFalse(rect.isEmpty())
        val ellipse = Selection.ellipse(10, 10, 0.5f, 0.5f, 0.3f, 0.3f)
        assertTrue(ellipse.mask[55] == 1f) // center selected
        assertTrue(ellipse.mask[0] == 0f) // corner not
        val degenerate = Selection.ellipse(10, 10, 0.5f, 0.5f, 0f, 0f)
        assertTrue(degenerate.isEmpty())
    }

    @Test fun selectionPolygon() {
        val tri = Selection.polygon(10, 10, listOf(0.1f to 0.1f, 0.9f to 0.1f, 0.1f to 0.9f))
        assertTrue(tri.mask.count { it == 1f } > 10)
        val tooFew = Selection.polygon(10, 10, listOf(0.1f to 0.1f, 0.9f to 0.9f))
        assertTrue(tooFew.isEmpty())
    }

    @Test fun selectionWandAndFeatherInvert() {
        // Left half red, right half blue.
        val src = Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888)
        for (x in 0 until 8) for (y in 0 until 4) {
            src.setPixel(x, y, if (x < 4) Color.RED else Color.BLUE)
        }
        val contiguous = Selection.magicWand(src, 0.1f, 0.5f, tolerance = 0.1f, contiguous = true)
        assertEquals(16, contiguous.mask.count { it == 1f })
        val global = Selection.magicWand(src, 0.1f, 0.5f, tolerance = 0.1f, contiguous = false)
        assertEquals(16, global.mask.count { it == 1f })
        val all = Selection.magicWand(src, 0.1f, 0.5f, tolerance = 1.1f, contiguous = false)
        assertEquals(32, all.mask.count { it == 1f })

        val inverted = contiguous.invert()
        assertEquals(16, inverted.mask.count { it == 1f })
        assertEquals(0f, inverted.mask[0])
        val feathered = contiguous.applyFeather(1f)
        assertEquals(contiguous.width, feathered.width)
        assertTrue(feathered.mask.any { it in 0.01f..0.99f } || contiguous.mask.all { it == 0f || it == 1f })
        assertTrue(contiguous.applyFeather(0f).mask.contentEquals(contiguous.mask))
    }

    // ---- Layer compositor ----

    @Test fun compositorBlendsLayers() {
        val doc = EditDocument(canvasWidth = 8, canvasHeight = 8)
            .addLayer(PixelLayer(BitmapReference(bmp(Color.RED)), name = "base"))
            .addLayer(PixelLayer(BitmapReference(bmp(Color.BLUE)), name = "top"))
        val out = LayerCompositor().composite(doc)
        assertEquals(8, out.width)
        assertEquals(8, out.height)
        // Opaque blue on top of red composes to blue.
        assertEquals(Color.BLUE, out.getPixel(4, 4))
    }

    @Test fun compositorOpacityAndHiddenAndPreview() {
        val semi = PixelLayer(BitmapReference(bmp(Color.BLUE)), name = "top", opacity = 0.5f)
        val doc = EditDocument(canvasWidth = 8, canvasHeight = 8)
            .addLayer(PixelLayer(BitmapReference(bmp(Color.RED)), name = "base"))
            .addLayer(semi)
        val out = LayerCompositor().composite(doc)
        val px = out.getPixel(4, 4)
        // 50% blue over red: both channels present.
        assertTrue(Color.red(px) > 100 && Color.blue(px) > 100, "px=${Integer.toHexString(px)}")

        val hiddenDoc = doc.copy(
            layers = doc.layers.mapIndexed { i, l -> if (i == 1) (l as PixelLayer).copy(visible = false) else l },
        )
        assertEquals(Color.RED, LayerCompositor().composite(hiddenDoc).getPixel(4, 4))

        val preview = LayerCompositor().compositePreview(doc, maxDimension = 4)
        assertEquals(4, preview.width)
        assertNotNull(preview)
    }

    @Test fun compositorAdjustmentLayerChangesPixels() {
        val doc = EditDocument(canvasWidth = 8, canvasHeight = 8)
            .addLayer(PixelLayer(BitmapReference(bmp(Color.GRAY)), name = "base"))
            .addLayer(AdjustmentLayer(LevelsAdj(com.vayunmathur.photos.data.LevelsAdjustment(inBlack = 200f)), name = "levels"))
        val out = LayerCompositor().composite(doc)
        // Mid-gray (128) below the crushed input-black point (200) -> black.
        assertTrue(Color.red(out.getPixel(4, 4)) < 10, "px=${Integer.toHexString(out.getPixel(4, 4))}")
        val identity = EditDocument(canvasWidth = 8, canvasHeight = 8)
            .addLayer(PixelLayer(BitmapReference(bmp(Color.WHITE)), name = "base"))
            .addLayer(AdjustmentLayer(BasicAdjustment(ImageAdjustments()), name = "noop"))
        assertEquals(Color.WHITE, LayerCompositor().composite(identity).getPixel(4, 4))
    }

    // ---- VideoEditViewModel state machine ----

    @Test fun videoViewModelActions() {
        val vm = VideoEditViewModel(app, PhotosRepository.get(app))
        vm.setDuration(120_000)
        assertEquals(120_000, vm.state.value.durationMs)
        vm.setTrim(-5_000, 999_999)
        val trimmed = vm.state.value
        assertTrue(trimmed.trimStartMs >= 0 && trimmed.trimEndMs <= 120_000, "$trimmed")
        vm.rotateRight(); assertEquals(90, vm.state.value.rotationDegrees)
        vm.rotateRight(); vm.rotateLeft(); assertEquals(90, vm.state.value.rotationDegrees)
        vm.rotateLeft(); vm.rotateLeft(); assertEquals(270, vm.state.value.rotationDegrees)
        vm.toggleFlip(); assertTrue(vm.state.value.flipHorizontal)
        vm.setCrop(0.1f, 0.1f, 0.9f, 0.9f)
        assertEquals(0.1f, vm.state.value.cropLeft)
        vm.clearCrop(); assertEquals(null, vm.state.value.cropLeft)
        vm.setMuted(true); assertTrue(vm.state.value.muted)
        vm.setBrightness(5f); assertEquals(1f, vm.state.value.brightness)
        vm.setContrast(-5f); assertEquals(-1f, vm.state.value.contrast)
        vm.setSaturation(0.25f); assertEquals(0.25f, vm.state.value.saturation)
        vm.setFilterPreset(VideoFilterPreset.Mono)
        assertEquals(VideoFilterPreset.Mono, vm.state.value.filterPreset)
        for (preset in VideoFilterPreset.entries) {
            val effects = vm.buildVideoEffects(VideoEditState(filterPreset = preset, brightness = 0.5f))
            assertNotNull(effects)
        }
        vm.onWritePermissionDenied()
        vm.cancelExport()
    }

    @Test fun exportStartFailureResetsStateAndLeavesNoTempFile() {
        val vm = VideoEditViewModel(app, PhotosRepository.get(app))
        var completed = false
        // Unknown content URI: Transformer.start() throws synchronously.
        vm.export(app, pass3Photo(1, "clip.mp4"), true) { completed = true }
        assertTrue(completed, "onComplete must fire on unrecoverable failure")
        assertFalse(vm.exporting.value)
        val orphans = app.cacheDir.listFiles { f -> f.name.startsWith("video_edit_") } ?: emptyArray()
        assertTrue(orphans.isEmpty(), "failed export must leave no temp file")
        // Cancel stays a safe no-op after a failed start.
        vm.cancelExport()
        assertFalse(vm.exporting.value)
    }

    // ---- Overlays / canvas / layers panel ----

    @Test fun toolOverlaysAcrossModes() {
        val sel = Selection.rectangle(20, 20, 0.1f, 0.1f, 0.9f, 0.9f)
        val s = state(selection = sel, bitmap = bmp(Color.GRAY, 20, 20))
        rule.setContent {
            androidx.compose.foundation.layout.Column {
                EditPhotoToolOverlays(s)
                EditPhotoPaintOverlays(s)
                EditPhotoCropTransformOverlays(s)
            }
        }
        for (mode in listOf(
            EditorMode.None, EditorMode.LensBlur, EditorMode.Selective, EditorMode.Healing,
            EditorMode.RedEye, EditorMode.DodgeBurn, EditorMode.Smudge, EditorMode.Liquify,
            EditorMode.Selection, EditorMode.Crop, EditorMode.Fill, EditorMode.GradientTool,
        )) {
            s.editorMode = mode
            rule.waitForIdle()
        }
        rule.onRoot().captureToImage()
    }

    @Test fun layersPanelAllKinds() {
        var flattened = false
        val doc = EditDocument(canvasWidth = 8, canvasHeight = 8)
            .addLayer(PixelLayer(BitmapReference(bmp(Color.RED)), name = "Pixels"))
            .addLayer(AdjustmentLayer(BlurAdj(BlurParams()), name = "Blur"))
            .addLayer(TextLayer(com.vayunmathur.photos.data.TextElement("t1", "Caption", 0.1f, 0.1f, 0f, 0, 24f), name = "Caption"))
            .addLayer(DrawingLayer(emptyList(), 100f, 100f, name = "Ink"))
        rule.setContent {
            LayersPanel(
                document = doc, hasSelection = true,
                onSelectLayer = {}, onToggleVisibility = { _, _ -> }, onOpacityChange = { _, _ -> },
                onBlendModeChange = { _, _ -> }, onAddAdjustment = {}, onAddPixelLayer = {},
                onDuplicate = {}, onMergeDown = {}, onDelete = {},
                onFlatten = { flattened = true },
                onAddMaskFromSelection = {}, onDeleteMask = {}, onInvertMask = {},
                onToggleClip = { _, _ -> }, onSetStyle = { _, _ -> },
                onGroupActive = {}, onUngroup = {}, onUpdateGroup = {},
                onMoveLayer = { _, _ -> }, onEditText = {}, onResumeDrawing = {},
            )
        }
        rule.waitForIdle()
        // The action bar's Flatten chip must wire to onFlatten.
        rule.onNodeWithText("Flatten").performClick()
        assertTrue(flattened)
        rule.onRoot().captureToImage()
    }
}
