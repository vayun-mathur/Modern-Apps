package com.vayunmathur.library.ocr

import com.vayunmathur.library.ml.RecognizedLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The native-line -> public-result mapping, exercised without a Vulkan device. */
class OcrMappingTest {

    private fun line(
        text: String,
        corners: List<Pair<Float, Float>> = listOf(10.2f to 20.6f, 110.4f to 20.6f, 110.4f to 60.2f, 10.2f to 60.2f),
        vertical: Boolean = false,
    ) = RecognizedLine(text = text, confidence = 0.9f, corners = corners, vertical = vertical)

    @Test
    fun boundsRoundOutwardsAndTextIsTrimmed() {
        val result = OcrEngine.linesToResult(listOf(line("  hello world  ")))
        assertEquals("hello world", result.text)
        val box = result.boxes.single()
        // floor on min edges, ceil on max edges: the rect covers the glyphs.
        assertEquals(10, box.left)
        assertEquals(20, box.top)
        assertEquals(111, box.right)
        assertEquals(61, box.bottom)
        assertEquals(
            listOf(
                OcrEngine.Corner(10.2f, 20.6f),
                OcrEngine.Corner(110.4f, 20.6f),
                OcrEngine.Corner(110.4f, 60.2f),
                OcrEngine.Corner(10.2f, 60.2f),
            ),
            box.corners,
        )
    }

    @Test
    fun rotatedQuadBoundsComeFromCornerExtremes() {
        val rotated = line(
            "tilt",
            corners = listOf(50f to 0f, 100f to 50f, 50f to 100f, 0f to 50f),
            vertical = true,
        )
        val box = OcrEngine.linesToResult(listOf(rotated)).boxes.single()
        assertEquals(0, box.left)
        assertEquals(0, box.top)
        assertEquals(100, box.right)
        assertEquals(100, box.bottom)
        assertTrue(box.vertical)
        assertEquals(50f, box.centerX)
        assertEquals(50f, box.centerY)
    }

    @Test
    fun blankLinesAndDegenerateBoxesAreDropped() {
        val result = OcrEngine.linesToResult(
            listOf(
                line(""),
                line("   "),
                // Zero-width quad: every x equal, so right == left even after rounding out.
                line("thin", corners = listOf(10f to 0f, 10f to 0f, 10f to 9f, 10f to 9f)),
                // Zero-height quad.
                line("flat", corners = listOf(0f to 7f, 9f to 7f, 9f to 7f, 0f to 7f)),
                line("kept"),
            ),
        )
        assertEquals(listOf("kept"), result.boxes.map { it.text })
        assertEquals("kept", result.text)
    }

    @Test
    fun multipleLinesJoinWithNewlinesInOrder() {
        val second = line("second", corners = listOf(0f to 100f, 50f to 100f, 50f to 120f, 0f to 120f))
        val result = OcrEngine.linesToResult(listOf(line("first"), second))
        assertEquals("first\nsecond", result.text)
        assertEquals(2, result.boxes.size)
    }

    @Test
    fun noLinesMeansEmptyResult() {
        assertEquals(OcrEngine.OcrResult("", emptyList()), OcrEngine.linesToResult(emptyList()))
        assertEquals(
            OcrEngine.OcrResult("", emptyList()),
            OcrEngine.linesToResult(listOf(line("  "))),
        )
    }

    @Test
    fun negativeCoordinatesRoundOutwardsToo() {
        val box = OcrEngine.linesToResult(
            listOf(line("edge", corners = listOf(-5.5f to -2.5f, 5.5f to -2.5f, 5.5f to 2.5f, -5.5f to 2.5f))),
        ).boxes.single()
        assertEquals(-6, box.left)
        assertEquals(-3, box.top)
        assertEquals(6, box.right)
        assertEquals(3, box.bottom)
    }
}
