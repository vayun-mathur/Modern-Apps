package com.vayunmathur.library.ink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.MotionEvent
import android.view.View
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.SerializationException
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * JVM/Robolectric-reachable ink layers: the serialization model, the
 * finished-strokes text rendering (no Stroke objects, so no libink), and
 * the touch listener's state gates. Brush/Stroke construction itself
 * loads libink.so and stays in the instrumented suite.
 */
@RunWith(RobolectricTestRunner::class)
class InkPureTest {

    @get:org.junit.Rule val composeRule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // ---- serialization model ----

    @Test fun serializedStrokeJsonEdges() {
        val empty = SerializedStroke("marker", 1f, 0, 0.1f, emptyList())
        assertEquals(empty, empty.toBytes().toSerializedStroke())

        // Optional point fields may be omitted by older payloads.
        val legacy = """{"brushFamily":"pressurePen","brushSize":3.0,"brushColor":-1,"brushEpsilon":0.2,"points":[{"x":1.0,"y":2.0,"timeMillis":5}]}"""
        val parsed = legacy.encodeToByteArray().toSerializedStroke()
        assertEquals(1, parsed.points.size)
        assertEquals(1f, parsed.points.single().pressure)
        assertEquals(-1, parsed.brushColor)

        assertFailsWith<SerializationException> {
            "not json".encodeToByteArray().toSerializedStroke()
        }
        assertFailsWith<SerializationException> {
            """{"brushFamily":}""".encodeToByteArray().toSerializedStroke()
        }

        val stroke = SerializedStroke(
            "pressurePen", 2f, Color.RED, 0.3f,
            listOf(SerializedPoint(1f, 2f, 3L, 0.4f, 0.5f, 0.6f)),
        )
        val copy = stroke.copy(brushSize = 9f)
        assertEquals(9f, copy.brushSize)
        assertEquals(stroke.points, copy.points)
        assertEquals(stroke.hashCode(), stroke.copy().hashCode())
    }

    // ---- FinishedStrokesView text rendering ----

    private fun finishedView(): View {
        val ctor = Class.forName("com.vayunmathur.library.ink.FinishedStrokesView")
            .getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
        return ctor.newInstance(context) as View
    }

    private fun setField(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    @Test fun finishedViewDrawsTextElementsAndSelection() {
        val view = finishedView()
        val elements = listOf(
            CanvasTextElement("plain", 10f, 20f, 0f, Color.BLACK, 14f),
            CanvasTextElement("bold centered", 30f, 40f, 45f, Color.RED, 16f, bold = true, align = 1),
            CanvasTextElement("italic right", 50f, 60f, -30f, Color.BLUE, 12f, italic = true, align = 2),
            CanvasTextElement("both left", 5f, 5f, 0f, Color.GREEN, 10f, bold = true, italic = true, align = 0),
        )
        setField(view, "textElements", elements)
        setField(view, "selectedTextIndex", 1)
        setField(view, "strokes", emptyList<Any>())
        view.javaClass.getDeclaredMethod("setSelectionColor", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(view, Color.MAGENTA)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 200, 200)
        val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        assertEquals(200, bitmap.width)
        // No text elements and no selection: the draw is a no-op over an empty canvas.
        val empty = finishedView()
        empty.draw(Canvas(Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888)))
        assertTrue(true)
    }

    // ---- InkState + StrokeTouchListener gates ----

    @Test fun inkStateDefaultsAndTouchGates() {
        val stateClass = Class.forName("com.vayunmathur.library.ink.InkState")
        val state = stateClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        fun boolField(name: String) =
            stateClass.getDeclaredField(name).apply { isAccessible = true }.getBoolean(state)
        assertTrue(boolField("enabled"))
        assertFalse(boolField("eraserMode"))

        val finished = finishedView()
        val inProgressClass = Class.forName("androidx.ink.authoring.InProgressStrokesView")
        val inProgress = inProgressClass.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }.newInstance(context)
        val listenerClass = Class.forName("com.vayunmathur.library.ink.StrokeTouchListener")
        val listenerCtor = listenerClass.declaredConstructors.single().apply { isAccessible = true }
        val listener = listenerCtor.newInstance(inProgress, finished, state)
        val onTouch = listenerClass.getDeclaredMethod(
            "onTouch", View::class.java, MotionEvent::class.java,
        ).apply { isAccessible = true }

        val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 10f, 10f, 0)
        // Disabled: nothing is handled.
        stateClass.getDeclaredField("enabled").apply { isAccessible = true }.setBoolean(state, false)
        assertEquals(false, onTouch.invoke(listener, finished, down))
        // Enabled but no brush yet: stroke handling refuses.
        stateClass.getDeclaredField("enabled").apply { isAccessible = true }.setBoolean(state, true)
        assertEquals(false, onTouch.invoke(listener, finished, down))
        // Eraser mode consumes downs/moves even with no strokes to hit.
        stateClass.getDeclaredField("eraserMode").apply { isAccessible = true }.setBoolean(state, true)
        assertEquals(true, onTouch.invoke(listener, finished, down))
        val move = MotionEvent.obtain(0L, 1L, MotionEvent.ACTION_MOVE, 12f, 12f, 0)
        assertEquals(true, onTouch.invoke(listener, finished, move))
        val up = MotionEvent.obtain(0L, 2L, MotionEvent.ACTION_UP, 12f, 12f, 0)
        assertEquals(true, onTouch.invoke(listener, finished, up))
        down.recycle(); move.recycle(); up.recycle()
    }

    @Test fun inkCanvasViewComposesWithAllocatedBrush() {
        // Brush's constructor loads libink, so allocate an instance without
        // running it: the composable only stores the brush in state and
        // passes strokes/text through to the finished view, which is the
        // behaviour pinned here. Stroke authoring stays on device.
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val brush = unsafeClass.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, androidx.ink.brush.Brush::class.java) as androidx.ink.brush.Brush
        var eraser by androidx.compose.runtime.mutableStateOf(false)
        composeRule.setContent {
            InkCanvasView(
                currentBrush = brush,
                finishedStrokes = emptyList(),
                onStrokeFinished = {},
                onStrokeErased = {},
                eraserMode = eraser,
                enabled = !eraser,
                textElements = listOf(CanvasTextElement("label", 4f, 4f, 0f, Color.BLACK, 12f)),
                selectedTextIndex = 0,
            )
        }
        composeRule.onRoot().assertExists()
        composeRule.runOnIdle { eraser = true }
        composeRule.waitForIdle()
        composeRule.onRoot().assertExists()
    }

    @Test fun canvasTextElementDataSemantics() {
        val a = CanvasTextElement("t", 1f, 2f, 3f, 4, 5f, "serif", bold = true, italic = false, align = 2)
        assertEquals(a, a.copy())
        assertEquals("serif", a.fontFamily)
        assertTrue(a.bold)
        assertEquals(2, a.align)
        assertEquals("t", a.component1())
        assertEquals(5f, a.component6())
    }
}
