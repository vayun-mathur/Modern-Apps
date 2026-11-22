package com.vayunmathur.office.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.IconCrop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.library.ui.odf.ContinuousParagraphEditor
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfImage
import com.vayunmathur.library.ui.odf.OdfTableCell
import com.vayunmathur.office.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import com.vayunmathur.office.platform.firstSpan
/**
 * Visible keyboard-focus indication for the editor's non-Material stops
 * (paragraph runs, table cells, image/chart blocks, the title): a 2dp ring in
 * the theme's primary colour — the same colour the editor uses for the caret
 * and active toolbar state — drawn only while the stop holds focus. The
 * transparent unfocused border keeps the ring from shifting layout, and
 * pointer/touch interaction is untouched (a tap still shows the caret, the
 * ring appears only as focus state).
 */
@Composable
internal fun Modifier.officeFocusRing(focused: Boolean): Modifier =
    border(
        2.dp,
        if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
        RoundedCornerShape(6.dp))

// --- Text Document (continuous editor) ---

internal sealed class DocSegment {
    data class Paragraphs(val start: Int, val endInclusive: Int) : DocSegment()
    data class Block(val index: Int) : DocSegment()
}

private fun buildSegments(content: List<OdfContentBlock>): List<DocSegment> {
    val segments = mutableListOf<DocSegment>()
    var runStart = -1
    content.forEachIndexed { i, block ->
        if (block is OdfContentBlock.Paragraph) {
            if (runStart < 0) runStart = i
        } else {
            if (runStart >= 0) { segments.add(DocSegment.Paragraphs(runStart, i - 1)); runStart = -1 }
            segments.add(DocSegment.Block(i))
        }
    }
    if (runStart >= 0) segments.add(DocSegment.Paragraphs(runStart, content.size - 1))
    return segments
}

// Stable LazyColumn identity: the content index is the model's own identity
// (every edit callback — onRunTextChange, onCellTextChange, onChartClick —
// addresses blocks by this index), so keying by it keeps editor state with
// its block when neighbours are inserted/removed without re-keying on text
// (a text-derived key would change per keystroke and destroy editor state).
private fun DocSegment.lazyKey(): String = when (this) {
    is DocSegment.Paragraphs -> "paragraphs:$start-$endInclusive"
    is DocSegment.Block -> "block:$index"
}

private fun DocSegment.lazyContentType(content: List<OdfContentBlock>): String = when (this) {
    is DocSegment.Paragraphs -> "paragraphs"
    is DocSegment.Block -> when (content[index]) {
        is OdfContentBlock.Table -> "block-table"
        is OdfContentBlock.Image -> "block-image"
        is OdfContentBlock.PageBreak -> "block-pagebreak"
        is OdfContentBlock.Chart -> "block-chart"
        is OdfContentBlock.Formula -> "block-formula"
        is OdfContentBlock.TableOfContents -> "block-toc"
        is OdfContentBlock.Paragraph -> "block-paragraph"
        is OdfContentBlock.SectionStart, OdfContentBlock.SectionEnd -> "block-section"
    }
}

@Composable
fun TextDocumentView(
    doc: OdfDocument.TextDocument,
    searchQuery: String = "",
    fontSizeMultiplier: Float = 1f,
    listState: LazyListState = rememberLazyListState(),
    onRunSelectionChange: (Int, Int, Int, Int) -> Unit = { _, _, _, _ -> },
    onRunTextChange: (Int, Int, String) -> Unit = { _, _, _ -> },
    onRunEnter: (Int, Int, Int) -> Int? = { _, _, _ -> null },
    onRunBackspace: (Int, Int, Int) -> Int? = { _, _, _ -> null },
    onToggleCheckbox: (Int) -> Unit = {},
    onDeletePrevBlock: (Int) -> Unit = {},
    onCellTextChange: (Int, Int, Int, String) -> Unit = { _, _, _, _ -> },
    onCellFocus: (Int, Int, Int) -> Unit = { _, _, _ -> },
    onChartClick: (Int) -> Unit = {},
    onCropImage: (Int) -> Unit = {},
    remoteCarets: List<com.vayunmathur.library.ui.odf.RemoteCaret> = emptyList(),
    // Keyboard focus: [bodyFocusRequester] lands on the first interactive body
    // stop (first paragraph run, else first table cell / image / chart) so the
    // screen's Escape handler can return focus to the body; [onBodyFocusChanged]
    // reports whether any body stop currently holds focus (hasFocus).
    bodyFocusRequester: FocusRequester? = null,
    onBodyFocusChanged: (Boolean) -> Unit = {}
) {
    val segments = remember(doc.content) { buildSegments(doc.content) }
    // First interactive stop in document (visual) order: paragraph runs and
    // table/image/chart blocks are stops; page breaks, formulas, TOCs and
    // section markers are not interactive.
    val firstStopSegment = segments.indexOfFirst { seg ->
        when (seg) {
            is DocSegment.Paragraphs -> true
            is DocSegment.Block -> when (doc.content[seg.index]) {
                is OdfContentBlock.Table, is OdfContentBlock.Image, is OdfContentBlock.Chart -> true
                else -> false
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)
            .onFocusChanged { onBodyFocusChanged(it.hasFocus) }
    ) {
        if (doc.headerParagraphs.isNotEmpty()) {
            // Header stays one composed block: its paragraphs render inside a
            // single Surface, so splitting them into separate items would
            // break the shared background/padding. It is already lazy at
            // item granularity (composed only when scrolled into view).
            item(key = "header", contentType = "header") {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    Column(modifier = Modifier.padding(8.dp)) { for (para in doc.headerParagraphs) ParagraphView(
                        para,
                        "",
                        fontSizeMultiplier) }
                }
            }
        }

        items(
            count = segments.size,
            key = { si -> segments[si].lazyKey() },
            contentType = { si -> segments[si].lazyContentType(doc.content) },
        ) { si ->
            when (val seg = segments[si]) {
                is DocSegment.Paragraphs -> {
                    var segmentFocused by remember { mutableStateOf(false) }
                    ContinuousParagraphEditor(
                        doc, seg.start, seg.endInclusive, fontSizeMultiplier, onRunSelectionChange, onRunTextChange,
                        modifier = Modifier.fillMaxWidth().officeFocusRing(segmentFocused),
                        onEnter = { gPos -> onRunEnter(seg.start, seg.endInclusive, gPos) },
                        onBackspace = { gPos -> onRunBackspace(seg.start, seg.endInclusive, gPos) },
                        onToggleCheckbox = onToggleCheckbox,
                        onDeletePrevBlock = { onDeletePrevBlock(seg.start) },
                        onFocusChangedCb = { segmentFocused = it },
                        focusRequester = if (si == firstStopSegment) bodyFocusRequester else null,
                        remoteCarets = remoteCarets,
                    )
                }
                is DocSegment.Block -> when (val block = doc.content[seg.index]) {
                    is OdfContentBlock.Table -> TableView(
                        block.table,
                        seg.index,
                        fontSizeMultiplier,
                        onCellTextChange,
                        onCellFocus,
                        firstCellFocusRequester = if (si == firstStopSegment) bodyFocusRequester else null)
                    is OdfContentBlock.Image -> {
                        var fullScreen by remember { mutableStateOf(false) }
                        var imageFocused by remember { mutableStateOf(false) }
                        if (fullScreen) FullScreenImage(
                            block.image,
                            onCrop = { fullScreen = false; onCropImage(seg.index) }) { fullScreen = false }
                        else Box(
                            modifier = Modifier
                                .then(
                                    if (si == firstStopSegment && bodyFocusRequester != null) {
                                        Modifier.focusRequester(bodyFocusRequester)
                                    } else Modifier)
                                .onFocusChanged { imageFocused = it.isFocused }
                                .officeFocusRing(imageFocused)
                                .clickable { fullScreen = true }) { OdfImageView(block.image) }
                    }
                    is OdfContentBlock.PageBreak -> PageBreakView()
                    is OdfContentBlock.Chart -> {
                        var chartFocused by remember { mutableStateOf(false) }
                        OdfChartView(
                            block.chart,
                            onClick = { onChartClick(seg.index) },
                            modifier = Modifier
                                .then(
                                    if (si == firstStopSegment && bodyFocusRequester != null) {
                                        Modifier.focusRequester(bodyFocusRequester)
                                    } else Modifier)
                                .onFocusChanged { chartFocused = it.isFocused }
                                .officeFocusRing(chartFocused))
                    }
                    is OdfContentBlock.Formula -> MathView(block.mathml)
                    is OdfContentBlock.TableOfContents -> {
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Text(
                                block.title,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium)
                            for (entry in block.entries) {
                                Text(
                                    entry.spans.joinToString("") { it.text },
                                    modifier = Modifier.padding(start = entry.marginLeft.dp),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                    is OdfContentBlock.Paragraph -> {}
                    is OdfContentBlock.SectionStart, OdfContentBlock.SectionEnd -> {}
                }
            }
        }

        if (doc.footnotes.isNotEmpty()) {
            item(key = "footnotes-divider", contentType = "divider") {
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            }
            // Keyed by citation, the footnote's stable identity (the serializer
            // also associates footnotes by citation). The body-paragraph loop
            // stays a single composed block inside the footnote's Row: the
            // citation + body are one visual unit, so per-paragraph items
            // would fragment that Row without making the footnote itself
            // any lazier — it is already lazy at footnote granularity.
            items(
                count = doc.footnotes.size,
                key = { index -> "footnote:${doc.footnotes[index].citation}" },
                contentType = { "footnote" },
            ) { index ->
                val fn = doc.footnotes[index]
                Row(modifier = Modifier.padding(vertical = 2.dp)) {
                    Text("${fn.citation} ", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    Column(Modifier.weight(1f)) { for (para in fn.body) ParagraphView(
                        para,
                        searchQuery,
                        fontSizeMultiplier) }
                }
            }
        }

        if (doc.footerParagraphs.isNotEmpty()) {
            // Single composed block, as for the header (shared Surface).
            item(key = "footer", contentType = "footer") {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Column(modifier = Modifier.padding(8.dp)) { for (para in doc.footerParagraphs) ParagraphView(
                        para,
                        "",
                        fontSizeMultiplier) }
                }
            }
        }
    }
}

@Composable
private fun PageBreakView() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 24.dp),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant)
}

