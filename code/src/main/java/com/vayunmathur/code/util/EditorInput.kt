package com.vayunmathur.code.util

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Pure editor-input logic, kept out of the ViewModel so it can be unit-tested and so
 * `onEditorChange` stays a thin call site.
 *
 * [applyEditorInput] looks at a single-character insertion (the common typing case) and, when
 * enabled, carries indentation onto a new line, opens an indented block between a bracket pair,
 * auto-closes brackets/quotes and "types over" a closer that already sits after the caret.
 * Anything that is not a clean single-character insertion is returned unchanged.
 */

private const val OPENERS = "([{"
private const val CLOSERS = ")]}"
private const val QUOTES = "\"'"

private fun matchingCloser(opener: Char): Char = when (opener) {
    '(' -> ')'
    '[' -> ']'
    '{' -> '}'
    else -> opener
}

/**
 * True when [new] is [old] with exactly [length] characters inserted so that the insertion ends at
 * [caret]. Compared in place: the equivalent `substring + concat + equals` allocated three copies
 * of the whole buffer on every keystroke, which is what made typing in a multi-megabyte file stall.
 */
private fun isPlainInsertion(old: String, new: String, caret: Int, length: Int): Boolean {
    val prefixLength = caret - length
    if (prefixLength < 0 || new.length != old.length + length) return false
    return new.regionMatches(0, old, 0, prefixLength) &&
        new.regionMatches(caret, old, prefixLength, old.length - prefixLength)
}

fun applyEditorInput(
    old: TextFieldValue,
    new: TextFieldValue,
    indentUnit: String,
    autoIndent: Boolean,
    autoCloseBrackets: Boolean,
): TextFieldValue {
    if (!new.selection.collapsed) return new
    val input = SingleCharInput.parse(old, new) ?: return handlePaste(old, new, autoIndent)
    if (input.char == '\n' && autoIndent) {
        return handleNewline(old, input, indentUnit)
    }
    if (autoCloseBrackets) {
        handleBracket(input, old, new)?.let { return it }
    }
    return new
}

private class SingleCharInput(val caret: Int, val char: Char, val pos: Int, val afterChar: Char?) {
    companion object {
        fun parse(old: TextFieldValue, new: TextFieldValue): SingleCharInput? {
            val caret = new.selection.start
            val diff = new.text.length - old.text.length
            // Must be exactly one character longer, inserted so removing it reproduces old text.
            if (diff != 1 || caret < 1) return null
            if (!isPlainInsertion(old.text, new.text, caret, 1)) return null
            val pos = caret - 1 // index in old.text where the character was inserted
            // The character that was to the right of the caret.
            return SingleCharInput(caret, new.text[caret - 1], pos, old.text.getOrNull(pos))
        }
    }
}

private fun handlePaste(old: TextFieldValue, new: TextFieldValue, autoIndent: Boolean): TextFieldValue {
    val caret = new.selection.start
    val diff = new.text.length - old.text.length
    // Paste: a multi-character insertion containing a newline. Re-base its indentation onto the
    // caret line so pasted blocks line up with their new surroundings.
    if (diff <= 1 || caret < diff) return new
    if (!isPlainInsertion(old.text, new.text, caret, diff)) return new
    val pasted = new.text.substring(caret - diff, caret)
    if (autoIndent && pasted.contains('\n')) {
        return reindentPaste(old.text, caret - diff, pasted)
    }
    return new
}

// Newline: carry the current line's leading whitespace; open a block between a bracket pair.
private fun handleNewline(old: TextFieldValue, input: SingleCharInput, indentUnit: String): TextFieldValue {
    val indent = lineIndent(old.text, input.pos)
    val beforeChar = old.text.getOrNull(input.pos - 1)
    if (isBracketPair(beforeChar, input.afterChar)) {
        val head = old.text.substring(0, input.pos) + "\n" + indent + indentUnit
        val text = head + "\n" + indent + old.text.substring(input.pos)
        return TextFieldValue(text, TextRange(head.length))
    }
    if (indent.isEmpty()) {
        val text = old.text.substring(0, input.pos) + "\n" + old.text.substring(input.pos)
        return TextFieldValue(text, TextRange(input.caret))
    }
    val text = old.text.substring(0, input.pos) + "\n" + indent + old.text.substring(input.pos)
    return TextFieldValue(text, TextRange(input.caret + indent.length))
}

private fun lineIndent(text: String, pos: Int): String {
    val lineStart = text.lastIndexOf('\n', pos - 1) + 1
    var i = lineStart
    while (i < pos && (text[i] == ' ' || text[i] == '\t')) i++
    return text.substring(lineStart, i)
}

private fun isBracketPair(beforeChar: Char?, afterChar: Char?): Boolean {
    if (beforeChar == null || afterChar == null) return false
    return beforeChar in OPENERS && matchingCloser(beforeChar) == afterChar
}

private fun handleBracket(
    input: SingleCharInput,
    old: TextFieldValue,
    new: TextFieldValue,
): TextFieldValue? {
    // Type-over: typing a closer/quote that already sits immediately after the caret.
    if ((input.char in CLOSERS || input.char in QUOTES) && input.afterChar == input.char) {
        return TextFieldValue(old.text, TextRange(input.caret))
    }
    // Auto-close: an opener gets its matching closer; a quote gets a second quote.
    val closer = when {
        input.char in OPENERS -> matchingCloser(input.char)
        input.char in QUOTES -> input.char
        else -> null
    }
    if (closer == null) return null
    val text = new.text.substring(0, input.caret) + closer + new.text.substring(input.caret)
    return TextFieldValue(text, TextRange(input.caret))
}

// ---- Line operations (pure; each is a single undo step at the call site) ----

/**
 * The `[start, end)` character range of the block of whole lines the selection touches: from
 * the start of the line containing the selection start to the end (before the newline) of the
 * line containing the selection end.
 */
private fun lineBlock(text: String, selMin: Int, selMax: Int): Pair<Int, Int> {
    val start = text.lastIndexOf('\n', selMin - 1) + 1
    var end = text.indexOf('\n', selMax)
    if (end < 0) end = text.length
    return start to end
}

/** Duplicates the line(s) the selection touches, placing the copy below and moving the caret to it. */
fun duplicateLine(value: TextFieldValue): TextFieldValue {
    val text = value.text
    val (start, end) = lineBlock(text, value.selection.min, value.selection.max)
    val block = text.substring(start, end)
    val newText = text.substring(0, end) + "\n" + block + text.substring(end)
    val shift = block.length + 1
    val sel = value.selection
    return TextFieldValue(newText, TextRange(sel.start + shift, sel.end + shift))
}

/** Deletes the line(s) the selection touches, along with one adjacent newline. */
fun deleteLine(value: TextFieldValue): TextFieldValue {
    val text = value.text
    val (start, end) = lineBlock(text, value.selection.min, value.selection.max)
    var removeStart = start
    val removeEnd: Int
    if (end < text.length) {
        removeEnd = end + 1 // drop the trailing newline
    } else {
        removeEnd = end
        if (start > 0) removeStart = start - 1 // last line: drop the preceding newline instead
    }
    val newText = text.substring(0, removeStart) + text.substring(removeEnd)
    val caret = removeStart.coerceAtMost(newText.length)
    return TextFieldValue(newText, TextRange(caret))
}

/** Swaps the line(s) the selection touches with the line above; a no-op on the first line. */
fun moveLineUp(value: TextFieldValue): TextFieldValue {
    val text = value.text
    val (start, end) = lineBlock(text, value.selection.min, value.selection.max)
    if (start == 0) return value
    val prevStart = text.lastIndexOf('\n', start - 2) + 1
    val prevLine = text.substring(prevStart, start - 1)
    val block = text.substring(start, end)
    val newText = text.substring(0, prevStart) + block + "\n" + prevLine + text.substring(end)
    val shift = -(prevLine.length + 1)
    val sel = value.selection
    return TextFieldValue(newText, TextRange(sel.start + shift, sel.end + shift))
}

/** Swaps the line(s) the selection touches with the line below; a no-op on the last line. */
fun moveLineDown(value: TextFieldValue): TextFieldValue {
    val text = value.text
    val (start, end) = lineBlock(text, value.selection.min, value.selection.max)
    if (end >= text.length) return value
    val nextStart = end + 1
    var nextEnd = text.indexOf('\n', nextStart)
    if (nextEnd < 0) nextEnd = text.length
    val nextLine = text.substring(nextStart, nextEnd)
    val block = text.substring(start, end)
    val newText = text.substring(0, start) + nextLine + "\n" + block + text.substring(nextEnd)
    val shift = nextLine.length + 1
    val sel = value.selection
    return TextFieldValue(newText, TextRange(sel.start + shift, sel.end + shift))
}

/**
 * Toggles a line comment [prefix] (e.g. `//`, `#`, `--`) on every line the selection touches.
 * If every non-blank line is already commented it uncomments; otherwise it comments, inserting
 * `"$prefix "` at each line's first non-whitespace column. Blank lines are left alone. The
 * returned selection spans the affected block.
 */
fun toggleLineComment(value: TextFieldValue, prefix: String): TextFieldValue {
    val text = value.text
    val (start, end) = lineBlock(text, value.selection.min, value.selection.max)
    val block = text.substring(start, end)
    val lines = block.split('\n')
    val nonBlank = lines.filter { it.isNotBlank() }
    val allCommented = nonBlank.isNotEmpty() && nonBlank.all { it.trimStart().startsWith(prefix) }

    val newLines = lines.map { line ->
        if (line.isBlank()) return@map line
        val indentLen = line.indexOfFirst { it != ' ' && it != '\t' }.let { if (it < 0) 0 else it }
        val indent = line.substring(0, indentLen)
        val rest = line.substring(indentLen)
        if (allCommented) {
            var r = rest.removePrefix(prefix)
            if (r.startsWith(" ")) r = r.substring(1)
            indent + r
        } else {
            "$indent$prefix $rest"
        }
    }
    val newBlock = newLines.joinToString("\n")
    val newText = text.substring(0, start) + newBlock + text.substring(end)
    return TextFieldValue(newText, TextRange(start, start + newBlock.length))
}

/**
 * Indents every line the selection touches by [indentUnit]; the returned selection spans the block.
 */
fun indentSelection(value: TextFieldValue, indentUnit: String): TextFieldValue {
    val text = value.text
    val (start, end) = lineBlock(text, value.selection.min, value.selection.max)
    val newBlock = text.substring(start, end).split('\n').joinToString("\n") { indentUnit + it }
    val newText = text.substring(0, start) + newBlock + text.substring(end)
    return TextFieldValue(newText, TextRange(start, start + newBlock.length))
}

/**
 * Removes up to [tabWidth] leading spaces (or one leading tab) from every line the selection
 * touches; the returned selection spans the block.
 */
fun dedentSelection(value: TextFieldValue, tabWidth: Int): TextFieldValue {
    val text = value.text
    val (start, end) = lineBlock(text, value.selection.min, value.selection.max)
    val newBlock = text.substring(start, end).split('\n').joinToString("\n") { line ->
        var removed = 0
        var i = 0
        while (i < line.length && removed < tabWidth) {
            when (line[i]) {
                ' ' -> {
                    removed++
                    i++
                }
                '\t' -> {
                    removed = tabWidth
                    i++
                }
                else -> break
            }
        }
        line.substring(i)
    }
    val newText = text.substring(0, start) + newBlock + text.substring(end)
    return TextFieldValue(newText, TextRange(start, start + newBlock.length))
}

/** Char offset of the start of a 1-based [line] in [text], clamped to `0..text.length`. */
fun lineStartOffset(text: String, line: Int): Int {
    val target = line.coerceAtLeast(1)
    var offset = 0
    var current = 1
    while (current < target) {
        val nl = text.indexOf('\n', offset)
        if (nl < 0) return text.length
        offset = nl + 1
        current++
    }
    return offset.coerceAtMost(text.length)
}

/** One matching line from a project search: its 1-based [line] number and a trimmed [preview]. */
data class LineMatch(val line: Int, val preview: String)

/**
 * Pure line-by-line matcher shared by the project search. Returns at most [limit] matching lines.
 * An invalid regex (when [useRegex]) yields no matches rather than throwing.
 */
fun findLineMatches(
    text: String,
    query: String,
    caseSensitive: Boolean,
    useRegex: Boolean,
    limit: Int = Int.MAX_VALUE,
): List<LineMatch> {
    if (query.isEmpty()) return emptyList()
    val regex = if (useRegex) {
        runCatching {
            Regex(query, if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE))
        }.getOrNull() ?: return emptyList()
    } else {
        null
    }
    val result = ArrayList<LineMatch>()
    var lineNo = 0
    for (line in text.lineSequence()) {
        lineNo++
        val hit = if (regex != null) {
            regex.containsMatchIn(line)
        } else {
            line.contains(query, ignoreCase = !caseSensitive)
        }
        if (hit) {
            result.add(LineMatch(lineNo, line.trim().take(PREVIEW_LIMIT)))
            if (result.size >= limit) break
        }
    }
    return result
}

private const val PREVIEW_LIMIT = 200

// ---- Paste re-indentation & save transforms (pure) ----

private fun leadingWhitespaceLen(line: String): Int {
    var i = 0
    while (i < line.length && (line[i] == ' ' || line[i] == '\t')) i++
    return i
}

/**
 * Inserts [pasted] at [caret] in [text], re-basing the block's indentation onto the caret line.
 *
 * The minimal common indentation across the pasted block's non-blank lines is stripped, and the
 * caret line's leading whitespace ([caret]'s line indent) is re-applied to every line after the
 * first (the first pasted line continues at the caret, which already sits after that indent).
 * Relative indentation within the block is preserved.
 */
fun reindentPaste(text: String, caret: Int, pasted: String): TextFieldValue {
    val lineStart = text.lastIndexOf('\n', caret - 1) + 1
    val baseIndent = text.substring(lineStart, lineStart + leadingWhitespaceLen(text.substring(lineStart, caret)))

    val lines = pasted.split('\n')
    val nonBlank = lines.filter { it.isNotBlank() }
    val common = nonBlank.minOfOrNull { leadingWhitespaceLen(it) } ?: 0

    val reindented = lines.mapIndexed { idx, line ->
        val stripped = if (line.isBlank()) "" else line.substring(common.coerceAtMost(line.length))
        if (idx == 0) stripped else baseIndent + stripped
    }
    val block = reindented.joinToString("\n")
    val newText = text.substring(0, caret) + block + text.substring(caret)
    return TextFieldValue(newText, TextRange(caret + block.length))
}

/** Removes trailing spaces and tabs from every line (used by the trim-on-save preference). */
fun trimTrailingWhitespace(text: String): String =
    text.split('\n').joinToString("\n") { it.trimEnd(' ', '\t') }

/** Ensures the text ends with exactly one trailing newline (used by the final-newline preference). */
fun ensureFinalNewline(text: String): String =
    if (text.isEmpty() || text.endsWith('\n')) text else text + "\n"
