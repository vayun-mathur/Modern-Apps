package com.vayunmathur.code.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import com.vayunmathur.code.R
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.code.platform.CodeActions
import com.vayunmathur.code.platform.CodeUiState
import com.vayunmathur.code.domain.extractSymbols
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.DrawerValue
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconMenu
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.ModalDrawerSheet
import com.vayunmathur.library.ui.ModalNavigationDrawer
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.isExpandedWidth
import com.vayunmathur.library.ui.rememberDrawerState
import kotlinx.coroutines.launch

/**
 * Top-level editor scaffold: a navigation drawer holding the [FileTreePane], a top bar to
 * open it, then a tab strip, toolbar, optional find bar and the [CodeEditor] itself. Opening a
 * folder navigates to the in-app folder browser; single files still use the system file picker.
 *
 * No dependency on the ViewModel, so it can be rendered from a `@Preview` — see
 * `src/screenshotTest`, which is where the store listing images come from.
 */
@Composable
fun EditorScreen(
    state: CodeUiState,
    actions: CodeActions,
    onOpenFolder: () -> Unit = {},
    onOpenFile: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenGit: () -> Unit = {},
    onOpenTerminal: () -> Unit = {},
    onOpenPreview: () -> Unit = {},
    onExitApp: () -> Unit = {},
    /**
     * Seeds for the screen's own UI-only state (is the drawer showing, is the find bar open
     * and on what query). The app always takes the defaults; previews set them so a given
     * screen can be captured without driving the UI to get there.
     */
    initialDrawerOpen: Boolean = false,
    initialFind: String? = null,
) {
    val drawerState = rememberDrawerState(
        if (initialDrawerOpen) DrawerValue.Open else DrawerValue.Closed
    )
    val scope = rememberCoroutineScope()
    var showFind by remember { mutableStateOf(initialFind != null) }
    var showGoToLine by remember { mutableStateOf(false) }
    var showQuickOpen by remember { mutableStateOf(false) }
    var showPalette by remember { mutableStateOf(false) }
    var showOutline by remember { mutableStateOf(false) }
    var showMergeResolver by remember { mutableStateOf(false) }
    var showProblems by remember { mutableStateOf(false) }
    var showExitGuard by remember { mutableStateOf(false) }
    val anyDirty = state.tabs.any { it.isDirty }

    // Guard back/close when there are unsaved changes; offer Save all / Discard / Cancel.
    BackHandler(enabled = anyDirty) { showExitGuard = true }

    // Expanded windows keep the tree beside the editor instead of in a drawer.
    val expanded = isExpandedWidth()

    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed) {
                    return@onPreviewKeyEvent false
                }
                when (event.key) {
                    Key.P -> {
                        if (event.isShiftPressed) {
                            showPalette = true
                        } else {
                            actions.refreshProjectFiles()
                            showQuickOpen = true
                        }
                        true
                    }
                    Key.O -> {
                        if (event.isShiftPressed) {
                            showOutline = true
                            true
                        } else {
                            false
                        }
                    }
                    else -> false
                }
            },
    ) {
    if (expanded) {
        ExpandedEditorScaffold(
            state = state,
            actions = actions,
            onOpenFolder = onOpenFolder,
            onOpenFile = onOpenFile,
            onOpenSettings = onOpenSettings,
            onOpenSearch = onOpenSearch,
            onOpenGit = onOpenGit,
            onOpenTerminal = onOpenTerminal,
            onOpenPreview = onOpenPreview,
            showFind = showFind,
            onToggleFind = { showFind = !showFind },
            onGoToLine = { showGoToLine = true },
            onOpenQuickOpen = {
                actions.refreshProjectFiles()
                showQuickOpen = true
            },
            onOpenPalette = { showPalette = true },
            onOpenOutline = { showOutline = true },
            onResolveConflicts = { showMergeResolver = true },
            onOpenProblems = { showProblems = true },
            initialFind = initialFind,
        )
    } else {
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                FileTreePane(
                    state = state,
                    actions = actions,
                    onOpenFolder = onOpenFolder,
                    onOpenFile = onOpenFile,
                    onFileOpened = { scope.launch { drawerState.close() } },
                )
            }
        },
    ) {
        AppScaffold(
            title = state.currentTab?.name ?: "Code",
            navigationIcon = {
                IconButton(onClick = { scope.launch { drawerState.open() } }) { IconMenu() }
            },
            actions = {
                IconButton(onClick = {
                    actions.refreshProjectFiles()
                    showQuickOpen = true
                }) { IconSearch() }
                IconButton(onClick = onOpenSettings) { IconSettings() }
            },
        ) { padding ->
            EditorBody(
                state = state,
                actions = actions,
                onOpenFolder = onOpenFolder,
                onOpenFile = onOpenFile,
                onOpenSearch = onOpenSearch,
                onOpenGit = onOpenGit,
                onOpenTerminal = onOpenTerminal,
                onOpenPreview = onOpenPreview,
                onOpenQuickOpen = {
                    actions.refreshProjectFiles()
                    showQuickOpen = true
                },
                onOpenPalette = { showPalette = true },
                onOpenOutline = { showOutline = true },
                onResolveConflicts = { showMergeResolver = true },
                onOpenProblems = { showProblems = true },
                onToggleFind = { showFind = !showFind },
                onGoToLine = { showGoToLine = true },
                showFind = showFind,
                onCloseFind = { showFind = false },
                initialFind = initialFind,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }
    }
    }

    if (showQuickOpen) {
        FuzzyPickerDialog(
            title = stringResource(R.string.quick_open),
            placeholder = stringResource(R.string.quick_open_hint),
            items = quickOpenItems(state) { actions.openPath(it) },
            emptyQueryItems = recentOpenItems(state) { actions.openPath(it) },
            onDismiss = { showQuickOpen = false },
        )
    }
    if (showPalette) {
        FuzzyPickerDialog(
            title = stringResource(R.string.command_palette),
            placeholder = stringResource(R.string.command_palette_hint),
            items = editorCommands(
                actions = actions,
                onToggleFind = { showFind = true },
                onGoToLine = { showGoToLine = true },
                onQuickOpen = {
                    actions.refreshProjectFiles()
                    showQuickOpen = true
                },
                onOutline = { showOutline = true },
                onResolveConflicts = { showMergeResolver = true },
                onProblems = { showProblems = true },
                onOpenSearch = onOpenSearch,
                onOpenGit = onOpenGit,
                onOpenTerminal = onOpenTerminal,
                onOpenPreview = onOpenPreview,
                onOpenSettings = onOpenSettings,
                onOpenFolder = onOpenFolder,
                onOpenFile = onOpenFile,
            ),
            onDismiss = { showPalette = false },
        )
    }
    if (showGoToLine) {
        GoToLineDialog(
            onGo = { actions.goToLine(it) },
            onDismiss = { showGoToLine = false },
        )
    }
    val outlineTab = state.activeTab
    if (showOutline && outlineTab != null) {
        val symbols = remember(outlineTab.value.text, outlineTab.language) {
            extractSymbols(outlineTab.value.text, outlineTab.language)
        }
        FuzzyPickerDialog(
            title = stringResource(R.string.go_to_symbol),
            placeholder = stringResource(R.string.go_to_symbol_hint),
            items = symbols.map { symbol ->
                PickerItem(
                    primary = symbol.name,
                    secondary = "${symbol.kind.name.lowercase()} \u00B7 ${symbol.line}",
                    matchKey = symbol.name,
                ) { actions.goToLine(symbol.line) }
            },
            onDismiss = { showOutline = false },
        )
    }
    val conflictTab = state.activeTab
    if (showMergeResolver && conflictTab != null) {
        MergeResolverDialog(
            text = conflictTab.value.text,
            onResolve = { actions.resolveConflicts(it) },
            onDismiss = { showMergeResolver = false },
        )
    }
    if (showProblems) {
        FuzzyPickerDialog(
            title = stringResource(R.string.problems),
            placeholder = stringResource(R.string.problems),
            items = state.diagnostics.map { d ->
                PickerItem(
                    primary = d.message,
                    secondary = "${d.severity.name.lowercase()} \u00B7 ${d.line + 1}",
                    matchKey = d.message,
                ) { actions.goToLine(d.line + 1) }
            },
            onDismiss = { showProblems = false },
        )
    }
    if (showExitGuard) {
        AlertDialog(
            onDismissRequest = { showExitGuard = false },
            title = { Text(stringResource(R.string.exit_unsaved_title)) },
            text = { Text(stringResource(R.string.exit_unsaved_message)) },
            confirmButton = {
                Row {
                    TextButton(onClick = { showExitGuard = false; onExitApp() }) {
                        Text(stringResource(R.string.discard))
                    }
                    TextButton(onClick = { actions.saveAll(); showExitGuard = false; onExitApp() }) {
                        Text(stringResource(R.string.save_all))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitGuard = false }) {
                    Text(stringResource(UiR.string.cancel))
                }
            },
        )
    }
    }
}
