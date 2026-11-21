package com.vayunmathur.pdf.platform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.geometry.Rect
import com.vayunmathur.pdf.data.CapturedImage
import com.vayunmathur.pdf.data.Quadrilateral
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Exporter output is unchanged by drawing the warp + scan filter straight
 * onto the page canvas: each new direct draw is compared pixel-for-pixel
 * with the old two-step reference (warp into an intermediate bitmap, then
 * a filtered copy of the finished page onto itself), re-created here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PdfExporterZeroCopyTest {

    private fun patternedSrc(): Bitmap {
        val bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        for (y in 0 until 4) for (x in 0 until 4) {
            bmp.setPixel(x, y, Color.argb(255, 20 + x * 50, 30 + y * 50, (x + y) * 25))
        }
        return bmp
    }

    private fun pixels(bmp: Bitmap): List<Int> =
        (0 until bmp.height).flatMap { y -> (0 until bmp.width).map { x -> bmp.getPixel(x, y) } }

    private fun whitePage(w: Int, h: Int): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }

    /** The pre-fix filter step: copy the page, draw the copy back through the paint. */
    private fun oldApplyFilter(page: Bitmap, filter: ScanFilter) {
        val paint = scanFilterPaint(filter) ?: return
        val copy = page.copy(Bitmap.Config.ARGB_8888, false)
        Canvas(page).drawBitmap(copy, 0f, 0f, paint)
        copy.recycle()
    }

    @Test fun scanFilterPaintIsNullOnlyForNone() {
        assertNull(scanFilterPaint(ScanFilter.NONE))
        for (f in ScanFilter.entries) {
            if (f != ScanFilter.NONE) kotlin.test.assertNotNull(scanFilterPaint(f))
        }
    }

    @Test fun directWarpWithFilterMatchesWarpThenFilterReference() {
        val quad = Quadrilateral.default()
        for (filter in ScanFilter.entries) {
            val src = patternedSrc()
            // Old: warp to an intermediate, blit it, then filter a page copy.
            val warped = warpQuadToBitmap(src, quad, 4, 4)
            val reference = whitePage(4, 4)
            Canvas(reference).drawBitmap(warped, 0f, 0f, null)
            warped.recycle()
            oldApplyFilter(reference, filter)

            // New: one draw of the source through warp matrix + filter paint.
            val direct = whitePage(4, 4)
            drawCropped(
                src,
                CapturedImage(Uri.parse("content://x/1"), quadrilateral = quad),
                Canvas(direct), 4, 4, 1f, scanFilterPaint(filter),
            )
            assertEquals(pixels(reference), pixels(direct), "filter $filter changed exporter pixels")
        }
    }

    @Test fun directCropWithFilterMatchesCropThenFilterReference() {
        val crop = Rect(0f, 0f, 0.5f, 1f)
        val filter = ScanFilter.CONTRAST
        val src = patternedSrc()
        val reference = whitePage(2, 4)
        val srcRect = android.graphics.Rect(0, 0, 2, 4)
        Canvas(reference).drawBitmap(src, srcRect, android.graphics.Rect(0, 0, 2, 4), null)
        oldApplyFilter(reference, filter)

        val direct = whitePage(2, 4)
        drawCropped(
            src,
            CapturedImage(Uri.parse("content://x/1"), cropRect = crop),
            Canvas(direct), 2, 4, 1f, scanFilterPaint(filter),
        )
        assertEquals(pixels(reference), pixels(direct))
    }
}
