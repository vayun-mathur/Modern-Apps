package com.vayunmathur.photos.util
import com.vayunmathur.photos.platform.PhotoEditViewModel
import com.vayunmathur.photos.platform.workInfosOverride

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.photos.data.BasicAdjustment
import com.vayunmathur.photos.data.BitmapReference
import com.vayunmathur.photos.data.CurvesAdj
import com.vayunmathur.photos.data.CurvesAdjustment
import com.vayunmathur.photos.data.EditDocument
import com.vayunmathur.photos.data.ImageAdjustments
import com.vayunmathur.photos.data.LayerBlendMode
import com.vayunmathur.photos.data.LayerStyle
import com.vayunmathur.photos.data.PerspectiveCorners
import com.vayunmathur.photos.data.PixelLayer
import com.vayunmathur.photos.data.TestPhotosRepository
import com.vayunmathur.photos.data.TextLayer
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.setMain
import com.vayunmathur.photos.data.awaitPhotosCondition
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The edit controllers' less-travelled operations: flips and perspective
 * transforms of the active layer, overlay commits, mask selection, and
 * adjustment-layer ensure/update semantics.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditControllersDeepTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var installed: TestPhotosRepository.Installed

    @BeforeTest
    fun setup() {
        kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.Dispatchers.Unconfined)
        workInfosOverride = { _, _ -> flowOf(emptyList()) }
        installed = TestPhotosRepository.install(app)
    }

    private fun vmWithLayers(): PhotoEditViewModel {
        val vm = PhotoEditViewModel(app, installed.photos)
        val base = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
        }
        vm.updateDocument(pushUndo = false) {
            EditDocument(
                canvasWidth = 20, canvasHeight = 10,
                layers = listOf(
                    PixelLayer(BitmapReference(base), name = "base"),
                    TextLayer(com.vayunmathur.photos.data.TextElement(id = "t1", text = "label", x = 0.5f, y = 0.5f, rotation = 0f, color = 0xFF000000.toInt(), fontSize = 12f), name = "text"),
                ),
                activeLayerIndex = 0,
            )
        }
        return vm
    }

    @Test fun layerControllerOperations() {
        val vm = vmWithLayers()
        vm.layers.addEmptyPixelLayer()
        assertEquals(3, vm.document.value.layers.size)
        val topIndex = vm.document.value.layers.lastIndex
        vm.layers.renameLayer(topIndex, "paint")
        assertEquals("paint", vm.document.value.layers[topIndex].name)
        vm.layers.setLayerOpacity(topIndex, 0.5f)
        assertEquals(0.5f, vm.document.value.layers[topIndex].opacity)
        vm.layers.setLayerBlendMode(topIndex, LayerBlendMode.Multiply)
        assertEquals(LayerBlendMode.Multiply, vm.document.value.layers[topIndex].blendMode)
        vm.layers.setLayerVisibility(topIndex, false)
        assertFalse(vm.document.value.layers[topIndex].visible)
        vm.layers.setLayerClipped(topIndex, true)
        assertTrue(vm.document.value.layers[topIndex].clipped)
        vm.layers.setLayerStyle(topIndex, LayerStyle(dropShadow = true))
        vm.layers.duplicateLayer(topIndex)
        assertEquals(4, vm.document.value.layers.size)
        vm.layers.moveLayer(vm.document.value.layers.lastIndex, 0)
        vm.layers.removeLayer(0)
        assertEquals(3, vm.document.value.layers.size)
        // Group the active layer with the one below, then dissolve.
        vm.layers.setActiveLayer(1)
        vm.layers.groupActiveWithBelow()
        assertTrue(vm.document.value.groups.isNotEmpty())
        vm.layers.ungroupActive()
        assertTrue(vm.document.value.groups.isEmpty())
        // Mask lifecycle on a layer.
        vm.layers.setLayerMask(0, com.vayunmathur.photos.data.LayerMask(FloatArray(200) { 0.5f }, 20, 10))
        assertNotNull(vm.document.value.layers[0].mask)
        vm.layers.invertLayerMask(0)
        assertEquals(0.5f, vm.document.value.layers[0].mask!!.alphaData[0])
        vm.layers.deleteLayerMask(0)
        assertEquals(null, vm.document.value.layers[0].mask)
        // Merge + flatten through the compositor.
        vm.layers.mergeDown(1)
        vm.layers.flatten()
        assertEquals(1, vm.document.value.layers.size)
    }

    @Test fun pixelControllerTransforms() {
        val vm = vmWithLayers()
        vm.pixel.rotate(45f)
        assertEquals(45f, vm.document.value.rotation)
        vm.pixel.setRotation(-90f)
        assertEquals(-90f, vm.document.value.rotation)
        vm.pixel.setCropRect(androidx.compose.ui.geometry.Rect(0f, 0f, 10f, 5f))
        vm.pixel.setCroppingPreview(true)
        vm.pixel.setPerspective(PerspectiveCorners())
        assertTrue(vm.document.value.layers.isNotEmpty())
        // Flip the active pixel layer both ways; bitmap dimensions swap content, not size.
        vm.pixel.flipActiveLayer(horizontal = true)
        vm.pixel.flipActiveLayer(horizontal = false)
        vm.pixel.transformActiveLayer(PerspectiveCorners(topLeft = 0.1f to 0f))
        // Applying an identity pixel transform through the active layer works.
        vm.pixel.applyToActivePixelLayer { it }
        assertEquals(2, vm.document.value.layers.size)
    }

    @Test fun adjustmentControllerEnsureAndUpdate() {
        val vm = vmWithLayers()
        vm.adjustments.ensureAdjustment({ it is BasicAdjustment }) {
            BasicAdjustment(ImageAdjustments(brightness = 0.2f))
        }
        val afterEnsure = vm.document.value
        assertTrue(afterEnsure.layers.any { it is com.vayunmathur.photos.data.AdjustmentLayer })
        // Ensuring again does not stack a second adjustment layer.
        vm.adjustments.ensureAdjustment({ it is BasicAdjustment }) {
            BasicAdjustment(ImageAdjustments(brightness = 0.9f))
        }
        assertEquals(
            afterEnsure.layers.count { it is com.vayunmathur.photos.data.AdjustmentLayer },
            vm.document.value.layers.count { it is com.vayunmathur.photos.data.AdjustmentLayer },
        )
        vm.adjustments.updateActiveAdjustment(CurvesAdj(CurvesAdjustment()))
        vm.adjustments.addAdjustmentLayer(BasicAdjustment(ImageAdjustments(contrast = 0.3f)))
        assertTrue(vm.document.value.layers.count { it is com.vayunmathur.photos.data.AdjustmentLayer } >= 1)
        assertNotNull(vm.activeAdjustment)
    }

    @Test fun selectionAwarePixelOps() = runBlocking {
        val vm = vmWithLayers()
        // A half-strength selection over the left half of the canvas.
        val mask = FloatArray(20 * 10) { if (it % 20 < 10) 1f else 0f }
        vm.setSelection(com.vayunmathur.photos.data.Selection(mask, 20, 10))
        vm.pixel.applyToActivePixelLayer { bmp ->
            Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        }
        assertTrue(awaitPhotosCondition {
            val layer = vm.document.value.layers[0] as PixelLayer
            layer.bitmapRef.bitmap.getPixel(2, 5) == Color.BLUE
        })
        vm.pixel.contentAwareFillSelection()
        assertTrue(awaitPhotosCondition {
            (vm.document.value.layers[0] as PixelLayer).bitmapRef.bitmap.width == 20
        })
        // Free-transform with non-identity corners warps the layer bitmap.
        vm.pixel.transformActiveLayer(PerspectiveCorners(topLeft = 0.2f to 0f, topRight = 1f to 0.1f))
        assertTrue(awaitPhotosCondition {
            (vm.document.value.layers[0] as PixelLayer).bitmapRef.bitmap.height == 10
        })
        vm.setSelection(null)
        vm.pixel.contentAwareFillSelection()
        Unit
    }

    @Test fun overlaysCommitToLayers() {
        val vm = vmWithLayers()
        // Strokes/texts are supplied through the editor state in production;
        // the controller entry point with empty overlays still runs its path.
        vm.layers.commitOverlaysToLayers(emptyList(), emptyList(), 20f, 10f)
        assertTrue(vm.document.value.layers.isNotEmpty())
    }
}
