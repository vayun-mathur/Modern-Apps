package com.vayunmathur.photos.photoslice2

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.photos.data.BitmapReference
import com.vayunmathur.photos.data.EditDocument
import com.vayunmathur.photos.data.LayerBlendMode
import com.vayunmathur.photos.data.LayerMask
import com.vayunmathur.photos.data.LayerStyle
import com.vayunmathur.photos.data.PixelLayer
import com.vayunmathur.photos.data.PhotosRepository
import com.vayunmathur.photos.util.PhotoEditViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LayerControllerEditingPass3Test {

    private val app: Application
        get() = ApplicationProvider.getApplicationContext<android.content.Context>()
            .applicationContext as Application

    private fun bmp(color: Int): Bitmap =
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    private fun vmWith(vararg layers: Pair<String, Int>): PhotoEditViewModel {
        val vm = PhotoEditViewModel(app, PhotosRepository.get(app))
        var doc = EditDocument(canvasWidth = 8, canvasHeight = 8)
        layers.forEach { (name, color) -> doc = doc.addLayer(PixelLayer(BitmapReference(bmp(color)), name = name)) }
        vm.updateDocument(pushUndo = false) { doc }
        return vm
    }

    @Test
    fun `layer stack operations through the controller`() {
        val vm = vmWith("base" to Color.RED, "mid" to Color.GREEN, "top" to Color.BLUE)
        assertEquals(3, vm.document.value.layers.size)

        vm.layers.setActiveLayer(1)
        assertEquals(1, vm.document.value.activeLayerIndex)

        vm.layers.renameLayer(1, "middle")
        assertEquals("middle", vm.document.value.layers[1].name)

        vm.layers.setLayerVisibility(1, false)
        assertTrue(!vm.document.value.layers[1].visible)
        vm.layers.setLayerVisibility(1, true)
        assertTrue(vm.document.value.layers[1].visible)

        vm.layers.setLayerOpacity(1, 0.5f)
        assertEquals(0.5f, vm.document.value.layers[1].opacity)

        vm.layers.setLayerBlendMode(1, LayerBlendMode.Multiply)
        assertEquals(LayerBlendMode.Multiply, vm.document.value.layers[1].blendMode)

        vm.layers.moveLayer(1, 2)
        assertEquals("middle", vm.document.value.layers[2].name)

        vm.layers.duplicateLayer(2)
        assertEquals(4, vm.document.value.layers.size)

        vm.layers.removeLayer(3)
        assertEquals(3, vm.document.value.layers.size)
    }

    @Test
    fun `mask clip style and group operations`() {
        val vm = vmWith("base" to Color.RED, "top" to Color.BLUE)
        val mask = LayerMask(FloatArray(64) { 1f }, 8, 8)
        vm.layers.setLayerMask(1, mask)
        assertNotNull(vm.document.value.layers[1].mask)
        vm.layers.invertLayerMask(1)
        assertEquals(0f, vm.document.value.layers[1].mask!!.alphaData[0])
        vm.layers.deleteLayerMask(1)
        assertNull(vm.document.value.layers[1].mask)

        vm.layers.setLayerClipped(1, true)
        assertTrue(vm.document.value.layers[1].clipped)

        vm.layers.setLayerStyle(1, LayerStyle(dropShadow = true))
        assertTrue(vm.document.value.layers[1].style.dropShadow)

        vm.layers.setActiveLayer(1)
        vm.layers.groupActiveWithBelow()
        val grouped = vm.document.value.layers[1].groupId
        assertNotNull(grouped)
        assertEquals(grouped, vm.document.value.layers[0].groupId)
        vm.layers.ungroupActive()
        assertNull(vm.document.value.layers[1].groupId)
    }

    @Test
    fun `merge down flatten and add empty layer`() {
        val vm = vmWith("base" to Color.RED, "top" to Color.BLUE)
        vm.layers.addEmptyPixelLayer()
        assertEquals(3, vm.document.value.layers.size)
        vm.layers.mergeDown(2)
        assertEquals(2, vm.document.value.layers.size)
        vm.layers.flatten()
        assertEquals(1, vm.document.value.layers.size)
    }

    @Test
    fun `undo and redo walk the document history`() {
        val vm = vmWith("base" to Color.RED)
        vm.layers.addEmptyPixelLayer()
        assertEquals(2, vm.document.value.layers.size)
        assertTrue(vm.canUndo.value)
        vm.undo()
        assertEquals(1, vm.document.value.layers.size)
        assertTrue(vm.canRedo.value)
        vm.redo()
        assertEquals(2, vm.document.value.layers.size)
    }
}
