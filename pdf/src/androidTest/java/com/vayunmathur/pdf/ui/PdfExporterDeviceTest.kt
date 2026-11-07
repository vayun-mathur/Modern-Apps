package com.vayunmathur.pdf.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.geometry.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vayunmathur.pdf.model.CapturedImage
import com.vayunmathur.pdf.model.Quadrilateral
import com.vayunmathur.pdf.util.ScanFilter
import com.vayunmathur.pdf.util.savePdfToUri
import com.vayunmathur.pdf.util.warpQuadToBitmap
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device exporter tests: the JVM/Robolectric PdfDocument cannot write a real
 * PDF stream and its ImageDecoder is unimplemented, so [savePdfToUri]'s page
 * rendering, scan filters and perspective warp are only honestly exercisable
 * here on the emulator.
 */
@RunWith(AndroidJUnit4::class)
class PdfExporterDeviceTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun pngFile(name: String, w: Int = 60, h: Int = 80): File {
        val f = File(ctx.cacheDir, name)
        f.outputStream().use {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.WHITE)
                val canvas = android.graphics.Canvas(this)
                val paint = android.graphics.Paint().apply { color = Color.BLACK }
                canvas.drawRect(10f, 10f, 30f, 40f, paint)
            }.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        return f
    }

    @Test
    fun savePdfToUriWritesRealPdfForEachFilter() { runBlocking {
        val src = pngFile("page.png")
        for (filter in ScanFilter.entries) {
            val out = File(ctx.cacheDir, "out-$filter.pdf")
            val ok = savePdfToUri(ctx, listOf(CapturedImage(Uri.fromFile(src))), Uri.fromFile(out), filter)
            assertTrue("save with filter $filter should succeed", ok)
            assertTrue("out $filter should be non-empty", out.length() > 100)
            // Real PDF header
            assertEquals("%PDF", out.inputStream().use { String(it.readNBytes(4)) })
        }
    } }

    @Test
    fun savePdfToUriWithCropAndQuad() { runBlocking {
        val src = pngFile("page2.png", 100, 100)
        val out = File(ctx.cacheDir, "out-crop.pdf")
        val cropped = CapturedImage(Uri.fromFile(src), cropRect = Rect(0.1f, 0.1f, 0.9f, 0.9f))
        assertTrue(savePdfToUri(ctx, listOf(cropped), Uri.fromFile(out)))
        val quad = CapturedImage(Uri.fromFile(src), quadrilateral = Quadrilateral.default())
        val out2 = File(ctx.cacheDir, "out-quad.pdf")
        assertTrue(savePdfToUri(ctx, listOf(quad, cropped), Uri.fromFile(out2), ScanFilter.CONTRAST))
        assertTrue(out2.length() > 100)
    } }

    @Test
    fun savePdfToUriBadSourceFailsCleanly() { runBlocking {
        val out = File(ctx.cacheDir, "out-bad.pdf")
        val bad = savePdfToUri(ctx, listOf(CapturedImage(Uri.parse("content://missing/1"))), Uri.fromFile(out))
        assertFalse("no decodable pages must fail cleanly", bad)
        // OCR variant also degrades cleanly on this host
        savePdfToUri(ctx, listOf(CapturedImage(Uri.fromFile(pngFile("page3.png")))), Uri.fromFile(File(ctx.cacheDir, "out-ocr.pdf")), ScanFilter.NONE, addOcr = true)
    } }

    @Test
    fun warpQuadOnDevice() {
        val src = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val out = warpQuadToBitmap(src, Quadrilateral.default(), 20, 10)
        assertEquals(20, out.width); assertEquals(10, out.height)
        assertEquals(Color.BLUE, out.getPixel(5, 5))
        // Degenerate quad falls back to a bounding-box crop without crashing
        val flat = Quadrilateral(
            androidx.compose.ui.geometry.Offset(0.5f, 0.5f), androidx.compose.ui.geometry.Offset(0.5f, 0.5f),
            androidx.compose.ui.geometry.Offset(0.5f, 0.5f), androidx.compose.ui.geometry.Offset(0.5f, 0.5f),
        )
        val fallback = warpQuadToBitmap(src, flat, 10, 10)
        assertTrue(fallback.width >= 1 && fallback.height >= 1)
    }
}
