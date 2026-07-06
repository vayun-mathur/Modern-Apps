package com.vayunmathur.office.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vayunmathur.office.odf.*
import com.vayunmathur.library.ui.odf.*

// --- Headings for outline ---

data class HeadingItem(val text: String, val level: Int, val contentIndex: Int)

fun extractHeadings(doc: OdfDocument.TextDocument): List<HeadingItem> {
    val headings = mutableListOf<HeadingItem>()
    doc.content.forEachIndexed { index, block ->
        if (block is OdfContentBlock.Paragraph) {
            val style = block.paragraph.style
            if (style == ParagraphStyle.HEADING1 || style == ParagraphStyle.HEADING2 ||
                style == ParagraphStyle.HEADING3 || style == ParagraphStyle.HEADING4
            ) {
                val text = block.paragraph.spans.joinToString("") { it.text }
                val level = when (style) {
                    ParagraphStyle.HEADING1 -> 1; ParagraphStyle.HEADING2 -> 2
                    ParagraphStyle.HEADING3 -> 3; else -> 4
                }
                headings.add(HeadingItem(text, level, index))
            }
        }
    }
    return headings
}

fun countWords(doc: OdfDocument.TextDocument): Int {
    var count = 0
    for (block in doc.content) if (block is OdfContentBlock.Paragraph) for (span in block.paragraph.spans) count += span.text.split(Regex("\\s+")).count { it.isNotEmpty() }
    return count
}

fun countChars(doc: OdfDocument.TextDocument): Int {
    var count = 0
    for (block in doc.content) if (block is OdfContentBlock.Paragraph) for (span in block.paragraph.spans) count += span.text.length
    return count
}

fun readingTimeMinutes(doc: OdfDocument.TextDocument): Int = maxOf(1, (countWords(doc) + 199) / 200)

// --- Color Picker ---

@Composable
fun ColorPickerDialog(title: String, onColorSelected: (Long?) -> Unit, onDismiss: () -> Unit) {
    val colors = listOf(
        null, 0xFF000000L, 0xFFFFFFFF, 0xFFFF0000L, 0xFF00FF00L, 0xFF0000FFL,
        0xFFFFFF00L, 0xFFFF00FFL, 0xFF00FFFFL, 0xFF800000L, 0xFF008000L, 0xFF000080L,
        0xFF808000L, 0xFF800080L, 0xFF008080L, 0xFF808080L, 0xFFC0C0C0L,
        0xFFFF6600L, 0xFF6633CCL, 0xFF336699L, 0xFF993366L, 0xFF333300L,
        0xFF003300L, 0xFF003366L, 0xFF660066L, 0xFF333333L
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                for (row in colors.chunked(6)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (c in row) {
                            val bgColor = c?.let { Color(it.toInt()) } ?: Color.Transparent
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(bgColor, CircleShape)
                                    .border(1.dp, Color.Gray, CircleShape)
                                    .clickable { onColorSelected(c); onDismiss() }
                            ) {
                                if (c == null) Text("∅", Modifier.align(Alignment.Center), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// --- Font Size Picker ---

@Composable
fun FontSizePickerDialog(onSizeSelected: (Float) -> Unit, onDismiss: () -> Unit) {
    val sizes = listOf(8f, 9f, 10f, 11f, 12f, 14f, 16f, 18f, 20f, 24f, 28f, 32f, 36f, 48f, 72f)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Font Size") },
        text = {
            Column {
                for (row in sizes.chunked(5)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (size in row) {
                            Surface(
                                modifier = Modifier.clickable { onSizeSelected(size); onDismiss() },
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Text("${size.toInt()}", modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// --- Special Characters Dialog ---

@Composable
fun SpecialCharsDialog(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val chars = listOf(
        "©", "®", "™", "°", "±", "×", "÷", "≈", "≠", "≤", "≥", "∞",
        "•", "·", "—", "–", "…", "§", "¶", "†", "‡", "★", "☆", "✓",
        "→", "←", "↑", "↓", "⇒", "⇔", "€", "£", "¥", "¢", "α", "β",
        "γ", "δ", "π", "Σ", "Ω", "µ", "½", "¼", "¾", "²", "³", "“"
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Special Character") },
        text = {
            Column {
                for (row in chars.chunked(6)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (ch in row) {
                            Surface(
                                modifier = Modifier.size(40.dp).clickable { onPick(ch); onDismiss() },
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) { Box(contentAlignment = Alignment.Center) { Text(ch, style = MaterialTheme.typography.titleMedium) } }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// --- Footnote / Comment / Header-Footer Dialogs ---

@Composable
fun FootnoteDialog(onAdd: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Insert Footnote") },
        text = { TextField(value = text, onValueChange = { text = it }, label = { Text("Footnote text") }, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { if (text.isNotBlank()) { onAdd(text); onDismiss() } }) { Text("Insert") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
fun CommentDialog(onAdd: (author: String, text: String) -> Unit, onDismiss: () -> Unit) {
    var author by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Insert Comment") },
        text = {
            Column {
                TextField(value = author, onValueChange = { author = it }, label = { Text("Author") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                TextField(value = text, onValueChange = { text = it }, label = { Text("Comment") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { if (text.isNotBlank()) { onAdd(author, text); onDismiss() } }) { Text("Insert") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
fun HeaderFooterDialog(initialHeader: String, initialFooter: String, onSave: (header: String, footer: String) -> Unit, onDismiss: () -> Unit) {
    var header by remember { mutableStateOf(initialHeader) }
    var footer by remember { mutableStateOf(initialFooter) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Header & Footer") },
        text = {
            Column {
                TextField(value = header, onValueChange = { header = it }, label = { Text("Header") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                TextField(value = footer, onValueChange = { footer = it }, label = { Text("Footer") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onSave(header, footer); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

// --- Insert Table Dialog ---

@Composable
fun InsertTableDialog(onInsert: (rows: Int, cols: Int) -> Unit, onDismiss: () -> Unit) {
    var rows by remember { mutableStateOf("3") }
    var cols by remember { mutableStateOf("3") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Insert Table") },
        text = {
            Column {
                TextField(value = rows, onValueChange = { rows = it }, label = { Text("Rows") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                TextField(value = cols, onValueChange = { cols = it }, label = { Text("Columns") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val r = rows.toIntOrNull()?.coerceIn(1, 50) ?: 3
                val c = cols.toIntOrNull()?.coerceIn(1, 26) ?: 3
                onInsert(r, c)
                onDismiss()
            }) { Text("Insert") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// --- Insert Hyperlink Dialog ---

@Composable
fun InsertHyperlinkDialog(onInsert: (text: String, url: String) -> Unit, onDismiss: () -> Unit) {
    var linkText by remember { mutableStateOf("") }
    var linkUrl by remember { mutableStateOf("https://") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Insert Hyperlink") },
        text = {
            Column {
                TextField(value = linkText, onValueChange = { linkText = it }, label = { Text("Display Text") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                TextField(value = linkUrl, onValueChange = { linkUrl = it }, label = { Text("URL") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (linkText.isNotBlank() && linkUrl.isNotBlank()) { onInsert(linkText, linkUrl); onDismiss() }
            }) { Text("Insert") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// --- Go-to-Slide Dialog ---

@Composable
fun GoToSlideDialog(total: Int, onGo: (Int) -> Unit, onDismiss: () -> Unit) {
    var input by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Go to Slide") },
        text = {
            TextField(value = input, onValueChange = { input = it },
                label = { Text("Slide number (1-$total)") }, singleLine = true)
        },
        confirmButton = {
            TextButton(onClick = {
                val n = input.toIntOrNull()
                if (n != null && n in 1..total) { onGo(n - 1); onDismiss() }
            }) { Text("Go") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// --- Add Bookmark Dialog ---

@Composable
fun AddBookmarkDialog(onAdd: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Bookmark") },
        text = { TextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) { onAdd(name); onDismiss() } }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// --- Settings Dialog ---

@Composable
fun SettingsDialog(
    autoSave: Boolean,
    autoSaveInterval: Int,
    defaultFontSize: Float,
    onSave: (autoSave: Boolean, interval: Int, fontSize: Float) -> Unit,
    onDismiss: () -> Unit
) {
    var autoSaveEnabled by remember { mutableStateOf(autoSave) }
    var interval by remember { mutableStateOf(autoSaveInterval.toString()) }
    var fontSize by remember { mutableFloatStateOf(defaultFontSize) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Auto-save", modifier = Modifier.weight(1f))
                    TextButton(onClick = { autoSaveEnabled = !autoSaveEnabled }) {
                        Text(if (autoSaveEnabled) "ON" else "OFF",
                            color = if (autoSaveEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }
                }
                if (autoSaveEnabled) {
                    TextField(value = interval, onValueChange = { interval = it },
                        label = { Text("Interval (seconds)") }, singleLine = true)
                }
                Spacer(Modifier.height(16.dp))
                Text("Default Font Size: ${fontSize.toInt()}pt")
                androidx.compose.material3.Slider(value = fontSize, onValueChange = { fontSize = it }, valueRange = 8f..48f)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(autoSaveEnabled, interval.toIntOrNull() ?: 60, fontSize)
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// --- Sort Dialog ---

@Composable
fun SortDialog(maxCols: Int, onSort: (colIndex: Int, ascending: Boolean) -> Unit, onDismiss: () -> Unit) {
    var col by remember { mutableStateOf("0") }
    var ascending by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sort Rows") },
        text = {
            Column {
                TextField(value = col, onValueChange = { col = it },
                    label = { Text("Column index (0-${maxCols - 1})") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Order:", modifier = Modifier.weight(1f))
                    TextButton(onClick = { ascending = !ascending }) {
                        Text(if (ascending) "A→Z ↑" else "Z→A ↓")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val c = col.toIntOrNull()?.coerceIn(0, maxCols - 1) ?: 0
                onSort(c, ascending); onDismiss()
            }) { Text("Sort") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun ChartEditorDialog(initial: OdfChart?, onConfirm: (OdfChart) -> Unit, onDismiss: () -> Unit) {
    var type by remember { mutableStateOf(initial?.type ?: ChartType.BAR) }
    var categories by remember { mutableStateOf((initial?.categories ?: listOf("Category 1", "Category 2", "Category 3")).joinToString(", ")) }
    var seriesText by remember {
        mutableStateOf(
            (initial?.series ?: listOf(OdfChartSeries("Series 1", listOf(3f, 5f, 2f))))
                .joinToString("\n") { s -> s.name + ": " + s.values.joinToString(", ") { formatAxis(it) } }
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Insert Chart" else "Edit Chart") },
        text = {
            Column {
                Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    Text("Type:", modifier = Modifier.padding(end = 4.dp))
                    for (t in ChartType.entries) {
                        TextButton(onClick = { type = t }) {
                            Text(t.name.lowercase().replaceFirstChar { it.uppercase() }, color = if (type == t) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
                TextField(value = categories, onValueChange = { categories = it }, label = { Text("Categories (comma separated)") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                TextField(value = seriesText, onValueChange = { seriesText = it }, label = { Text("Series (Name: v1, v2, ...)") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cats = categories.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                val series = seriesText.lines().mapNotNull { line ->
                    if (line.isBlank()) return@mapNotNull null
                    val name = (if (line.contains(":")) line.substringBefore(":") else "Series").trim().ifEmpty { "Series" }
                    val valsPart = if (line.contains(":")) line.substringAfter(":") else line
                    val vals = valsPart.split(",").mapNotNull { it.trim().toFloatOrNull() }
                    if (vals.isEmpty()) null else OdfChartSeries(name, vals)
                }
                if (cats.isNotEmpty() && series.isNotEmpty()) { onConfirm(OdfChart(type, cats, series)); onDismiss() }
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// --- Text Document (continuous editor) ---

private sealed class DocSegment {
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
                Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    Column(modifier = Modifier.padding(8.dp)) { for (para in doc.headerParagraphs) ParagraphView(para, "", fontSizeMultiplier) }
                }
            }
        }

        items(segments.size) { si ->
            when (val seg = segments[si]) {
                is DocSegment.Paragraphs -> ContinuousParagraphEditor(
                    doc, seg.start, seg.endInclusive, fontSizeMultiplier, onRunSelectionChange, onRunTextChange,
                    onEnter = { gPos -> onRunEnter(seg.start, seg.endInclusive, gPos) },
                    onBackspace = { gPos -> onRunBackspace(seg.start, seg.endInclusive, gPos) },
                    onToggleCheckbox = onToggleCheckbox,
                    onDeletePrevBlock = { onDeletePrevBlock(seg.start) },
                    remoteCarets = remoteCarets,
                )
                is DocSegment.Block -> when (val block = doc.content[seg.index]) {
                    is OdfContentBlock.Table -> TableView(block.table, seg.index, searchQuery, fontSizeMultiplier, onCellTextChange, onCellFocus)
                    is OdfContentBlock.Image -> {
                        var fullScreen by remember { mutableStateOf(false) }
                        if (fullScreen) FullScreenImage(block.image, onCrop = { fullScreen = false; onCropImage(seg.index) }) { fullScreen = false }
                        else Box(modifier = Modifier.clickable { fullScreen = true }) { OdfImageView(block.image) }
                    }
                    is OdfContentBlock.PageBreak -> PageBreakView()
                    is OdfContentBlock.Chart -> OdfChartView(block.chart, onClick = { onChartClick(seg.index) })
                    is OdfContentBlock.Formula -> MathView(block.mathml)
                    is OdfContentBlock.TableOfContents -> {
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Text(block.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
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
                    Column(Modifier.weight(1f)) { for (para in fn.body) ParagraphView(para, searchQuery, fontSizeMultiplier) }
                }
            }
        }

        if (doc.footerParagraphs.isNotEmpty()) {
            item {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Column(modifier = Modifier.padding(8.dp)) { for (para in doc.footerParagraphs) ParagraphView(para, "", fontSizeMultiplier) }
                }
            }
        }
    }
}

/** Evaluates a cell's conditional-format rules against its value, returning the first match. (Round 3) */
private fun evalCondFormat(rules: List<OdfCondFormat>, numeric: Double?, text: String): OdfCondFormat? {
    val re = Regex("(value\\(\\)|cell-content\\(\\))\\s*(<=|>=|<>|=|<|>)\\s*(.+)")
    for (rule in rules) {
        val m = re.find(rule.condition.trim()) ?: continue
        val op = m.groupValues[2]
        val rhs = m.groupValues[3].trim()
        val matched = if (rhs.startsWith("\"")) {
            val rstr = rhs.trim('"')
            when (op) { "=" -> text == rstr; "<>" -> text != rstr; else -> false }
        } else {
            val rhsNum = rhs.toDoubleOrNull()
            if (numeric != null && rhsNum != null) when (op) {
                "<" -> numeric < rhsNum; "<=" -> numeric <= rhsNum; ">" -> numeric > rhsNum
                ">=" -> numeric >= rhsNum; "=" -> numeric == rhsNum; "<>" -> numeric != rhsNum; else -> false
            } else false
        }
        if (matched) return rule
    }
    return null
}

// --- Paragraph rendering (read-only: used for headers, footers, footnotes, table cells, slides) ---

@Composable
private fun paragraphBaseStyle(style: ParagraphStyle): TextStyle = when (style) {
    ParagraphStyle.HEADING1 -> MaterialTheme.typography.headlineLarge
    ParagraphStyle.HEADING2 -> MaterialTheme.typography.headlineMedium
    ParagraphStyle.HEADING3 -> MaterialTheme.typography.headlineSmall
    ParagraphStyle.HEADING4 -> MaterialTheme.typography.titleLarge
    ParagraphStyle.BODY -> MaterialTheme.typography.bodyLarge
    ParagraphStyle.LIST_ITEM -> MaterialTheme.typography.bodyLarge
    ParagraphStyle.TABLE_HEADER -> MaterialTheme.typography.titleSmall
}

// --- Paragraph rendering ---

@Composable
fun ParagraphView(paragraph: OdfParagraph, searchQuery: String = "", fontSizeMultiplier: Float = 1f, nightTextColor: Color = Color.Unspecified) {
    val baseStyle = paragraphBaseStyle(paragraph.style)
    val prefix = listPrefixFor(paragraph)
    val hasLinks = paragraph.spans.any { it.href != null }
    val hasAnnotations = paragraph.spans.any { it.annotation != null }
    val context = LocalContext.current
    val highlightColor = Color(0xFFFFEB3B)

    val annotatedString = buildAnnotatedString {
        if (prefix.isNotEmpty()) append(prefix)
        for (span in paragraph.spans) {
            val spanAnnotation = span.annotation
            if (spanAnnotation != null) {
                val start = length
                withStyle(SpanStyle(background = Color(0xFFFFF9C4))) { append(span.text) }
                addStringAnnotation("ANNOTATION", "${spanAnnotation.author ?: ""}\n${spanAnnotation.paragraphs.joinToString("\n") { p -> p.spans.joinToString("") { it.text } }}", start, length)
                continue
            }
            val decorations = mutableListOf<TextDecoration>()
            if (span.underline) decorations.add(TextDecoration.Underline)
            if (span.strikethrough) decorations.add(TextDecoration.LineThrough)
            val rawFontSize = span.fontSize?.sp ?: baseStyle.fontSize
            val baseFontSize = if (rawFontSize != TextUnit.Unspecified) rawFontSize * fontSizeMultiplier else rawFontSize
            val effectiveFontSize = if ((span.superscript || span.subscript) && baseFontSize != TextUnit.Unspecified) baseFontSize * 0.7f else baseFontSize
            val spanColor = span.color
            val spanTextColor = when {
                spanColor != null -> Color(spanColor.toInt())
                nightTextColor != Color.Unspecified -> nightTextColor
                else -> Color.Unspecified
            }
            val spanStyle = SpanStyle(
                fontWeight = if (span.bold) FontWeight.Bold else null,
                fontStyle = if (span.italic) FontStyle.Italic else null,
                fontSize = effectiveFontSize,
                textDecoration = if (decorations.isNotEmpty()) TextDecoration.combine(decorations) else null,
                color = spanTextColor,
                background = span.backgroundColor?.let { Color(it.toInt()) } ?: Color.Unspecified,
                letterSpacing = span.letterSpacing?.sp ?: TextUnit.Unspecified,
                baselineShift = when { span.superscript -> BaselineShift.Superscript; span.subscript -> BaselineShift.Subscript; else -> null }
            )
            val shownText = when (span.textTransform) {
                "uppercase" -> span.text.uppercase()
                "lowercase" -> span.text.lowercase()
                "capitalize" -> span.text.split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
                else -> span.text
            }
            if (searchQuery.isNotEmpty() && span.text.contains(searchQuery, ignoreCase = true)) {
                var remaining = span.text
                while (remaining.isNotEmpty()) {
                    val idx = remaining.indexOf(searchQuery, ignoreCase = true)
                    if (idx < 0) { linkOrPlain(span, spanStyle, remaining); break }
                    if (idx > 0) linkOrPlain(span, spanStyle, remaining.substring(0, idx))
                    withStyle(spanStyle.copy(background = highlightColor)) { append(remaining.substring(idx, idx + searchQuery.length)) }
                    remaining = remaining.substring(idx + searchQuery.length)
                }
            } else linkOrPlain(span, spanStyle, shownText)
        }
    }

    val indentDp = if (paragraph.marginLeft > 0 || paragraph.listLevel > 1) (paragraph.marginLeft + maxOf(0, paragraph.listLevel - 1) * 16f).dp else 0.dp
    val verticalPadding = when (paragraph.style) {
        ParagraphStyle.HEADING1 -> 12.dp; ParagraphStyle.HEADING2 -> 10.dp; ParagraphStyle.HEADING3 -> 8.dp; ParagraphStyle.HEADING4 -> 6.dp
        ParagraphStyle.BODY -> 2.dp; ParagraphStyle.LIST_ITEM -> 1.dp; ParagraphStyle.TABLE_HEADER -> 4.dp
    }
    val topPad = if (paragraph.marginTop > 0) paragraph.marginTop.dp else verticalPadding
    val bottomPad = if (paragraph.marginBottom > 0) paragraph.marginBottom.dp else verticalPadding
    val modifier = Modifier.fillMaxWidth()
        .then(if (indentDp > 0.dp) Modifier.padding(start = indentDp) else Modifier)
        .then(paragraph.borderColor?.let { Modifier.border(1.dp, Color(it.toInt())) } ?: Modifier)
        .then(paragraph.backgroundColor?.let { Modifier.background(Color(it.toInt())) } ?: Modifier)
        .padding(top = topPad, bottom = bottomPad)
    val lineHeight = paragraph.lineHeightPercent?.let { lh ->
        val fs = if (baseStyle.fontSize != TextUnit.Unspecified) baseStyle.fontSize else 16.sp
        fs * fontSizeMultiplier * lh
    } ?: TextUnit.Unspecified
    val scaledStyle = if (fontSizeMultiplier != 1f && baseStyle.fontSize != TextUnit.Unspecified) {
        baseStyle.copy(fontSize = baseStyle.fontSize * fontSizeMultiplier, textAlign = paragraph.alignment ?: TextAlign.Unspecified, lineHeight = lineHeight, color = if (nightTextColor != Color.Unspecified) nightTextColor else baseStyle.color)
    } else baseStyle.copy(textAlign = paragraph.alignment ?: TextAlign.Unspecified, lineHeight = lineHeight, color = if (nightTextColor != Color.Unspecified) nightTextColor else baseStyle.color)

    if (hasLinks || hasAnnotations) {
        var expandedAnnotation by remember { mutableStateOf<String?>(null) }
        Column {
            @Suppress("DEPRECATION")
            ClickableText(text = annotatedString, style = scaledStyle, modifier = modifier, onClick = { offset ->
                annotatedString.getStringAnnotations("URL", offset, offset).firstOrNull()?.let { a ->
                    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(a.item))) } catch (_: Exception) {}; return@ClickableText
                }
                annotatedString.getStringAnnotations("ANNOTATION", offset, offset).firstOrNull()?.let { a ->
                    expandedAnnotation = if (expandedAnnotation == a.item) null else a.item
                }
            })
            AnimatedVisibility(visible = expandedAnnotation != null) { expandedAnnotation?.let { AnnotationPopup(it) } }
        }
    } else Text(text = annotatedString, style = scaledStyle, modifier = modifier)
}

private fun androidx.compose.ui.text.AnnotatedString.Builder.linkOrPlain(span: OdfSpan, style: SpanStyle, text: String) {
    val href = span.href
    if (href != null) {
        val s = length; withStyle(style) { append(text) }; addStringAnnotation("URL", href, s, length)
    } else withStyle(style) { append(text) }
}

@Composable
private fun AnnotationPopup(content: String) {
    val parts = content.split("\n", limit = 2)
    val author = parts[0].ifEmpty { null }
    val body = if (parts.size > 1) parts[1] else ""
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            if (author != null) Text(author, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onTertiaryContainer)
            if (body.isNotEmpty()) Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
        }
    }
}

@Composable
private fun FullScreenImage(image: OdfImage, onCrop: (() -> Unit)? = null, onDismiss: () -> Unit) {
    val bitmap = remember(image.path, image.imageData.size) { BitmapFactory.decodeByteArray(image.imageData, 0, image.imageData.size) }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.9f)).clickable { onDismiss() }, contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap = bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxWidth().padding(16.dp), contentScale = ContentScale.Fit)
        if (onCrop != null) {
            TextButton(onClick = onCrop, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
                Icon(painterResource(com.vayunmathur.library.R.drawable.crop_24px), contentDescription = null, tint = Color.White)
                Spacer(Modifier.width(4.dp))
                Text("Crop", color = Color.White)
            }
        }
    }
}

/** Non-destructive crop editor: adjust four insets with a live preview. (Phase 5) */
@Composable
fun ImageCropDialog(
    image: OdfImage,
    onApply: (Float, Float, Float, Float) -> Unit,
    onDismiss: () -> Unit,
    onRotate: () -> Unit = {},
    onReplace: () -> Unit = {}
) {
    var left by remember { mutableFloatStateOf(image.cropLeftPct) }
    var top by remember { mutableFloatStateOf(image.cropTopPct) }
    var right by remember { mutableFloatStateOf(image.cropRightPct) }
    var bottom by remember { mutableFloatStateOf(image.cropBottomPct) }
    val preview = image.copy(cropLeftPct = left, cropTopPct = top, cropRightPct = right, cropBottomPct = bottom, width = 0f, height = 0f)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Image") },
        text = {
            Column {
                Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) { OdfImageView(preview) }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onRotate() }) { Text("Rotate 90°") }
                    TextButton(onClick = { onReplace(); onDismiss() }) { Text("Replace…") }
                }
                CropSlider("Left", left) { left = it }
                CropSlider("Top", top) { top = it }
                CropSlider("Right", right) { right = it }
                CropSlider("Bottom", bottom) { bottom = it }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(left, top, right, bottom); onDismiss() }) { Text("Apply") } },
        dismissButton = {
            Row {
                TextButton(onClick = { onApply(0f, 0f, 0f, 0f); onDismiss() }) { Text("Reset") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

@Composable
private fun CropSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(64.dp), style = MaterialTheme.typography.labelMedium)
        androidx.compose.material3.Slider(value = value, onValueChange = onChange, valueRange = 0f..0.45f, modifier = Modifier.weight(1f))
        Text("${(value * 100).toInt()}%", Modifier.width(40.dp), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun TableView(
    table: OdfTable,
    blockIndex: Int,
    searchQuery: String = "",
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
                for ((c, cell) in row.cells.withIndex()) {
                    if (cell.isCovered) continue
                    Box(
                        Modifier.width(colWidthDp(c, cell.colSpan))
                            .fillMaxHeight()
                            .border(0.7.dp, MaterialTheme.colorScheme.outline)
                            .then(cell.backgroundColor?.let { Modifier.background(Color(it.toInt())) } ?: Modifier)
                            .padding(8.dp)
                    ) {
                        EditableCell(cell, onSurface, fontSizeMultiplier, onFocus = { onCellFocus(blockIndex, r, c) }) { txt -> onCellTextChange(blockIndex, r, c, txt) }
                    }
                }
            }
        }
        if (table.rows.isEmpty()) Text("(empty table)", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
    }
}

@Composable
private fun EditableCell(cell: OdfTableCell, onSurface: Color, mult: Float, onFocus: () -> Unit, onChange: (String) -> Unit) {
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

@Composable
fun OdfImageView(image: OdfImage, modifier: Modifier = Modifier) {
    val bitmap = remember(image.path, image.imageData.size) {
        if (image.imageData.isNotEmpty()) try { BitmapFactory.decodeByteArray(image.imageData, 0, image.imageData.size) } catch (_: Exception) { null } else null
    }
    if (bitmap != null) {
        val rot = if (image.rotationDegrees != 0f) Modifier.rotate(image.rotationDegrees) else Modifier
        val hasCrop = image.cropLeftPct > 0f || image.cropTopPct > 0f || image.cropRightPct > 0f || image.cropBottomPct > 0f
        if (hasCrop) {
            // Non-destructive crop: draw only the visible source rectangle, scaled to fill. (Phase 5)
            val bw = bitmap.width; val bh = bitmap.height
            val visW = (1f - image.cropLeftPct - image.cropRightPct).coerceIn(0.05f, 1f)
            val visH = (1f - image.cropTopPct - image.cropBottomPct).coerceIn(0.05f, 1f)
            val srcX = (image.cropLeftPct * bw).toInt().coerceIn(0, bw - 1)
            val srcY = (image.cropTopPct * bh).toInt().coerceIn(0, bh - 1)
            val srcW = (visW * bw).toInt().coerceIn(1, bw - srcX)
            val srcH = (visH * bh).toInt().coerceIn(1, bh - srcY)
            val aspect = (srcW.toFloat() / srcH).coerceIn(0.1f, 10f)
            val img = bitmap.asImageBitmap()
            val sized = if (image.width > 0f && image.height > 0f)
                modifier.width((image.width * (160f / 96f)).coerceAtMost(700f).dp).aspectRatio(aspect)
            else modifier.fillMaxWidth().aspectRatio(aspect.coerceIn(0.3f, 4f))
            Canvas(sized.then(rot).padding(vertical = 4.dp)) {
                drawImage(
                    image = img,
                    srcOffset = IntOffset(srcX, srcY),
                    srcSize = IntSize(srcW, srcH),
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1))
                )
            }
            return
        }
        val useExplicit = image.width > 0f && image.height > 0f
        if (useExplicit) {
            // Honor the frame's svg:width/height (px@96dpi -> dp), capped to a readable width. (A2)
            val widthDp = (image.width * (160f / 96f)).coerceAtMost(700f)
            val aspect = (image.width / image.height).coerceIn(0.1f, 10f)
            Image(
                bitmap = bitmap.asImageBitmap(), contentDescription = null,
                modifier = modifier.width(widthDp.dp).aspectRatio(aspect).then(rot).padding(vertical = 4.dp),
                contentScale = ContentScale.Fit
            )
        } else {
            val aspect = if (bitmap.height > 0) bitmap.width.toFloat() / bitmap.height.toFloat() else 1.5f
            Image(
                bitmap = bitmap.asImageBitmap(), contentDescription = null,
                modifier = modifier.fillMaxWidth().aspectRatio(aspect.coerceIn(0.3f, 4f)).then(rot).padding(vertical = 4.dp),
                contentScale = ContentScale.Fit
            )
        }
    } else {
        Box(modifier.fillMaxWidth().height(120.dp).padding(vertical = 4.dp).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Text("[Image]", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val chartPalette = listOf(
    Color(0xFF1F6FC0), Color(0xFFE8551E), Color(0xFFF2B600),
    Color(0xFF3FA34D), Color(0xFF8E44AD), Color(0xFF16A2B8), Color(0xFFD81B60)
)

private fun formatAxis(v: Float): String = if (v == v.toLong().toFloat()) v.toLong().toString() else "%.1f".format(v)

@Composable
private fun OdfChartView(chart: OdfChart, onClick: () -> Unit = {}) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    Column(Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 12.dp)) {
        chart.title?.let { Text(it, style = MaterialTheme.typography.titleSmall, color = onSurface, modifier = Modifier.padding(bottom = 4.dp)) }
        // Legend
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            chart.series.forEachIndexed { i, s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).background(chartPalette[i % chartPalette.size]))
                    Spacer(Modifier.width(4.dp))
                    Text(s.name, style = MaterialTheme.typography.labelMedium, color = onSurface)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        val labelArgb = onSurface.toArgb()
        Canvas(Modifier.fillMaxWidth().height(240.dp)) {
            val maxV = (chart.series.flatMap { it.values }.maxOrNull() ?: 1f).coerceAtLeast(1f)
            val leftPad = 72f; val bottomPad = 56f; val topPad = 12f; val rightPad = 12f
            val plotW = size.width - leftPad - rightPad
            val plotH = size.height - bottomPad - topPad
            val axisPaint = android.graphics.Paint().apply { color = labelArgb; textSize = 26f; isAntiAlias = true }
            val centerPaint = android.graphics.Paint().apply { color = labelArgb; textSize = 26f; isAntiAlias = true; textAlign = android.graphics.Paint.Align.CENTER }
            val steps = 5
            for (s in 0..steps) {
                val v = maxV * s / steps
                val y = topPad + plotH - plotH * s / steps
                drawLine(gridColor, Offset(leftPad, y), Offset(leftPad + plotW, y), 1f)
                drawContext.canvas.nativeCanvas.drawText(formatAxis(v), 6f, y + 9f, axisPaint)
            }
            drawLine(onSurface, Offset(leftPad, topPad), Offset(leftPad, topPad + plotH), 2f)
            drawLine(onSurface, Offset(leftPad, topPad + plotH), Offset(leftPad + plotW, topPad + plotH), 2f)
            val catCount = chart.categories.size.coerceAtLeast(1)
            when (chart.type) {
                ChartType.LINE -> {
                    val stepX = if (catCount > 1) plotW / (catCount - 1) else plotW
                    chart.series.forEachIndexed { si, ser ->
                        val col = chartPalette[si % chartPalette.size]
                        var prev: Offset? = null
                        for (ci in 0 until catCount) {
                            val v = ser.values.getOrNull(ci) ?: 0f
                            val p = Offset(leftPad + stepX * ci, topPad + plotH - plotH * (v / maxV))
                            prev?.let { drawLine(col, it, p, 4f) }
                            drawCircle(col, 5f, p)
                            prev = p
                        }
                    }
                    for (ci in 0 until catCount) {
                        val x = leftPad + (if (catCount > 1) plotW / (catCount - 1) else plotW) * ci
                        drawContext.canvas.nativeCanvas.drawText(chart.categories[ci], x, topPad + plotH + 34f, centerPaint)
                    }
                }
                ChartType.SCATTER -> {
                    val stepX = if (catCount > 1) plotW / (catCount - 1) else plotW
                    chart.series.forEachIndexed { si, ser ->
                        val col = chartPalette[si % chartPalette.size]
                        for (ci in 0 until catCount) {
                            val v = ser.values.getOrNull(ci) ?: 0f
                            drawCircle(col, 7f, Offset(leftPad + stepX * ci, topPad + plotH - plotH * (v / maxV)))
                        }
                    }
                    for (ci in 0 until catCount) {
                        val x = leftPad + (if (catCount > 1) plotW / (catCount - 1) else plotW) * ci
                        drawContext.canvas.nativeCanvas.drawText(chart.categories.getOrElse(ci) { "" }, x, topPad + plotH + 34f, centerPaint)
                    }
                }
                ChartType.PIE, ChartType.DONUT -> {
                    val values = chart.series.firstOrNull()?.values ?: emptyList()
                    val total = values.sum().coerceAtLeast(0.0001f)
                    val d = minOf(plotW, plotH)
                    val topLeft = Offset(leftPad + (plotW - d) / 2, topPad + (plotH - d) / 2)
                    var startAngle = -90f
                    values.forEachIndexed { i, v ->
                        val sweep = 360f * (v / total)
                        drawArc(chartPalette[i % chartPalette.size], startAngle, sweep, true, topLeft, Size(d, d))
                        startAngle += sweep
                    }
                    if (chart.type == ChartType.DONUT) {
                        val hole = d * 0.5f
                        drawArc(Color.White, 0f, 360f, true, Offset(topLeft.x + (d - hole) / 2, topLeft.y + (d - hole) / 2), Size(hole, hole))
                    }
                }
                ChartType.STACKED_BAR -> {
                    val groupW = plotW / catCount
                    val pad = groupW * 0.2f
                    val barW = groupW - 2 * pad
                    val stackMax = (0 until catCount).maxOfOrNull { ci -> chart.series.sumOf { (it.values.getOrNull(ci) ?: 0f).toDouble() }.toFloat() }?.coerceAtLeast(1f) ?: 1f
                    for (ci in 0 until catCount) {
                        var yCursor = topPad + plotH
                        chart.series.forEachIndexed { si, ser ->
                            val v = ser.values.getOrNull(ci) ?: 0f
                            val h = plotH * (v / stackMax)
                            drawRect(chartPalette[si % chartPalette.size], Offset(leftPad + groupW * ci + pad, yCursor - h), Size(barW, h))
                            yCursor -= h
                        }
                        drawContext.canvas.nativeCanvas.drawText(chart.categories.getOrElse(ci) { "" }, leftPad + groupW * ci + groupW / 2, topPad + plotH + 34f, centerPaint)
                    }
                }
                else -> { // BAR / AREA -> grouped bars
                    val serCount = chart.series.size.coerceAtLeast(1)
                    val groupW = plotW / catCount
                    val pad = groupW * 0.15f
                    val barW = (groupW - 2 * pad) / serCount
                    for (ci in 0 until catCount) {
                        val gx = leftPad + groupW * ci + pad
                        for (si in 0 until serCount) {
                            val v = chart.series[si].values.getOrNull(ci) ?: 0f
                            val h = plotH * (v / maxV)
                            drawRect(chartPalette[si % chartPalette.size], Offset(gx + barW * si, topPad + plotH - h), Size(barW * 0.92f, h))
                        }
                        drawContext.canvas.nativeCanvas.drawText(chart.categories.getOrElse(ci) { "" }, leftPad + groupW * ci + groupW / 2, topPad + plotH + 34f, centerPaint)
                    }
                }
            }
        }
    }
}

@Composable
private fun PageBreakView() {
    HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp), thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
}

// --- Math (MathML) layout rendering (D34) ---

@Composable
fun MathView(mathml: String) {
    val node = remember(mathml) { OdfMath.parse(mathml) }
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        if (node == null) {
            Text(mathml.take(200), style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic)
        } else {
            MathNodeView(node, 20f, MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun MathNodeView(node: MathNode, sizeSp: Float, color: Color) {
    when (node) {
        is MathNode.Row -> Row(verticalAlignment = Alignment.CenterVertically) {
            for (child in node.children) MathNodeView(child, sizeSp, color)
        }
        is MathNode.Token -> Text(
            node.text,
            fontSize = sizeSp.sp,
            color = color,
            fontStyle = if (!node.isOperator && node.text.length == 1 && node.text[0].isLetter()) FontStyle.Italic else FontStyle.Normal,
            modifier = Modifier.padding(horizontal = if (node.isOperator) 3.dp else 0.5.dp)
        )
        is MathNode.Frac -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 3.dp).width(IntrinsicSize.Max)) {
            MathNodeView(node.numerator, sizeSp * 0.95f, color)
            HorizontalDivider(color = color, thickness = 1.5.dp, modifier = Modifier.fillMaxWidth())
            MathNodeView(node.denominator, sizeSp * 0.95f, color)
        }
        is MathNode.Sup -> Row(verticalAlignment = Alignment.Top) {
            MathNodeView(node.base, sizeSp, color)
            MathNodeView(node.exponent, sizeSp * 0.7f, color)
        }
        is MathNode.Sub -> Row(verticalAlignment = Alignment.Bottom) {
            MathNodeView(node.base, sizeSp, color)
            MathNodeView(node.subscript, sizeSp * 0.7f, color)
        }
        is MathNode.SubSup -> Row(verticalAlignment = Alignment.CenterVertically) {
            MathNodeView(node.base, sizeSp, color)
            Column {
                MathNodeView(node.superscript, sizeSp * 0.7f, color)
                MathNodeView(node.subscript, sizeSp * 0.7f, color)
            }
        }
        is MathNode.Sqrt -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text("\u221A", fontSize = sizeSp.sp, color = color)
            Column { HorizontalDivider(color = color, thickness = 1.dp); Box(Modifier.padding(top = 1.dp)) { MathNodeView(node.radicand, sizeSp, color) } }
        }
        is MathNode.Root -> Row(verticalAlignment = Alignment.CenterVertically) {
            MathNodeView(node.index, sizeSp * 0.6f, color)
            Text("\u221A", fontSize = sizeSp.sp, color = color)
            Column { HorizontalDivider(color = color, thickness = 1.dp); Box(Modifier.padding(top = 1.dp)) { MathNodeView(node.radicand, sizeSp, color) } }
        }
        is MathNode.Fenced -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(node.open, fontSize = sizeSp.sp, color = color)
            MathNodeView(node.body, sizeSp, color)
            Text(node.close, fontSize = sizeSp.sp, color = color)
        }
        is MathNode.Under -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            MathNodeView(node.base, sizeSp, color)
            MathNodeView(node.under, sizeSp * 0.7f, color)
        }
        is MathNode.Over -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            MathNodeView(node.over, sizeSp * 0.7f, color)
            MathNodeView(node.base, sizeSp, color)
        }
        is MathNode.UnderOver -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            MathNodeView(node.over, sizeSp * 0.7f, color)
            MathNodeView(node.base, sizeSp, color)
            MathNodeView(node.under, sizeSp * 0.7f, color)
        }
        is MathNode.Table -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            for (r in node.rows) MathNodeView(r, sizeSp, color)
        }
        is MathNode.TableRow -> Row(verticalAlignment = Alignment.CenterVertically) {
            for (cell in node.cells) Box(Modifier.padding(horizontal = 4.dp)) { MathNodeView(cell, sizeSp, color) }
        }
        is MathNode.Multiscripts -> Row(verticalAlignment = Alignment.CenterVertically) {
            if (node.preSub != null || node.preSup != null) Column {
                node.preSup?.let { MathNodeView(it, sizeSp * 0.7f, color) }
                node.preSub?.let { MathNodeView(it, sizeSp * 0.7f, color) }
            }
            MathNodeView(node.base, sizeSp, color)
            if (node.postSub != null || node.postSup != null) Column {
                node.postSup?.let { MathNodeView(it, sizeSp * 0.7f, color) }
                node.postSub?.let { MathNodeView(it, sizeSp * 0.7f, color) }
            }
        }
    }
}

// --- Spreadsheet ---

@Composable
fun SpreadsheetView(
    doc: OdfDocument.Spreadsheet, searchQuery: String = "", fontSizeMultiplier: Float = 1f,
    isEditMode: Boolean = false,
    onCellTextChange: (Int, Int, Int, String) -> Unit = { _, _, _, _ -> },
    onAddRow: (Int, Int) -> Unit = { _, _ -> }, onAddColumn: (Int) -> Unit = {},
    onDeleteRow: (Int, Int) -> Unit = { _, _ -> }, onDeleteColumn: (Int, Int) -> Unit = { _, _ -> },
    onRenameSheet: (Int, String) -> Unit = { _, _ -> }, onAddSheet: () -> Unit = {}, onDeleteSheet: (Int) -> Unit = {},
    onCellBold: (Int, Int, Int) -> Unit = { _, _, _ -> }, onCellItalic: (Int, Int, Int) -> Unit = { _, _, _ -> },
    onCellColor: (Int, Int, Int, Long?) -> Unit = { _, _, _, _ -> }, onCellBgColor: (Int, Int, Int, Long?) -> Unit = { _, _, _, _ -> },
    onCellAlignment: (Int, Int, Int, TextAlign?) -> Unit = { _, _, _, _ -> },
    onMergeCells: (Int, Int, Int, Int, Int) -> Unit = { _, _, _, _, _ -> },
    onUnmergeCells: (Int, Int, Int) -> Unit = { _, _, _ -> },
    onSort: (Int, Int, Boolean) -> Unit = { _, _, _ -> },
    onCellSelected: (Int, Int, Int) -> Unit = { _, _, _ -> },
    onFloatingBoundsChange: (Int, Int, Float, Float, Float, Float) -> Unit = { _, _, _, _, _, _ -> },
    onFloatingTextChange: (Int, Int, String) -> Unit = { _, _, _ -> },
    onFloatingDelete: (Int, Int) -> Unit = { _, _ -> },
    onFloatingCrop: (Int, Int) -> Unit = { _, _ -> },
    onSetFreeze: (Int, Int, Int) -> Unit = { _, _, _ -> }
) {
    if (doc.sheets.isEmpty()) { Text("Empty spreadsheet", modifier = Modifier.padding(16.dp)); return }

    var selectedSheet by remember { mutableIntStateOf(0) }
    var selectedFloating by remember { mutableIntStateOf(-1) }
    var editingCell by remember { mutableStateOf<Triple<Int, Int, Int>?>(null) }
    var dropdownCell by remember { mutableStateOf<Triple<Int, Int, Int>?>(null) }
    val validationsByName = remember(doc) { doc.validations.associateBy { it.name } }
    var editText by remember { mutableStateOf(TextFieldValue("")) }
    var showRenameSheet by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var showSortDialog by remember { mutableStateOf(false) }

    // A3: keep the selected sheet/floating indices valid after undo/redo shrinks the lists.
    selectedSheet = selectedSheet.coerceIn(0, doc.sheets.size - 1)
    if (selectedFloating >= doc.sheets[selectedSheet].floating.size) selectedFloating = -1

    Column(modifier = Modifier.fillMaxSize()) {
        if (doc.sheets.size > 1 || isEditMode) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PrimaryScrollableTabRow(selectedTabIndex = selectedSheet, modifier = Modifier.weight(1f)) {
                    doc.sheets.forEachIndexed { index, sheet ->
                        Tab(selected = selectedSheet == index, onClick = { selectedSheet = index; editingCell = null; selectedFloating = -1; onCellSelected(index, -1, -1) },
                            text = { if (isEditMode) Text(sheet.name, Modifier.clickable { renameText = sheet.name; showRenameSheet = true }) else Text(sheet.name) })
                    }
                }
                if (isEditMode) TextButton(onClick = { onAddSheet() }) { Text("+") }
            }
        } else {
            Text(doc.sheets[0].name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp, 8.dp))
        }

        if (isEditMode && editingCell != null) {
            val (_, ri, ci) = editingCell!!
            val focusRequester = remember { FocusRequester() }
            val rowCount = doc.sheets[selectedSheet].rows.size
            LaunchedEffect(editingCell) { try { focusRequester.requestFocus() } catch (_: Exception) {} }
            fun commitAndAdvance() {
                val (si, r, c) = editingCell!!
                onCellTextChange(si, r, c, editText.text)
                if (r + 1 < rowCount) {
                    editingCell = Triple(si, r + 1, c)
                    onCellSelected(si, r + 1, c)
                    val nextText = doc.sheets[si].rows.getOrNull(r + 1)?.cells?.getOrNull(c)?.let { it.formula ?: it.text } ?: ""
                    editText = TextFieldValue(nextText, TextRange(0, nextText.length))
                } else { editingCell = null; onCellSelected(si, -1, -1) }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${columnLabel(ci)}${ri + 1}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 8.dp))
                TextField(value = editText, onValueChange = { editText = it }, singleLine = true,
                    modifier = Modifier.weight(1f).focusRequester(focusRequester),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { commitAndAdvance() }, onDone = { commitAndAdvance() }),
                    colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant))
                TextButton(onClick = { val (si, r, c) = editingCell!!; onCellTextChange(si, r, c, editText.text); editingCell = null; onCellSelected(si, -1, -1) }) { Text("Done") }
            }
        }

        if (isEditMode) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { val ri = editingCell?.second ?: (doc.sheets[selectedSheet].rows.size - 1); onAddRow(selectedSheet, ri) }) { Text("+ Row") }
                TextButton(onClick = { onAddColumn(selectedSheet) }) { Text("+ Col") }
                if (editingCell != null) {
                    TextButton(onClick = { onDeleteRow(selectedSheet, editingCell!!.second); editingCell = null }) { Text("- Row") }
                    TextButton(onClick = { onDeleteColumn(selectedSheet, editingCell!!.third); editingCell = null }) { Text("- Col") }
                }
                TextButton(onClick = { showSortDialog = true }) { Text("Sort") }
                run {
                    val sheet0 = doc.sheets[selectedSheet]
                    val frozen = sheet0.freezeRows > 0 || sheet0.freezeCols > 0
                    if (frozen) {
                        TextButton(onClick = { onSetFreeze(selectedSheet, 0, 0) }) { Text("Unfreeze") }
                    } else {
                        TextButton(onClick = {
                            // Freeze rows above and columns left of the active/editing cell (default: header row).
                            val r = editingCell?.second ?: 1
                            val c = editingCell?.third ?: 0
                            onSetFreeze(selectedSheet, r, c)
                        }) { Text("Freeze") }
                    }
                }
                Spacer(Modifier.weight(1f))
                if (doc.sheets.size > 1) TextButton(onClick = { onDeleteSheet(selectedSheet); if (selectedSheet >= doc.sheets.size - 1) selectedSheet = maxOf(0, doc.sheets.size - 2) }) { Text("- Sheet", color = MaterialTheme.colorScheme.error) }
            }
        }

        val sheet = doc.sheets[selectedSheet]
        val workbook = remember(doc) { doc.sheets.associateBy { it.name } }
        val maxCols = sheet.rows.maxOfOrNull { it.cells.count { c -> !c.isCovered } } ?: 0
        fun colWidthDp(start: Int, span: Int): androidx.compose.ui.unit.Dp {
            var w = 0f
            for (k in start until start + span) {
                val px = sheet.columnWidths.getOrNull(k)
                w += if (px != null && px > 0f) (px * (160f / 96f)).coerceIn(40f, 400f) else 84f
            }
            return w.dp
        }

        // A1: anchor floating objects inside the scrolled grid content (rides both scroll axes).
        // Map model px@96 -> grid content dp by sizing the overlay to the content and passing the
        // content's dp dimensions as the reference space.
        val hScroll = rememberScrollState()
        var contentWidthDp = 40f
        for (col in 0 until maxCols) contentWidthDp += colWidthDp(col, 1).value
        val contentHeightDp = 26f + sheet.rows.size * 33f

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                Row(Modifier.horizontalScroll(hScroll).padding(horizontal = 4.dp)) {
                    Box {
                        Column {
                            Row {
                                Box(Modifier.defaultMinSize(minWidth = 40.dp).background(MaterialTheme.colorScheme.surfaceVariant).border(0.5.dp, MaterialTheme.colorScheme.outline).padding(4.dp), contentAlignment = Alignment.Center) { Text("") }
                                for (col in 0 until maxCols) Box(Modifier.width(colWidthDp(col, 1)).background(MaterialTheme.colorScheme.surfaceVariant).border(0.5.dp, MaterialTheme.colorScheme.outline).padding(4.dp), contentAlignment = Alignment.Center) {
                                    Text(columnLabel(col), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                }
                            }
                            for ((rowIdx, row) in sheet.rows.withIndex()) {
                                Row(Modifier.height(IntrinsicSize.Min)) {
                                    Box(Modifier.defaultMinSize(minWidth = 40.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceVariant).border(0.5.dp, MaterialTheme.colorScheme.outline).padding(4.dp), contentAlignment = Alignment.Center) {
                                        Text("${rowIdx + 1}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                    }
                                    var colspanSkip = 0
                                    for ((cellIdx, cell) in row.cells.withIndex()) {
                                        if (cell.isCovered) {
                                            // Covered by a horizontal merge to its left: consume silently.
                                            if (colspanSkip > 0) { colspanSkip--; continue }
                                            // Covered by a vertical (row) merge from above: render an empty
                                            // aligned placeholder so columns don't shift. (Tier 0 bugfix)
                                            Box(Modifier.width(colWidthDp(cellIdx, 1)).fillMaxHeight()
                                                .border(0.5.dp, MaterialTheme.colorScheme.outline)) {}
                                            continue
                                        }
                                        colspanSkip = if (cell.spannedColumns > 1) cell.spannedColumns - 1 else 0
                                        val isEditing = editingCell?.let { it.first == selectedSheet && it.second == rowIdx && it.third == cellIdx } == true
                                        val isMatch = searchQuery.isNotEmpty() && cell.text.contains(searchQuery, ignoreCase = true)
                                        val displayText = OdfFormulaEngine.displayValue(sheet, rowIdx, cellIdx, workbook, sheet.name)
                                        val cf = if (cell.condFormats.isEmpty()) null else evalCondFormat(cell.condFormats, cell.numberValue ?: displayText.toDoubleOrNull(), displayText)
                                        val effBg = cf?.backgroundColor ?: cell.backgroundColor
                                        Box(
                                            Modifier.width(colWidthDp(cellIdx, cell.spannedColumns))
                                                .fillMaxHeight()
                                                .border(if (isEditing) 2.dp else 0.5.dp, if (isEditing) MaterialTheme.colorScheme.primary else (cell.borderColor?.let { Color(it.toInt()) } ?: MaterialTheme.colorScheme.outline))
                                                .then(if (!isEditing && cell.borders?.isEmpty() == false) Modifier.drawBehind {
                                                    val sw = 1.5.dp.toPx()
                                                    OdfBorders.renderColor(cell.borders!!.top)?.let { drawLine(Color(it.toInt()), Offset(0f, 0f), Offset(size.width, 0f), sw) }
                                                    OdfBorders.renderColor(cell.borders!!.bottom)?.let { drawLine(Color(it.toInt()), Offset(0f, size.height), Offset(size.width, size.height), sw) }
                                                    OdfBorders.renderColor(cell.borders!!.left)?.let { drawLine(Color(it.toInt()), Offset(0f, 0f), Offset(0f, size.height), sw) }
                                                    OdfBorders.renderColor(cell.borders!!.right)?.let { drawLine(Color(it.toInt()), Offset(size.width, 0f), Offset(size.width, size.height), sw) }
                                                } else Modifier)
                                                .then(if (isMatch) Modifier.background(Color(0xFFFFEB3B).copy(alpha = 0.3f)) else effBg?.let { Modifier.background(Color(it.toInt())) } ?: Modifier)
                                                .then(if (isEditMode) Modifier.clickable { editingCell = Triple(selectedSheet, rowIdx, cellIdx); selectedFloating = -1; onCellSelected(selectedSheet, rowIdx, cellIdx); val t = cell.formula ?: cell.text; editText = TextFieldValue(t, TextRange(0, t.length)) } else Modifier)
                                                .then(if (cell.annotation != null) Modifier.drawBehind {
                                                    // Red corner triangle marks a cell comment. (Round 3)
                                                    val s = 7.dp.toPx()
                                                    drawPath(androidx.compose.ui.graphics.Path().apply {
                                                        moveTo(size.width - s, 0f); lineTo(size.width, 0f); lineTo(size.width, s); close()
                                                    }, Color(0xFFD32F2F))
                                                } else Modifier)
                                                .padding(8.dp, 4.dp)
                                        ) {
                                            val cellAlign = cell.alignment ?: if (OdfFormulaEngine.isNumeric(sheet, rowIdx, cellIdx, workbook, sheet.name)) TextAlign.End else null
                                            Text(displayText,
                                                style = MaterialTheme.typography.bodyMedium.let { if (fontSizeMultiplier != 1f && it.fontSize != TextUnit.Unspecified) it.copy(fontSize = it.fontSize * fontSizeMultiplier) else it },
                                                fontWeight = if (cell.bold) FontWeight.Bold else null,
                                                fontStyle = if (cell.italic) FontStyle.Italic else null,
                                                color = (cf?.textColor ?: cell.textColor)?.let { Color(it.toInt()) } ?: Color.Unspecified,
                                                textAlign = cellAlign, maxLines = if (cell.wrap) Int.MAX_VALUE else 3)
                                            // Data-validation list dropdown (Round 3).
                                            val listVals = cell.validationName?.let { validationsByName[it]?.listValues() }
                                            if (listVals != null && isEditMode) {
                                                val here = Triple(selectedSheet, rowIdx, cellIdx)
                                                Box(Modifier.align(Alignment.CenterEnd)) {
                                                    Text("▾", Modifier.clickable { dropdownCell = here }, fontWeight = FontWeight.Bold)
                                                    DropdownMenu(expanded = dropdownCell == here, onDismissRequest = { if (dropdownCell == here) dropdownCell = null }) {
                                                        for (opt in listVals) DropdownMenuItem(text = { Text(opt) }, onClick = { onCellTextChange(selectedSheet, rowIdx, cellIdx, opt); dropdownCell = null })
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (isEditMode || sheet.floating.isNotEmpty()) {
                            FloatingElementLayer(
                                elements = sheet.floating, refW = contentWidthDp, refH = contentHeightDp,
                                modifier = Modifier.matchParentSize(),
                                editMode = isEditMode, selectedIndex = selectedFloating,
                                keyPrefix = "sheet$selectedSheet", interactiveBackground = false,
                                onSelect = { selectedFloating = it },
                                onElementTextChange = { ei, t -> onFloatingTextChange(selectedSheet, ei, t) },
                                onBoundsChange = { ei, x, y, w, h -> onFloatingBoundsChange(selectedSheet, ei, x, y, w, h) },
                                onDelete = { ei -> onFloatingDelete(selectedSheet, ei); selectedFloating = -1 },
                                onCropImage = { ei -> onFloatingCrop(selectedSheet, ei) }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showRenameSheet) {
        AlertDialog(onDismissRequest = { showRenameSheet = false }, title = { Text("Rename Sheet") },
            text = { TextField(value = renameText, onValueChange = { renameText = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { onRenameSheet(selectedSheet, renameText); showRenameSheet = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { showRenameSheet = false }) { Text("Cancel") } })
    }
    if (showSortDialog) {
        val maxC = doc.sheets[selectedSheet].rows.maxOfOrNull { it.cells.size } ?: 1
        SortDialog(maxC, onSort = { col, asc -> onSort(selectedSheet, col, asc) }, onDismiss = { showSortDialog = false })
    }
}

private fun columnLabel(index: Int): String {
    val sb = StringBuilder(); var n = index
    do { sb.insert(0, ('A' + n % 26)); n = n / 26 - 1 } while (n >= 0)
    return sb.toString()
}

// --- Presentation ---

@Composable
fun PresentationView(
    doc: OdfDocument.Presentation,
    isEditMode: Boolean = false,
    onAddSlide: (Int) -> Unit = {},
    onDeleteSlide: (Int) -> Unit = {},
    onDuplicateSlide: (Int) -> Unit = {},
    onMoveSlideUp: (Int) -> Unit = {},
    onMoveSlideDown: (Int) -> Unit = {},
    onElementTextChange: (slideIndex: Int, elementIndex: Int, text: String) -> Unit = { _, _, _ -> },
    onAddTextBox: (Int) -> Unit = {},
    onElementBoundsChange: (Int, Int, Float, Float, Float, Float) -> Unit = { _, _, _, _, _, _ -> },
    onDeleteElement: (Int, Int) -> Unit = { _, _ -> },
    selectedElement: Int = -1,
    onSlideChange: (Int) -> Unit = {},
    onElementSelected: (Int, Int) -> Unit = { _, _ -> },
    onCropImage: (Int, Int) -> Unit = { _, _ -> }
) {
    if (doc.slides.isEmpty()) { Text("Empty presentation", modifier = Modifier.padding(16.dp)); return }

    var currentSlide by remember { mutableIntStateOf(0) }
    var showGoToSlide by remember { mutableStateOf(false) }
    var showSlideshow by remember { mutableStateOf(false) }
    var dragAccumulator by remember { mutableFloatStateOf(0f) }
    currentSlide = currentSlide.coerceIn(0, doc.slides.size - 1)
    LaunchedEffect(currentSlide) { onSlideChange(currentSlide); onElementSelected(currentSlide, -1) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Main slide with swipe gesture
        Box(
            modifier = Modifier.weight(1f).pointerInput(doc.slides.size) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (dragAccumulator < -100 && currentSlide < doc.slides.size - 1) currentSlide++
                        else if (dragAccumulator > 100 && currentSlide > 0) currentSlide--
                        dragAccumulator = 0f
                    },
                    onHorizontalDrag = { _, dragAmount -> dragAccumulator += dragAmount }
                )
            }
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                item {
                    SlideCard(
                        slide = doc.slides[currentSlide],
                        editMode = isEditMode,
                        selectedIndex = selectedElement,
                        onSelect = { onElementSelected(currentSlide, it) },
                        onElementTextChange = { ei, t -> onElementTextChange(currentSlide, ei, t) },
                        onBoundsChange = { ei, x, y, w, h -> onElementBoundsChange(currentSlide, ei, x, y, w, h) },
                        onDelete = { ei -> onDeleteElement(currentSlide, ei); onElementSelected(currentSlide, -1) },
                        onCropImage = { ei -> onCropImage(currentSlide, ei) }
                    )
                }
            }
        }

        // Slide editing controls
        if (isEditMode) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onAddSlide(currentSlide) }) { Text("+ Slide") }
                TextButton(onClick = { onAddTextBox(currentSlide) }) { Text("+ Text") }
                TextButton(onClick = { onDuplicateSlide(currentSlide) }) { Text("Dup") }
                TextButton(onClick = { onMoveSlideUp(currentSlide); if (currentSlide > 0) currentSlide-- }) { Text("↑") }
                TextButton(onClick = { onMoveSlideDown(currentSlide); if (currentSlide < doc.slides.size - 1) currentSlide++ }) { Text("↓") }
                if (doc.slides.size > 1) TextButton(onClick = { onDeleteSlide(currentSlide); currentSlide = minOf(currentSlide, doc.slides.size - 2).coerceAtLeast(0) }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            }
        }

        // Slide thumbnail strip
        if (doc.slides.size > 1) {
            HorizontalDivider()
            LazyRow(Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                items(doc.slides.size) { index ->
                    SlideThumbnail(doc.slides[index], index, index == currentSlide) { currentSlide = index }
                }
            }
        }

        // Navigation bar
        Surface(tonalElevation = 3.dp) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { if (currentSlide > 0) currentSlide-- }, enabled = currentSlide > 0) { Text("◀ Prev") }
                TextButton(onClick = { showSlideshow = true }) { Text("▶ Play") }
                TextButton(onClick = { showGoToSlide = true }) {
                    Text("Slide ${currentSlide + 1} of ${doc.slides.size}", style = MaterialTheme.typography.titleSmall)
                }
                TextButton(onClick = { if (currentSlide < doc.slides.size - 1) currentSlide++ }, enabled = currentSlide < doc.slides.size - 1) { Text("Next ▶") }
            }
        }
    }

    if (showGoToSlide) GoToSlideDialog(doc.slides.size, onGo = { currentSlide = it }, onDismiss = { showGoToSlide = false })
    if (showSlideshow) SlideshowDialog(doc.slides, currentSlide, onSlideChange = { currentSlide = it }, onDismiss = { showSlideshow = false })
}

/** Editable text field for a slide element, bound by a stable key so it resets per slide/element. (I62) */
@Composable
private fun SlideElementTextField(key: String, initial: String, label: String, onChange: (String) -> Unit) {
    var tfv by remember(key) { mutableStateOf(TextFieldValue(initial)) }
    TextField(
        value = tfv,
        onValueChange = { tfv = it; onChange(it.text) },
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant)
    )
}

/** Fullscreen slideshow: tap right half = next, left half = previous. (I63) */
@Composable
private fun SlideshowDialog(slides: List<OdfSlide>, startIndex: Int, onSlideChange: (Int) -> Unit, onDismiss: () -> Unit) {
    var index by remember { mutableIntStateOf(startIndex.coerceIn(0, slides.size - 1)) }
    Dialog(onDismissRequest = { onSlideChange(index); onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val slide = slides[index]
            val (refW, refH) = slideBounds(slide)
            Box(Modifier.fillMaxWidth().aspectRatio((refW / refH).coerceIn(0.5f, 3f)).align(Alignment.Center).background(Color.White)) {
                SlideCanvas(slide, refW, refH)
            }
            // Tap zones
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight().clickable { if (index > 0) { index--; onSlideChange(index) } })
                Box(Modifier.weight(1f).fillMaxHeight().clickable { if (index < slides.size - 1) { index++; onSlideChange(index) } else { onSlideChange(index); onDismiss() } })
            }
            Text("${index + 1} / ${slides.size}", color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp))
            TextButton(onClick = { onSlideChange(index); onDismiss() }, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) { Text("Close", color = Color.White) }
        }
    }
}

@Composable
private fun SlideThumbnail(slide: OdfSlide, index: Int, isSelected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.width(120.dp).aspectRatio(16f / 9f).clickable { onClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 4.dp else 1.dp),
        colors = CardDefaults.cardColors(containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant),
        border = if (isSelected) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Box(Modifier.fillMaxSize().then(slide.backgroundColor?.let { Modifier.background(Color(it.toInt())) } ?: Modifier).padding(4.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val firstText = slide.elements.firstNotNullOfOrNull { el ->
                    when (el) { is OdfSlideElement.Frame -> el.frame.paragraphs.firstOrNull()?.spans?.joinToString("") { it.text }?.take(30); is OdfSlideElement.Shape -> el.shape.text.firstOrNull()?.spans?.joinToString("") { it.text }?.take(30) }
                }
                Text("${index + 1}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                if (firstText != null) Text(firstText, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun DrawingView(doc: OdfDocument.Drawing) {
    if (doc.pages.isEmpty()) { Text("Empty drawing", modifier = Modifier.padding(16.dp)); return }
    var currentPage by remember { mutableIntStateOf(0) }
    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.weight(1f).padding(8.dp)) { item { SlideCard(doc.pages[currentPage]) } }
        if (doc.pages.size > 1) Surface(tonalElevation = 3.dp) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { if (currentPage > 0) currentPage-- }, enabled = currentPage > 0) { Text("◀ Prev") }
                Text("Page ${currentPage + 1} of ${doc.pages.size}", style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { if (currentPage < doc.pages.size - 1) currentPage++ }, enabled = currentPage < doc.pages.size - 1) { Text("Next ▶") }
            }
        }
    }
}

@Composable
private fun SlideCard(
    slide: OdfSlide,
    editMode: Boolean = false,
    selectedIndex: Int = -1,
    onSelect: (Int) -> Unit = {},
    onElementTextChange: (Int, String) -> Unit = { _, _ -> },
    onBoundsChange: (Int, Float, Float, Float, Float) -> Unit = { _, _, _, _, _ -> },
    onDelete: (Int) -> Unit = {},
    onCropImage: (Int) -> Unit = {}
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(slide.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        val (refW, refH) = slideBounds(slide)
        val ratio = (refW / refH).coerceIn(0.5f, 3f)
        if (editMode) {
            // Edit mode: render the canvas in a non-clipping Box so selection handles that sit at
            // negative offsets near the slide edges aren't cut off by the Card's clip. (C1)
            Box(Modifier.fillMaxWidth().aspectRatio(ratio).padding(horizontal = 8.dp)) {
                Surface(Modifier.matchParentSize(), shape = RoundedCornerShape(4.dp), shadowElevation = 2.dp, color = MaterialTheme.colorScheme.surface) {}
                SlideCanvas(slide, refW, refH, true, selectedIndex, onSelect, onElementTextChange, onBoundsChange, onDelete, onCropImage)
            }
        } else {
            Card(modifier = Modifier.fillMaxWidth().aspectRatio(ratio).padding(horizontal = 8.dp), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
                SlideCanvas(slide, refW, refH, false, selectedIndex, onSelect, onElementTextChange, onBoundsChange, onDelete, onCropImage)
            }
        }
        if (slide.notes.isNotEmpty()) {
            var expanded by remember { mutableStateOf(false) }
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.padding(start = 8.dp)) { Text(if (expanded) "Hide Notes" else "Speaker Notes") }
            if (expanded) Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { for (note in slide.notes) ParagraphView(note) }
        }
    }
}

/** Default ODF slide coordinate space (28cm x 21cm in px@96), or element bounds if larger. (I60) */
private fun slideBounds(slide: OdfSlide): Pair<Float, Float> {
    var maxR = 0f; var maxB = 0f
    for (el in slide.elements) {
        val (x, y, w, h) = when (el) {
            is OdfSlideElement.Frame -> listOf(el.frame.x, el.frame.y, el.frame.width, el.frame.height)
            is OdfSlideElement.Shape -> listOf(el.shape.x, el.shape.y, el.shape.width, el.shape.height)
        }
        maxR = maxOf(maxR, x + w); maxB = maxOf(maxB, y + h)
    }
    val refW = maxOf(maxR, 1058f)
    val refH = maxOf(maxB, 794f)
    return refW to refH
}

@Composable
private fun SlideCanvas(
    slide: OdfSlide,
    refW: Float,
    refH: Float,
    editMode: Boolean = false,
    selectedIndex: Int = -1,
    onSelect: (Int) -> Unit = {},
    onElementTextChange: (Int, String) -> Unit = { _, _ -> },
    onBoundsChange: (Int, Float, Float, Float, Float) -> Unit = { _, _, _, _, _ -> },
    onDelete: (Int) -> Unit = {},
    onCropImage: (Int) -> Unit = {}
) {
    FloatingElementLayer(
        elements = slide.elements, refW = refW, refH = refH,
        editMode = editMode, selectedIndex = selectedIndex, keyPrefix = slide.name,
        backgroundColor = slide.backgroundColor,
        onSelect = onSelect, onElementTextChange = onElementTextChange,
        onBoundsChange = onBoundsChange, onDelete = onDelete, onCropImage = onCropImage
    )
}

/**
 * Shared overlay that renders floating elements (frames/shapes) over a reference px@96 canvas,
 * with tap-to-select, drag-to-move, corner resize handles and delete in edit mode. Reused by
 * slides and (Phase 4) spreadsheet floating layers. (Phase 1)
 */
@Composable
fun FloatingElementLayer(
    elements: List<OdfSlideElement>,
    refW: Float,
    refH: Float,
    editMode: Boolean,
    selectedIndex: Int,
    keyPrefix: String,
    modifier: Modifier = Modifier.fillMaxSize(),
    backgroundColor: Long? = null,
    interactiveBackground: Boolean = true,
    onSelect: (Int) -> Unit = {},
    onElementTextChange: (Int, String) -> Unit = { _, _ -> },
    onBoundsChange: (Int, Float, Float, Float, Float) -> Unit = { _, _, _, _, _ -> },
    onDelete: (Int) -> Unit = {},
    onCropImage: (Int) -> Unit = {}
) {
    BoxWithConstraints(
        modifier
            .then(backgroundColor?.let { Modifier.background(Color(it.toInt())) } ?: Modifier)
            .then(if (editMode && interactiveBackground) Modifier.pointerInput(elements.size) { detectTapGestures { onSelect(-1) } } else Modifier)
    ) {
        val scaleW = maxWidth / refW
        val scaleH = maxHeight / refH
        val density = LocalDensity.current.density
        val pxPerModelW = (scaleW.value * density).coerceAtLeast(0.01f)
        val pxPerModelH = (scaleH.value * density).coerceAtLeast(0.01f)
        // Scale text proportionally to the canvas (pt -> px@96 -> scaled dp). (I60)
        val fontScale = ((96f / 72f) * (maxWidth.value / refW)).coerceIn(0.2f, 4f)

        var live by remember(selectedIndex, elements) { mutableStateOf<FloatArray?>(null) }

        elements.forEachIndexed { ei, element ->
            val isSel = editMode && ei == selectedIndex
            val b = if (isSel) (live ?: element.bounds()) else element.bounds()
            val boxW = (scaleW * b[2]).coerceAtLeast(1.dp)
            val boxH = (scaleH * b[3]).coerceAtLeast(1.dp)
            // Text frames wrap height when neither selected nor editing so large fonts aren't truncated.
            val isImageFrame = element is OdfSlideElement.Frame && (element.frame.image != null || element.frame.chart != null)
            val fixedHeight = editMode || isImageFrame || element is OdfSlideElement.Shape
            var base = Modifier.offset(x = scaleW * b[0], y = scaleH * b[1]).width(boxW)
            base = if (fixedHeight) base.height(boxH) else base
            Box(base) {
                fun startBounds0() = live ?: element.bounds()
                // Body drag-to-move for selected non-text elements (shapes, image/chart frames). (C1)
                val canBodyDrag = isSel && (element is OdfSlideElement.Shape ||
                    (element is OdfSlideElement.Frame && (element.frame.image != null || element.frame.chart != null)))
                Box(
                    Modifier.fillMaxSize()
                        .then(if (editMode && !isSel) Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant) else Modifier)
                        .then(if (editMode && !isSel) Modifier.pointerInput(ei) { detectTapGestures { onSelect(ei) } } else Modifier)
                        .then(if (canBodyDrag) Modifier.pointerInput(ei, selectedIndex) {
                            detectDragGestures(
                                onDragStart = { live = element.bounds() },
                                onDragEnd = { live?.let { onBoundsChange(ei, it[0], it[1], it[2], it[3]) }; live = null },
                                onDragCancel = { live = null }
                            ) { change, drag ->
                                change.consume()
                                val c = startBounds0()
                                live = floatArrayOf(c[0] + drag.x / pxPerModelW, c[1] + drag.y / pxPerModelH, c[2], c[3])
                            }
                        } else Modifier)
                ) {
                    when (element) {
                        is OdfSlideElement.Frame -> PositionedFrame(element.frame, fontScale, isSel, "$keyPrefix#$ei") { onElementTextChange(ei, it) }
                        is OdfSlideElement.Shape -> PositionedShape(element.shape, fontScale, isSel, "$keyPrefix#$ei") { onElementTextChange(ei, it) }
                    }
                }
                if (isSel) {
                    Box(Modifier.fillMaxSize().border(2.dp, MaterialTheme.colorScheme.primary))
                    fun startBounds() = live ?: element.bounds()
                    fun commit() { live?.let { onBoundsChange(ei, it[0], it[1], it[2], it[3]) }; live = null }
                    // Move handle (top center, above the box).
                    ElementHandle(
                        Modifier.align(Alignment.TopCenter).offset(y = (-22).dp),
                        icon = com.vayunmathur.library.R.drawable.drag_handle_24px,
                        onStart = { live = element.bounds() }, onEnd = { commit() }
                    ) { dx, dy ->
                        val c = startBounds()
                        live = floatArrayOf(c[0] + dx / pxPerModelW, c[1] + dy / pxPerModelH, c[2], c[3])
                    }
                    // Corner resize handles.
                    ElementHandle(Modifier.align(Alignment.TopStart).offset((-8).dp, (-8).dp), onStart = { live = element.bounds() }, onEnd = { commit() }) { dx, dy ->
                        val c = startBounds(); val mdx = dx / pxPerModelW; val mdy = dy / pxPerModelH
                        live = floatArrayOf(c[0] + mdx, c[1] + mdy, (c[2] - mdx).coerceAtLeast(20f), (c[3] - mdy).coerceAtLeast(20f))
                    }
                    ElementHandle(Modifier.align(Alignment.TopEnd).offset(8.dp, (-8).dp), onStart = { live = element.bounds() }, onEnd = { commit() }) { dx, dy ->
                        val c = startBounds(); val mdx = dx / pxPerModelW; val mdy = dy / pxPerModelH
                        live = floatArrayOf(c[0], c[1] + mdy, (c[2] + mdx).coerceAtLeast(20f), (c[3] - mdy).coerceAtLeast(20f))
                    }
                    ElementHandle(Modifier.align(Alignment.BottomStart).offset((-8).dp, 8.dp), onStart = { live = element.bounds() }, onEnd = { commit() }) { dx, dy ->
                        val c = startBounds(); val mdx = dx / pxPerModelW; val mdy = dy / pxPerModelH
                        live = floatArrayOf(c[0] + mdx, c[1], (c[2] - mdx).coerceAtLeast(20f), (c[3] + mdy).coerceAtLeast(20f))
                    }
                    ElementHandle(Modifier.align(Alignment.BottomEnd).offset(8.dp, 8.dp), onStart = { live = element.bounds() }, onEnd = { commit() }) { dx, dy ->
                        val c = startBounds(); val mdx = dx / pxPerModelW; val mdy = dy / pxPerModelH
                        live = floatArrayOf(c[0], c[1], (c[2] + mdx).coerceAtLeast(20f), (c[3] + mdy).coerceAtLeast(20f))
                    }
                    // Delete (top end, above the box).
                    Box(Modifier.align(Alignment.TopEnd).offset(x = 12.dp, y = (-24).dp).size(22.dp)
                        .background(MaterialTheme.colorScheme.errorContainer, CircleShape)
                        .border(1.dp, MaterialTheme.colorScheme.error, CircleShape)
                        .clickable { onDelete(ei) }, contentAlignment = Alignment.Center) {
                        Icon(painterResource(com.vayunmathur.library.R.drawable.delete_24px), contentDescription = "Delete element", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
                    }
                    // Crop (top start, above the box) — only for image frames.
                    if (element is OdfSlideElement.Frame && element.frame.image != null) {
                        Box(Modifier.align(Alignment.TopStart).offset(x = (-12).dp, y = (-24).dp).size(22.dp)
                            .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape)
                            .border(1.dp, MaterialTheme.colorScheme.secondary, CircleShape)
                            .clickable { onCropImage(ei) }, contentAlignment = Alignment.Center) {
                            Icon(painterResource(com.vayunmathur.library.R.drawable.crop_24px), contentDescription = "Crop image", tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        }
    }
}

/** A small draggable selection handle (move/resize). Drag deltas are reported in pixels. (Phase 1) */
@Composable
private fun ElementHandle(modifier: Modifier, icon: Int? = null, onStart: () -> Unit, onEnd: () -> Unit, onDrag: (Float, Float) -> Unit) {
    Box(
        modifier.size(18.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape)
            .border(1.5.dp, Color.White, CircleShape)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { onStart() },
                    onDragEnd = { onEnd() },
                    onDragCancel = { onEnd() }
                ) { change, drag -> change.consume(); onDrag(drag.x, drag.y) }
            },
        contentAlignment = Alignment.Center
    ) {
        if (icon != null) Icon(painterResource(icon), contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
    }
}

/** Editable in-place text overlay for a slide element. (I62) */
@Composable
private fun SlideTextEditor(key: String, paragraphs: List<OdfParagraph>, fontScale: Float, onChange: (String) -> Unit, onFocus: () -> Unit = {}) {
    val initial = paragraphs.joinToString("\n") { p -> p.spans.joinToString("") { it.text } }
    var tfv by remember(key) { mutableStateOf(TextFieldValue(initial)) }
    val baseSp = (paragraphs.firstOrNull()?.spans?.firstOrNull()?.fontSize ?: 18f) * fontScale
    val bold = paragraphs.firstOrNull()?.spans?.firstOrNull()?.bold == true
    val italic = paragraphs.firstOrNull()?.spans?.firstOrNull()?.italic == true
    val color = paragraphs.firstOrNull()?.spans?.firstOrNull()?.color?.let { Color(it.toInt()) } ?: MaterialTheme.colorScheme.onSurface
    BasicTextField(
        value = tfv,
        onValueChange = { tfv = it; onChange(it.text) },
        textStyle = TextStyle(color = color, fontSize = baseSp.coerceAtLeast(8f).sp, fontWeight = if (bold) FontWeight.Bold else null, fontStyle = if (italic) FontStyle.Italic else null),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) onFocus() }
    )
}

@Composable
private fun PositionedFrame(frame: OdfFrame, fontScale: Float, editing: Boolean = false, editKey: String = "", onTextChange: (String) -> Unit = {}) {
    Box(Modifier.fillMaxSize()
        .then(frame.fillColor?.let { Modifier.background(Color(it.toInt())) } ?: Modifier)
        .then(frame.strokeColor?.let { Modifier.border((frame.strokeWidth ?: 1f).dp.coerceAtLeast(0.5.dp), Color(it.toInt())) } ?: Modifier)
    ) {
        frame.image?.let { OdfImageView(it, Modifier.fillMaxSize()) }
        frame.chart?.let { OdfChartView(it) }
        if (editing && frame.image == null && frame.chart == null) {
            SlideTextEditor(editKey, frame.paragraphs, fontScale, onTextChange)
        } else if (frame.image == null && frame.chart == null) {
            Column { for (para in frame.paragraphs) ParagraphView(para, fontSizeMultiplier = fontScale) }
        }
    }
}

@Composable
private fun PositionedShape(shape: OdfShape, fontScale: Float, editing: Boolean = false, editKey: String = "", onTextChange: (String) -> Unit = {}) {
    val fillColor = shape.fillColor?.let { Color(it.toInt()) } ?: Color.Transparent
    val strokeColor = shape.strokeColor?.let { Color(it.toInt()) } ?: MaterialTheme.colorScheme.outline
    val strokeW = shape.strokeWidth ?: 1f
    Box(Modifier.fillMaxSize().then(if (shape.rotationDegrees != 0f) Modifier.rotate(shape.rotationDegrees) else Modifier)) {
        Canvas(Modifier.fillMaxSize()) {
            val dashEffect = if (shape.strokeDashed) PathEffect.dashPathEffect(floatArrayOf(strokeW * 4f, strokeW * 4f)) else null
            val strokeStyle = Stroke(strokeW, pathEffect = dashEffect)
            val grad = shape.fillGradient
            val fillBrush: Brush? = grad?.let { g ->
                val rad = Math.toRadians(g.angle.toDouble())
                val ex = Math.cos(rad).toFloat(); val ey = Math.sin(rad).toFloat()
                Brush.linearGradient(
                    listOf(Color(g.startColor.toInt()), Color(g.endColor.toInt())),
                    start = Offset(0f, 0f),
                    end = Offset(if (ex == 0f && ey == 0f) size.width else size.width * ex, size.height * ey)
                )
            }
            when (shape) {
                is OdfShape.Rect -> { if (fillBrush != null) drawRect(fillBrush) else drawRect(fillColor); drawRect(strokeColor, style = strokeStyle) }
                is OdfShape.Ellipse -> { if (fillBrush != null) drawOval(fillBrush) else drawOval(fillColor); drawOval(strokeColor, style = strokeStyle) }
                is OdfShape.Line -> drawLine(strokeColor, Offset.Zero, Offset(size.width, size.height), strokeW, pathEffect = dashEffect)
                is OdfShape.CustomShape -> { if (fillBrush != null) drawRect(fillBrush) else drawRect(fillColor); drawRect(strokeColor, style = strokeStyle) }
                is OdfShape.Polyline -> {
                    if (shape.points.size >= 2) {
                        val path = androidx.compose.ui.graphics.Path()
                        shape.points.forEachIndexed { i, (px, py) ->
                            val fx = if (shape.width != 0f) (px - shape.x) / shape.width * size.width else 0f
                            val fy = if (shape.height != 0f) (py - shape.y) / shape.height * size.height else 0f
                            if (i == 0) path.moveTo(fx, fy) else path.lineTo(fx, fy)
                        }
                        if (shape.closed) { path.close(); drawPath(path, fillColor) }
                        drawPath(path, strokeColor, style = Stroke(strokeW))
                    }
                }
            }
        }
        if (editing && shape !is OdfShape.Line) {
            Box(Modifier.padding(4.dp).align(Alignment.Center)) { SlideTextEditor(editKey, shape.text, fontScale, onTextChange) }
        } else if (shape.text.isNotEmpty()) {
            Column(Modifier.padding(4.dp).align(Alignment.Center)) { for (para in shape.text) ParagraphView(para, fontSizeMultiplier = fontScale) }
        }
    }
}
