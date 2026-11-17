package com.vayunmathur.code.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.code.domain.syntax.SyntaxTransformation
import com.vayunmathur.code.domain.syntax.rememberSyntaxColors
import com.vayunmathur.code.platform.CodeActions
import com.vayunmathur.code.domain.Completion
import com.vayunmathur.code.domain.Diagnostic
import com.vayunmathur.code.domain.DiagnosticSeverity
import com.vayunmathur.code.platform.TabUiState
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import kotlin.math.roundToInt

/**
 * The editing surface: an optional find/replace bar above a scroll-synced line-number gutter
 * and a monospace [BasicTextField]. Syntax highlighting and match highlighting are applied
 * through a [SyntaxTransformation]; horizontal scrolling is enabled only when soft-wrap is off.
 */
@Composable
fun CodeEditor(
    tab: TabUiState,
    actions: CodeActions,
    softWrap: Boolean,
    showFind: Boolean,
    onCloseFind: () -> Unit,
    modifier: Modifier = Modifier,
    fontSize: Int = 14,
    /** Preview seam: the query the find bar starts on. The app always starts it empty. */
    initialQuery: String = "",
    completions: List<Completion> = emptyList(),
    showCompletions: Boolean = false,
    editorTheme: String = com.vayunmathur.code.domain.syntax.EditorThemes.DEFAULT,
    diagnostics: List<Diagnostic> = emptyList(),
    /** True when this is the secondary split pane, so focus routes shared actions there. */
    secondaryPane: Boolean = false,
    /** When set, edits route here instead of [CodeActions.onEditorChange] (used by the second pane). */
    onValueChangeOverride: ((androidx.compose.ui.text.input.TextFieldValue) -> Unit)? = null,
) {
    // Find/replace state is per-tab, so it resets when switching files. Keyed on the file
    // name rather than the tab value: the latter is rebuilt on every keystroke, which would
    // clear the query as the user types.
    var query by remember(tab.name) { mutableStateOf(initialQuery) }
    var replacement by remember(tab.name) { mutableStateOf("") }
    var caseSensitive by remember(tab.name) { mutableStateOf(false) }
    var useRegex by remember(tab.name) { mutableStateOf(false) }
    var activeMatch by remember(tab.name) { mutableIntStateOf(0) }

    val text = tab.value.text
    val regexValid = remember(query, useRegex) {
        !useRegex || query.isEmpty() || runCatching { Regex(query) }.isSuccess
    }
    val matches = remember(text, query, caseSensitive, useRegex) {
        findMatchRanges(text, query, caseSensitive, useRegex)
    }

    LaunchedEffect(matches.size) {
        if (activeMatch >= matches.size) activeMatch = 0
    }

    val goTo: (Int) -> Unit = { target ->
        if (matches.isNotEmpty()) {
            val idx = ((target % matches.size) + matches.size) % matches.size
            activeMatch = idx
            val range = matches[idx]
            actions.setSelection(TextRange(range.first, range.last + 1))
        }
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
                    if (matches.isNotEmpty()) {
                        if (useRegex) {
                            actions.replaceMatchRegex(matches[activeMatch], query, replacement, caseSensitive)
                        } else {
                            actions.replaceRange(matches[activeMatch], replacement)
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

        val editorStyle = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = fontSize.sp,
            lineHeight = (fontSize * 1.4f).sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        val syntaxColors = rememberSyntaxColors(editorTheme)
        val highlightMatches = if (showFind) matches else emptyList()
        val caret = if (tab.value.selection.collapsed) tab.value.selection.start else -1
        val tsSpans = rememberTreeSitterSpans(text, tab.language, syntaxColors)
        val transformation = remember(tab.language, syntaxColors, highlightMatches, activeMatch, caret, tsSpans) {
            SyntaxTransformation(tab.language.spec, syntaxColors, highlightMatches, activeMatch, caret, tsSpans)
        }

        val verticalScroll = rememberScrollState()
        val horizontalScroll = rememberScrollState()
        // Scroll offsets change on every scroll frame; read them through derived
        // state so composition itself does not subscribe to the raw scroll values
        // (only the completion-popup position below observes them).
        val hScrollPx by remember { derivedStateOf { horizontalScroll.value } }
        val vScrollPx by remember { derivedStateOf { verticalScroll.value } }
        val lineCount = remember(text) { text.count { it == '\n' } + 1 }
        var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

        Row(Modifier.fillMaxSize()) {
            LineGutter(lineCount, verticalScroll, editorStyle, diagnostics)
            Box(Modifier.weight(1f).fillMaxHeight()) {
                BasicTextField(
                    value = tab.value,
                    onValueChange = onValueChangeOverride ?: actions::onEditorChange,
                    modifier = Modifier
                        .fillMaxSize()
                        .onFocusChanged { if (it.isFocused) actions.focusPane(secondaryPane) }
                        .verticalScroll(verticalScroll)
                        .then(if (softWrap) Modifier else Modifier.horizontalScroll(horizontalScroll))
                        .padding(horizontal = 8.dp),
                    textStyle = editorStyle,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    visualTransformation = transformation,
                    onTextLayout = { layoutResult = it },
                )
                val layout = layoutResult
                if (showCompletions && completions.isNotEmpty() && caret >= 0 && layout != null) {
                    val padPx = with(LocalDensity.current) { 8.dp.roundToPx() }
                    val safeCaret = caret.coerceIn(0, layout.layoutInput.text.length)
                    val cursorRect = layout.getCursorRect(safeCaret)
                    val x = (cursorRect.left - hScrollPx + padPx).roundToInt()
                    val y = (cursorRect.bottom - vScrollPx).roundToInt()
                    CompletionPopup(
                        completions = completions,
                        offsetX = x,
                        offsetY = y,
                        onAccept = { actions.acceptCompletion(it) },
                        onDismiss = { actions.dismissCompletions() },
                    )
                }
            }
        }
    }
}

/**
 * The line-number column. Rendered as a single right-aligned [Text] (one number per line) so
 * it stays cheap for large files, and sharing [verticalScroll] keeps it locked to the editor.
 * A thin overlay [Canvas] draws a severity dot beside any line with [diagnostics] (inline squiggles
 * on a [BasicTextField] are impractical, so the classic editor shows gutter markers only).
 * With soft-wrap on, wrapped lines shift the text below their number — an accepted trade-off.
 */
@Composable
private fun LineGutter(
    lineCount: Int,
    verticalScroll: androidx.compose.foundation.ScrollState,
    style: TextStyle,
    diagnostics: List<Diagnostic> = emptyList(),
) {
    val numbers = remember(lineCount) { (1..lineCount).joinToString("\n") }
    val width = (lineCount.toString().length * 10 + 16).dp
    val density = LocalDensity.current
    val lineHeightPx = with(density) { style.lineHeight.toPx() }
    val byLine = remember(diagnostics) { diagnostics.groupBy { it.line } }
    val errorColor = MaterialTheme.colorScheme.error
    val warningColor = Color(0xFFFFB300)
    val infoColor = MaterialTheme.colorScheme.onSurfaceVariant
    Box(Modifier.width(width)) {
        Text(
            text = numbers,
            modifier = Modifier
                .verticalScroll(verticalScroll)
                .fillMaxHeight()
                .padding(horizontal = 4.dp),
            style = style,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Right,
        )
        if (byLine.isNotEmpty() && lineHeightPx > 0f) {
            Canvas(Modifier.matchParentSize()) {
                val scroll = verticalScroll.value.toFloat()
                for ((line, ds) in byLine) {
                    val worst = ds.minByOrNull { it.severity.ordinal } ?: continue
                    val cy = line * lineHeightPx - scroll + lineHeightPx / 2f
                    if (cy < -lineHeightPx || cy > size.height + lineHeightPx) continue
                    val color = when (worst.severity) {
                        DiagnosticSeverity.ERROR -> errorColor
                        DiagnosticSeverity.WARNING -> warningColor
                        DiagnosticSeverity.INFO -> infoColor
                    }
                    drawCircle(color, radius = 3f, center = androidx.compose.ui.geometry.Offset(4f, cy))
                }
            }
        }
    }
}
