package com.vayunmathur.code.platform
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.viewModelScope
import com.vayunmathur.code.domain.syntax.Language
import kotlinx.coroutines.launch
import com.vayunmathur.code.domain.Resolution
import com.vayunmathur.code.domain.applyResolutions
import com.vayunmathur.code.domain.computeFoldRegions
import com.vayunmathur.code.domain.formatJson
import com.vayunmathur.code.domain.formatXml

/** [CodeEditActions] implementation, delegating buffer storage to the ViewModel. */
internal class EditorEditActions(private val vm: EditorViewModel) : CodeEditActions {

    override fun toggleComment() {
        val prefix = vm.activeTab?.language?.lineCommentPrefix ?: return
        vm.applyLineEdit { toggleLineComment(it, prefix) }
    }

    override fun duplicateLine() = vm.applyLineEdit(::duplicateLine)

    override fun moveLineUp() = vm.applyLineEdit(::moveLineUp)

    override fun moveLineDown() = vm.applyLineEdit(::moveLineDown)

    override fun deleteLine() = vm.applyLineEdit(::deleteLine)

    override fun formatDocument() {
        val tab = vm.activeTab ?: return
        val formatted = when (tab.language) {
            Language.JSON -> formatJson(tab.value.text)
            Language.XML -> formatXml(tab.value.text)
            else -> null
        } ?: return
        if (formatted == tab.value.text) return
        tab.pushUndo(tab.value)
        tab.value = TextFieldValue(formatted, TextRange(formatted.length))
        if (vm.autoSave) vm.scheduleAutoSave()
        vm.scheduleDiagnostics()
    }

    override fun resolveConflicts(resolutions: List<Resolution>) {
        val tab = vm.activeTab ?: return
        val resolved = applyResolutions(tab.value.text, resolutions)
        if (resolved == tab.value.text) return
        tab.pushUndo(tab.value)
        tab.value = TextFieldValue(resolved, TextRange(resolved.length))
        if (vm.autoSave) vm.scheduleAutoSave()
        vm.scheduleDiagnostics()
    }

    override fun setSelection(range: TextRange) {
        val tab = vm.activeTab ?: return
        tab.value = tab.value.copy(selection = range)
    }

    /** Moves the caret to the start of [line] (1-based), without recording an undo step. */
    override fun goToLine(line: Int) {
        val tab = vm.activeTab ?: return
        val offset = lineStartOffset(tab.value.text, line)
        setSelection(TextRange(offset))
    }

    override fun toggleFold(headerLine: Int) {
        val tab = vm.activeTab ?: return
        tab.foldedHeaders =
            if (headerLine in tab.foldedHeaders) tab.foldedHeaders - headerLine
            else tab.foldedHeaders + headerLine
        vm.persistFoldState(tab)
    }

    override fun foldAllInTab() {
        val tab = vm.activeTab ?: return
        tab.foldedHeaders = computeFoldRegions(tab.value.text).map { it.startLine }.toSet()
        vm.persistFoldState(tab)
    }

    override fun unfoldAll() {
        val tab = vm.activeTab ?: return
        tab.foldedHeaders = emptySet()
        vm.persistFoldState(tab)
    }

    override fun replaceRange(range: IntRange, replacement: String) {
        val tab = vm.activeTab ?: return
        val text = tab.value.text
        if (range.first < 0 || range.last + 1 > text.length) return
        val newText = text.substring(0, range.first) + replacement + text.substring(range.last + 1)
        vm.commitEdit(tab, TextFieldValue(newText, TextRange(range.first + replacement.length)))
    }

    override fun replaceAll(matches: List<IntRange>, replacement: String) {
        val tab = vm.activeTab ?: return
        if (matches.isEmpty()) return
        val text = tab.value.text
        val sb = StringBuilder(text.length)
        var last = 0
        for (m in matches.sortedBy { it.first }) {
            if (m.first < last) continue
            sb.append(text, last, m.first)
            sb.append(replacement)
            last = m.last + 1
        }
        sb.append(text, last, text.length)
        vm.commitEdit(tab, TextFieldValue(sb.toString(), TextRange(sb.length)))
    }

    override fun replaceMatchRegex(range: IntRange, pattern: String, replacement: String, caseSensitive: Boolean) {
        val tab = vm.activeTab ?: return
        val text = tab.value.text
        if (range.first < 0 || range.last + 1 > text.length) return
        val regex = runCatching { vm.buildRegex(pattern, caseSensitive) }.getOrNull() ?: return
        val sub = text.substring(range.first, range.last + 1)
        val replaced = runCatching { regex.replace(sub, replacement) }.getOrNull() ?: return
        replaceRange(range, replaced)
    }

    override fun replaceAllRegex(pattern: String, replacement: String, caseSensitive: Boolean) {
        val tab = vm.activeTab ?: return
        val regex = runCatching { vm.buildRegex(pattern, caseSensitive) }.getOrNull() ?: return
        val text = tab.value.text
        val newText = runCatching { regex.replace(text, replacement) }.getOrNull() ?: return
        if (newText == text) return
        vm.commitEdit(tab, TextFieldValue(newText, TextRange(newText.length)))
    }
}
