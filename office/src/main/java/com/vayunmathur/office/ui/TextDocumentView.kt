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
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

import com.vayunmathur.office.platform.firstSpan
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
    remoteCarets: List<com.vayunmathur.library.ui.odf.RemoteCaret> = emptyList()
) {
    val segments = remember(doc.content) { buildSegments(doc.content) }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        if (doc.headerParagraphs.isNotEmpty()) {
            item {
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

        items(segments.size) { si ->
            when (val seg = segments[si]) {
                is DocSegment.Paragraphs -> ContinuousParagraphEditor(
                    doc, seg.start, seg.endInclusive, fontSizeMultiplier, onRunSelectionChange, onRunTextChange,
                    modifier = Modifier.fillMaxWidth(),
                    onEnter = { gPos -> onRunEnter(seg.start, seg.endInclusive, gPos) },
                    onBackspace = { gPos -> onRunBackspace(seg.start, seg.endInclusive, gPos) },
                    onToggleCheckbox = onToggleCheckbox,
                    onDeletePrevBlock = { onDeletePrevBlock(seg.start) },
                    remoteCarets = remoteCarets,
                )
                is DocSegment.Block -> when (val block = doc.content[seg.index]) {
                    is OdfContentBlock.Table -> TableView(
                        block.table,
                        seg.index,
                        fontSizeMultiplier,
                        onCellTextChange,
                        onCellFocus)
                    is OdfContentBlock.Image -> {
                        var fullScreen by remember { mutableStateOf(false) }
                        if (fullScreen) FullScreenImage(
                            block.image,
                            onCrop = { fullScreen = false; onCropImage(seg.index) }) { fullScreen = false }
                        else Box(modifier = Modifier.clickable { fullScreen = true }) { OdfImageView(block.image) }
                    }
                    is OdfContentBlock.PageBreak -> PageBreakView()
                    is OdfContentBlock.Chart -> OdfChartView(block.chart, onClick = { onChartClick(seg.index) })
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
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp)) }
            items(doc.footnotes.size) { index ->
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
            item {
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
private fun FullScreenImage(image: OdfImage, onCrop: (() -> Unit)? = null, onDismiss: () -> Unit) {
    val bitmap = remember(
        image.path,
        image.imageData.size) { BitmapFactory.decodeByteArray(image.imageData, 0, image.imageData.size) }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.9f)).clickable { onDismiss() },
        contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            contentScale = ContentScale.Fit)
        if (onCrop != null) {
            TextButton(onClick = onCrop, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
                IconCrop(tint = Color.White)
                androidx.compose.foundation.layout.Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.crop), color = Color.White)
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

@Composable
private fun TableView(
    table: com.vayunmathur.library.ui.odf.OdfTable,
    blockIndex: Int,
    fontSizeMultiplier: Float = 1f,
    onCellTextChange: (Int, Int, Int, String) -> Unit = { _, _, _, _ -> },
    onCellFocus: (Int, Int, Int) -> Unit = { _, _, _ -> }
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    fun colWidthDp(start: Int, span: Int): androidx.compose.ui.unit.Dp {
        var w = 0f
        for (k in start until start + span) {
            val px = table.columns.getOrNull(k)?.width
            w += if (px != null && px > 0f) px * (160f / 96f) else 110f
        }
        return w.dp
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).horizontalScroll(rememberScrollState())) {
        for ((r, row) in table.rows.withIndex()) {
            Row(Modifier.height(IntrinsicSize.Min)) {
                var colspanSkip = 0
                for ((c, cell) in row.cells.withIndex()) {
                    if (cell.isCovered) {
                        // Covered by a horizontal merge to its left: the anchor already claimed the width.
                        if (colspanSkip > 0) { colspanSkip--; continue }
                        // Covered by a vertical (row) merge from above: render an aligned placeholder so
                        // columns below the merge don't collapse and shift left.
                        Box(Modifier.width(colWidthDp(c, 1)).fillMaxHeight()
                            .border(0.7.dp, MaterialTheme.colorScheme.outline)) {}
                        continue
                    }
                    colspanSkip = if (cell.colSpan > 1) cell.colSpan - 1 else 0
                    Box(
                        Modifier.width(colWidthDp(c, cell.colSpan))
                            .fillMaxHeight()
                            .border(0.7.dp, MaterialTheme.colorScheme.outline)
                            .then(cell.backgroundColor?.let { Modifier.background(Color(it.toInt())) } ?: Modifier)
                            .padding(8.dp)
                    ) {
                        EditableCell(
                            cell,
                            onSurface,
                            fontSizeMultiplier,
                            onFocus = { onCellFocus(blockIndex, r, c) })
                        { txt -> onCellTextChange(blockIndex, r, c, txt) }
                    }
                }
            }
        }
        if (table.rows.isEmpty()) Text(
            stringResource(R.string.empty_table),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(8.dp))
    }
}

@Composable
private fun EditableCell(
    cell: OdfTableCell,
    onSurface: Color,
    mult: Float,
    onFocus: () -> Unit,
    onChange: (String) -> Unit) {
    val plain = cell.paragraphs.joinToString("\n") { p -> p.spans.joinToString("") { it.text } }
    var tfv by remember { mutableStateOf(TextFieldValue(plain)) }
    if (tfv.text != plain) tfv = TextFieldValue(plain, TextRange(tfv.selection.end.coerceIn(0, plain.length)))
    // Reflect uniform cell character formatting (C26).
    val firstSpan = cell.paragraphs.firstOrNull()?.spans?.firstOrNull()
    val cellAlign = cell.paragraphs.firstOrNull()?.alignment
    val style = MaterialTheme.typography.bodyMedium.copy(
        color = firstSpan?.color?.let { Color(it.toInt()) } ?: onSurface,
        fontSize = (14f * mult).sp,
        fontWeight = if (firstSpan?.bold == true) FontWeight.Bold else null,
        fontStyle = if (firstSpan?.italic == true) FontStyle.Italic else null,
        textAlign = cellAlign ?: TextAlign.Unspecified
    )
    BasicTextField(
        value = tfv,
        onValueChange = { nv -> val changed = nv.text != tfv.text; tfv = nv; if (changed) onChange(nv.text) },
        textStyle = style,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) onFocus() }
    )
}
