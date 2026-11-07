package com.vayunmathur.pdf.ui

import android.content.Context
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.pdf.util.SafePdfDocument
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PageItemLoadedTest {
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
        val f = File(ctx.cacheDir, "pageitem-${System.nanoTime()}.pdf"); f.writeBytes(minimalPdf())
        SafePdfDocument.open(ctx, Uri.fromFile(f))!!.also { it.renderPage(0) }
    }

    private fun renderItem(editMode: Boolean, tool: EditTool, onWidth: (Float) -> Unit) {
        val doc = openDoc()
        rule.setContent {
            DynamicTheme {
                SafePdfPageItem(
                    document = doc, index = 0, version = 0, ocr = null,
                    editMode = editMode, tool = tool, shape = ShapeKind.RECT_OUTLINE,
                    markup = MarkupKind.HIGHLIGHT, color = Color.Red, strokeWidth = 2f,
                    selected = null,
                    highlights = if (editMode) emptyList() else listOf(androidx.compose.ui.geometry.Rect(70f, 680f, 220f, 715f)),
                    currentHighlight = if (editMode) androidx.compose.ui.geometry.Rect(70f, 680f, 220f, 715f) else null,
                    scope = CoroutineScope(Dispatchers.Default),
                    onSelect = {}, onEdited = {}, onCreated = {}, onMoved = { _, _, _ -> },
                    onFormEdited = {}, onPageWidth = onWidth,
                    textSession = null, onStartText = {}, onTextChange = {},
                    onCommitText = {}, onRequestImage = {}, onRequestNote = {}, onRequestCallout = { _, _ -> },
                    polyDraft = null, onAddPolyPoint = {}, onLinkPage = {},
                )
            }
        }
        // Wait until the decoded page reports its width (proves the overlay slot composed)
        rule.waitUntil(timeoutMillis = 10_000) { widthSeen }
        rule.waitForIdle()
        rule.onRoot().captureToImage()
        docRef = doc
    }

    private var widthSeen = false
    private var docRef: SafePdfDocument? = null

    @Test fun pageItemReadOnlyLoadedDrawsOverlays() {
        widthSeen = false
        renderItem(editMode = false, tool = EditTool.SELECT) { widthSeen = true }
        assertTrue(widthSeen)
        rule.onRoot().performTouchInput { swipe(centerLeft, centerRight, 200) }
        rule.waitForIdle()
        docRef?.close()
    }

    @Test fun pageItemEditLoadedDrawsOverlays() {
        widthSeen = false
        renderItem(editMode = true, tool = EditTool.DRAW) { widthSeen = true }
        assertTrue(widthSeen)
        rule.onRoot().performTouchInput { swipe(centerLeft, centerRight, 200) }
        rule.waitForIdle()
        docRef?.close()
    }

    @Test fun pageItemFailedPageShowsPlaceholder() {
        val doc = openDoc()
        widthSeen = false
        rule.setContent {
            DynamicTheme {
                SafePdfPageItem(
                    document = doc, index = 99, version = 0, ocr = null,
                    editMode = false, tool = EditTool.SELECT, shape = ShapeKind.RECT_OUTLINE,
                    markup = MarkupKind.HIGHLIGHT, color = Color.Red, strokeWidth = 2f,
                    selected = null, highlights = emptyList(), currentHighlight = null,
                    scope = CoroutineScope(Dispatchers.Default),
                    onSelect = {}, onEdited = {}, onCreated = {}, onMoved = { _, _, _ -> },
                    onFormEdited = {}, textSession = null, onStartText = {}, onTextChange = {},
                    onCommitText = {}, onRequestImage = {}, onRequestNote = {}, onRequestCallout = { _, _ -> },
                    polyDraft = null, onAddPolyPoint = {}, onLinkPage = {},
                )
            }
        }
        rule.waitForIdle()
        rule.onRoot().captureToImage()
        doc.close()
    }
}
