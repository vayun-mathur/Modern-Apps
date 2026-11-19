package com.vayunmathur.pdf.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Checkbox
import com.vayunmathur.library.ui.DrawerState
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconEdit
import com.vayunmathur.library.ui.IconKeyboardArrowDown
import com.vayunmathur.library.ui.IconKeyboardArrowUp
import com.vayunmathur.library.ui.IconLock
import com.vayunmathur.library.ui.IconMenu
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.IconRedo
import com.vayunmathur.library.ui.IconRedact
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.IconShare
import com.vayunmathur.library.ui.IconUndo
import com.vayunmathur.library.ui.IconVisible
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.SmallFloatingActionButton
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextField
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.isExpandedWidth
import com.vayunmathur.pdf.R
import com.vayunmathur.pdf.platform.SafePdfDocument
import kotlinx.coroutines.launch

/** Edit toolbar wired to [SafePdfViewerState]; shared by bottom bar and wide side panel. */
@Composable
internal fun SafePdfEditToolbarPane(
    state: SafePdfViewerState,
    document: SafePdfDocument?,
) {
    EditToolbar(
        tool = state.tool,
        onTool = {
            // Leaving the multi-point tools finalizes any in-progress draft.
            if (it != EditTool.POLYLINE && it != EditTool.BEZIER) state.commitPoly(document)
            state.tool = it; state.selected = null
        },
        shape = state.shape,
        onShape = { state.shape = it; state.tool = EditTool.SHAPE; state.selected = null; state.commitPoly(document) },
        markup = state.markup,
        onMarkup = { state.markup = it; state.tool = EditTool.MARKUP; state.selected = null; state.commitPoly(document) },
        color = state.color,
        onColor = { state.color = it },
        onStyle = { state.showStyle = true },
        canDelete = state.selected != null,
        onDelete = { state.deleteSelected(document) },
        onDuplicate = { state.duplicateSelected(document) },
    )
}

/**
 * App chrome around the page viewer: search bar, actions, bottom edit bar,
 * polyline FAB, wide-layout switch, and the outline drawer. Logic verbatim
 * from [SafePdfViewerScreen]'s scaffold section.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SafePdfViewerChrome(
    state: SafePdfViewerState,
    bundle: SafePdfDocumentBundle,
    scroll: SafePdfScrollState,
    zoom: SafePdfZoomState,
    drawerState: DrawerState,
    pageViewer: @Composable (Modifier) -> Unit,
    searchFocus: FocusRequester,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onSaveInPlace: () -> Unit,
    onSaveCopyName: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val document = bundle.document
    // Expanded edit mode moves the toolbar into the wide layout's side panel
    // (and hides the compact bottom bar) so it never covers page content.
    val expandedWide = isExpandedWidth() && state.editMode
    var showSaveMenu by remember { mutableStateOf(false) }

    PdfOutlineDrawer(
        outline = bundle.outline,
        drawerState = drawerState,
        onSelectPage = { page ->
            scope.launch {
                if (page >= 0) scroll.scrollToPage(page, zoom.zoom)
                drawerState.close()
            }
        },
    ) {
        AppScaffold(
            modifier = Modifier.fillMaxSize(),
            title = {
                if (state.searching) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextField(
                            value = state.query,
                            onValueChange = { state.query = it },
                            modifier = Modifier.weight(1f).focusRequester(searchFocus),
                            placeholder = { Text(stringResource(R.string.search_label)) },
                            singleLine = true,
                        )
                        Checkbox(checked = state.caseSensitive, onCheckedChange = { state.caseSensitive = it })
                        Text(stringResource(R.string.aa), style = MaterialTheme.typography.labelSmall)
                    }
                }
            },
            navigationIcon = {
                if (state.searching) {
                    IconNavigation { state.searching = false; state.query = "" }
                } else if (bundle.outline.isNotEmpty()) {
                    IconButton({ scope.launch { drawerState.open() } }) { IconMenu() }
                } else {
                    IconNavigation { onBack() }
                }
            },
            actions = {
                if (state.searching) {
                    if (state.matches.isNotEmpty()) {
                        Text(
                            "${state.matchIndex + 1}/${state.matches.size}",
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        IconButton({ if (state.matchIndex > 0) state.matchIndex-- }) {
                            IconKeyboardArrowUp()
                        }
                        IconButton({ if (state.matchIndex < state.matches.size - 1) state.matchIndex++ }) {
                            IconKeyboardArrowDown()
                        }
                    }
                } else {
                    if (!state.editMode) {
                        IconButton({ state.searching = true }) { IconSearch() }
                        IconButton({ state.showEncrypt = true }) {
                            IconLock()
                        }
                    }
                    if (state.editMode) {
                        IconButton({ state.undo(document) }, enabled = state.undoStack.isNotEmpty()) {
                            IconUndo()
                        }
                        IconButton({ state.redo(document) }, enabled = state.redoStack.isNotEmpty()) {
                            IconRedo()
                        }
                    }
                    if (bundle.hasRedactions) {
                        IconButton({ state.applyRedactions(document) }) {
                            IconRedact()
                        }
                    }
                    IconButton({ state.toggleEditMode(document) }) {
                        if (state.editMode) IconVisible() else IconEdit()
                    }
                    if (state.undoStack.isNotEmpty() || state.nonUndoDirty) {
                        Box {
                            IconButton({ showSaveMenu = true }) { IconSave() }
                            DropdownMenu(
                                expanded = showSaveMenu,
                                onDismissRequest = { showSaveMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.pdf_save)) },
                                    onClick = { showSaveMenu = false; onSaveInPlace() },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.save_as_u2026)) },
                                    onClick = {
                                        showSaveMenu = false
                                        onSaveCopyName("edited.pdf")
                                    },
                                )
                            }
                        }
                    } else {
                        IconButton({ onShare() }) { IconShare() }
                    }
                }
            },
            bottomBar = {
                // Expanded edit mode hosts the toolbar in the wide layout's side
                // panel instead, so the bottom bar is hidden there.
                if (state.editMode && !expandedWide) {
                    SafePdfEditToolbarPane(state, document)
                }
            },
            floatingActionButton = {
                val draft = state.polyDraft
                if (state.editMode && draft != null) {
                    Column {
                        SmallFloatingActionButton(onClick = { state.polyDraft = null }) {
                            IconClose()
                        }
                        Spacer(Modifier.padding(4.dp))
                        FloatingActionButton(
                            onClick = { state.commitPoly(document) },
                        ) { IconCheck() }
                    }
                }
            },
        ) { innerPadding ->
            // Expanded edit mode: real pages beside the tools panel so the toolbar
            // never covers content. Otherwise the compact full-size viewer.
            if (expandedWide) {
                PdfViewerWideLayout(
                    viewer = { pageViewer(Modifier.fillMaxSize()) },
                    tools = { SafePdfEditToolbarPane(state, document) },
                    modifier = Modifier.padding(innerPadding),
                )
            } else {
                Box(
                    Modifier
                        .padding(innerPadding)
                        .fillMaxSize()
                ) {
                    pageViewer(Modifier.fillMaxSize())
                }
            }
        }
    }
}
