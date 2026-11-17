package com.vayunmathur.code.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import com.vayunmathur.code.domain.syntax.SyntaxColors
import com.vayunmathur.code.domain.syntax.TsColorSpan
import com.vayunmathur.code.platform.CodeActions
import com.vayunmathur.code.domain.Diagnostic
import com.vayunmathur.code.domain.DiagnosticSeverity
import com.vayunmathur.code.domain.FoldRegion
/** Values the editor canvas reads; built per composition in [CodeEditorView]. */
internal data class EditorCanvasData(
    val value: TextFieldValue,
    val text: String,
    val lines: List<String>,
    val lineStarts: IntArray,
    val visibleLines: List<Int>,
    val visualStarts: IntArray,
    val totalVisualRows: Int,
    val wrapCounts: IntArray,
    val lineHeight: Float,
    val charWidth: Float,
    val gutterWidth: Float,
    val scrollY: Float,
    val scrollX: Float,
    val wrapping: Boolean,
    val wrapConstraints: Constraints,
    val style: TextStyle,
    val colors: SyntaxColors,
    val spec: com.vayunmathur.code.domain.syntax.LanguageSpec?,
    val tsSpans: List<TsColorSpan>?,
    val highlightMatches: List<IntRange>,
    val activeMatch: Int,
    val diagnosticsByLine: Map<Int, List<Diagnostic>>,
    val foldedHeaders: Set<Int>,
    val foldByHeader: Map<Int, FoldRegion>,
    val extraCarets: List<Int>,
    val composing: TextRange?,
    val showIndentGuides: Boolean,
    val showWhitespace: Boolean,
    val tabWidth: Int,
    val gutterColor: Color,
    val caretColor: Color,
    val guideColor: Color,
    val extraCaretColor: Color,
    val severityErrorColor: Color,
    val severityWarningColor: Color,
    val severityInfoColor: Color,
)

/** Callbacks the canvas fires; thin wrappers over [CodeEditorView]'s local state and [CodeActions]. */
internal data class EditorCanvasEvents(
    val actions: CodeActions,
    val emit: (TextFieldValue) -> Unit,
    val onViewportChange: (IntSize) -> Unit,
    val onTapCaret: (Int) -> Unit,
    val onToggleFold: (Int) -> Unit,
    val onComposingChange: (TextRange?) -> Unit,
    val onSetExtraCarets: (List<Int>) -> Unit,
    val onFocusPane: (Boolean) -> Unit,
    val secondaryPane: Boolean,
    val tabWidth: Int,
)

/**
 * The virtualized editor canvas: scroll/tap/IME/key handling plus the viewport draw dispatch
 * ([drawPlainLines] / [drawWrappedLines]).
 */
@Composable
internal fun EditorCanvas(
    data: EditorCanvasData,
    events: EditorCanvasEvents,
    measurer: TextMeasurer,
    vScroll: androidx.compose.foundation.gestures.ScrollableState,
    hScroll: androidx.compose.foundation.gestures.ScrollableState,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val value = data.value
    val actions = events.actions
    fun severityColor(s: DiagnosticSeverity): Color = when (s) {
        DiagnosticSeverity.ERROR -> data.severityErrorColor
        DiagnosticSeverity.WARNING -> data.severityWarningColor
        DiagnosticSeverity.INFO -> data.severityInfoColor
    }
    Canvas(
        modifier
            .onSizeChanged { events.onViewportChange(it) }
            .scrollable(vScroll, Orientation.Vertical)
            .scrollable(hScroll, Orientation.Horizontal)
            .pointerInput(data.text, data.visibleLines, data.wrapping) {
                detectTapGestures { pos ->
                    if (data.wrapping) {
                        val visualRow = ((pos.y + data.scrollY) / data.lineHeight).toInt()
                            .coerceIn(0, (data.totalVisualRows - 1).coerceAtLeast(0))
                        val source = data.visibleLines.getOrNull(visualRowToVisibleIndex(data.visibleLines, data.visualStarts, visualRow))
                        if (pos.x < data.gutterWidth && source != null && data.foldByHeader.containsKey(source)) {
                            events.onToggleFold(source)
                        } else {
                            events.onTapCaret(offsetAtWrapped(
                                pos.x, pos.y, data.scrollY, data.lineHeight, data.gutterWidth,
                                data.visibleLines, data.visualStarts, data.totalVisualRows,
                                data.lines, data.lineStarts, data.text.length,
                                measurer, data.style, data.wrapConstraints, data.spec, data.colors,
                            ))
                            events.onSetExtraCarets(emptyList())
                            focusRequester.requestFocus()
                        }
                    } else {
                        val row = ((pos.y + data.scrollY) / data.lineHeight).toInt()
                        val source = displayRowToSource(data.visibleLines, row)
                        if (pos.x < data.gutterWidth && source != null && data.foldByHeader.containsKey(source)) {
                            events.onToggleFold(source)
                        } else {
                            events.onTapCaret(offsetAt(
                                pos.x, pos.y, data.scrollY, data.scrollX, data.lineHeight,
                                data.charWidth, data.gutterWidth, data.visibleLines,
                                data.lines, data.lineStarts, data.text.length,
                            ))
                            events.onSetExtraCarets(emptyList())
                            focusRequester.requestFocus()
                        }
                    }
                }
            }
            .focusRequester(focusRequester)
            .focusable()
            .onFocusChanged { if (it.isFocused) events.onFocusPane(events.secondaryPane) }
            .codeEditorInput(
                enabled = true,
                value = value,
                onValueChange = events.emit,
                onComposingChange = events.onComposingChange,
            )
            .onPreviewKeyEvent { event ->
                handleEditorKey(
                    event = event,
                    value = value,
                    tabWidth = events.tabWidth,
                    extraCarets = data.extraCarets,
                    setExtraCarets = events.onSetExtraCarets,
                    emit = events.emit,
                    move = { actions.setSelection(it) },
                    onSave = { actions.save() },
                    onComment = { actions.toggleComment() },
                    foldAll = { actions.foldAllInTab() },
                    unfoldAll = { actions.unfoldAll() },
                    toggleFoldAtCaret = { caret ->
                        val line = lineOfOffset(data.lineStarts, caret)
                        val header = data.foldByHeader.keys.filter { h ->
                            val r = data.foldByHeader[h]!!
                            line in r.startLine..r.endLine
                        }.maxOrNull()
                        if (header != null) actions.toggleFold(header)
                    },
                )
            },
    ) {
        val caret = if (value.selection.collapsed) value.selection.start else -1
        val selMin = value.selection.min
        val selMax = value.selection.max

        if (!data.wrapping) {
            drawPlainLines(
                measurer = measurer, style = data.style, charWidth = data.charWidth,
                lineHeight = data.lineHeight, gutterWidth = data.gutterWidth,
                scrollX = data.scrollX, scrollY = data.scrollY,
                visibleLines = data.visibleLines, lines = data.lines, lineStarts = data.lineStarts,
                caret = caret, selMin = selMin, selMax = selMax,
                highlightMatches = data.highlightMatches, activeMatch = data.activeMatch,
                caretColor = data.caretColor, colors = data.colors, spec = data.spec, tsSpans = data.tsSpans,
                diagnosticsByLine = data.diagnosticsByLine, severityColor = ::severityColor,
                foldedHeaders = data.foldedHeaders, foldByHeader = data.foldByHeader,
                extraCarets = data.extraCarets, extraCaretColor = data.extraCaretColor,
                composing = data.composing, showIndentGuides = data.showIndentGuides,
                tabWidth = data.tabWidth, guideColor = data.guideColor,
                showWhitespace = data.showWhitespace, gutterColor = data.gutterColor,
            )
        } else {
            drawWrappedLines(
                measurer = measurer, style = data.style, wrapConstraints = data.wrapConstraints,
                lineHeight = data.lineHeight, gutterWidth = data.gutterWidth, scrollY = data.scrollY,
                visibleLines = data.visibleLines, visualStarts = data.visualStarts,
                totalVisualRows = data.totalVisualRows, wrapCounts = data.wrapCounts,
                lines = data.lines, lineStarts = data.lineStarts,
                caret = caret, selMin = selMin, selMax = selMax,
                highlightMatches = data.highlightMatches, activeMatch = data.activeMatch,
                caretColor = data.caretColor, colors = data.colors, spec = data.spec, tsSpans = data.tsSpans,
                diagnosticsByLine = data.diagnosticsByLine, severityColor = ::severityColor,
                foldedHeaders = data.foldedHeaders, foldByHeader = data.foldByHeader,
                extraCarets = data.extraCarets, extraCaretColor = data.extraCaretColor,
                composing = data.composing, gutterColor = data.gutterColor,
            )
        }
    }
}
