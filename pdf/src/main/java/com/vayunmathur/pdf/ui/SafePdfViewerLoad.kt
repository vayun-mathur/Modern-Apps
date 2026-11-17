package com.vayunmathur.pdf.ui

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.vayunmathur.library.ocr.OcrEngine
import com.vayunmathur.pdf.platform.PdfStateStore
import com.vayunmathur.pdf.platform.SafeOutlineItem
import com.vayunmathur.pdf.platform.SafePdfDocument
import kotlinx.coroutines.delay

/** Document load outputs owned by the screen's load effect. */
internal class SafePdfDocumentBundle(
    val loadState: LoadState,
    val document: SafePdfDocument?,
    val outline: List<SafeOutlineItem>,
    val hasRedactions: Boolean,
    val ocrEngine: OcrEngine,
)

/**
 * Document lifecycle for [SafePdfViewerScreen]: password-gated open (with
 * [onBack] for the password dialog's dismiss), search prewarm, shared OCR
 * engine, outline/redaction flags, and page-count sync into [state].
 */
@Composable
internal fun rememberSafePdfDocument(
    context: Context,
    uri: Uri,
    state: SafePdfViewerState,
    onBack: () -> Unit,
): SafePdfDocumentBundle {
    // Password handling for encrypted PDFs.
    var password by remember(uri) { mutableStateOf<String?>(null) }
    var needsPassword by remember(uri) { mutableStateOf(false) }
    var pwError by remember(uri) { mutableStateOf(false) }

    val loadState by produceState<LoadState>(LoadState.Loading, uri, password) {
        value = LoadState.Loading
        val doc = SafePdfDocument.open(context, uri, password)
        value = if (doc != null) {
            needsPassword = false
            LoadState.Loaded(doc)
        } else {
            when (SafePdfDocument.passwordState(context, uri)) {
                1 -> { needsPassword = true; pwError = password != null; LoadState.Loading }
                else -> LoadState.Error(notPdf = !SafePdfDocument.looksLikePdf(context, uri))
            }
        }
    }
    val document = (loadState as? LoadState.Loaded)?.document
    DisposableEffect(document) { onDispose { document?.close() } }

    if (needsPassword) {
        var pwInput by remember { mutableStateOf("") }
        SafePdfPasswordPrompt(
            pwInput = pwInput,
            onPwInput = { pwInput = it },
            pwError = pwError,
            onOpen = { needsPassword = false; password = pwInput },
            onBack = onBack,
        )
    }

    // Prebuild the search index in the background so the first query is instant.
    LaunchedEffect(document) { document?.prewarmSearch() }

    // On-device OCR engine (PP-OCRv5 / ncnn), shared across all pages so that
    // scanned PDFs with no embedded text layer still become selectable. Lazy:
    // native models only load on the first page that actually needs OCR.
    val ocrEngine = remember { OcrEngine(context.applicationContext) }
    DisposableEffect(Unit) { onDispose { ocrEngine.close() } }

    // Outline (bookmarks) + navigation drawer.
    val outline by produceState(emptyList<SafeOutlineItem>(), document) {
        value = document?.outline() ?: emptyList()
    }
    // Show the Apply-redactions action only while redaction annotations exist.
    val hasRedactions by produceState(false, state.undoStack.size, state.redoStack.size, state.pageMgrVersion) {
        value = document?.hasRedactions() ?: false
    }

    LaunchedEffect(document) {
        state.pageCount = document?.pageCount ?: 0
    }

    return SafePdfDocumentBundle(loadState, document, outline, hasRedactions, ocrEngine)
}

/** Search debounce + last-read-page restore/persist effects. */
@Composable
internal fun SafePdfScreenEffects(
    context: Context,
    uri: Uri,
    state: SafePdfViewerState,
    document: SafePdfDocument?,
    scroll: SafePdfScrollState,
    zoom: SafePdfZoomState,
) {
    LaunchedEffect(state.query, document, state.searching, state.caseSensitive) {
        val doc = document
        if (doc == null || !state.searching || state.query.isBlank()) {
            state.matches = emptyList()
            return@LaunchedEffect
        }
        delay(250)
        state.matches = doc.search(state.query, state.caseSensitive).map { m ->
            m.page to androidx.compose.ui.geometry.Rect(m.x0, m.y0, m.x1, m.y1)
        }
        state.matchIndex = 0
    }

    LaunchedEffect(state.matchIndex, state.matches) {
        state.matches.getOrNull(state.matchIndex)?.let { scroll.scrollToPage(it.first, zoom.zoom) }
    }

    // Restore last-read page, then persist the current page as it changes.
    LaunchedEffect(document) {
        if (document != null) {
            val p = PdfStateStore.restoreSafePage(context, uri)
            if (p > 0) runCatching { scroll.scrollToPage(p, zoom.zoom) }
        }
    }
    LaunchedEffect(document) {
        if (document == null) return@LaunchedEffect
        androidx.compose.runtime.snapshotFlow { scroll.currentPage() }
            .collect { PdfStateStore.saveSafePage(context, uri, it) }
    }
}
