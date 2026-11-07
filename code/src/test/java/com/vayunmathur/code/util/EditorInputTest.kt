package com.vayunmathur.code.util

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for the pure editor helpers: smart input (indent carry, block-open, auto-close,
 * type-over), go-to-line offset math and the project-search line matcher.
 */
class EditorInputTest {

    private fun apply(
        old: TextFieldValue,
        new: TextFieldValue,
        indentUnit: String = "  ",
        autoIndent: Boolean = true,
        autoCloseBrackets: Boolean = true,
    ) = applyEditorInput(old, new, indentUnit, autoIndent, autoCloseBrackets)

    @Test
    fun newlineCarriesLeadingWhitespace() {
        val old = TextFieldValue("    foo", TextRange(7))
        val new = TextFieldValue("    foo\n", TextRange(8))
        val result = apply(old, new)
        assertEquals("    foo\n    ", result.text)
        assertEquals(12, result.selection.start)
    }

    @Test
    fun newlineBetweenBracketsOpensIndentedBlock() {
        val old = TextFieldValue("{}", TextRange(1))
        val new = TextFieldValue("{\n}", TextRange(2))
        val result = apply(old, new)
        assertEquals("{\n  \n}", result.text)
        assertEquals(4, result.selection.start)
    }

    @Test
    fun newlineBetweenBracketsCarriesExistingIndent() {
        val old = TextFieldValue("    {}", TextRange(5))
        val new = TextFieldValue("    {\n}", TextRange(6))
        val result = apply(old, new)
        assertEquals("    {\n      \n    }", result.text)
        assertEquals(12, result.selection.start)
    }

    @Test
    fun autoCloseInsertsMatchingBracket() {
        val old = TextFieldValue("", TextRange(0))
        val new = TextFieldValue("(", TextRange(1))
        val result = apply(old, new)
        assertEquals("()", result.text)
        assertEquals(1, result.selection.start)
    }

    @Test
    fun autoCloseInsertsMatchingQuote() {
        val old = TextFieldValue("", TextRange(0))
        val new = TextFieldValue("\"", TextRange(1))
        val result = apply(old, new)
        assertEquals("\"\"", result.text)
        assertEquals(1, result.selection.start)
    }

    @Test
    fun typingCloserOverExistingCloserTypesOver() {
        val old = TextFieldValue("()", TextRange(1))
        val new = TextFieldValue("())", TextRange(2))
        val result = apply(old, new)
        assertEquals("()", result.text)
        assertEquals(2, result.selection.start)
    }

    @Test
    fun autoCloseDisabledLeavesInsertionUntouched() {
        val old = TextFieldValue("", TextRange(0))
        val new = TextFieldValue("(", TextRange(1))
        val result = apply(old, new, autoCloseBrackets = false)
        assertEquals("(", result.text)
        assertEquals(1, result.selection.start)
    }

    @Test
    fun autoIndentDisabledLeavesNewlineUntouched() {
        val old = TextFieldValue("    foo", TextRange(7))
        val new = TextFieldValue("    foo\n", TextRange(8))
        val result = apply(old, new, autoIndent = false)
        assertEquals("    foo\n", result.text)
        assertEquals(8, result.selection.start)
    }

    @Test
    fun multiCharacterChangeIsReturnedUnchanged() {
        val old = TextFieldValue("", TextRange(0))
        val new = TextFieldValue("abc", TextRange(3))
        val result = apply(old, new)
        assertEquals("abc", result.text)
        assertEquals(3, result.selection.start)
    }

    @Test
    fun plainCharacterIsReturnedUnchanged() {
        val old = TextFieldValue("a", TextRange(1))
        val new = TextFieldValue("ab", TextRange(2))
        val result = apply(old, new)
        assertEquals("ab", result.text)
        assertEquals(2, result.selection.start)
    }

    @Test
    fun editThatIsNotAPlainInsertionAtTheCaretIsReturnedUnchanged() {
        // Same length delta as typing one character, but the surrounding text also changed, so
        // smart input must not treat it as an insertion of "(" and auto-close it.
        val old = TextFieldValue("foo", TextRange(3))
        val new = TextFieldValue("f(oo", TextRange(4))
        val result = apply(old, new)
        assertEquals("f(oo", result.text)
    }

    @Test
    fun lineStartOffsetFindsLineStarts() {
        val text = "a\nbb\nccc"
        assertEquals(0, lineStartOffset(text, 1))
        assertEquals(2, lineStartOffset(text, 2))
        assertEquals(5, lineStartOffset(text, 3))
    }

    @Test
    fun lineStartOffsetClampsOutOfRange() {
        val text = "a\nbb\nccc"
        assertEquals(text.length, lineStartOffset(text, 99))
        assertEquals(0, lineStartOffset(text, 0))
        assertEquals(0, lineStartOffset(text, -5))
    }

    @Test
    fun findLineMatchesLiteralIsCaseInsensitiveByDefault() {
        val text = "Foo\nbar\nfoobar"
        val matches = findLineMatches(text, "foo", caseSensitive = false, useRegex = false)
        assertEquals(listOf(1, 3), matches.map { it.line })
        assertEquals(listOf("Foo", "foobar"), matches.map { it.preview })
    }

    @Test
    fun findLineMatchesLiteralRespectsCaseSensitivity() {
        val text = "Foo\nfoo"
        val matches = findLineMatches(text, "foo", caseSensitive = true, useRegex = false)
        assertEquals(listOf(2), matches.map { it.line })
    }

    @Test
    fun findLineMatchesRegex() {
        val text = "foo\nbar\nfoobar"
        val matches = findLineMatches(text, "^foo", caseSensitive = true, useRegex = true)
        assertEquals(listOf(1, 3), matches.map { it.line })
    }

    @Test
    fun findLineMatchesRespectsLimit() {
        val text = "foo\nfoo\nfoo"
        val matches = findLineMatches(text, "foo", caseSensitive = false, useRegex = false, limit = 1)
        assertEquals(1, matches.size)
    }

    @Test
    fun findLineMatchesInvalidRegexYieldsNothing() {
        val text = "foo(bar"
        val matches = findLineMatches(text, "(", caseSensitive = false, useRegex = true)
        assertEquals(emptyList(), matches.map { it.line })
    }

    // ---- Line operations ----

    @Test
    fun duplicateLineCopiesCurrentLineBelow() {
        val result = duplicateLine(TextFieldValue("foo\nbar", TextRange(1)))
        assertEquals("foo\nfoo\nbar", result.text)
        assertEquals(5, result.selection.start)
    }

    @Test
    fun deleteLineRemovesCurrentLineAndNewline() {
        val result = deleteLine(TextFieldValue("foo\nbar\nbaz", TextRange(5)))
        assertEquals("foo\nbaz", result.text)
        assertEquals(4, result.selection.start)
    }

    @Test
    fun deleteLineOnLastLineDropsPrecedingNewline() {
        val result = deleteLine(TextFieldValue("foo\nbar", TextRange(6)))
        assertEquals("foo", result.text)
        assertEquals(3, result.selection.start)
    }

    @Test
    fun moveLineUpSwapsWithLineAbove() {
        val result = moveLineUp(TextFieldValue("foo\nbar\nbaz", TextRange(5)))
        assertEquals("bar\nfoo\nbaz", result.text)
        assertEquals(1, result.selection.start)
    }

    @Test
    fun moveLineUpOnFirstLineIsNoOp() {
        val value = TextFieldValue("foo\nbar", TextRange(1))
        val result = moveLineUp(value)
        assertEquals("foo\nbar", result.text)
        assertEquals(1, result.selection.start)
    }

    @Test
    fun moveLineDownSwapsWithLineBelow() {
        val result = moveLineDown(TextFieldValue("foo\nbar\nbaz", TextRange(1)))
        assertEquals("bar\nfoo\nbaz", result.text)
        assertEquals(5, result.selection.start)
    }

    @Test
    fun moveLineDownOnLastLineIsNoOp() {
        val value = TextFieldValue("foo\nbar", TextRange(5))
        val result = moveLineDown(value)
        assertEquals("foo\nbar", result.text)
        assertEquals(5, result.selection.start)
    }

    // ---- Comment toggle ----

    @Test
    fun toggleCommentAddsPrefixWithSpace() {
        val result = toggleLineComment(TextFieldValue("foo", TextRange(0)), "//")
        assertEquals("// foo", result.text)
    }

    @Test
    fun toggleCommentRemovesPrefixAndSpace() {
        val result = toggleLineComment(TextFieldValue("// foo", TextRange(0)), "//")
        assertEquals("foo", result.text)
    }

    @Test
    fun toggleCommentPreservesIndent() {
        val result = toggleLineComment(TextFieldValue("    foo", TextRange(4)), "//")
        assertEquals("    // foo", result.text)
    }

    @Test
    fun toggleCommentAcrossMultipleLines() {
        val value = TextFieldValue("foo\nbar", TextRange(0, 7))
        val result = toggleLineComment(value, "#")
        assertEquals("# foo\n# bar", result.text)
    }

    @Test
    fun toggleCommentUncommentsWhenAllCommented() {
        val value = TextFieldValue("# foo\n# bar", TextRange(0, 11))
        val result = toggleLineComment(value, "#")
        assertEquals("foo\nbar", result.text)
    }

    @Test
    fun toggleCommentLeavesBlankLinesAlone() {
        val value = TextFieldValue("foo\n\nbar", TextRange(0, 8))
        val result = toggleLineComment(value, "//")
        assertEquals("// foo\n\n// bar", result.text)
    }

    // ---- Paste re-indentation ----

    @Test
    fun pasteReindentsBlockOntoCaretIndent() {
        // Caret sits after 4 spaces of indentation; the pasted block is de-dented then re-based.
        val result = reindentPaste("    ", 4, "if (x) {\n    y()\n}")
        assertEquals("    if (x) {\n        y()\n    }", result.text)
    }

    @Test
    fun pasteStripsCommonLeadingIndent() {
        val result = reindentPaste("", 0, "    a\n      b")
        assertEquals("a\n  b", result.text)
    }

    @Test
    fun pasteThroughApplyEditorInputReindents() {
        val old = TextFieldValue("    ", TextRange(4))
        val new = TextFieldValue("    a\n    b", TextRange(11))
        val result = applyEditorInput(old, new, "  ", autoIndent = true, autoCloseBrackets = true)
        // Base indent "    " is re-applied to the second line (its own indent is common-stripped to 0).
        assertEquals("    a\n        b", result.text)
    }

    @Test
    fun pasteKeepsBlankLinesBlank() {
        val result = reindentPaste("  ", 2, "a\n\n  b")
        // Blank middle line keeps its own whitespace (only non-blank lines
        // re-base); later lines pick up the caret indent.
        assertEquals("  a\n  \n    b", result.text)
    }

    @Test
    fun newlineWithNoIndentInsertsNewline() {
        val old = TextFieldValue("foo", TextRange(3))
        val new = TextFieldValue("foo\n", TextRange(4))
        val result = apply(old, new)
        // No indent to carry and no bracket pair: the newline the user typed
        // must survive (previously it was dropped, so Enter did nothing).
        assertEquals("foo\n", result.text)
        assertEquals(4, result.selection.start)
    }

    @Test
    fun newlineWithNoIndentSplitsLineAtCaret() {
        val old = TextFieldValue("foobar", TextRange(3))
        val new = TextFieldValue("foo\nbar", TextRange(4))
        val result = apply(old, new)
        assertEquals("foo\nbar", result.text)
        assertEquals(4, result.selection.start)
    }

    @Test
    fun pasteBlankLinesBecomeEmpty() {
        val result = reindentPaste("", 0, "a\n\nb")
        assertEquals("a\n\nb", result.text)
    }

    // ---- Save transforms ----

    @Test
    fun trimTrailingWhitespaceRemovesLineEndSpaces() {
        assertEquals("a\nb", trimTrailingWhitespace("a  \nb\t"))
    }

    @Test
    fun trimTrailingWhitespaceKeepsInteriorAndNewlines() {
        assertEquals("a b\n\n", trimTrailingWhitespace("a b \n \n"))
    }

    @Test
    fun ensureFinalNewlineAddsWhenMissing() {
        assertEquals("a\n", ensureFinalNewline("a"))
    }

    @Test
    fun ensureFinalNewlineNoOpWhenPresentOrEmpty() {
        assertEquals("a\n", ensureFinalNewline("a\n"))
        assertEquals("", ensureFinalNewline(""))
    }
}
