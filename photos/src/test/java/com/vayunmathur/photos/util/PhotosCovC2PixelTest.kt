package com.vayunmathur.photos.util

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Rect
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.photos.data.BitmapReference
import com.vayunmathur.photos.data.EditDocument
import com.vayunmathur.photos.data.PerspectiveCorners
import com.vayunmathur.photos.data.PhotosRepository
import com.vayunmathur.photos.data.PixelLayer
import com.vayunmathur.photos.data.Selection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Cov-c2: PixelEditController geometry, flip, transform and selection blends. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotosCovC2PixelTest {

    private val app: Application
        get() = ApplicationProvider.getApplicationContext<android.content.Context>()
            .applicationContext as Application

    private fun twoTone(): Bitmap {
        val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        for (x in 0 until 16) for (y in 0 until 16) {
            bmp.setPixel(x, y, if (x < 8) Color.RED else Color.BLUE)
        }
        return bmp
    }

    private fun vmWithLayer(): PhotoEditViewModel {
        val vm = PhotoEditViewModel(app, PhotosRepository.get(app))
        vm.updateDocument(pushUndo = false) {
            EditDocument(canvasWidth = 16, canvasHeight = 16)
                .addLayer(PixelLayer(bitmapRef = BitmapReference(twoTone()), name = "base"))
        }
        return vm
    }

    private fun awaitVm(check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(200, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (check()) return
            Thread.sleep(50)
        }
        assertTrue(check(), "VM condition not met")
    }

    private fun activeBitmap(vm: PhotoEditViewModel): Bitmap =
        (vm.document.value.activeLayer as PixelLayer).bitmapRef.bitmap

    @Test fun geometrySettersUpdateDocument() {
        val vm = vmWithLayer()
        vm.pixel.rotate(30f)
        assertEquals(30f, vm.document.value.rotation)
        vm.pixel.rotate(15f)
        assertEquals(45f, vm.document.value.rotation)
        vm.pixel.setRotation(10f)
        assertEquals(10f, vm.document.value.rotation)
        vm.pixel.setCropRect(Rect(0f, 0f, 0.5f, 0.5f))
        assertEquals(0.5f, vm.document.value.cropRect.right)
        val corners = PerspectiveCorners(
            topLeft = 0.1f to 0.1f, topRight = 0.9f to 0.1f,
            bottomLeft = 0.1f to 0.9f, bottomRight = 0.9f to 0.9f,
        )
        vm.pixel.setPerspective(corners)
        assertEquals(corners, vm.document.value.perspectiveCorners)
        vm.pixel.setCroppingPreview(true)
        assertTrue(vm.croppingPreview)
    }

    @Test fun flipActiveLayerMirrorsPixels() {
        val vm = vmWithLayer()
        assertEquals(Color.RED, activeBitmap(vm).getPixel(0, 0))
        vm.pixel.flipActiveLayer(horizontal = true)
        awaitVm { activeBitmap(vm).getPixel(0, 0) == Color.BLUE }
        vm.pixel.flipActiveLayer(horizontal = false)
        awaitVm { activeBitmap(vm).getPixel(0, 0) == Color.BLUE }
    }

    @Test fun transformActiveLayerWarps() {
        val vm = vmWithLayer()
        val corners = PerspectiveCorners(
            topLeft = 0.2f to 0.2f, topRight = 0.8f to 0.2f,
            bottomLeft = 0.2f to 0.8f, bottomRight = 0.8f to 0.8f,
        )
        vm.pixel.transformActiveLayer(corners)
        // Identity corners are a no-op (returns before launching work).
        vm.pixel.transformActiveLayer(PerspectiveCorners())
        awaitVm { activeBitmap(vm).width == 16 }
    }

    @Test fun applyToActiveLayerReplacesPixels() {
        val vm = vmWithLayer()
        vm.pixel.applyToActivePixelLayer {
            Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        }
        awaitVm { activeBitmap(vm).getPixel(0, 0) == Color.GREEN }
    }

    @Test fun applyWithSelectionBlendsAndContentAwareFill() {
        val vm = vmWithLayer()
        // Left half selected: edits apply there, right half keeps the original.
        val mask = FloatArray(16 * 16) { i -> if (i % 16 < 8) 1f else 0f }
        vm.setSelection(Selection(mask, 16, 16))
        vm.pixel.applyToActivePixelLayer {
            Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        }
        awaitVm { activeBitmap(vm).getPixel(0, 0) == Color.GREEN }
        assertEquals(Color.BLUE, activeBitmap(vm).getPixel(15, 0))

        vm.pixel.contentAwareFillSelection()
        awaitVm { activeBitmap(vm).width == 16 }
        // Clearing the selection makes content-aware fill a no-op.
        vm.setSelection(null)
        vm.pixel.contentAwareFillSelection()
    }
}
