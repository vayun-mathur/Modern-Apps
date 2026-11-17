package com.vayunmathur.code.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.code.R
import com.vayunmathur.code.platform.CodeActions
import com.vayunmathur.code.platform.CodeUiState
import com.vayunmathur.code.platform.TabUiState
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconCode
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.AppScaffold

/** The tab strip / toolbar / editor column shared by the drawer and Expanded layouts. */
@Composable
internal fun EditorBody(
    state: CodeUiState,
    actions: CodeActions,
    onOpenFolder: () -> Unit,
    onOpenFile: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenGit: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenPreview: () -> Unit,
    onOpenQuickOpen: () -> Unit,
    onOpenPalette: () -> Unit,
    onOpenOutline: () -> Unit,
    onResolveConflicts: () -> Unit,
    onOpenProblems: () -> Unit,
    onToggleFind: () -> Unit,
    onGoToLine: () -> Unit,
    showFind: Boolean,
    onCloseFind: () -> Unit,
    initialFind: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        val tab = state.currentTab
        if (tab == null) {
            EmptyEditorState(onOpenFolder = onOpenFolder, onOpenFile = onOpenFile)
        } else {
            TabStrip(state, actions)
            HorizontalDivider()
            EditorToolbar(
                state = state,
                actions = actions,
                onToggleFind = onToggleFind,
                onGoToLine = onGoToLine,
                onOpenSearch = onOpenSearch,
                onOpenGit = onOpenGit,
                onOpenTerminal = onOpenTerminal,
                onOpenPreview = onOpenPreview,
                onOpenQuickOpen = onOpenQuickOpen,
                onOpenPalette = onOpenPalette,
                onOpenOutline = onOpenOutline,
                onResolveConflicts = onResolveConflicts,
                onOpenProblems = onOpenProblems,
            )
            HorizontalDivider()
            if (tab.changedOnDisk) {
                DiskChangedBanner(
                    onReload = { actions.reloadFromDisk() },
                    onKeep = { actions.dismissDiskChange() },
                )
                HorizontalDivider()
            }
            val secondaryTab = state.secondaryTab
            if (secondaryTab != null) {
                SplitEditors(
                    state = state,
                    actions = actions,
                    primary = tab,
                    secondary = secondaryTab,
                    showFind = showFind,
                    onCloseFind = onCloseFind,
                    initialQuery = initialFind.orEmpty(),
                    modifier = Modifier.weight(1f),
                )
            } else {
                if (state.experimentalEditor) {
                    CodeEditorView(
                        tab = tab,
                        actions = actions,
                        fontSize = state.fontSize,
                        editorTheme = state.editorTheme,
                        modifier = Modifier.weight(1f),
                        tabWidth = state.tabWidth,
                        softWrap = state.softWrap,
                        showWhitespace = state.showWhitespace,
                        showIndentGuides = state.showIndentGuides,
                        showMinimap = state.showMinimap,
                        showFind = showFind,
                        onCloseFind = onCloseFind,
                        initialQuery = initialFind.orEmpty(),
                        completions = state.completions,
                        showCompletions = state.showCompletions,
                        diagnostics = state.diagnostics,
                    )
                } else {
                    CodeEditor(
                        tab = tab,
                        actions = actions,
                        softWrap = state.softWrap,
                        fontSize = state.fontSize,
                        showFind = showFind,
                        onCloseFind = onCloseFind,
                        modifier = Modifier.weight(1f),
                        initialQuery = initialFind.orEmpty(),
                        completions = state.completions,
                        showCompletions = state.showCompletions,
                        editorTheme = state.editorTheme,
                        diagnostics = state.diagnostics,
                    )
                }
            }
        }
    }
}

/** Expanded scaffold: tree beside the editor under one app bar. */
@Composable
internal fun ExpandedEditorScaffold(
    state: CodeUiState,
    actions: CodeActions,
    onOpenFolder: () -> Unit,
    onOpenFile: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenGit: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenPreview: () -> Unit,
    showFind: Boolean,
    onToggleFind: () -> Unit,
    onGoToLine: () -> Unit,
    onOpenQuickOpen: () -> Unit,
    onOpenPalette: () -> Unit,
    onOpenOutline: () -> Unit,
    onResolveConflicts: () -> Unit,
    onOpenProblems: () -> Unit,
    initialFind: String?,
) {
    AppScaffold(
        title = state.currentTab?.name ?: "Code",
        actions = {
            IconButton(onClick = onOpenQuickOpen) { IconSearch() }
            IconButton(onClick = onOpenSettings) { IconSettings() }
        },
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        CodeWideLayout(
            tree = { treeModifier ->
                FileTreePane(
                    state = state,
                    actions = actions,
                    onOpenFolder = onOpenFolder,
                    onOpenFile = onOpenFile,
                    onFileOpened = {},
                )
            },
            editor = { editorModifier ->
                EditorBody(
                    state = state,
                    actions = actions,
                    onOpenFolder = onOpenFolder,
                    onOpenFile = onOpenFile,
                    onOpenSearch = onOpenSearch,
                    onOpenGit = onOpenGit,
                    onOpenTerminal = onOpenTerminal,
                    onOpenPreview = onOpenPreview,
                    onOpenQuickOpen = onOpenQuickOpen,
                    onOpenPalette = onOpenPalette,
                    onOpenOutline = onOpenOutline,
                    onResolveConflicts = onResolveConflicts,
                    onOpenProblems = onOpenProblems,
                    onToggleFind = onToggleFind,
                    onGoToLine = onGoToLine,
                    showFind = showFind,
                    onCloseFind = onToggleFind,
                    initialFind = initialFind,
                    modifier = editorModifier,
                )
            },
            terminal = {},
            showTerminal = false,
            modifier = Modifier.fillMaxSize().padding(padding),
        )
    }
}

/** Shown when nothing is open: prompts the user to open a folder or a single file. */
@Composable
internal fun EmptyEditorState(onOpenFolder: () -> Unit, onOpenFile: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconCode(Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(16.dp))
        Text(stringResource(R.string.no_file_open), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.size(8.dp))
        Text(
            stringResource(R.string.open_a_folder_to_browse_your_project_or),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onOpenFolder) { Text(stringResource(R.string.open_folder)) }
            Button(onClick = onOpenFile) { Text(stringResource(R.string.open_file)) }
        }
    }
}

/**
 * Two editor panes side by side (or stacked when narrow). Each pane edits its own tab and reports
 * focus via [CodeActions.focusPane], so the shared toolbar/find/navigation act on the focused pane.
 * The find bar is shown in whichever pane currently holds focus.
 */
@Composable
internal fun SplitEditors(
    state: CodeUiState,
    actions: CodeActions,
    primary: TabUiState,
    secondary: TabUiState,
    showFind: Boolean,
    onCloseFind: () -> Unit,
    initialQuery: String,
    modifier: Modifier = Modifier,
) {
    val focusSecondary = state.focusedSecondary
    BoxWithConstraints(modifier) {
        val wide = maxWidth >= 640.dp
        val primaryPane: @Composable (Modifier) -> Unit = { paneModifier ->
            Column(paneModifier) {
                PaneHeader(primary.name, onClose = null)
                CodeEditor(
                    tab = primary,
                    actions = actions,
                    softWrap = state.softWrap,
                    fontSize = state.fontSize,
                    showFind = showFind && !focusSecondary,
                    onCloseFind = onCloseFind,
                    modifier = Modifier.weight(1f),
                    initialQuery = initialQuery,
                    completions = state.completions,
                    showCompletions = state.showCompletions && !focusSecondary,
                    editorTheme = state.editorTheme,
                    secondaryPane = false,
                    diagnostics = state.diagnostics,
                )
            }
        }
        val secondaryPane: @Composable (Modifier) -> Unit = { paneModifier ->
            Column(paneModifier) {
                PaneHeader(secondary.name, onClose = { actions.toggleSplit() })
                CodeEditor(
                    tab = secondary,
                    actions = actions,
                    softWrap = state.softWrap,
                    fontSize = state.fontSize,
                    showFind = showFind && focusSecondary,
                    onCloseFind = onCloseFind,
                    modifier = Modifier.weight(1f),
                    editorTheme = state.editorTheme,
                    secondaryPane = true,
                    onValueChangeOverride = actions::onSecondaryEditorChange,
                )
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                primaryPane(Modifier.weight(1f).fillMaxHeight())
                Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outline))
                secondaryPane(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                primaryPane(Modifier.weight(1f).fillMaxWidth())
                HorizontalDivider()
                secondaryPane(Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

/** A thin header above a split pane: the file name, plus an optional close (secondary) button. */
@Composable
internal fun PaneHeader(name: String, onClose: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium,
        )
        if (onClose != null) {
            IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                IconClose(Modifier.size(16.dp))
            }
        }
    }
}
