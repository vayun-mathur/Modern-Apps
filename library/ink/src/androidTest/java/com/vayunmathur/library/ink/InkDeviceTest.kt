package com.vayunmathur.library.ink

import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.brush.Brush
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.Stroke
import androidx.test.core.app.ActivityScenario
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** On-device: Brush/Stroke construction loads libink.so, which has no host build. */
class InkDeviceTest {

    private val brush: Brush = Brush.createWithColorIntArgb(
        family = StockBrushes.pressurePen(),
        colorIntArgb = 0xFF000000.toInt(),
        size = 5f,
        epsilon = 0.1f,
    )

    private fun sample(family: String = "marker") = SerializedStroke(
        brushFamily = family,
        brushSize = 4.5f,
        brushColor = 0xFF336699.toInt(),
        brushEpsilon = 0.25f,
        points = listOf(
            SerializedPoint(1f, 2f, 0L, pressure = 0.5f, tiltRadians = 0.1f, orientationRadians = 0.2f),
            SerializedPoint(11f, 22f, 16L, pressure = 0.7f, tiltRadians = 0.3f, orientationRadians = 0.4f),
            SerializedPoint(21f, 12f, 32L),
        ),
    )

    @Test fun brushFamiliesAndStrokeRoundTrip() {
        assertEquals("pressurePen", StockBrushes.pressurePen().toName())
        assertEquals("highlighter", StockBrushes.highlighter().toName())
        assertEquals("marker", StockBrushes.marker().toName())
        assertEquals(StockBrushes.marker(), "marker".toBrushFamily())
        assertEquals(StockBrushes.pressurePen(), "airbrush".toBrushFamily())

        val original = sample()
        val stroke = original.deserialize()
        assertEquals(original.brushSize, stroke.brush.size)
        assertEquals(original.brushColor, stroke.brush.colorIntArgb)
        val again = stroke.serialize()
        assertEquals("marker", again.brushFamily)
        assertEquals(original.points.size, again.points.size)
        for ((e, a) in original.points.zip(again.points)) {
            assertEquals(e.x, a.x, 0.001f)
            assertEquals(e.y, a.y, 0.001f)
            assertEquals(e.timeMillis, a.timeMillis)
            assertEquals(e.pressure, a.pressure, 0.001f)
        }
        assertTrue(sample().copy(points = emptyList()).deserialize().serialize().points.isEmpty())
    }

    @Test fun translateShiftsPoints() {
        val moved = sample().deserialize().translate(10f, -5f).serialize()
        for ((e, a) in sample().points.zip(moved.points)) {
            assertEquals(e.x + 10f, a.x, 0.001f)
            assertEquals(e.y - 5f, a.y, 0.001f)
            assertEquals(e.timeMillis, a.timeMillis)
        }
    }

    private fun findViews(root: View, simpleName: String): List<View> {
        val out = mutableListOf<View>()
        fun walk(v: View) {
            if (v.javaClass.simpleName == simpleName) out += v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    @Test fun canvasStrokeEraserAndDisabled() {
        val existing = sample().deserialize().translate(100f, 100f)
        val finished = mutableListOf<Stroke>()
        val erased = mutableListOf<Stroke>()
        val finishLatch = CountDownLatch(1)
        val eraser = mutableStateOf(false)
        val enabled = mutableStateOf(true)
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        lateinit var inProgress: InProgressStrokesView
        scenario.onActivity { activity ->
            activity.setContent {
                InkCanvasView(
                    currentBrush = brush,
                    finishedStrokes = listOf(existing),
                    onStrokeFinished = { finished += it; finishLatch.countDown() },
                    onStrokeErased = { erased += it },
                    eraserMode = eraser.value,
                    enabled = enabled.value,
                    textElements = listOf(
                        CanvasTextElement("plain", 0f, 0f, 0f, 0xFF000000.toInt(), 14f),
                        CanvasTextElement("bi", 0f, 30f, 15f, 0xFFFF0000.toInt(), 14f, bold = true, italic = true, align = 1),
                        CanvasTextElement("b", 0f, 60f, 0f, 0xFF00FF00.toInt(), 14f, bold = true, align = 2),
                        CanvasTextElement("i", 0f, 90f, 0f, 0xFF0000FF.toInt(), 14f, italic = true, align = 99),
                    ),
                    selectedStrokeIndex = 0,
                    selectedTextIndex = 1,
                    selectionColor = Color.Red,
                )
            }
        }
        Thread.sleep(1_000)
        scenario.onActivity { activity ->
            val finishedView = findViews(activity.window.decorView, "FinishedStrokesView").first()
            finishedView.draw(Canvas())
            inProgress = findViews(activity.window.decorView, "InProgressStrokesView")
                .first() as InProgressStrokesView
            fun touch(action: Int, x: Float, y: Float): Boolean {
                val ev = MotionEvent.obtain(100L, 200L, action, x, y, 0)
                return try { inProgress.dispatchTouchEvent(ev) } finally { ev.recycle() }
            }
            assertTrue(touch(MotionEvent.ACTION_DOWN, 20f, 20f))
            assertTrue(touch(MotionEvent.ACTION_MOVE, 40f, 30f))
            assertTrue(touch(MotionEvent.ACTION_UP, 60f, 30f))
        }
        assertTrue("stroke never finished", finishLatch.await(10, TimeUnit.SECONDS))
        scenario.onActivity {
            fun touch(action: Int, x: Float, y: Float): Boolean {
                val ev = MotionEvent.obtain(100L, 200L, action, x, y, 0)
                return try { inProgress.dispatchTouchEvent(ev) } finally { ev.recycle() }
            }
            assertTrue(touch(MotionEvent.ACTION_DOWN, 20f, 60f))
            assertTrue(touch(MotionEvent.ACTION_CANCEL, 20f, 60f))
            eraser.value = true
        }
        Thread.sleep(500)
        scenario.onActivity {
            fun touch(action: Int, x: Float, y: Float): Boolean {
                val ev = MotionEvent.obtain(100L, 200L, action, x, y, 0)
                return try { inProgress.dispatchTouchEvent(ev) } finally { ev.recycle() }
            }
            // Existing stroke spans roughly (100,100)-(180,140) after translation.
            assertTrue(touch(MotionEvent.ACTION_DOWN, 130f, 120f))
            assertEquals(1, erased.size)
            assertSame(existing, erased.first())
            assertTrue(touch(MotionEvent.ACTION_MOVE, 1500f, 1500f))
            assertEquals(1, erased.size)
            enabled.value = false
        }
        Thread.sleep(500)
        scenario.onActivity {
            val ev = MotionEvent.obtain(100L, 200L, MotionEvent.ACTION_DOWN, 130f, 120f, 0)
            assertFalse(try { inProgress.dispatchTouchEvent(ev) } finally { ev.recycle() })
        }
        scenario.close()
    }
}
