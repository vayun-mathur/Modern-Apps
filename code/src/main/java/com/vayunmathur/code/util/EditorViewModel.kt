package com.vayunmathur.code.util

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.code.syntax.EditorThemes
import com.vayunmathur.code.syntax.Language
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.Charset

/**
 * Activity-scoped state for the editor: the open folder tree, the set of open tabs and the
 * editor preferences. An [AndroidViewModel] (obtained via `by viewModels()`) so it can reach
 * the ContentResolver and DataStore through the application context.
 *
 * It implements [CodeActions] and projects itself into a [CodeUiState] so the screens can be
 * rendered without it; reading [uiState] inside a composable subscribes to the same
 * `mutableStateOf`/`mutableStateListOf` members the screens used to read directly.
 *
 * The file backend is real [File] paths (the app holds `MANAGE_EXTERNAL_STORAGE`), so folder,
 * tree and file operations all go through [FileFiles].
 */
class EditorViewModel(application: Application) : AndroidViewModel(application) {

    internal val context get() = getApplication<Application>()
    internal val prefs = EditorPrefs(context)

    /** Coroutine scope for delegate action classes (which cannot see the protected [viewModelScope]). */
    internal val scope: CoroutineScope get() = viewModelScope

    /** The composed editor actions, split into role delegates to stay under the function budget. */
    val actions: CodeActions by lazy {
        CodeActionsFacade(
            EditorTabActions(this),
            EditorTreeActions(this),
            EditorEditActions(this),
            EditorSearchActions(this),
            EditorSettingsActions(this),
        )
    }

    // ---- File tree ----
    var rootDir by mutableStateOf<File?>(null)
        internal set
    var rootName by mutableStateOf<String?>(null)
        internal set
    val nodes = mutableStateListOf<TreeNode>()

    // ---- Tabs ----
    val tabs = mutableStateListOf<OpenTab>()
    var currentIndex by mutableStateOf(-1)
        internal set
    val currentTab: OpenTab? get() = tabs.getOrNull(currentIndex)

    /** When set, a second pane shows this tab beside the current one (split view). */
    var secondaryIndex by mutableStateOf<Int?>(null)
        internal set

    /** True when the secondary split pane holds focus, so shared actions target it instead. */
    var focusedSecondary by mutableStateOf(false)
        internal set

    /** The tab shared toolbar/find/navigation actions target: the focused pane's tab. */
    internal val activeTab: OpenTab?
        get() = if (focusedSecondary) secondaryIndex?.let { tabs.getOrNull(it) } else currentTab

    // ---- Preferences ----
    var softWrap by mutableStateOf(false)
        internal set

    // These five are read as `fontSize`/`tabWidth`/... but written through the CodeActions
    // `setFontSize`/`setTabWidth`/... methods. Backing them by a private MutableState (rather than
    // a `var ... private set`) avoids a JVM signature clash between the generated property setter
    // and the same-named interface method.
    internal val fontSizeState = mutableStateOf(EditorPrefs.DEFAULT_FONT_SIZE)
    val fontSize: Int get() = fontSizeState.value
    internal val tabWidthState = mutableStateOf(EditorPrefs.DEFAULT_TAB_WIDTH)
    val tabWidth: Int get() = tabWidthState.value
    internal val themeModeState = mutableStateOf(EditorPrefs.THEME_SYSTEM)
    val themeMode: String get() = themeModeState.value
    internal val autoIndentState = mutableStateOf(true)
    val autoIndent: Boolean get() = autoIndentState.value
    internal val autoCloseBracketsState = mutableStateOf(true)
    val autoCloseBrackets: Boolean get() = autoCloseBracketsState.value
    internal val autoSaveState = mutableStateOf(false)
    val autoSave: Boolean get() = autoSaveState.value
    internal val trimTrailingOnSaveState = mutableStateOf(false)
    val trimTrailingOnSave: Boolean get() = trimTrailingOnSaveState.value
    internal val finalNewlineOnSaveState = mutableStateOf(false)
    val finalNewlineOnSave: Boolean get() = finalNewlineOnSaveState.value
    internal val editorThemeState = mutableStateOf(EditorThemes.DEFAULT)
    val editorTheme: String get() = editorThemeState.value
    internal val experimentalEditorState = mutableStateOf(false)
    val experimentalEditor: Boolean get() = experimentalEditorState.value
    internal val showWhitespaceState = mutableStateOf(false)
    val showWhitespace: Boolean get() = showWhitespaceState.value
    internal val showIndentGuidesState = mutableStateOf(false)
    val showIndentGuides: Boolean get() = showIndentGuidesState.value
    internal val showMinimapState = mutableStateOf(false)
    val showMinimap: Boolean get() = showMinimapState.value
    internal var autoSaveJob: Job? = null

    // ---- Project search ----
    val searchResults = mutableStateListOf<SearchResult>()
    var isSearching by mutableStateOf(false)
        internal set
    internal var searchJob: Job? = null

    // ---- Autocomplete ----
    val completions = mutableStateListOf<Completion>()
    var showCompletions by mutableStateOf(false)
        internal set
    internal var completionsJob: Job? = null

    // ---- Diagnostics ----
    val diagnostics = mutableStateListOf<Diagnostic>()
    internal var diagnosticsJob: Job? = null

    // ---- User snippets ----
    val userSnippets = mutableStateListOf<UserSnippet>()

    /** Persisted per-file collapsed fold headers (absolute path -> header lines). */
    internal val foldStateByPath = HashMap<String, Set<Int>>()

    // ---- Git ----
    var gitIsRepo by mutableStateOf(false)
        internal set
    var gitStatus by mutableStateOf<GitStatus?>(null)
        internal set
    val gitLog = mutableStateListOf<GitCommitInfo>()
    val gitBranches = mutableStateListOf<String>()
    var gitBusy by mutableStateOf(false)
        internal set
    var gitMessage by mutableStateOf<String?>(null)
        internal set
    var gitDiff by mutableStateOf<String?>(null)
        internal set
    var gitDiffRows by mutableStateOf<List<DiffRow>?>(null)
        internal set

    internal val gitUsernameState = mutableStateOf("")
    val gitUsername: String get() = gitUsernameState.value
    internal val gitTokenState = mutableStateOf("")
    val gitToken: String get() = gitTokenState.value
    internal val gitAuthorNameState = mutableStateOf("")
    val gitAuthorName: String get() = gitAuthorNameState.value
    internal val gitAuthorEmailState = mutableStateOf("")
    val gitAuthorEmail: String get() = gitAuthorEmailState.value

    // ---- Terminal ----
    val terminalLines = mutableStateListOf<String>()
    var terminalRunning by mutableStateOf(false)
        internal set
    internal var terminal: TerminalSession? = null

    // ---- Quick-open ----
    val projectFiles = mutableStateListOf<ProjectFileEntry>()
    internal val recentPaths = mutableStateListOf<String>()
    internal var projectFilesJob: Job? = null

    /** Snapshot of everything the screens draw; rebuilt on every read, as Compose expects. */
    val uiState: CodeUiState
        get() = CodeUiState(
            tabs = tabs.map {
                TabUiState(
                    name = it.name,
                    value = it.value,
                    language = it.language,
                    isDirty = it.isDirty,
                    canUndo = it.canUndo,
                    canRedo = it.canRedo,
                    changedOnDisk = it.changedOnDisk,
                    charsetName = it.charset.name(),
                    lineEndingName = it.lineEnding.name,
                    foldedHeaders = it.foldedHeaders,
                )
            },
            currentIndex = currentIndex,
            secondaryIndex = secondaryIndex ?: -1,
            focusedSecondary = focusedSecondary,
            softWrap = softWrap,
            rootName = rootName,
            folderOpen = rootDir != null,
            nodes = nodes.map {
                TreeRowUiState(
                    name = it.entry.name,
                    depth = it.depth,
                    isDirectory = it.entry.isDirectory,
                    expanded = it.expanded,
                )
            },
            fontSize = fontSize,
            tabWidth = tabWidth,
            autoIndent = autoIndent,
            autoCloseBrackets = autoCloseBrackets,
            searchResults = searchResults.toList(),
            isSearching = isSearching,
            completions = completions.toList(),
            showCompletions = showCompletions,
            editorTheme = editorTheme,
            experimentalEditor = experimentalEditor,
            showWhitespace = showWhitespace,
            showIndentGuides = showIndentGuides,
            showMinimap = showMinimap,
            projectFiles = projectFiles.toList(),
            recentFiles = recentPaths.map { toProjectEntry(File(it)) },
            diagnostics = diagnostics.toList(),
        )

    init {
        viewModelScope.launch { softWrap = prefs.softWrap.first() }
        viewModelScope.launch { fontSizeState.value = prefs.fontSize.first() }
        viewModelScope.launch { tabWidthState.value = prefs.tabWidth.first() }
        viewModelScope.launch { themeModeState.value = prefs.themeMode.first() }
        viewModelScope.launch { autoIndentState.value = prefs.autoIndent.first() }
        viewModelScope.launch { autoCloseBracketsState.value = prefs.autoCloseBrackets.first() }
        viewModelScope.launch { autoSaveState.value = prefs.autoSave.first() }
        viewModelScope.launch { trimTrailingOnSaveState.value = prefs.trimTrailingOnSave.first() }
        viewModelScope.launch { finalNewlineOnSaveState.value = prefs.finalNewlineOnSave.first() }
        viewModelScope.launch { editorThemeState.value = prefs.editorTheme.first() }
        viewModelScope.launch { experimentalEditorState.value = prefs.experimentalEditor.first() }
        viewModelScope.launch { showWhitespaceState.value = prefs.showWhitespace.first() }
        viewModelScope.launch { showIndentGuidesState.value = prefs.showIndentGuides.first() }
        viewModelScope.launch { showMinimapState.value = prefs.showMinimap.first() }
        viewModelScope.launch { gitUsernameState.value = prefs.gitUsername.first() }
        viewModelScope.launch { gitTokenState.value = prefs.gitToken.first() }
        viewModelScope.launch { gitAuthorNameState.value = prefs.gitAuthorName.first() }
        viewModelScope.launch { gitAuthorEmailState.value = prefs.gitAuthorEmail.first() }
        viewModelScope.launch { recentPaths.addAll(prefs.recentFiles.first()) }
        viewModelScope.launch { userSnippets.addAll(prefs.userSnippets.first()) }
        viewModelScope.launch {
            prefs.foldState.first().forEach { (path, lines) -> foldStateByPath[path] = lines.toSet() }
            // Apply to any tabs already restored before the fold state finished loading.
            for (tab in tabs) {
                val path = tab.file?.absolutePath ?: continue
                foldStateByPath[path]?.let { tab.foldedHeaders = it }
            }
        }
        viewModelScope.launch {
            val stored = prefs.folderPath.first() ?: return@launch
            val dir = File(stored)
            if (dir.isDirectory) {
                runCatching { loadFolder(dir) }.onFailure { prefs.clearFolderPath() }
            } else {
                prefs.clearFolderPath()
            }
        }
        viewModelScope.launch { restoreSession() }
    }

    /** Reopens the tabs from the previous session (files that still exist), off the main thread. */
    private suspend fun restoreSession() {
        val paths = prefs.sessionPaths.first()
        if (paths.isEmpty()) return
        val current = prefs.sessionCurrent.first()
        for (path in paths) restoreTab(path)
        currentIndex = tabs.indexOfFirst { it.file?.absolutePath == current }
            .takeIf { it >= 0 } ?: if (tabs.isEmpty()) -1 else 0
        scheduleDiagnostics()
    }

    private suspend fun restoreTab(path: String) {
        if (tabs.any { it.key == path }) return
        val file = File(path)
        if (!file.isFile) return
        makeFileTab(file)?.let { tabs.add(it) }
    }

    /** Persists the current set of file-backed tabs and the foreground tab for session restore. */
    internal fun saveSession() {
        val paths = tabs.mapNotNull { it.file?.absolutePath }
        val current = currentTab?.file?.absolutePath
        viewModelScope.launch { prefs.setSession(paths, current) }
    }

    // ---- Folder handling ----
    // toggleNode implementation lives in EditorTreeActions; conformance via [actions].

    // ---- File operations ----
    // Helpers live in EditorFileTreeOps.kt; these overrides keep CodeActions conformance.

    /**
     * Re-lists the children of a folder and rebuilds that subtree in [nodes]. Expansion state
     * and already-loaded descendant rows of immediate child folders are preserved by path.
     * [parentIndex] null refreshes the tree root.
     */
    // Implemented in EditorFileTreeOps.kt as an internal extension.

    // ---- File operations (delegated) ----

    // ---- Tab handling (delegated) ----

    /** Closes any open tab whose file is [file] or lives beneath it (for a deleted directory). */
    // Implemented in EditorFileTreeOps.kt as an internal extension.

    // ---- Tab handling ----
    // selectTab/closeTab via EditorTabActions; open helpers live in EditorFileTreeOps.kt.

    // ---- Editing (via EditorTabActions) ----

    /** Applies smart input to [tab] as a single undo step; only the primary pane drives completions. */
    internal fun editTab(tab: OpenTab, new: TextFieldValue, isPrimary: Boolean) {
        val indentUnit = " ".repeat(tabWidth)
        val processed = applyEditorInput(tab.value, new, indentUnit, autoIndent, autoCloseBrackets)
        if (processed.text != tab.value.text) tab.pushUndo(tab.value)
        tab.value = processed
        if (autoSaveState.value) scheduleAutoSave()
        if (isPrimary) actions.requestCompletions()
        scheduleDiagnostics()
    }

    // ---- Autocomplete (via EditorSearchActions) ----

    // ---- User snippets ----

    fun addSnippet(snippet: UserSnippet) {
        userSnippets.add(snippet)
        persistSnippets()
    }

    fun updateSnippet(index: Int, snippet: UserSnippet) {
        if (index in userSnippets.indices) {
            userSnippets[index] = snippet
            persistSnippets()
        }
    }

    fun deleteSnippet(index: Int) {
        if (index in userSnippets.indices) {
            userSnippets.removeAt(index)
            persistSnippets()
        }
    }

    private fun persistSnippets() {
        viewModelScope.launch { prefs.setUserSnippets(userSnippets.toList()) }
    }

    // ---- Git ----
    // Operations live in EditorGitOps.kt as extensions.

    // ---- Terminal ----
    // Operations live in EditorTerminalOps.kt as extensions.

    override fun onCleared() {
        super.onCleared()
        terminal?.close()
    }

    /** Debounced off-main recompute of diagnostics for the current tab. */
    internal fun scheduleDiagnostics() {
        diagnosticsJob?.cancel()
        val tab = currentTab
        if (tab == null) {
            diagnostics.clear()
            return
        }
        val text = tab.value.text
        val language = tab.language
        diagnosticsJob = viewModelScope.launch {
            delay(DIAGNOSTICS_DELAY_MS)
            val result = withContext(Dispatchers.Default) { computeDiagnostics(text, language) }
            diagnostics.clear()
            diagnostics.addAll(result)
        }
    }

    /** Applies a pure whole-line edit as a single undo step. */
    internal fun applyLineEdit(transform: (TextFieldValue) -> TextFieldValue) {
        val tab = activeTab ?: return
        val next = transform(tab.value)
        if (next.text == tab.value.text && next.selection == tab.value.selection) return
        tab.pushUndo(tab.value)
        tab.value = next
        if (autoSaveState.value) scheduleAutoSave()
        scheduleDiagnostics()
    }

    // ---- Line/format/fold/undo/save (via EditorEditActions + EditorTabActions) ----

    /** Records [tab]'s fold headers in the per-path store and persists it (file-backed tabs only). */
    internal fun persistFoldState(tab: OpenTab) {
        val path = tab.file?.absolutePath ?: return
        if (tab.foldedHeaders.isEmpty()) foldStateByPath.remove(path)
        else foldStateByPath[path] = tab.foldedHeaders
        viewModelScope.launch { prefs.setFoldState(foldStateByPath.mapValues { it.value.toList() }) }
    }

    // Save/reload/external-change handling lives in EditorSaveOps.kt as extensions.

    // ---- Settings (via EditorSettingsActions) ----

    fun setAutoSave(enabled: Boolean) {
        autoSaveState.value = enabled
        viewModelScope.launch { prefs.setAutoSave(enabled) }
        if (enabled) scheduleAutoSave()
    }

    fun setTrimTrailingOnSave(enabled: Boolean) {
        trimTrailingOnSaveState.value = enabled
        viewModelScope.launch { prefs.setTrimTrailingOnSave(enabled) }
    }

    fun setFinalNewlineOnSave(enabled: Boolean) {
        finalNewlineOnSaveState.value = enabled
        viewModelScope.launch { prefs.setFinalNewlineOnSave(enabled) }
    }

    fun setEditorTheme(theme: String) {
        editorThemeState.value = theme
        viewModelScope.launch { prefs.setEditorTheme(theme) }
    }

    fun setExperimentalEditor(enabled: Boolean) {
        experimentalEditorState.value = enabled
        viewModelScope.launch { prefs.setExperimentalEditor(enabled) }
    }

    fun setShowWhitespace(enabled: Boolean) {
        showWhitespaceState.value = enabled
        viewModelScope.launch { prefs.setShowWhitespace(enabled) }
    }

    fun setShowIndentGuides(enabled: Boolean) {
        showIndentGuidesState.value = enabled
        viewModelScope.launch { prefs.setShowIndentGuides(enabled) }
    }

    fun setShowMinimap(enabled: Boolean) {
        showMinimapState.value = enabled
        viewModelScope.launch { prefs.setShowMinimap(enabled) }
    }

    // ---- Find & replace (via EditorEditActions) ----

    /** Commits a find/replace edit to [tab] as one undo step, bypassing smart input. */
    internal fun commitEdit(tab: OpenTab, new: TextFieldValue) {
        if (new.text != tab.value.text) tab.pushUndo(tab.value)
        tab.value = new
        if (autoSaveState.value) scheduleAutoSave()
        scheduleDiagnostics()
    }

    internal fun buildRegex(pattern: String, caseSensitive: Boolean): Regex =
        Regex(pattern, if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE))

    // ---- Project search / quick-open (via EditorSearchActions + EditorTreeActions) ----

    private companion object {
        internal const val DIAGNOSTICS_DELAY_MS = 400L
    }
}
