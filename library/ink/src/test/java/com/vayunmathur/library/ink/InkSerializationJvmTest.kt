package com.vayunmathur.library.ink

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pure-Kotlin parts of the serialization layer. Brush/Stroke need libink.so, so the
 *  round-trips through them live in the instrumented suite. */
class InkSerializationJvmTest {

    @Test fun bytesRoundTripPreservesEveryField() {
        val original = SerializedStroke(
            brushFamily = "highlighter",
            brushSize = 4.5f,
            brushColor = 0xFF336699.toInt(),
            brushEpsilon = 0.25f,
            points = listOf(
                SerializedPoint(1f, 2f, 0L, pressure = 0.5f, tiltRadians = 0.1f, orientationRadians = 0.2f),
                SerializedPoint(11f, 22f, 16L),
            ),
        )
        assertEquals(original, original.toBytes().toSerializedStroke())
    }

    @Test fun unknownKeysFromNewerPayloadsAreIgnored() {
        val payload = """{"brushFamily":"marker","brushSize":2.0,"brushColor":123,"brushEpsilon":0.5,"points":[],"future":true}"""
        val parsed = payload.encodeToByteArray().toSerializedStroke()
        assertEquals("marker", parsed.brushFamily)
        assertEquals(2f, parsed.brushSize)
        assertTrue(parsed.points.isEmpty())
    }

    @Test fun pointDefaults() {
        val p = SerializedPoint(x = 3f, y = 4f, timeMillis = 9L)
        assertEquals(1f, p.pressure)
        assertEquals(0f, p.tiltRadians)
        assertEquals(0f, p.orientationRadians)
        assertEquals(p, p.copy())
    }

    @Test fun textElementDefaults() {
        val elem = CanvasTextElement(text = "hi", x = 1f, y = 2f, rotation = 0f, color = 7, fontSize = 12f)
        assertEquals("sans-serif", elem.fontFamily)
        assertEquals(false, elem.bold)
        assertEquals(false, elem.italic)
        assertEquals(0, elem.align)
    }
}
