package com.vayunmathur.library.ocr

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OcrEngineTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    // ---- TextBox / Corner / OcrResult value semantics ----

    @Test
    fun defaultCornersAreTheAxisAlignedQuadInReadingOrder() {
        val box = OcrEngine.TextBox(text = "hello", left = 10, top = 20, right = 110, bottom = 60)
        assertEquals(
            listOf(
                OcrEngine.Corner(10f, 20f),
                OcrEngine.Corner(110f, 20f),
                OcrEngine.Corner(110f, 60f),
                OcrEngine.Corner(10f, 60f),
            ),
            box.corners,
        )
        assertFalse(box.vertical)
    }

    @Test
    fun centersAreQuadAveragesNotBoundsMidpoints() {
        // A tilted quad whose corner average differs from the rect midpoint.
        val box = OcrEngine.TextBox(
            text = "tilt", left = 0, top = 0, right = 100, bottom = 40,
            corners = listOf(
                OcrEngine.Corner(0f, 10f),
                OcrEngine.Corner(90f, 0f),
                OcrEngine.Corner(100f, 30f),
                OcrEngine.Corner(10f, 40f),
            ),
            vertical = true,
        )
        assertEquals(50f, box.centerX)
        assertEquals(20f, box.centerY)
        assertTrue(box.vertical)
    }

    @Test
    fun textBoxAndCornerCompareByValue() {
        val a = OcrEngine.TextBox("same", 1, 2, 3, 4)
        assertEquals(a, a.copy())
        assertFalse(a == a.copy(text = "different"))
        assertFalse(a == a.copy(left = 9))
        assertEquals(OcrEngine.Corner(1.5f, -2f), OcrEngine.Corner(1.5f, -2f))
        assertFalse(OcrEngine.Corner(1f, 2f) == OcrEngine.Corner(2f, 1f))
        assertEquals(a.hashCode(), a.copy().hashCode())
    }

    @Test
    fun ocrResultCarriesTextAndBoxesTogether() {
        val box = OcrEngine.TextBox("line one", 0, 0, 50, 10)
        val result = OcrEngine.OcrResult("line one", listOf(box))
        assertEquals("line one", result.text)
        assertEquals(listOf(box), result.boxes)
        assertEquals(OcrEngine.OcrResult("", emptyList()), OcrEngine.OcrResult("", emptyList()))
        assertFalse(result == result.copy(text = "other"))
    }

    // ---- Inert-engine behaviour (no Vulkan/model runner on this host) ----

    @Test
    fun engineIsInertWithoutNativeRuntime() = runBlocking<Unit> {
        val engine = OcrEngine(context)
        assertFalse(engine.isAvailable())
        // Availability is latched: a second ask does not retry the native load.
        assertFalse(engine.isAvailable())
        engine.close()
    }

    @Test
    fun recognizeReturnsEmptyAndLeavesBitmapAlone() = runBlocking<Unit> {
        val engine = OcrEngine(context)
        val bitmap = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
        assertEquals("", engine.recognize(bitmap))
        val detailed = engine.recognizeDetailed(bitmap)
        assertEquals("", detailed.text)
        assertTrue(detailed.boxes.isEmpty())
        assertFalse(bitmap.isRecycled)
        bitmap.recycle()
        engine.close()
    }

    @Test
    fun closeIsIdempotentAndEngineStaysInertAfterClose() = runBlocking<Unit> {
        val engine = OcrEngine(context)
        engine.close()
        engine.close()
        assertFalse(engine.isAvailable())
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        assertEquals(OcrEngine.OcrResult("", emptyList()), engine.recognizeDetailed(bitmap))
        bitmap.recycle()
        engine.close()
    }

    @Test
    fun concurrentCallsAreSerializedAndAllInert() = runBlocking<Unit> {
        val engine = OcrEngine(context)
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val results = (1..8).map { async { engine.recognize(bitmap) } }.awaitAll()
        assertEquals(List(8) { "" }, results)
        assertFalse(bitmap.isRecycled)
        bitmap.recycle()
        engine.close()
    }

    @Test
    fun separateEnginesDoNotShareInitState() = runBlocking<Unit> {
        val first = OcrEngine(context)
        val second = OcrEngine(context)
        assertFalse(first.isAvailable())
        first.close()
        // Closing one engine must not poison the other's init latch.
        assertFalse(second.isAvailable())
        second.close()
    }
}
