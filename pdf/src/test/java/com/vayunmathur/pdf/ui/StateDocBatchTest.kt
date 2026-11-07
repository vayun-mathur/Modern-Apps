package com.vayunmathur.pdf.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.pdf.util.SafePdfDocument
import com.vayunmathur.pdf.util.addImageStamp
import com.vayunmathur.pdf.util.addText
import com.vayunmathur.pdf.util.moveAnnotation
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
class StateDocBatchTest {
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
        val f = File(ctx.cacheDir, "state-${System.nanoTime()}.pdf"); f.writeBytes(minimalPdf())
        SafePdfDocument.open(ctx, Uri.fromFile(f))!!
    }

    private suspend fun await(desc: String, cond: suspend () -> Boolean) =
        withTimeout(8000) { while (!cond()) delay(25) }

    @Test fun selectionTextCases() {
        val g = listOf(selGlyph("h", 0f, 0f, 10f, 10f), selGlyph("i", 10f, 0f, 20f, 10f), selGlyph("!", 20f, 0f, 30f, 10f))
        assertEquals("hi!", selectionText(g, 0..2))
        assertEquals("i", selectionText(g, 1..1))
        assertEquals("", selectionText(g, 5..9))
        assertEquals("", selectionText(emptyList(), 0..0))
        assertEquals("", selectionText(g, 2..1))
    }

    @Test fun allKindIconsCompose() {
        rule.setContent {
            DynamicTheme {
                androidx.compose.foundation.layout.Column {
                    for (k in MarkupKind.entries) { val icon = k.icon(); icon(Color.Red) }
                    for (k in ShapeKind.entries) { val icon = k.icon(); icon(Color.Blue) }
                }
            }
        }
        rule.waitForIdle()
    }

    @Test fun markupMenuSelects() {
        var picked: MarkupKind? = null
        rule.setContent { DynamicTheme { MarkupMenuButton(MarkupKind.HIGHLIGHT, true) { picked = it } } }
        // IconButton is the only click target; click via semantics on the button node
        rule.onAllNodes(androidx.compose.ui.test.hasClickAction()).onFirst().performClick()
        rule.onNodeWithText("Squiggly").assertIsDisplayed().performClick()
        assertEquals(MarkupKind.SQUIGGLY, picked)
    }

    @Test fun shapeMenuSelects() {
        var picked: ShapeKind? = null
        rule.setContent { DynamicTheme { ShapeMenuButton(ShapeKind.RECT_OUTLINE, false) { picked = it } } }
        rule.onAllNodes(androidx.compose.ui.test.hasClickAction()).onFirst().performClick()
        rule.onNodeWithText("Ellipse (filled)").assertIsDisplayed().performClick()
        assertEquals(ShapeKind.OVAL_FILL, picked)
    }

    @Test fun linesMenuSelects() {
        var picked: EditTool? = null
        rule.setContent { DynamicTheme { LinesMenuButton(EditTool.SELECT) { picked = it } } }
        rule.onAllNodes(androidx.compose.ui.test.hasClickAction()).onFirst().performClick()
        rule.onNodeWithText("Bézier curve").assertIsDisplayed().performClick()
        assertEquals(EditTool.BEZIER, picked)
    }

    @Test fun readAsJpegAndImageStampAndOnImagePicked() { runBlocking {
        val doc = openDoc()
        try {
            val png = File(ctx.cacheDir, "stamp.png")
            png.outputStream().use { Bitmap.createBitmap(30, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val jpeg = readAsJpeg(ctx, Uri.fromFile(png))
            assertNotNull(jpeg); assertEquals(30, jpeg.width); assertEquals(20, jpeg.height); assertTrue(jpeg.bytes.isNotEmpty())
            assertNull(readAsJpeg(ctx, Uri.parse("content://missing/img")))
            val stampId = doc.addImageStamp(0, 100f, 500f, 250f, 600f, jpeg.width, jpeg.height, jpeg.bytes)
            assertTrue(stampId != 0L, "image stamp id=$stampId")

            val state = SafePdfViewerState(CoroutineScope(Dispatchers.Default))
            state.imageTarget = 0 to Offset(120f, 650f)
            val before = doc.annotations(0).size
            state.onImagePicked(doc, ctx, Uri.fromFile(png))
            await("stamp annotation") { doc.annotations(0).size > before }
            assertNull(state.imageTarget)
        } finally { doc.close() }
    } }

    @Test fun commitPolyUndoMovedRemovedAndTextEditBranches() { runBlocking {
        val doc = openDoc()
        val state = SafePdfViewerState(CoroutineScope(Dispatchers.Default))
        try {
            // commitPoly straight + bezier
            state.polyDraft = PolyDraft(0, listOf(Offset(80f, 600f), Offset(150f, 650f), Offset(220f, 600f)), false)
            state.commitPoly(doc); assertNull(state.polyDraft)
            await("poly annot") { doc.annotations(0).isNotEmpty() }
            state.polyDraft = PolyDraft(0, listOf(Offset(80f, 500f), Offset(150f, 550f), Offset(220f, 500f)), true)
            state.commitPoly(doc)
            await("bezier annot") { doc.annotations(0).size >= 2 }

            // createCallout with doc
            state.createCallout(doc, 0, Offset(60f, 60f), Offset(160f, 160f), "callout text")
            await("callout annot") { doc.annotations(0).size >= 3 }

            // MOVED undo/redo
            val id = doc.annotations(0).first().id
            val before = doc.annotations(0).first { it.id == id }
            state.registerMoved(0, id, listOf(before.x0, before.y0, before.x1, before.y1), listOf(before.x0 + 20f, before.y0, before.x1 + 20f, before.y1))
            doc.moveAnnotation(0, id, before.x0 + 20f, before.y0, before.x1 + 20f, before.y1)
            state.undo(doc)
            await("undo moved") { state.redoStack.isNotEmpty() }
            state.redo(doc)
            await("redo moved") { state.undoStack.isNotEmpty() }

            // Text edit existing + delete-on-empty
            val textId = doc.addText(0, 100f, 300f, 300f, 330f, 0xFF000000.toInt(), 12f, "old")
            assertTrue(textId != 0L)
            state.textSession = TextSession(0, Offset(100f, 330f), 12f, 0, textId, androidx.compose.ui.text.input.TextFieldValue("new text"))
            state.commitTextAndClear(doc)
            delay(300)
            state.textSession = TextSession(0, Offset(100f, 330f), 12f, 0, textId, androidx.compose.ui.text.input.TextFieldValue(""))
            state.commitTextAndClear(doc)
            await("text deleted") { doc.annotations(0).none { it.id == textId } }
        } finally { doc.close() }
    } }

    @Test fun exportCapturedPdfViaViewModel() {
        val app = ctx as android.app.Application
        val vm = com.vayunmathur.pdf.util.PdfViewModel(app)
        val png = File(ctx.cacheDir, "vm-page.png")
        png.outputStream().use { Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }.compress(Bitmap.CompressFormat.PNG, 100, it) }
        vm.addCapturedImage(Uri.fromFile(png))
        val out = File(ctx.cacheDir, "vm-out.pdf")
        // viewModelScope runs on Main; Robolectric looper drives it
        vm.exportCapturedPdf(Uri.fromFile(out))
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        Thread.sleep(200)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }
}
