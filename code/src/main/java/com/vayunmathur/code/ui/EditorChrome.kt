package com.vayunmathur.code.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.vayunmathur.code.R
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.code.domain.syntax.Language
import com.vayunmathur.code.domain.syntax.MAX_HIGHLIGHT_CHARS
import com.vayunmathur.code.domain.syntax.SyntaxColors
import com.vayunmathur.code.domain.syntax.TsColorSpan
import com.vayunmathur.code.domain.Completion
import com.vayunmathur.code.util.TreeSitterNative
import com.vayunmathur.code.util.TsKind
import androidx.compose.ui.graphics.Color
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconChevronRight
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconKeyboardArrowUp
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shared editor chrome used by both the classic [CodeEditor] and the experimental
 * [CodeEditorView]: the find/replace bar, the autocomplete popup, and the pure match finder
 * that backs both editors' search highlighting.
 */

/**
 * All (non-empty) match ranges of [query] in [text]. When [useRegex] is set an invalid pattern
 * yields no matches rather than throwing. Ranges are end-inclusive (`first..last`).
 */
fun findMatchRanges(
    text: String,
    query: String,
    caseSensitive: Boolean,
    useRegex: Boolean,
): List<IntRange> {
    if (query.isEmpty()) return emptyList()
    return if (useRegex) {
        runCatching {
            val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
            Regex(query, options).findAll(text).map { it.range }.filter { !it.isEmpty() }.toList()
        }.getOrDefault(emptyList())
    } else {
        val found = ArrayList<IntRange>()
        var i = text.indexOf(query, 0, ignoreCase = !caseSensitive)
        while (i >= 0) {
            found.add(i until i + query.length)
            i = text.indexOf(query, i + query.length, ignoreCase = !caseSensitive)
        }
        found
    }
}

/**
 * The autocomplete list, anchored at ([offsetX], [offsetY]) pixels within the editor box (the
 * caller supplies the caret position). Non-focusable so typing keeps driving the editor.
 */
@Composable
fun CompletionPopup(
    completions: List<Completion>,
    offsetX: Int,
    offsetY: Int,
    onAccept: (Completion) -> Unit,
    onDismiss: () -> Unit,
) {
    Popup(
        alignment = Alignment.TopStart,
        offset = IntOffset(offsetX, offsetY),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = false),
    ) {
        Column(
            Modifier
                .width(240.dp)
                .heightIn(max = 200.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .verticalScroll(rememberScrollState()),
        ) {
            completions.forEach { completion ->
                Text(
                    text = completion.label,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onAccept(completion) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    style = TextStyle(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Maps a tree-sitter [TsKind] onto this theme's colours. */
fun SyntaxColors.tsColor(kind: TsKind): Color = when (kind) {
    TsKind.KEYWORD -> keyword
    TsKind.STRING -> string
    TsKind.NUMBER -> number
    TsKind.COMMENT -> comment
    TsKind.ANNOTATION -> annotation
    TsKind.FUNCTION -> function
    TsKind.TYPE -> type
    TsKind.PROPERTY -> type
}

/**
 * Native tree-sitter colour spans for [text] in [language] resolved against [colors], or null when
 * tree-sitter is unavailable / the language is unsupported — signalling the regex fallback. Blocking
 * (parses the whole file), so composables must go through [rememberTreeSitterSpans] rather than
 * calling this directly.
 */
fun treeSitterColorSpans(text: String, language: Language, colors: SyntaxColors): List<TsColorSpan>? =
    TreeSitterNative.spans(text, language)?.map { TsColorSpan(it.start, it.end, colors.tsColor(it.kind)) }

/**
 * [treeSitterColorSpans] parsed off the main thread.
 *
 * `remember` caches the result but still runs the parse during composition, so opening a large
 * file blocked the main thread for the whole parse — and did it again on every keystroke, since
 * the buffer text is a key. This starts at null (the per-line regex tokenizer takes over) and
 * swaps the spans in when the parse finishes. Documents past [MAX_HIGHLIGHT_CHARS] are never
 * parsed: applying that many spans on every text layout costs more than the colours are worth.
 */
@Composable
internal fun rememberTreeSitterSpans(
    text: String,
    language: Language,
    colors: SyntaxColors,
): List<TsColorSpan>? = produceState<List<TsColorSpan>?>(null, text, language, colors) {
    value = null
    if (text.length <= MAX_HIGHLIGHT_CHARS) {
        value = withContext(Dispatchers.Default) { treeSitterColorSpans(text, language, colors) }
    }
}.value
