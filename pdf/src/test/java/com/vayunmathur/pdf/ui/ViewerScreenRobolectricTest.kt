package com.vayunmathur.pdf.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.pdf.model.CapturedImage
import com.vayunmathur.pdf.model.Quadrilateral
import com.vayunmathur.pdf.util.ScanFilter
import com.vayunmathur.pdf.util.savePdfToUri
import com.vayunmathur.pdf.util.warpQuadToBitmap
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ViewerScreenRobolectricTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun minimalPdf(): ByteArray {
        val content = "BT /F1 24 Tf 100 700 Td (Hello) Tj ET"
        val objs = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
            "<< /Length ${content.length} >>\nstream\n$content\nendstream",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        )
        val sb = StringBuilder("%PDF-1.4\n"); val offsets = mutableListOf<Int>()
        objs.forEachIndexed { i, body -> offsets.add(sb.length); sb.append("${i + 1} 0 obj\n$body\nendobj\n") }
        val xref = sb.length
        sb.append("xref\n0 ${objs.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { sb.append(it.toString().padStart(10, '0')).append(" 00000 n \n") }
        sb.append("trailer\n<< /Size ${objs.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF")
        return sb.toString().toByteArray(Charsets.US_ASCII)
    }

    @Test fun viewerScreenLoadsRealPdf() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val f = File(ctx.cacheDir, "viewer.pdf"); f.writeBytes(minimalPdf())
        var backed = false
        rule.setContent { DynamicTheme { SafePdfViewerScreen(Uri.fromFile(f)) { backed = true } } }
        // Document opens on IO via produceState; wait for composition to settle repeatedly
        rule.waitUntil(timeoutMillis = 10_000) {
            try { rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Search", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            catch (_: Throwable) { false }
        }
        rule.waitForIdle()
        assertTrue(!backed)
    }

    @Test fun viewerScreenErrorForNonPdf() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val f = File(ctx.cacheDir, "not.pdf"); f.writeText("<html>error</html>")
        rule.setContent { DynamicTheme { SafePdfViewerScreen(Uri.fromFile(f)) {} } }
        rule.waitUntil(timeoutMillis = 10_000) {
            try { rule.onAllNodes(androidx.compose.ui.test.hasText("This file isn't a PDF", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            catch (_: Throwable) { false }
        }
        rule.waitForIdle()
    }

    @Test fun warpQuadProducesTargetSize() {
        val src = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val out = warpQuadToBitmap(src, Quadrilateral.default(), 20, 10)
        assertEquals(20, out.width); assertEquals(10, out.height)
        assertEquals(Color.BLUE, out.getPixel(5, 5))
        val clamped = warpQuadToBitmap(src, Quadrilateral.default(), 0, 0)
        assertTrue(clamped.width >= 1 && clamped.height >= 1)
    }

    @Test fun savePdfToUriWithNoImagesAndWithImage() { runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        // A PNG source image on disk
        val imgFile = File(ctx.cacheDir, "page.png")
        imgFile.outputStream().use { Bitmap.createBitmap(20, 28, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val outFile = File(ctx.cacheDir, "out.pdf")
        val ok = savePdfToUri(ctx, listOf(CapturedImage(Uri.fromFile(imgFile))), Uri.fromFile(outFile))
        // Robolectric's PdfDocument cannot write a real PDF stream, so the exporter
        // must degrade to a clean false here rather than throwing (on a device it returns true).
        kotlin.test.assertFalse(ok, "Robolectric PdfDocument write should fail cleanly, got success")
        val bad = savePdfToUri(ctx, listOf(CapturedImage(Uri.parse("content://missing/1"))), Uri.fromFile(File(ctx.cacheDir, "out2.pdf")))
        kotlin.test.assertFalse(bad, "undecodable source yields no pages, so the write must fail cleanly")
        // Filters exercise applyScanFilter paths when decode succeeds
        for (filter in ScanFilter.entries) {
            savePdfToUri(ctx, listOf(CapturedImage(Uri.fromFile(imgFile), quadrilateral = Quadrilateral.default())), Uri.fromFile(File(ctx.cacheDir, "out-$filter.pdf")), filter)
        }
    } }
}
