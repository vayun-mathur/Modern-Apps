package com.vayunmathur.code.platform
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.vayunmathur.code.domain.Completion
import com.vayunmathur.code.domain.computeCompletions
import com.vayunmathur.code.domain.currentWordPrefix

/** [CodeSearchActions] implementation, delegating lists and jobs to the ViewModel. */
internal class EditorSearchActions(private val vm: EditorViewModel) : CodeSearchActions {

    override fun requestCompletions() = vm.updateCompletions()

    override fun acceptCompletion(item: Completion) {
        val tab = vm.currentTab ?: return
        val v = tab.value
        val caret = v.selection.start
        val prefix = currentWordPrefix(v.text, caret)
        val start = caret - prefix.length
        val newText = v.text.substring(0, start) + item.insertText + v.text.substring(caret)
        val newCaret = (start + item.caretOffset).coerceIn(0, newText.length)
        tab.pushUndo(v)
        tab.value = TextFieldValue(newText, TextRange(newCaret))
        vm.actions.dismissCompletions()
        if (vm.autoSave) vm.scheduleAutoSave()
        vm.scheduleDiagnostics()
    }

    override fun dismissCompletions() {
        vm.completionsJob?.cancel()
        if (vm.completions.isNotEmpty()) vm.completions.clear()
        vm.showCompletions = false
    }

    override fun searchProject(query: String, caseSensitive: Boolean, useRegex: Boolean) =
        vm.searchProjectImpl(query, caseSensitive, useRegex)

    override fun openSearchResult(result: SearchResult) = vm.openSearchResultImpl(result)
}

/** Completion recompute with debounce; extracted so the ViewModel stays under budget. */
internal fun EditorViewModel.updateCompletions() {
    completionsJob?.cancel()
    val tab = currentTab
    if (tab == null || !tab.value.selection.collapsed) {
        actions.dismissCompletions()
        return
    }
    val prefix = currentWordPrefix(tab.value.text, tab.value.selection.start)
    if (prefix.length < MIN_COMPLETION_PREFIX) {
        actions.dismissCompletions()
        return
    }
    val buffers = tabs.map { it.value.text }.filter { it.length <= MAX_COMPLETION_BUFFER_CHARS }
    val language = tab.language
    val snippets = userSnippets.toList()
    completionsJob = viewModelScope.launch {
        delay(COMPLETIONS_DELAY_MS)
        val list = withContext(Dispatchers.Default) {
            computeCompletions(prefix, language, buffers, MAX_COMPLETIONS, snippets)
        }
        completions.clear()
        completions.addAll(list)
        showCompletions = list.isNotEmpty()
    }
}

internal const val COMPLETIONS_DELAY_MS = 150L
internal const val MIN_COMPLETION_PREFIX = 1
internal const val MAX_COMPLETIONS = 50

// Buffers larger than this are left out of the identifier scan. A multi-megabyte file has
// no useful completions in it anyway, and scanning it on every keystroke burns a core.
internal const val MAX_COMPLETION_BUFFER_CHARS = 1_000_000
