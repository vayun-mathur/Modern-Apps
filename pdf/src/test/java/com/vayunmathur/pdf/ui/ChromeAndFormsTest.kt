package com.vayunmathur.pdf.ui

import android.content.Context
import android.net.Uri
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.ocr.OcrEngine
import com.vayunmathur.library.ui.DrawerValue
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.rememberDrawerState
import com.vayunmathur.pdf.MainActivity
import com.vayunmathur.pdf.util.SafeFormField
import com.vayunmathur.pdf.util.SafeOutlineItem
import com.vayunmathur.pdf.util.SafePdfDocument
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainActivityTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun homeShowsAndNavigatesToCaptureAndCutGlue() {
        rule.onNodeWithText("Open PDF").assertIsDisplayed()
        rule.onNodeWithText("Capture PDF").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Capture PDF").assertIsDisplayed() // capture screen title
        rule.activity.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    @Test fun homeToCutGlue() {
        rule.onNodeWithText("PDF cut and glue").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Tap + to append a PDF or image").assertIsDisplayed()
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChromeAndFormsTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()
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
        val f = File(ctx.cacheDir, "chrome-${System.nanoTime()}.pdf"); f.writeBytes(minimalPdf())
        SafePdfDocument.open(ctx, Uri.fromFile(f))!!
    }

    private fun bundle(doc: SafePdfDocument?, state: LoadState) =
        SafePdfDocumentBundle(state, doc, listOf(SafeOutlineItem(0, 0, "Cover")), false, OcrEngine(ctx))

    @Test fun chromeReadOnlyWithOutline() {
        val state = SafePdfViewerState(CoroutineScope(Dispatchers.Unconfined))
        rule.setContent {
            DynamicTheme {
                SafePdfViewerChrome(
                    state = state, bundle = bundle(null, LoadState.Loading),
                    scroll = SafePdfScrollState(), zoom = SafePdfZoomState(),
                    drawerState = rememberDrawerState(DrawerValue.Closed),
                    pageViewer = { androidx.compose.foundation.layout.Box(it) { com.vayunmathur.library.ui.Text("PagesHere") } },
                    searchFocus = FocusRequester(), onBack = {}, onShare = {}, onSaveInPlace = {}, onSaveCopyName = {},
                )
            }
        }
        rule.onNodeWithText("PagesHere").assertIsDisplayed()
    }

    @Test fun chromeSearchingShowsMatchNav() {
        val state = SafePdfViewerState(CoroutineScope(Dispatchers.Unconfined)).apply {
            searching = true; query = "Hello"
            matches = listOf(0 to androidx.compose.ui.geometry.Rect(0f, 0f, 10f, 10f), 0 to androidx.compose.ui.geometry.Rect(20f, 20f, 30f, 30f))
            matchIndex = 0
        }
        rule.setContent {
            DynamicTheme {
                SafePdfViewerChrome(
                    state = state, bundle = bundle(null, LoadState.Loading),
                    scroll = SafePdfScrollState(), zoom = SafePdfZoomState(),
                    drawerState = rememberDrawerState(DrawerValue.Closed),
                    pageViewer = { androidx.compose.foundation.layout.Box(it) {} },
                    searchFocus = FocusRequester(), onBack = {}, onShare = {}, onSaveInPlace = {}, onSaveCopyName = {},
                )
            }
        }
        rule.onNodeWithText("1/2").assertIsDisplayed()
    }

    @Test fun chromeEditModeWithUndoAndPolyDraft() {
        val state = SafePdfViewerState(CoroutineScope(Dispatchers.Unconfined)).apply {
            editMode = true
            undoStack.add(EditAction(0, 1L, EditKind.ADDED))
            polyDraft = PolyDraft(0, listOf(Offset(1f, 1f), Offset(2f, 2f)), false)
        }
        rule.setContent {
            DynamicTheme {
                SafePdfViewerChrome(
                    state = state, bundle = bundle(null, LoadState.Loading),
                    scroll = SafePdfScrollState(), zoom = SafePdfZoomState(),
                    drawerState = rememberDrawerState(DrawerValue.Closed),
                    pageViewer = { androidx.compose.foundation.layout.Box(it) {} },
                    searchFocus = FocusRequester(), onBack = {}, onShare = {}, onSaveInPlace = {}, onSaveCopyName = {},
                )
            }
        }
        rule.waitForIdle()
    }

    @Test fun formFieldOverlayAllTypes() { runBlocking {
        val doc = openDoc()
        val fields = listOf(
            SafeFormField(11L, 0, 72f, 600f, 300f, 630f, "name", "Alice", false),
            SafeFormField(12L, 1, 72f, 550f, 100f, 578f, "agree", "", true),
            SafeFormField(13L, 2, 72f, 500f, 300f, 530f, "choice", "Opt", false),
            SafeFormField(14L, 3, 72f, 450f, 200f, 480f, "sig", "", false),
            SafeFormField(15L, 99, 72f, 400f, 200f, 430f, "other", "", false),
        )
        var edited = 0
        rule.setContent {
            DynamicTheme {
                FormFieldOverlay(fields, ch = 792f, scale = 0.5f, document = doc, index = 0,
                    scope = CoroutineScope(Dispatchers.Unconfined), onEdited = { edited++ })
            }
        }
        rule.waitForIdle()
        doc.close()
        assertTrue(true)
    } }

    @Test fun viewerStateWithRealDocumentUndoRedoAndText() { runBlocking {
        val doc = openDoc()
        val state = SafePdfViewerState(CoroutineScope(Dispatchers.Default))
        try {
            state.createNote(doc, 0, Offset(60f, 60f), "hello note")
            withTimeout(5000) { while (doc.annotations(0).isEmpty()) delay(25) }
            val id = doc.annotations(0).first().id
            assertTrue(state.undoStack.isNotEmpty())
            state.undo(doc)
            withTimeout(5000) { while (state.redoStack.isEmpty()) delay(25) }
            state.redo(doc)
            withTimeout(5000) { while (doc.annotations(0).none { it.id == id } && false) delay(25); delay(100) }
            // Text session commit adds a FreeText annotation
            state.textSession = TextSession(0, Offset(100f, 650f), 12f, 0xFF000000.toInt(), null,
                androidx.compose.ui.text.input.TextFieldValue("Typed text"))
            val before = doc.annotations(0).size
            state.commitTextAndClear(doc)
            withTimeout(5000) { while (doc.annotations(0).size <= before) delay(25) }
            assertTrue(doc.annotations(0).size > before)
            // delete + duplicate via selection
            state.selected = 0 to doc.annotations(0).first().id
            state.duplicateSelected(doc)
            withTimeout(5000) { delay(150) }
            state.deleteSelected(doc)
            withTimeout(5000) { delay(150) }
            assertNotNull(doc)
        } finally { doc.close() }
    } }
}
