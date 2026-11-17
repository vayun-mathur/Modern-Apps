package com.vayunmathur.code.platform
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.vayunmathur.code.domain.syntax.EditorThemes
import com.vayunmathur.code.domain.syntax.Language
import com.vayunmathur.code.domain.Completion
import com.vayunmathur.code.domain.Diagnostic
import com.vayunmathur.code.domain.DiagnosticSeverity
import com.vayunmathur.code.domain.Resolution
/**
 * The UI contract between [EditorViewModel] and the editor screens.
 *
 * The screens take a state value plus an actions interface rather than the ViewModel itself,
 * so they can be rendered by a `@Preview` — which is what the store listing images are
 * generated from. It lives in `util` rather than `ui` so the dependency runs one way:
 * `ui` depends on `util`, and the ViewModel implements [CodeActions].
 *
 * Note that the file paths, undo stacks and IO plumbing behind these values stay in `util`:
 * nothing the UI draws needs them, and a preview could not supply them.
 */

/** One open file as the editor draws it. */
data class TabUiState(
    val name: String,
    val value: TextFieldValue = TextFieldValue(),
    val language: Language = Language.PLAINTEXT,
    val isDirty: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    /** True when the file changed on disk while this (dirty) tab held unsaved edits. */
    val changedOnDisk: Boolean = false,
    /** Charset name shown in the status area (e.g. "UTF-8"). */
    val charsetName: String = "UTF-8",
    /** Line-ending name shown in the status area ("LF" or "CRLF"). */
    val lineEndingName: String = "LF",
    /** Fold header lines (0-based) currently collapsed; only the experimental editor draws folds. */
    val foldedHeaders: Set<Int> = emptySet(),
)

/** One row of the flat, lazily-expanded file tree. */
data class TreeRowUiState(
    val name: String,
    val depth: Int = 0,
    val isDirectory: Boolean = false,
    val expanded: Boolean = false,
)

/** One file under the open project, used by quick-open and the recent-files list. */
data class ProjectFileEntry(
    val path: String,
    val name: String,
    /** Path relative to the project root, shown as the secondary line in quick-open. */
    val relativePath: String,
)

/** One hit from a project-wide search: the file path, the 1-based line and a preview of that line. */
data class SearchResult(
    val path: String,
    val name: String,
    val line: Int,
    val preview: String,
)

/** Everything the editor screen draws. */
data class CodeUiState(
    val tabs: List<TabUiState> = emptyList(),
    val currentIndex: Int = -1,
    /** Index of the tab shown in the second split pane, or -1 when the editor is single-pane. */
    val secondaryIndex: Int = -1,
    /** True when the secondary split pane holds focus, so shared actions target it. */
    val focusedSecondary: Boolean = false,
    val softWrap: Boolean = false,
    /** Display name of the opened folder, shown as the file pane's header. */
    val rootName: String? = null,
    /** False until a folder has been picked, which is when the tree replaces its empty state. */
    val folderOpen: Boolean = false,
    val nodes: List<TreeRowUiState> = emptyList(),
    val fontSize: Int = 14,
    val tabWidth: Int = 4,
    val autoIndent: Boolean = true,
    val autoCloseBrackets: Boolean = true,
    val searchResults: List<SearchResult> = emptyList(),
    val isSearching: Boolean = false,
    val completions: List<Completion> = emptyList(),
    val showCompletions: Boolean = false,
    val editorTheme: String = EditorThemes.DEFAULT,
    /** When true, the editing surface uses the experimental virtualized [CodeEditorView]. */
    val experimentalEditor: Boolean = false,
    /** Renderer toggles for the experimental editor. */
    val showWhitespace: Boolean = false,
    val showIndentGuides: Boolean = false,
    val showMinimap: Boolean = false,
    /** All files under the open project, for quick-open (built lazily, cached in the ViewModel). */
    val projectFiles: List<ProjectFileEntry> = emptyList(),
    /** Most-recently-opened files, newest first, shown in quick-open when the query is empty. */
    val recentFiles: List<ProjectFileEntry> = emptyList(),
    /** Diagnostics for the current tab (squiggles, gutter markers, problems panel). */
    val diagnostics: List<Diagnostic> = emptyList(),
) {
    val currentTab: TabUiState? get() = tabs.getOrNull(currentIndex)
    val secondaryTab: TabUiState? get() = tabs.getOrNull(secondaryIndex)

    val errorCount: Int get() = diagnostics.count { it.severity == DiagnosticSeverity.ERROR }
    val warningCount: Int get() = diagnostics.count { it.severity == DiagnosticSeverity.WARNING }

    /** The tab the shared toolbar/find/navigation act on: the focused split pane's tab. */
    val activeTab: TabUiState? get() = if (focusedSecondary) secondaryTab ?: currentTab else currentTab
}

/**
 * Editor callbacks, split into role interfaces so no single interface exceeds the
 * function budget. Every method has a no-op default so a preview can render the screens
 * without supplying behaviour — [CodeActions.Noop] is the whole implementation a preview needs.
 *
 * Tabs and tree rows are addressed by index because that is all the state carries; the
 * ViewModel still owns the [OpenTab]/[TreeNode] behind each one.
 */
interface CodeTabActions {
    fun selectTab(index: Int) {}
    fun closeTab(index: Int) {}

    /** Open a second editor pane (or close it if already open). */
    fun toggleSplit() {}

    /** Report that a split pane gained focus, so shared actions target it ([secondary] = the 2nd pane). */
    fun focusPane(secondary: Boolean) {}

    fun undo() {}
    fun redo() {}
    fun save() {}

    /** Save every dirty, file-backed tab (used by the exit guard and the palette). */
    fun saveAll() {}

    /** Reload the current tab from disk, discarding in-memory edits ("changed on disk" banner). */
    fun reloadFromDisk() {}

    /** Dismiss the "changed on disk" banner, keeping the in-memory edits. */
    fun dismissDiskChange() {}

    fun toggleSoftWrap() {}
    fun insertText(insert: String) {}
    fun onEditorChange(new: TextFieldValue) {}

    /** Apply an edit to the secondary split pane's tab. */
    fun onSecondaryEditorChange(new: TextFieldValue) {}
}

/** File-tree and project-file callbacks. */
interface CodeTreeActions {
    /** Expand/collapse a directory row, or open a file row in a tab. */
    fun toggleNode(index: Int) {}

    /** Create a file under the given parent row (null = tree root) and open it in a tab. */
    fun createFile(parentIndex: Int?, name: String) {}

    /** Create a folder under the given parent row (null = tree root). */
    fun createFolder(parentIndex: Int?, name: String) {}

    /** Rename the file/folder at [index]. */
    fun renameNode(index: Int, newName: String) {}

    /** Delete the file/folder at [index], closing any tabs it (or its descendants) backs. */
    fun deleteNode(index: Int) {}

    /** Open a file by absolute path (from quick-open or the recent-files list). */
    fun openPath(path: String) {}

    /** Rebuild the cached [CodeUiState.projectFiles] list (called when quick-open is shown). */
    fun refreshProjectFiles() {}
}

/** Line editing, find/replace, formatting and folding callbacks. */
interface CodeEditActions {
    /** Toggle the line comment on the line(s) the selection touches (no-op if the language has none). */
    fun toggleComment() {}

    /** Duplicate the line(s) the selection touches. */
    fun duplicateLine() {}

    /** Move the line(s) the selection touches up one line. */
    fun moveLineUp() {}

    /** Move the line(s) the selection touches down one line. */
    fun moveLineDown() {}

    /** Delete the line(s) the selection touches. */
    fun deleteLine() {}

    /** Pretty-print the current file if it is JSON or XML (single undo step). */
    fun formatDocument() {}

    /** Replace the merge-conflict blocks in the current file with the chosen sides (single undo). */
    fun resolveConflicts(resolutions: List<Resolution>) {}

    /** Move the selection without recording an undo step (used by find navigation). */
    fun setSelection(range: TextRange) {}

    /** Move the caret to the start of [line] (1-based). */
    fun goToLine(line: Int) {}

    /** Toggle the fold at [headerLine] (0-based) in the active tab. */
    fun toggleFold(headerLine: Int) {}

    /** Collapse every foldable region in the active tab. */
    fun foldAllInTab() {}

    /** Expand every folded region in the active tab. */
    fun unfoldAll() {}

    fun replaceRange(range: IntRange, replacement: String) {}
    fun replaceAll(matches: List<IntRange>, replacement: String) {}

    /** Replace the match at [range] using regex substitution (supports `$1` group refs). */
    fun replaceMatchRegex(range: IntRange, pattern: String, replacement: String, caseSensitive: Boolean) {}

    /** Replace every regex match in the current file (supports `$1` group refs). */
    fun replaceAllRegex(pattern: String, replacement: String, caseSensitive: Boolean) {}
}

/** Search, completion and quick-open callbacks. */
interface CodeSearchActions {
    /** Recompute completions for the current caret position. */
    fun requestCompletions() {}

    /** Accept a completion, replacing the current word (or expanding a snippet). */
    fun acceptCompletion(item: Completion) {}

    /** Hide the completion popup. */
    fun dismissCompletions() {}

    /** Search every text file under the open folder for [query]. */
    fun searchProject(query: String, caseSensitive: Boolean, useRegex: Boolean) {}

    /** Open the file for a search result and jump to its line. */
    fun openSearchResult(result: SearchResult) {}
}

/** Editor setting callbacks. */
interface CodeSettingsActions {
    fun setFontSize(size: Int) {}
    fun setTabWidth(width: Int) {}
    fun setThemeMode(mode: String) {}
    fun setAutoIndent(enabled: Boolean) {}
    fun setAutoCloseBrackets(enabled: Boolean) {}
}

interface CodeActions :
    CodeTabActions,
    CodeTreeActions,
    CodeEditActions,
    CodeSearchActions,
    CodeSettingsActions {
    companion object {
        val Noop: CodeActions = object : CodeActions {}
    }
}
