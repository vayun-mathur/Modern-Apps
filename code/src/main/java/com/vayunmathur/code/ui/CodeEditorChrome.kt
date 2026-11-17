package com.vayunmathur.code.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.vayunmathur.code.domain.syntax.Language
import com.vayunmathur.code.domain.syntax.SyntaxColors
import com.vayunmathur.code.domain.syntax.TsColorSpan
import com.vayunmathur.code.platform.CodeActions
import com.vayunmathur.code.domain.Completion
import com.vayunmathur.library.ui.HorizontalDivider
import kotlin.math.roundToInt

/** Values the editor chrome reads; built per composition in [CodeEditorView]. */
internal data class EditorChromeData(
    val value: TextFieldValue,
    val lines: List<String>,
    val lineStarts: IntArray,
    val visibleLines: List<Int>,
    val visualStarts: IntArray,
    val lineHeight: Float,
    val charWidth: Float,
    val gutterWidth: Float,
    val scrollY: Float,
    val scrollX: Float,
    val maxScrollY: Float,
    val viewport: IntSize,
    val totalHeight: Float,
    val wrapping: Boolean,
    val wrapConstraints: Constraints,
    val wrapCounts: IntArray,
    val style: TextStyle,
    val colors: SyntaxColors,
    val spec: com.vayunmathur.code.domain.syntax.LanguageSpec?,
    val tsSpans: List<TsColorSpan>?,
    val completions: List<Completion>,
    val showCompletions: Boolean,
    val tabLanguage: Language,
    val gutterColor: Color,
    val caretColor: Color,
)

/** Callbacks the chrome fires; thin wrappers over [CodeEditorView]'s local state and [CodeActions]. */
internal data class EditorChromeEvents(
    val actions: CodeActions,
    val onScrollTo: (Float) -> Unit,
)

/**
 * The editor chrome below the canvas: the find/replace bar, the canvas-with-minimap row, and
 * the autocomplete popup anchored at the caret in Canvas coordinates (Phase 4).
 */
@Composable
internal fun EditorChrome(
    data: EditorChromeData,
    events: EditorChromeEvents,
    editorCanvas: @Composable (Modifier) -> Unit,
    showFind: Boolean,
    findBar: @Composable () -> Unit,
    showMinimap: Boolean,
    measurer: TextMeasurer,
    modifier: Modifier = Modifier,
) {
    val value = data.value
    Column(modifier.fillMaxSize()) {
        if (showFind) {
            findBar()
            HorizontalDivider()
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (showMinimap) {
                Row(Modifier.fillMaxSize()) {
                    editorCanvas(Modifier.weight(1f).fillMaxHeight())
                    Minimap(
                        lines = data.lines,
                        lineHeight = data.lineHeight,
                        scrollY = data.scrollY,
                        maxScrollY = data.maxScrollY,
                        viewportHeight = data.viewport.height.toFloat(),
                        totalHeight = data.totalHeight,
                        color = data.gutterColor.copy(alpha = 0.5f),
                        viewportColor = data.caretColor.copy(alpha = 0.2f),
                        onScrollTo = events.onScrollTo,
                        modifier = Modifier.width(56.dp).fillMaxHeight(),
                    )
                }
            } else {
                editorCanvas(Modifier.fillMaxSize())
            }

            // Autocomplete popup (Phase 4), anchored at the caret in Canvas coordinates.
            val caretOffset = value.selection.start
            if (data.showCompletions && data.completions.isNotEmpty() && value.selection.collapsed) {
                val src = lineOfOffset(data.lineStarts, caretOffset)
                val k = data.visibleLines.indexOf(src)
                if (k >= 0) {
                    val col = caretOffset - data.lineStarts[src]
                    val px: Int
                    val py: Int
                    if (data.wrapping) {
                        val layout = measurer.measure(annotatedLine(data.lines[src], data.spec, data.colors), data.style, constraints = data.wrapConstraints)
                        val rect = layout.getCursorRect(col.coerceIn(0, data.lines[src].length))
                        px = (data.gutterWidth + rect.left).roundToInt()
                        py = (data.visualStarts[k] * data.lineHeight - data.scrollY + rect.bottom).roundToInt()
                    } else {
                        px = (data.gutterWidth + col * data.charWidth - data.scrollX).roundToInt()
                        py = (k * data.lineHeight - data.scrollY + data.lineHeight).roundToInt()
                    }
                    CompletionPopup(
                        completions = data.completions,
                        offsetX = px,
                        offsetY = py,
                        onAccept = { events.actions.acceptCompletion(it) },
                        onDismiss = { events.actions.dismissCompletions() },
                    )
                }
            }
        }
    }
}
