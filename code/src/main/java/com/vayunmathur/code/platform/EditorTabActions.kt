package com.vayunmathur.code.platform
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

/** [CodeTabActions] implementation, delegating tab storage to the ViewModel. */
internal class EditorTabActions(private val vm: EditorViewModel) : CodeTabActions {

    override fun selectTab(index: Int) {
        if (index !in vm.tabs.indices) return
        vm.currentIndex = index
        // Never show the same tab in both panes.
        if (vm.secondaryIndex == index) vm.secondaryIndex = null
        vm.focusedSecondary = false
        vm.actions.dismissCompletions()
        vm.saveSession()
        vm.scheduleDiagnostics()
    }

    override fun closeTab(index: Int) {
        if (index !in vm.tabs.indices) return
        val removingCurrent = index == vm.currentIndex
        vm.tabs.removeAt(index)
        vm.currentIndex = when {
            vm.tabs.isEmpty() -> -1
            index < vm.currentIndex -> vm.currentIndex - 1
            removingCurrent -> index.coerceAtMost(vm.tabs.lastIndex)
            else -> vm.currentIndex
        }
        vm.secondaryIndex = vm.secondaryIndex?.let { s ->
            when {
                s == index -> null
                s > index -> s - 1
                else -> s
            }
        }?.takeIf { it in vm.tabs.indices && it != vm.currentIndex }
        if (vm.secondaryIndex == null) vm.focusedSecondary = false
        vm.saveSession()
    }

    override fun undo() {
        vm.activeTab?.undo()
    }

    override fun redo() {
        vm.activeTab?.redo()
    }

    override fun save() {
        val tab = vm.activeTab ?: return
        vm.saveTab(tab)
    }

    override fun saveAll() {
        for (tab in vm.tabs) if (tab.file != null && tab.isDirty) vm.saveTab(tab)
    }

    override fun reloadFromDisk() {
        val tab = vm.currentTab ?: return
        vm.scope.launch { vm.applyReload(tab) }
    }

    override fun dismissDiskChange() {
        val tab = vm.currentTab ?: return
        vm.scope.launch {
            val file = tab.file ?: return@launch
            val (modified, length) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                file.lastModified() to file.length()
            }
            tab.diskModified = modified
            tab.diskLength = length
            tab.changedOnDisk = false
        }
    }

    override fun toggleSoftWrap() {
        vm.softWrap = !vm.softWrap
        vm.scope.launch { vm.prefs.setSoftWrap(vm.softWrap) }
    }

    override fun insertText(insert: String) {
        val tab = vm.activeTab ?: return
        val v = tab.value
        val start = v.selection.min
        val end = v.selection.max
        val newText = v.text.substring(0, start) + insert + v.text.substring(end)
        vm.editTab(tab, TextFieldValue(newText, TextRange(start + insert.length)), isPrimary = tab === vm.currentTab)
    }

    override fun onEditorChange(new: TextFieldValue) {
        vm.editTab(vm.currentTab ?: return, new, isPrimary = true)
    }

    override fun onSecondaryEditorChange(new: TextFieldValue) {
        val tab = vm.secondaryIndex?.let { vm.tabs.getOrNull(it) } ?: return
        vm.editTab(tab, new, isPrimary = false)
    }

    /** Opens/closes the second editor pane, choosing an adjacent tab as the secondary. */
    override fun toggleSplit() {
        if (vm.secondaryIndex != null) {
            vm.secondaryIndex = null
            vm.focusedSecondary = false
            return
        }
        if (vm.tabs.size < 2 || vm.currentIndex < 0) return
        val other = (vm.currentIndex + 1).takeIf { it in vm.tabs.indices } ?: (vm.currentIndex - 1)
        vm.secondaryIndex = other.takeIf { it in vm.tabs.indices && it != vm.currentIndex }
    }

    /** Records which split pane holds focus, so shared toolbar/find/nav actions target it. */
    override fun focusPane(secondary: Boolean) {
        vm.focusedSecondary = secondary && vm.secondaryIndex != null
    }
}
