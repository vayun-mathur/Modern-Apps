package com.vayunmathur.pdf.util

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.pdf.model.Quadrilateral
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RobolectricUtilTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test fun pdfStateStoreRoundTripAndDefault() {
        val uri = Uri.parse("content://docs/report.pdf")
        assertEquals(0, PdfStateStore.restoreSafePage(app, uri))
        PdfStateStore.saveSafePage(app, uri, 7)
        assertEquals(7, PdfStateStore.restoreSafePage(app, uri))
        assertEquals(0, PdfStateStore.restoreSafePage(app, Uri.parse("content://docs/other.pdf")))
        PdfStateStore.saveSafePage(app, uri, 3)
        assertEquals(3, PdfStateStore.restoreSafePage(app, uri))
    }

    @Test fun pdfViewModelCapturedImageOps() {
        val vm = PdfViewModel(app)
        val u1 = Uri.parse("content://img/1"); val u2 = Uri.parse("content://img/2"); val u3 = Uri.parse("content://img/3")
        assertEquals(0, vm.addCapturedImage(u1)); assertEquals(1, vm.addCapturedImage(u2)); assertEquals(2, vm.addCapturedImage(u3))
        assertEquals(listOf(u1, u2, u3), vm.capturedImages.value.map { it.uri })
        vm.moveCapturedImage(0, 2)
        assertEquals(listOf(u2, u3, u1), vm.capturedImages.value.map { it.uri })
        vm.moveCapturedImage(9, 0); assertEquals(3, vm.capturedImages.value.size)
        vm.updateQuadrilateral(1, Quadrilateral.default())
        assertEquals(Quadrilateral.default(), vm.capturedImages.value[1].quadrilateral)
        assertNotNull(vm.capturedImages.value[1].cropRect)
        vm.updateQuadrilateral(99, Quadrilateral.default()) // no crash
        vm.removeCapturedImage(0); assertEquals(listOf(u3, u1), vm.capturedImages.value.map { it.uri })
        vm.removeCapturedImage(99); assertEquals(2, vm.capturedImages.value.size)
        vm.clearCapturedImages(); assertTrue(vm.capturedImages.value.isEmpty())
    }

    @Test fun capturedImageDefaultsUnderRobolectric() {
        val img = com.vayunmathur.pdf.model.CapturedImage(Uri.parse("content://x/1"))
        assertNull(img.cropRect); assertNull(img.quadrilateral)
    }

    @Test fun decodeRawBitmap() {
        val data = ByteArray(2 * 2 * 4) { 0xFF.toByte() }
        val bmp = SafePdfImages.decodeBitmap(2, 2, 0, data)
        assertNotNull(bmp); assertEquals(2, bmp.width); assertEquals(Color.WHITE, bmp.getPixel(0, 0))
        assertNull(SafePdfImages.decodeBitmap(2, 2, 0, ByteArray(4))) // too short
        assertNull(SafePdfImages.decodeBitmap(0, 2, 0, data))
        assertNull(SafePdfImages.decodeBitmap(2, 2, 99, data))
    }

    @Test fun autoFrameDetectorUniformBitmapReturnsNullOrQuadNoCrash() {
        val bmp = Bitmap.createBitmap(60, 60, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        // Uniform image has no document edges; detector should not crash
        AutoFrameDetector.detect(bmp)
    }

    @Test fun autoFrameDetectorFindsDarkDocumentOnWhite() {
        val bmp = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val canvas = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint().apply { color = Color.BLACK }
        canvas.drawRect(20f, 20f, 60f, 60f, paint)
        val quad = AutoFrameDetector.detect(bmp)
        // Black filled rect gives strong edges; if detected, corners near rect
        if (quad != null) {
            assertTrue(quad.topLeft.x < 0.5f && quad.bottomRight.x > 0.4f, quad.toString())
        }
    }
}
