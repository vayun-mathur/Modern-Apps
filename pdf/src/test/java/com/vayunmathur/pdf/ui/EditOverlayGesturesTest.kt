package com.vayunmathur.pdf.ui

import android.content.Context
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.pdf.util.SafeAnnotation
import com.vayunmathur.pdf.util.SafePdfDocument
import com.vayunmathur.pdf.util.addHighlight
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditOverlayGesturesTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

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

    private fun openDoc(): SafePdfDocument = runBlocking {
        val f = File(ctx.cacheDir, "gest-${System.nanoTime()}.pdf"); f.writeBytes(minimalPdf())
        val doc = SafePdfDocument.open(ctx, Uri.fromFile(f))!!
        doc
    }

    private class Callbacks {
        var selected: Long? = -99L
        var created: Long = -1L
        var edited: Int = 0
        var movedId: Long? = null
        var startText: TextSession? = null
        var imageReq: Offset? = null
        var noteReq: Offset? = null
        var callout: Pair<Offset, Offset>? = null
        var polyPoint: Offset? = null
    }

    private class Harness(tool: EditTool, shape: ShapeKind = ShapeKind.RECT_OUTLINE, markup: MarkupKind = MarkupKind.HIGHLIGHT, annotations: List<SafeAnnotation> = emptyList(), selected: Long? = null) {
        var tool by mutableStateOf(tool)
        var shape by mutableStateOf(shape)
        var markup by mutableStateOf(markup)
        var annotations by mutableStateOf(annotations)
        var selected by mutableStateOf(selected)
    }

    private fun render(doc: SafePdfDocument, h: Harness, cb: Callbacks) {
        rule.setContent {
            DynamicTheme {
                Box(Modifier.size(300.dp)) {
                    EditOverlay(
                        annotations = h.annotations, selected = h.selected, tool = h.tool,
                        shape = h.shape, markup = h.markup, color = Color.Red, strokeWidth = 2f,
                        ch = 800f, scale = 1f, toPage = { it },
                        document = doc, index = 0, scope = CoroutineScope(Dispatchers.Default),
                        onSelect = { cb.selected = it }, onEdited = { cb.edited++ }, onCreated = { cb.created = it },
                        onMoved = { id, _, _ -> cb.movedId = id }, onStartText = { cb.startText = it },
                        onRequestImage = { cb.imageReq = it }, onRequestNote = { cb.noteReq = it },
                        onRequestCallout = { a, b -> cb.callout = a to b },
                        polyDraft = null, onAddPolyPoint = { cb.polyPoint = it },
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun switch(h: Harness, tool: EditTool? = null, shape: ShapeKind? = null, markup: MarkupKind? = null, annotations: List<SafeAnnotation>? = null, selected: Long? = -999L) {
        rule.runOnUiThread {
            if (tool != null) h.tool = tool
            if (shape != null) h.shape = shape
            if (markup != null) h.markup = markup
            if (annotations != null) h.annotations = annotations
            if (selected != -999L) h.selected = selected
        }
        rule.waitForIdle()
    }

    private suspend fun awaitAnnots(doc: SafePdfDocument, atLeast: Int) =
        withTimeout(8000) { while (doc.annotations(0).size < atLeast) delay(25) }

    private fun drag(from: Offset, to: Offset) {
        rule.onRoot().performTouchInput { swipe(from, to, 250) }
        rule.waitForIdle()
    }

    private fun tap(at: Offset) {
        rule.onRoot().performTouchInput { click(at) }
        rule.waitForIdle()
    }

    @Test fun highlightDragCreatesAnnotation() { runBlocking {
        val doc = openDoc(); val cb = Callbacks()
        try {
            render(doc, Harness(EditTool.HIGHLIGHT), cb)
            drag(Offset(60f, 60f), Offset(220f, 120f))
            awaitAnnots(doc, 1)
            assertTrue(cb.created != 0L && cb.created != -1L, "created=${cb.created}")
        } finally { doc.close() }
    } }

    @Test fun markupStrikeoutDragCreatesAnnotation() { runBlocking {
        val doc = openDoc(); val cb = Callbacks()
        try {
            render(doc, Harness(EditTool.MARKUP, markup = MarkupKind.STRIKEOUT), cb)
            drag(Offset(60f, 60f), Offset(220f, 120f))
            awaitAnnots(doc, 1); assertTrue(cb.created != 0L)
        } finally { doc.close() }
    } }

    @Test fun shapeDragsCreateRectOvalAndPolygon() { runBlocking {
        val doc = openDoc(); val cb = Callbacks()
        try {
            val h = Harness(EditTool.SHAPE, shape = ShapeKind.RECT_FILL); render(doc, h, cb)
            drag(Offset(50f, 50f), Offset(200f, 150f)); awaitAnnots(doc, 1)
            switch(h, shape = ShapeKind.OVAL_OUTLINE)
            drag(Offset(50f, 50f), Offset(200f, 150f)); awaitAnnots(doc, 2)
            switch(h, shape = ShapeKind.TRIANGLE_FILL)
            drag(Offset(50f, 50f), Offset(200f, 150f)); awaitAnnots(doc, 3)
        } finally { doc.close() }
    } }

    @Test fun lineRedactAndDrawDragsCreateAnnotations() { runBlocking {
        val doc = openDoc(); val cb = Callbacks()
        try {
            val h = Harness(EditTool.LINE); render(doc, h, cb)
            drag(Offset(50f, 80f), Offset(250f, 80f)); awaitAnnots(doc, 1)
            switch(h, tool = EditTool.REDACT)
            drag(Offset(50f, 50f), Offset(200f, 150f)); awaitAnnots(doc, 2)
            switch(h, tool = EditTool.DRAW)
            drag(Offset(50f, 50f), Offset(250f, 150f)); awaitAnnots(doc, 3)
        } finally { doc.close() }
    } }

    @Test fun calloutDragRequestsCallout() {
        val doc = openDoc(); val cb = Callbacks()
        try {
            render(doc, Harness(EditTool.CALLOUT), cb)
            drag(Offset(50f, 50f), Offset(200f, 150f))
            assertNotNull(cb.callout)
            assertEquals(Offset(50f, 50f), cb.callout!!.first)
        } finally { doc.close() }
    }

    @Test fun tapToolsRequestTheirActions() {
        val doc = openDoc(); val cb = Callbacks()
        try {
            val h = Harness(EditTool.TEXT); render(doc, h, cb); tap(Offset(80f, 80f))
            assertNotNull(cb.startText); assertEquals("Text", cb.startText!!.value.text); assertEquals(null, cb.startText!!.annotId)
            switch(h, tool = EditTool.IMAGE); tap(Offset(90f, 90f)); assertEquals(Offset(90f, 90f), cb.imageReq)
            switch(h, tool = EditTool.NOTE); tap(Offset(70f, 70f)); assertEquals(Offset(70f, 70f), cb.noteReq)
            switch(h, tool = EditTool.POLYLINE); tap(Offset(60f, 60f)); assertEquals(Offset(60f, 60f), cb.polyPoint)
            switch(h, tool = EditTool.BEZIER); tap(Offset(55f, 55f)); assertEquals(Offset(55f, 55f), cb.polyPoint)
        } finally { doc.close() }
    }

    @Test fun selectTapSelectsFreeTextEditAndEmptyDeselects() {
        val doc = openDoc(); val cb = Callbacks()
        val annots = listOf(
            SafeAnnotation(7L, 2, 50f, 50f, 250f, 150f, 0xFF000000.toInt(), "box"),
            SafeAnnotation(8L, 1, 160f, 160f, 280f, 195f, 0xFF000000.toInt(), "editable"),
        )
        try {
            val h = Harness(EditTool.SELECT, annotations = annots, selected = null); render(doc, h, cb)
            tap(Offset(100f, 100f)); assertEquals(7L, cb.selected)
            // Tap already-selected FreeText (subtype 1) starts a text edit
            switch(h, selected = 8L)
            tap(Offset(200f, 175f))
            assertNotNull(cb.startText); assertEquals(8L, cb.startText!!.annotId); assertEquals("editable", cb.startText!!.value.text)
            // Tap empty space deselects (the harness overlay occupies the 300dp box at the origin;
            // use a point inside it with no annotation)
            switch(h, selected = 7L)
            tap(Offset(20f, 280f)); assertEquals(null, cb.selected)
        } finally { doc.close() }
    }

    @Test fun selectDragMovesAnnotation() { runBlocking {
        val doc = openDoc(); val cb = Callbacks()
        try {
            val id = doc.addHighlight(0, 50f, 500f, 250f, 560f, 0xFFFFFF00.toInt())
            assertTrue(id != 0L)
            val id2 = doc.addHighlight(0, 50f, 50f, 250f, 150f, 0xFFFFFF00.toInt())
            val annots2 = doc.annotations(0)
            render(doc, Harness(EditTool.SELECT, annotations = annots2, selected = null), cb)
            drag(Offset(100f, 100f), Offset(160f, 130f))
            withTimeout(8000) { while (cb.movedId == null) delay(25) }
            assertTrue(cb.movedId == id || cb.movedId == id2, "moved=${cb.movedId} id=$id id2=$id2")
        } finally { doc.close() }
    } }
}
