package com.vayunmathur.photos.domain

import com.vayunmathur.library.ocr.OcrEngine
import com.vayunmathur.photos.data.OcrBox
import com.vayunmathur.photos.data.OcrLayout
import com.vayunmathur.photos.data.parseOcrLayout
import com.vayunmathur.photos.data.toJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Slice 1/3 pass 3: OCR layout persistence shape and engine-result mapping. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotosSlice1OcrPass3Test {

    @Test
    fun layoutTextJoinsBoxLines() {
        val layout = OcrLayout(100, 200, listOf(OcrBox("hello", 0, 0, 10, 10), OcrBox("world", 0, 20, 10, 30)))
        assertEquals("hello\nworld", layout.text)
        assertEquals("", OcrLayout(1, 1, emptyList()).text)
        assertEquals("solo", OcrLayout(1, 1, listOf(OcrBox("  solo  ", 0, 0, 1, 1))).text.trim())
    }

    @Test
    fun layoutJsonRoundTripAndRejection() {
        val layout = OcrLayout(
            640, 480,
            listOf(OcrBox("line one", 1, 2, 30, 12, quad = listOf(1f, 2f, 30f, 2f), vertical = true)),
        )
        assertEquals(layout, parseOcrLayout(layout.toJson()))
        assertNull(parseOcrLayout(null))
        assertNull(parseOcrLayout(""))
        assertNull(parseOcrLayout("   "))
        assertNull(parseOcrLayout("{not json"))
        // Non-positive dimensions are treated as an unreadable older format.
        assertNull(parseOcrLayout(OcrLayout(0, 480, emptyList()).toJson()))
        assertNull(parseOcrLayout(OcrLayout(640, -1, emptyList()).toJson()))
        // Unknown keys are ignored (forward compatibility).
        val withExtra = layout.toJson().replaceFirst("{", "{\"future\":1,")
        assertEquals(layout, parseOcrLayout(withExtra))
    }

    @Test
    fun engineResultMapsToLayoutWithQuad() {
        val box = OcrEngine.TextBox(
            text = "Title", left = 10, top = 20, right = 110, bottom = 40, vertical = false,
        )
        val result = OcrEngine.OcrResult("Title", listOf(box))
        val layout = result.toLayout(200, 100)
        assertEquals(200, layout.w)
        assertEquals(100, layout.h)
        assertEquals(1, layout.boxes.size)
        val mapped = layout.boxes[0]
        assertEquals("Title", mapped.text)
        assertEquals(10, mapped.left)
        assertEquals(40, mapped.bottom)
        // Default corners flatten to [x0,y0, x1,y0, x1,y1, x0,y1].
        assertEquals(listOf(10f, 20f, 110f, 20f, 110f, 40f, 10f, 40f), mapped.quad)
        assertTrue(kotlin.math.abs(box.centerX - 60f) < 0.01f)
        assertTrue(kotlin.math.abs(box.centerY - 30f) < 0.01f)
        assertEquals(layout, parseOcrLayout(layout.toJson()))
    }
}
