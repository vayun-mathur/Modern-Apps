package com.vayunmathur.code.ui

import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Constraints
import com.vayunmathur.code.domain.syntax.LanguageSpec
import com.vayunmathur.code.domain.syntax.SyntaxColors
import com.vayunmathur.code.domain.syntax.TsColorSpan
import com.vayunmathur.code.domain.syntax.rememberSyntaxColors
import com.vayunmathur.code.platform.CodeActions
import com.vayunmathur.code.domain.Completion
import com.vayunmathur.code.domain.Diagnostic
import com.vayunmathur.code.domain.DiagnosticSeverity
import com.vayunmathur.code.platform.TabUiState
import com.vayunmathur.code.domain.computeFoldRegions
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.MaterialTheme

/**
 * The experimental virtualized editor engine (Phases 7–8). A [Canvas] draws only the lines inside
 * the viewport (with folded ranges skipped entirely), so syntax highlighting is per-visible-line.
 * It reuses the existing edit pipeline — input routes through [CodeActions.onEditorChange] /
 * [CodeActions.setSelection] — so undo/redo, auto-save and smart input keep working.
 *
 * Phase-8 additions: real line-hiding folds with gutter arrows, multi-cursor (Ctrl-D / Alt-tap),
 * indent guides, whitespace rendering, a minimap, and editor keyboard shortcuts. Find/replace,
 * autocomplete, soft-wrap and the system-keyboard IME (via [codeEditorInput]) are all wired; the
 * hardware-key path ([handleEditorKey]) remains for keys the IME does not deliver.
 */
@Composable
fun CodeEditorView(
    tab: TabUiState,
    actions: CodeActions,
    fontSize: Int,
    editorTheme: String,
    modifier: Modifier = Modifier,
    tabWidth: Int = 4,
    softWrap: Boolean = false,
    showWhitespace: Boolean = false,
    showIndentGuides: Boolean = false,
    showMinimap: Boolean = false,
    showFind: Boolean = false,
    onCloseFind: () -> Unit = {},
    initialQuery: String = "",
    completions: List<Completion> = emptyList(),
    showCompletions: Boolean = false,
    diagnostics: List<Diagnostic> = emptyList(),
    secondaryPane: Boolean = false,
    onValueChangeOverride: ((TextFieldValue) -> Unit)? = null,
) {
    val value = tab.value
    val text = value.text
    val colors = rememberSyntaxColors(editorTheme)
    val onSurface = MaterialTheme.colorScheme.onSurface
    val gutterColor = MaterialTheme.colorScheme.onSurfaceVariant
    val caretColor = MaterialTheme.colorScheme.primary
    val guideColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    val extraCaretColor = MaterialTheme.colorScheme.tertiary
    val errorColor = MaterialTheme.colorScheme.error
    val warningColor = Color(0xFFFFB300)
    val infoColor = gutterColor
    fun severityColor(s: DiagnosticSeverity): Color = when (s) {
        DiagnosticSeverity.ERROR -> errorColor
        DiagnosticSeverity.WARNING -> warningColor
        DiagnosticSeverity.INFO -> infoColor
    }
    val diagnosticsByLine = remember(diagnostics) { diagnostics.groupBy { it.line } }
    val spec = tab.language.spec
    // Native tree-sitter spans (parsed off the main thread); null → the regex tokenizer in
    // [annotatedLine] is used, both while the parse is in flight and when it is unavailable.
    val tsSpans = rememberTreeSitterSpans(text, tab.language, colors)

    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = remember(fontSize, onSurface) {
        TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize.sp, color = onSurface)
    }
    val metrics = remember(style) { measurer.measure("M", style) }
    val lineHeight = metrics.size.height.toFloat().coerceAtLeast(1f)
    val charWidth = metrics.size.width.toFloat().coerceAtLeast(1f)

    val lines = remember(text) { text.split("\n") }
    val lineStarts = remember(text) {
        IntArray(lines.size).also { starts ->
            var acc = 0
            for (i in lines.indices) {
                starts[i] = acc
                acc += lines[i].length + 1
            }
        }
    }
    val longestLine = remember(text) { lines.maxOfOrNull { it.length } ?: 0 }
    val gutterWidth = with(density) { ((lines.size.toString().length * 10) + 28).dp.toPx() }

    // Folding state lives on the tab (persisted per file); multi-cursor is view-local.
    val foldedHeaders = tab.foldedHeaders
    var extraCarets by remember(tab.name) { mutableStateOf(listOf<Int>()) }
    // In-progress IME composition region (reported by the system keyboard), drawn underlined.
    var composing by remember(tab.name) { mutableStateOf<TextRange?>(null) }

    val foldByHeader = remember(text) { computeFoldRegions(text).associateBy { it.startLine } }
    val hiddenLines = remember(text, foldedHeaders) {
        val hidden = HashSet<Int>()
        for (header in foldedHeaders) {
            val region = foldByHeader[header] ?: continue
            for (l in (region.startLine + 1)..region.endLine) hidden.add(l)
        }
        hidden
    }
    val visibleLines = remember(text, hiddenLines) { lines.indices.filter { it !in hiddenLines } }

    var scrollY by remember { mutableFloatStateOf(0f) }
    var scrollX by remember { mutableFloatStateOf(0f) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val focusRequester = remember { FocusRequester() }

    // ---- Soft-wrap (Phase 5): each source line may span several visual rows. ----
    val wrapping = softWrap && viewport.width > 0
    val wrapWidthPx = (viewport.width - gutterWidth).coerceAtLeast(1f)
    val wrapConstraints = Constraints(maxWidth = wrapWidthPx.toInt().coerceAtLeast(1))
    // Wrapped-row count per source line (1 for empty lines / when not wrapping).
    val wrapCounts = remember(text, wrapping, wrapWidthPx.toInt(), style) {
        if (!wrapping) {
            IntArray(lines.size) { 1 }
        } else {
            IntArray(lines.size) { i ->
                val ln = lines[i]
                if (ln.isEmpty()) 1
                else measurer.measure(AnnotatedString(ln), style, constraints = wrapConstraints).lineCount.coerceAtLeast(1)
            }
        }
    }
    // Cumulative visual-row start for each visible source line (last entry = total visual rows).
    val visualStarts = remember(visibleLines, wrapCounts, wrapping) {
        IntArray(visibleLines.size + 1).also { arr ->
            var acc = 0
            for (k in visibleLines.indices) {
                arr[k] = acc
                acc += if (wrapping) wrapCounts[visibleLines[k]] else 1
            }
            arr[visibleLines.size] = acc
        }
    }
    val totalVisualRows = visualStarts.last()

    val totalHeight = lineHeight * totalVisualRows
    val totalWidth = if (wrapping) viewport.width.toFloat() else gutterWidth + longestLine * charWidth + charWidth
    val maxScrollY = (totalHeight - viewport.height).coerceAtLeast(0f)
    val maxScrollX = if (wrapping) 0f else (totalWidth - viewport.width).coerceAtLeast(0f)
    scrollY = scrollY.coerceIn(0f, maxScrollY)
    scrollX = scrollX.coerceIn(0f, maxScrollX)

    val vScroll = rememberScrollableState { delta ->
        val newY = (scrollY - delta).coerceIn(0f, maxScrollY)
        val consumed = scrollY - newY
        scrollY = newY
        consumed
    }
    val hScroll = rememberScrollableState { delta ->
        val newX = (scrollX - delta).coerceIn(0f, maxScrollX)
        val consumed = scrollX - newX
        scrollX = newX
        consumed
    }

    val emit: (TextFieldValue) -> Unit = onValueChangeOverride ?: actions::onEditorChange

    // ---- Find state (Phase 4): mirrors the classic editor, highlighted in the Canvas below. ----
    var query by remember(tab.name) { mutableStateOf(initialQuery) }
    var replacement by remember(tab.name) { mutableStateOf("") }
    var caseSensitive by remember(tab.name) { mutableStateOf(false) }
    var useRegex by remember(tab.name) { mutableStateOf(false) }
    var activeMatch by remember(tab.name) { mutableIntStateOf(0) }
    val regexValid = remember(query, useRegex) {
        !useRegex || query.isEmpty() || runCatching { Regex(query) }.isSuccess
    }
    val matches = remember(text, query, caseSensitive, useRegex) {
        findMatchRanges(text, query, caseSensitive, useRegex)
    }
    val highlightMatches = if (showFind) matches else emptyList()
    LaunchedEffect(matches.size) { if (activeMatch >= matches.size) activeMatch = 0 }
    val goTo: (Int) -> Unit = { target ->
        if (matches.isNotEmpty()) {
            val idx = ((target % matches.size) + matches.size) % matches.size
            activeMatch = idx
            val r = matches[idx]
            actions.setSelection(TextRange(r.first, r.last + 1))
        }
    }
    // Keep the active match scrolled into view as the user navigates.
    LaunchedEffect(activeMatch, matches.size) {
        if (showFind && matches.isNotEmpty() && viewport.height > 0) {
            val r = matches[activeMatch.coerceIn(0, matches.size - 1)]
            val k = visibleLines.indexOf(lineOfOffset(lineStarts, r.first))
            if (k >= 0) {
                val top = visualStarts[k] * lineHeight
                if (top < scrollY) {
                    scrollY = top
                } else if (top + lineHeight > scrollY + viewport.height) {
                    scrollY = (top - viewport.height + lineHeight).coerceAtLeast(0f)
                }
            }
        }
    }

    val editorCanvas: @Composable (Modifier) -> Unit = { canvasModifier ->
        EditorCanvas(
            data = EditorCanvasData(
                value = value, text = text, lines = lines, lineStarts = lineStarts,
                visibleLines = visibleLines, visualStarts = visualStarts,
                totalVisualRows = totalVisualRows, wrapCounts = wrapCounts,
                lineHeight = lineHeight, charWidth = charWidth, gutterWidth = gutterWidth,
                scrollY = scrollY, scrollX = scrollX, wrapping = wrapping,
                wrapConstraints = wrapConstraints, style = style, colors = colors,
                spec = spec, tsSpans = tsSpans,
                highlightMatches = highlightMatches, activeMatch = activeMatch,
                diagnosticsByLine = diagnosticsByLine,
                foldedHeaders = foldedHeaders, foldByHeader = foldByHeader,
                extraCarets = extraCarets, composing = composing,
                showIndentGuides = showIndentGuides, showWhitespace = showWhitespace,
                tabWidth = tabWidth, gutterColor = gutterColor, caretColor = caretColor,
                guideColor = guideColor, extraCaretColor = extraCaretColor,
                severityErrorColor = errorColor, severityWarningColor = warningColor,
                severityInfoColor = infoColor,
            ),
            events = EditorCanvasEvents(
                actions = actions, emit = emit,
                onViewportChange = { viewport = it },
                onTapCaret = { actions.setSelection(TextRange(it)) },
                onToggleFold = { actions.toggleFold(it) },
                onComposingChange = { composing = it },
                onSetExtraCarets = { extraCarets = it },
                onFocusPane = { actions.focusPane(it) },
                secondaryPane = secondaryPane, tabWidth = tabWidth,
            ),
            measurer = measurer, vScroll = vScroll, hScroll = hScroll,
            focusRequester = focusRequester, modifier = canvasModifier,
        )
    }

    Column(modifier.fillMaxSize()) {
        if (showFind) {
            FindBar(
                query = query,
                replacement = replacement,
                caseSensitive = caseSensitive,
                useRegex = useRegex,
                regexValid = regexValid,
                matchCount = matches.size,
                activeMatch = activeMatch,
                onQueryChange = { query = it; activeMatch = 0 },
                onReplacementChange = { replacement = it },
                onToggleCase = { caseSensitive = !caseSensitive },
                onToggleRegex = { useRegex = !useRegex; activeMatch = 0 },
                onNext = { goTo(activeMatch + 1) },
                onPrev = { goTo(activeMatch - 1) },
                onReplace = {
                    matches.getOrNull(activeMatch)?.let { range ->
                        if (useRegex) {
                            actions.replaceMatchRegex(range, query, replacement, caseSensitive)
                        } else {
                            actions.replaceRange(range, replacement)
                        }
                    }
                },
                onReplaceAll = {
                    if (useRegex) {
                        actions.replaceAllRegex(query, replacement, caseSensitive)
                    } else {
                        actions.replaceAll(matches, replacement)
                    }
                },
                onClose = onCloseFind,
            )
            HorizontalDivider()
        }

        EditorChrome(
            data = EditorChromeData(
                value = value, lines = lines, lineStarts = lineStarts,
                visibleLines = visibleLines, visualStarts = visualStarts,
                lineHeight = lineHeight, charWidth = charWidth, gutterWidth = gutterWidth,
                scrollY = scrollY, scrollX = scrollX, maxScrollY = maxScrollY,
                viewport = viewport, totalHeight = totalHeight, wrapping = wrapping,
                wrapConstraints = wrapConstraints, wrapCounts = wrapCounts,
                style = style, colors = colors, spec = spec, tsSpans = tsSpans,
                completions = completions, showCompletions = showCompletions,
                tabLanguage = tab.language,
                gutterColor = gutterColor, caretColor = caretColor,
            ),
            events = EditorChromeEvents(
                actions = actions,
                onScrollTo = { fraction -> scrollY = (fraction * maxScrollY).coerceIn(0f, maxScrollY) },
            ),
            editorCanvas = editorCanvas,
            showFind = false,
            findBar = {},
            showMinimap = showMinimap,
            measurer = measurer,
            modifier = Modifier.weight(1f),
        )
    }
}
