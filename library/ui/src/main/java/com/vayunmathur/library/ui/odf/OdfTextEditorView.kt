package com.vayunmathur.library.ui.odf

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.material3.Text

/**
 * Shared, framework-light text-run editor extracted from the Office app so the markdown editor and
 * Office can use a single implementation. Renders a contiguous run of paragraphs as one
 * [BasicTextField] with a visual transformation that injects list/heading prefixes and applies
 * inline span styling, and reports edits back through callbacks. Pure Compose + ODF model only.
 */

internal fun headingSizeSp(style: ParagraphStyle): Float? = when (style) {
    ParagraphStyle.HEADING1 -> 30f
    ParagraphStyle.HEADING2 -> 26f
    ParagraphStyle.HEADING3 -> 22f
    ParagraphStyle.HEADING4 -> 20f
    else -> null
}

/** Default bullet glyph per nesting level: • → ◦ → ▪ (cycling). */
private fun bulletForLevel(level: Int): String = when ((level - 1).coerceAtLeast(0) % 3) {
    0 -> "\u2022"   // • filled disc
    1 -> "\u25E6"   // ◦ hollow circle
    else -> "\u25AA" // ▪ small square
}

/** Default number format + suffix per nesting level: 1. → a) → i) (cycling). */
private fun numberStyleForLevel(level: Int): Pair<String, String> = when ((level - 1).coerceAtLeast(0) % 3) {
    0 -> "1" to "."
    1 -> "a" to ")"
    else -> "i" to ")"
}

/** Visible list prefix (indent + bullet/number) for a paragraph. (A1/F42) */
fun listPrefixFor(para: OdfParagraph): String {
    // Headings: show automatic outline numbering if present, else no generic prefix. (bugfix + Round 3)
    if (para.style == ParagraphStyle.HEADING1 || para.style == ParagraphStyle.HEADING2 ||
        para.style == ParagraphStyle.HEADING3 || para.style == ParagraphStyle.HEADING4) {
        return para.outlineNumber?.let { "$it " } ?: ""
    }
    if (para.listLevel <= 0 && para.style != ParagraphStyle.LIST_ITEM) return ""
    val level = maxOf(para.listLevel, 1)
    val indent = "    ".repeat(level - 1)
    return when (para.listType) {
        ListType.NUMBERED -> {
            // Default markdown numbering varies the style by nesting level (1. → a) → i) → …).
            val defaultStyle = para.listNumberFormat == "1" && para.listNumberSuffix == "." && para.listNumberPrefix.isEmpty()
            if (defaultStyle) {
                val (fmt, suffix) = numberStyleForLevel(level)
                indent + formatListNumber(para.listItemIndex, fmt) + suffix + "  "
            } else {
                indent + para.listNumberPrefix + formatListNumber(para.listItemIndex, para.listNumberFormat) + para.listNumberSuffix + "  "
            }
        }
        ListType.CHECKBOX ->
            indent + (if (para.listChecked) "\u2611" else "\u2610") + "  "
        ListType.BULLET -> {
            // Default markdown bullets vary the glyph by nesting level (• → ◦ → ▪).
            val ch = if (para.listBulletChar == "\u2022") bulletForLevel(level) else sanitizeBulletChar(para.listBulletChar)
            indent + ch + "  "
        }
    }
}

/**
 * Maps a list bullet glyph to something the app font can actually render. ODF list styles often
 * specify bullets from a symbol font's Private Use Area (StarSymbol/OpenSymbol/Wingdings), which
 * show as a "notdef" tofu box; fall back to a standard bullet for those. (bugfix)
 */
private fun sanitizeBulletChar(ch: String): String {
    if (ch.isBlank()) return "\u2022"
    val c = ch[0]
    return when {
        c.code in 0xE000..0xF8FF -> "\u2022"   // Private Use Area (symbol fonts)
        c == '\uFFFD' -> "\u2022"              // replacement char
        c.isISOControl() -> "\u2022"
        else -> ch
    }
}

/** OffsetMapping for list prefixes injected at the start of each paragraph in a run. (A1) */
private class PrefixOffsetMapping(
    private val origStarts: IntArray,
    private val transStarts: IntArray,
    private val prefixLens: IntArray,
    private val lens: IntArray,
    private val origLen: Int,
    private val transLen: Int
) : OffsetMapping {
    override fun originalToTransformed(offset: Int): Int {
        val o = offset.coerceIn(0, origLen)
        var p = 0
        for (i in origStarts.indices) { if (origStarts[i] <= o) p = i else break }
        val inPara = (o - origStarts[p]).coerceIn(0, lens[p])
        return (transStarts[p] + prefixLens[p] + inPara).coerceIn(0, transLen)
    }
    override fun transformedToOriginal(offset: Int): Int {
        val t = offset.coerceIn(0, transLen)
        var p = 0
        for (i in transStarts.indices) { if (transStarts[i] <= t) p = i else break }
        val afterPrefix = t - transStarts[p] - prefixLens[p]
        val inPara = afterPrefix.coerceIn(0, lens[p])
        return (origStarts[p] + inPara).coerceIn(0, origLen)
    }
}

/**
 * Remaps a caret offset when the editor's text is replaced externally (e.g. a collaborator's merged
 * edit) so the caret follows its surrounding content: insertions/deletions *before* the caret shift
 * it, changes *after* it leave it put, and an edit *at* the caret lands at the start of the change.
 */
fun remapCaret(old: String, new: String, caret: Int): Int {
    if (old == new) return caret
    val lim = minOf(old.length, new.length)
    var cp = 0
    while (cp < lim && old[cp] == new[cp]) cp++
    var cs = 0
    while (cs < (old.length - cp) && cs < (new.length - cp) && old[old.length - 1 - cs] == new[new.length - 1 - cs]) cs++
    val delta = new.length - old.length
    return when {
        caret <= cp -> caret                        // change is at/after the caret
        caret >= old.length - cs -> caret + delta   // change is entirely before the caret
        else -> cp                                  // caret sat inside the changed region
    }
}

/** A collaborator's caret to render in the editor: [offset] in the plain text, ARGB [color], [name]. */
data class RemoteCaret(val offset: Int, val color: Long, val name: String)

@Composable
fun ContinuousParagraphEditor(
    doc: OdfDocument.TextDocument,
    start: Int,
    endInclusive: Int,
    fontSizeMultiplier: Float,
    onSelectionChange: (Int, Int, Int, Int) -> Unit,
    onTextChange: (Int, Int, String) -> Unit,
    onEnter: (gPos: Int) -> Int? = { null },
    onBackspace: (gPos: Int) -> Int? = { null },
    onToggleCheckbox: ((globalParaIndex: Int) -> Unit)? = null,
    onFocusChangedCb: (Boolean) -> Unit = {},
    onDeletePrevBlock: () -> Unit = {},
    remoteCarets: List<RemoteCaret> = emptyList(),
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    val paras = (start..endInclusive).mapNotNull { (doc.content[it] as? OdfContentBlock.Paragraph)?.paragraph }
    val plainText = paras.joinToString("\n") { p -> p.spans.joinToString("") { it.text } }
    var tfv by remember { mutableStateOf(TextFieldValue(plainText)) }
    var pendingCaret by remember { mutableStateOf<Int?>(null) }
    if (tfv.text != plainText) {
        val caret = pendingCaret ?: remapCaret(tfv.text, plainText, tfv.selection.end)
        tfv = TextFieldValue(plainText, TextRange(caret.coerceIn(0, plainText.length)))
    }
    pendingCaret = null
    val onSurface = MaterialTheme.colorScheme.onSurface
    val prefixColor = MaterialTheme.colorScheme.onSurfaceVariant
    val lens = remember(paras) { paras.map { p -> p.spans.sumOf { it.text.length } } }
    val prefixes = remember(paras) { paras.map { listPrefixFor(it) } }
    // Recomputed every composition so list prefixes (e.g. renumbered ordered lists, checkbox state)
    // are always reflected immediately, even when the underlying text is unchanged.
    val transformation = VisualTransformation {
        buildDocTransformed(it.text, paras, lens, prefixes, onSurface, prefixColor, fontSizeMultiplier)
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    // When the run's shape changes (a paragraph added/removed by Enter/Backspace), the caller's
    // tracked run range + selection go stale. Report the fresh range + caret so toolbar targeting
    // (focused paragraph) stays correct even before the next user interaction.
    var prevRange by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    LaunchedEffect(start, endInclusive) {
        val key = start to endInclusive
        if (prevRange != null && prevRange != key) {
            onSelectionChange(start, endInclusive, tfv.selection.min, tfv.selection.max)
        }
        prevRange = key
    }
    Box(modifier) {
        // Let Compose own the caret. The built-in cursor is positioned with the SAME internal
        // (visually-transformed) TextLayoutResult and OffsetMapping that lay out the aligned glyphs,
        // so getCursorRect reflects each paragraph's TextAlign (set via ParagraphStyle below) and the
        // caret stays aligned with the displayed text for left/center/right/justified paragraphs.
        BasicTextField(
            value = tfv,
            onValueChange = onValueChange@{ nv ->
                val oldText = tfv.text
                val oldSel = tfv.selection
                // Smart list behavior: detect a single newline insertion (Enter) or single-char
                // delete (Backspace) at a collapsed caret and offer it to the list handlers first.
                val isEnter = nv.selection.collapsed && nv.text.length == oldText.length + 1 &&
                    nv.selection.start in 1..nv.text.length && nv.text[nv.selection.start - 1] == '\n' &&
                    nv.text.substring(0, nv.selection.start - 1) + nv.text.substring(nv.selection.start) == oldText
                if (isEnter) {
                    val newCaret = onEnter(nv.selection.start - 1)
                    if (newCaret != null) { pendingCaret = newCaret; return@onValueChange }
                }
                val isBackspace = nv.selection.collapsed && oldSel.collapsed && nv.text.length == oldText.length - 1 &&
                    oldText.substring(0, nv.selection.start) + oldText.substring(nv.selection.start + 1) == nv.text
                if (isBackspace) {
                    val newCaret = onBackspace(nv.selection.start + 1)
                    if (newCaret != null) { pendingCaret = newCaret; return@onValueChange }
                }
                val textChanged = nv.text != oldText
                tfv = nv
                onSelectionChange(start, endInclusive, nv.selection.min, nv.selection.max)
                if (textChanged) onTextChange(start, endInclusive, nv.text)
            },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = onSurface, lineHeight = (22f * fontSizeMultiplier).sp),
            visualTransformation = transformation,
            onTextLayout = { layout = it },
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth()
                .onPreviewKeyEvent { ev ->
                    // Backspace at the very start of this run deletes the object (image, page break,
                    // table of contents, chart…) sitting just above it.
                    if (ev.type == KeyEventType.KeyDown && ev.key == Key.Backspace &&
                        tfv.selection.collapsed && tfv.selection.start == 0 && start > 0
                    ) { onDeletePrevBlock(); true } else false
                }
                .onFocusChanged { onFocusChangedCb(it.isFocused) }
        )
        // Tappable overlays over each checkbox glyph so tapping toggles its checked state.
        val lay = layout
        if (onToggleCheckbox != null && lay != null) {
            val transStarts = runTransStarts(lens, prefixes)
            val density = LocalDensity.current
            paras.forEachIndexed { pi, para ->
                if (para.style == ParagraphStyle.LIST_ITEM && para.listType == ListType.CHECKBOX) {
                    val glyphOffset = transStarts[pi]
                    val rect = runCatching { lay.getBoundingBox(glyphOffset) }.getOrNull() ?: return@forEachIndexed
                    with(density) {
                        Box(
                            Modifier
                                .offset(x = rect.left.toDp(), y = rect.top.toDp())
                                .size(width = (rect.height).toDp(), height = rect.height.toDp())
                                .clickable { onToggleCheckbox(start + pi) }
                        )
                    }
                }
            }
        }
        // Remote collaborators' carets (live presence). Positions use the plain-text offset; for
        // plain prose the visual transform is identity so this is exact (list prefixes shift it a bit).
        if (lay != null && remoteCarets.isNotEmpty()) {
            val density = LocalDensity.current
            for (rc in remoteCarets) {
                val off = rc.offset.coerceIn(0, tfv.text.length)
                val rect = runCatching { lay.getCursorRect(off) }.getOrNull() ?: continue
                val color = Color(rc.color.toInt())
                with(density) {
                    Box(
                        Modifier
                            .offset(x = rect.left.toDp(), y = rect.top.toDp())
                            .width(2.dp)
                            .height((rect.bottom - rect.top).toDp())
                            .background(color)
                    )
                    Box(
                        Modifier
                            .offset(x = rect.left.toDp(), y = (rect.top - 14f).toDp())
                            .background(color)
                    ) {
                        Text(rc.name, color = Color.White, fontSize = 8.sp, modifier = Modifier.padding(horizontal = 2.dp))
                    }
                }
            }
        }
    }
}

private fun sameLayout(a: OdfParagraph, b: OdfParagraph): Boolean =
    a.alignment == b.alignment && a.lineHeightPercent == b.lineHeightPercent &&
        a.textIndent == 0f && b.textIndent == 0f

/**
 * Transformed-text start offset (start of the injected prefix) for each paragraph. Every paragraph
 * boundary contributes exactly one separator char (see [buildDocTransformed]), so the accounting is
 * simply prefix + text + 1 per non-final paragraph.
 */
private fun runTransStarts(lens: List<Int>, prefixes: List<String>): IntArray {
    val n = lens.size
    val transStarts = IntArray(n)
    var t = 0
    for (i in 0 until n) {
        transStarts[i] = t
        t += prefixes[i].length + lens[i] + (if (i < n - 1) 1 else 0)
    }
    return transStarts
}

private fun buildDocTransformed(
    text: String, paras: List<OdfParagraph>, lens: List<Int>, prefixes: List<String>,
    baseColor: Color, prefixColor: Color, mult: Float
): TransformedText {
    val n = paras.size
    val origStarts = IntArray(n)
    val transStarts = IntArray(n)
    val prefixLens = IntArray(n) { prefixes[it].length }

    // Paragraph-break model. Compose makes each ParagraphStyle range its own paragraph, and a '\n'
    // that lands between two ParagraphStyle ranges becomes an extra blank "gap" line. So we keep a
    // '\n' separator only between consecutive paragraphs that share the same paragraph-level layout
    // (alignment, line spacing, no first-line indent): those sit inside ONE shared ParagraphStyle,
    // where the '\n' is an ordinary single line break. At a real layout change the two paragraphs
    // need separate ParagraphStyles, so instead of a '\n' (which would blank-line) OR nothing (which
    // would collapse the end of one block and the start of the next onto a single caret offset and
    // break caret placement / hit-testing), we emit a zero-width space (U+200B) kept INSIDE the
    // first paragraph's ParagraphStyle range: it adds no blank line and no visible glyph, but gives
    // the boundary a real, distinct offset so the caret lands correctly. Either way exactly one
    // separator char is emitted between paragraphs, so origStarts and transStarts both advance by
    // len + 1 per boundary.
    val withinGroup = BooleanArray(n) // withinGroup[i] = paragraphs i and i+1 share a ParagraphStyle
    for (i in 0 until n - 1) {
        withinGroup[i] = sameLayout(paras[i], paras[i + 1]) || lens[i + 1] == 0
    }
    if (n > 0 && lens[0] == 0 && n > 1) withinGroup[0] = true // keep a leading empty paragraph with its neighbour

    var oAcc = 0; var tAcc = 0
    for (i in 0 until n) {
        origStarts[i] = oAcc
        transStarts[i] = tAcc
        oAcc += lens[i] + 1
        tAcc += prefixLens[i] + lens[i] + (if (i < n - 1) 1 else 0)
    }
    val origLen = text.length
    val transLen = if (n == 0) 0 else transStarts[n - 1] + prefixLens[n - 1] + lens[n - 1]

    val annotated = buildAnnotatedString {
        var groupStart = 0   // annotated offset where the current ParagraphStyle group began
        var groupFirst = 0   // index of the first paragraph in the current group
        for (i in paras.indices) {
            if (i == 0 || !withinGroup[i - 1]) { groupStart = length; groupFirst = i }
            val para = paras[i]
            // prefix (styled muted, never bold/italic)
            if (prefixes[i].isNotEmpty()) {
                withStyle(SpanStyle(color = prefixColor)) { append(prefixes[i]) }
            }
            // paragraph text from original
            val pStartOrig = origStarts[i]
            val pEndOrig = (pStartOrig + lens[i]).coerceAtMost(text.length)
            val paraText = if (pEndOrig > pStartOrig) text.substring(pStartOrig, pEndOrig) else ""
            val textStart = length
            append(paraText)
            val textEnd = length
            headingSizeSp(para.style)?.let { addStyle(SpanStyle(fontSize = (it * mult).sp, fontWeight = FontWeight.Bold), textStart, textEnd) }
            var off = textStart
            for (span in para.spans) {
                val segEnd = (off + span.text.length).coerceAtMost(textEnd)
                if (segEnd > off) {
                    val decorations = mutableListOf<TextDecoration>()
                    if (span.underline) decorations.add(TextDecoration.Underline)
                    if (span.strikethrough) decorations.add(TextDecoration.LineThrough)
                    if (span.changeKind == "insertion") decorations.add(TextDecoration.Underline)
                    if (span.changeKind == "deletion") decorations.add(TextDecoration.LineThrough)
                    val changeColor = when (span.changeKind) { "insertion" -> Color(0xFF1B7F3B); "deletion" -> Color(0xFFC62828); else -> null }
                    addStyle(
                        SpanStyle(
                            fontWeight = if (span.bold) FontWeight.Bold else null,
                            fontStyle = if (span.italic) FontStyle.Italic else null,
                            fontSize = span.fontSize?.let { (it * mult).sp } ?: TextUnit.Unspecified,
                            textDecoration = if (decorations.isNotEmpty()) TextDecoration.combine(decorations) else null,
                            color = changeColor ?: span.color?.let { Color(it.toInt()) } ?: Color.Unspecified,
                            background = span.backgroundColor?.let { Color(it.toInt()) } ?: Color.Unspecified,
                            letterSpacing = span.letterSpacing?.sp ?: TextUnit.Unspecified,
                            baselineShift = when { span.superscript -> BaselineShift.Superscript; span.subscript -> BaselineShift.Subscript; else -> null }
                        ), off, segEnd
                    )
                }
                off = segEnd
            }
            val groupEnds = i == n - 1 || !withinGroup[i]
            // Between-group separator: append the zero-width space BEFORE applying the group's
            // ParagraphStyle so it stays inside the group's range (no orphan blank line).
            if (groupEnds && i != n - 1) append("\u200B")
            // At the end of a group, emit ONE ParagraphStyle spanning every paragraph in the group
            // (prefixes, text and interior '\n' separators). The group's paragraph-level layout is
            // taken from its first non-empty paragraph so that an absorbed empty paragraph never
            // overrides the alignment/indent of real content. (A5/A6 + paragraph-spacing fix)
            if (groupEnds) {
                val styleSrc = (groupFirst..i).firstOrNull { lens[it] > 0 }?.let { paras[it] } ?: paras[groupFirst]
                val firstLineIndent = if (styleSrc.textIndent != 0f) styleSrc.textIndent.sp else 0.sp
                // Justify is stretched at draw time, but BasicTextField computes caret / selection /
                // handle positions from the UNSTRETCHED glyph advances, so they drift left of the
                // displayed justified glyphs. Render the editable field with Start alignment so the
                // caret and selection match the glyphs exactly; the OdfParagraph.alignment model is
                // untouched, so read-only rendering (ParagraphView) and export stay justified.
                // (justified-selection alignment fix)
                val editorAlign = if (styleSrc.alignment == TextAlign.Justify) TextAlign.Start else (styleSrc.alignment ?: TextAlign.Unspecified)
                addStyle(
                    androidx.compose.ui.text.ParagraphStyle(
                        textAlign = editorAlign,
                        lineHeight = styleSrc.lineHeightPercent?.let { (22f * mult * it).sp } ?: TextUnit.Unspecified,
                        textIndent = if (styleSrc.textIndent != 0f) androidx.compose.ui.text.style.TextIndent(firstLine = firstLineIndent, restLine = 0.sp) else androidx.compose.ui.text.style.TextIndent.None
                    ),
                    groupStart, length
                )
            } else {
                // Within a group: an ordinary single line break that stays inside the group's range.
                append("\n")
            }
        }
    }
    return TransformedText(annotated, PrefixOffsetMapping(origStarts, transStarts, prefixLens, lens.toIntArray(), origLen, transLen))
}
