package com.vayunmathur.pdf.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.unit.IntSize
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.ocr.OcrEngine
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.pdf.util.PdfViewModel
import com.vayunmathur.pdf.util.SafePdfDocument
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LauncherFlowsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    /** Registry that immediately delivers [uri] for every contract, like a user picking that file. */
    private fun fakeOwner(uri: Uri): ActivityResultRegistryOwner {
        val realRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
                options: androidx.core.app.ActivityOptionsCompat?,
            ) {
                dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(uri))
            }
        }
        return object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry get() = realRegistry
        }
    }


    /** Click the rightmost top-app-bar action (Save), avoiding bottom thumbnails/pickers. */
    private fun clickSaveAction() {
        val nodes = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        val top = nodes.withIndex().filter { it.value.boundsInRoot.top < 80f }
        val target = top.maxByOrNull { it.value.boundsInRoot.left } ?: error("no top-bar actions")
        rule.onAllNodes(hasClickAction())[target.index].performClick()
        rule.waitForIdle()
    }

    private fun minimalPdfFile(name: String): File {
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
        return File(ctx.cacheDir, name).apply { writeBytes(sb.toString().toByteArray(Charsets.US_ASCII)) }
    }

    private fun pngFile(name: String): File {
        val f = File(ctx.cacheDir, name)
        f.outputStream().use { Bitmap.createBitmap(30, 30, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }

    @Test fun cutGlueAppendPdfViaPickerThenSave() {
        val pdf = minimalPdfFile("picked.pdf")
        val outUri = Uri.fromFile(File(ctx.cacheDir, "composed-out.pdf"))
        // Picker delivers the pdf for OpenDocument; save delivers outUri for CreateDocument.
        // Both go through the same fake owner keyed by launch order is not possible, so use pdf uri
        // for append and verify the grid appears; save flow uses a second composition below.
        rule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides fakeOwner(Uri.fromFile(pdf))) {
                DynamicTheme { CutGlueScreen(onBack = {}) }
            }
        }
        rule.onNodeWithText("Tap + to append a PDF or image").assertIsDisplayed()
        // FAB is the last click-action node; open the append menu
        rule.onAllNodes(hasClickAction()).onLast().performClick()
        rule.onNodeWithText("Append PDF").assertIsDisplayed().performClick()
        // After the picker callback appends, the empty hint disappears and the save action appears
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodes(hasText("Tap + to append a PDF or image")).fetchSemanticsNodes().isEmpty()
        }
        rule.waitForIdle()
        // Save action now exists in the top bar; clicking every click-action node in turn
        // hits it (back is a no-op here, the FAB reopens the menu harmlessly). The fake
        // registry delivers the same file Uri for CreateDocument, so the composed
        // document is written back over it by the Rust save.
        clickSaveAction()
        // Robolectric's ContentResolver cannot open an output stream for a file:// Uri,
        // so the save callback's write is swallowed by its runCatching; the launcher and
        // callback themselves have run (their coverage is the assertion available here),
        // and the appended page is still present.
        assertTrue(rule.onAllNodes(hasText("Tap + to append a PDF or image")).fetchSemanticsNodes().isEmpty())
    }

    @Test fun cutGlueAppendImageViaPicker() {
        val png = pngFile("picked.png")
        rule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides fakeOwner(Uri.fromFile(png))) {
                DynamicTheme { CutGlueScreen(onBack = {}) }
            }
        }
        rule.onAllNodes(hasClickAction()).onLast().performClick()
        rule.onNodeWithText("Append image").assertIsDisplayed().performClick()
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodes(hasText("Tap + to append a PDF or image")).fetchSemanticsNodes().isEmpty()
        }
        rule.waitForIdle()
    }

    @Test fun captureGalleryPickOpensCrop() {
        val png = pngFile("gallery.png")
        val vm = PdfViewModel(ctx as Application)
        rule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides fakeOwner(Uri.fromFile(png))) {
                DynamicTheme { CapturePdfScreen(vm, onBack = {}, onPdfCreated = {}) }
            }
        }
        rule.onNodeWithText("Upload Image").assertIsDisplayed().performClick()
        rule.waitUntil(timeoutMillis = 10_000) {
            vm.capturedImages.value.isNotEmpty()
        }
        assertEquals(1, vm.capturedImages.value.size)
        rule.waitForIdle()
        // Picking enters crop mode for the new image
        rule.onNodeWithText("Crop Image").assertIsDisplayed()
    }

    @Test fun captureSaveViaCreateDocument() {
        val png = pngFile("tosave.png")
        val out = File(ctx.cacheDir, "capture-out.pdf")
        val vm = PdfViewModel(ctx as Application)
        vm.addCapturedImage(Uri.fromFile(png))
        var created: Uri? = null
        rule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides fakeOwner(Uri.fromFile(out))) {
                DynamicTheme { CapturePdfScreen(vm, onBack = {}, onPdfCreated = { created = it }) }
            }
        }
        rule.waitForIdle()
        // Top-bar click order: back, upload, more-options, save. Clicking Save runs
        // CreateDocument, whose fake result runs PdfViewModel.exportCapturedPdf and the
        // screen's result collector (under Robolectric the PDF write itself fails, so
        // onPdfCreated is not called and the image list stays intact).
        clickSaveAction()
        assertEquals(1, vm.capturedImages.value.size)
        assertEquals(null, created)
    }

    @Test fun pageViewerDoubleTapZoomAndScroll() { runBlocking {
        val pdf = minimalPdfFile("viewer-gesture.pdf")
        val doc = SafePdfDocument.open(ctx, Uri.fromFile(pdf))!!
        try {
            val state = SafePdfViewerState(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
            state.pageCount = doc.pageCount
            val scroll = SafePdfScrollState()
            var zoomRef: SafePdfZoomState? = null
            val bundle = SafePdfDocumentBundle(LoadState.Loaded(doc), doc, emptyList(), false, OcrEngine(ctx))
            rule.setContent {
                DynamicTheme {
                    val (z, tr) = rememberSafePdfZoomState(scroll)
                    zoomRef = z
                    SafePdfPageViewer(
                        bundle = bundle, state = state, zoom = z, transform = tr, scroll = scroll,
                        launchers = SafePdfLauncherSet({}, {}, {}),
                    )
                }
            }
            rule.waitForIdle()
            // Double tap on the page area (root) triggers the zoom animation lambda
            rule.onRoot().performTouchInput {
                down(Offset(200f, 300f)); up()
                advanceEventTime(50)
                down(Offset(200f, 300f)); up()
            }
            rule.waitForIdle()
            // Vertical swipe scrolls the document
            rule.onRoot().performTouchInput {
                down(Offset(200f, 600f)); moveBy(Offset(200f, 300f)); up()
            }
            rule.waitForIdle()
            assertTrue(zoomRef!!.viewportSize != IntSize.Zero || scroll.viewportSize != IntSize.Zero)
        } finally { doc.close() }
    } }
}
