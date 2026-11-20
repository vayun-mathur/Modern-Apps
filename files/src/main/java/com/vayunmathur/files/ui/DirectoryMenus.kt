package com.vayunmathur.files.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.vayunmathur.files.R
import com.vayunmathur.files.platform.FileBrowserItem
import com.vayunmathur.files.platform.FilesActions
import com.vayunmathur.files.platform.FilesUiState
import com.vayunmathur.files.platform.SortBy
import com.vayunmathur.files.platform.ViewMode
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconArchive
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconCopy
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconEdit
import com.vayunmathur.library.ui.IconGrid
import com.vayunmathur.library.ui.IconInfo
import com.vayunmathur.library.ui.IconList
import com.vayunmathur.library.ui.IconMoreVert
import com.vayunmathur.library.ui.IconNewFile
import com.vayunmathur.library.ui.IconNewFolder
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.IconShare
import com.vayunmathur.library.ui.IconStar
import com.vayunmathur.library.ui.IconUnarchive
import com.vayunmathur.library.ui.IconVisibilityOff
import com.vayunmathur.library.ui.IconVisible
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.Text

/** Top-bar action slot for [DirectoryScreen]: search / selection / overflow menus. */
@Composable
internal fun DirectoryTopActions(
    state: FilesUiState,
    actions: FilesActions,
    canModifyHere: Boolean,
    isReadOnly: Boolean,
    onRename: (FileBrowserItem) -> Unit,
    onShowProperties: (FileBrowserItem) -> Unit,
    onArchive: () -> Unit,
    onPickUnzipDestination: () -> Unit,
    onShowNewFolder: () -> Unit,
    onShowNewFile: () -> Unit,
) {
    DirectoryTopActions(
        selectedPaths = state.selectedPaths,
        isSearchActive = state.isSearchActive,
        hasIncomingUris = state.hasIncomingUris,
        sortBy = state.sortBy,
        sortAscending = state.sortAscending,
        viewMode = state.viewMode,
        showHidden = state.showHidden,
        actions = actions,
        canModifyHere = canModifyHere,
        isReadOnly = isReadOnly,
        onRename = onRename,
        onShowProperties = onShowProperties,
        onArchive = onArchive,
        onPickUnzipDestination = onPickUnzipDestination,
        onShowNewFolder = onShowNewFolder,
        onShowNewFile = onShowNewFile,
    )
}

@Composable
internal fun DirectoryTopActions(
    selectedPaths: Set<FileBrowserItem>,
    isSearchActive: Boolean,
    hasIncomingUris: Boolean,
    sortBy: SortBy,
    sortAscending: Boolean,
    viewMode: ViewMode,
    showHidden: Boolean,
    actions: FilesActions,
    canModifyHere: Boolean,
    isReadOnly: Boolean,
    onRename: (FileBrowserItem) -> Unit,
    onShowProperties: (FileBrowserItem) -> Unit,
    onArchive: () -> Unit,
    onPickUnzipDestination: () -> Unit,
    onShowNewFolder: () -> Unit,
    onShowNewFile: () -> Unit,
) {
    var showOverflow by remember { mutableStateOf(false) }
    var showSelectionOverflow by remember { mutableStateOf(false) }

    val zipToUnzip = remember(selectedPaths) {
        selectedPaths.singleOrNull()?.takeIf {
            !it.isDirectory && it.realFile != null && it.name.endsWith(".zip", ignoreCase = true)
        }
    }

    if (isSearchActive) {
        IconButton(onClick = { actions.setSearchActive(false) }) { IconClose() }
    } else if (selectedPaths.isNotEmpty()) {
        IconButton(onClick = { actions.clearSelection() }) { IconClose() }
        IconButton(onClick = { actions.deleteSelection() }) { IconDelete() }
        IconButton(onClick = { showSelectionOverflow = true }) { IconMoreVert() }
        DropdownMenu(
            expanded = showSelectionOverflow,
            onDismissRequest = { showSelectionOverflow = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(UiR.string.copy)) },
                leadingIcon = { IconCopy() },
                onClick = { showSelectionOverflow = false; actions.copySelection() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.cut)) },
                leadingIcon = { IconArchive() },
                onClick = { showSelectionOverflow = false; actions.cutSelection() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(UiR.string.share)) },
                leadingIcon = { IconShare() },
                onClick = { showSelectionOverflow = false; actions.shareSelection() },
            )
            val single = selectedPaths.singleOrNull()
            if (single != null && !single.isDirectory) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.open_with)) },
                    leadingIcon = { IconShare() },
                    onClick = { showSelectionOverflow = false; actions.openWith(single) },
                )
            }
            if (single != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(UiR.string.rename)) },
                    leadingIcon = { IconEdit() },
                    onClick = {
                        showSelectionOverflow = false
                        onRename(single)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.properties)) },
                    leadingIcon = { IconInfo() },
                    onClick = { showSelectionOverflow = false; onShowProperties(single) },
                )
            }
            if (single != null && single.isDirectory && single.realFile != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.add_bookmark)) },
                    leadingIcon = { IconStar() },
                    onClick = { showSelectionOverflow = false; actions.addBookmark(single) },
                )
            }
            if (canModifyHere) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.archive)) },
                    leadingIcon = { IconArchive() },
                    onClick = {
                        showSelectionOverflow = false
                        onArchive()
                    },
                )
            }
            if (zipToUnzip != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.archive_selection)) },
                    leadingIcon = { IconUnarchive() },
                    onClick = { showSelectionOverflow = false; onPickUnzipDestination() },
                )
            }
            if (hasIncomingUris) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.files_saved)) },
                    leadingIcon = { IconSave() },
                    onClick = { showSelectionOverflow = false; actions.saveIncomingUris() },
                )
            }
        }
    } else {
        if (hasIncomingUris && !isReadOnly) {
            IconButton(onClick = { actions.saveIncomingUris() }) { IconSave() }
        }
        IconButton(onClick = { actions.setSearchActive(true) }) { IconSearch() }
        IconButton(onClick = { showOverflow = true }) { IconMoreVert() }
        DropdownMenu(
            expanded = showOverflow,
            onDismissRequest = { showOverflow = false },
        ) {
            SortBy.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(sortLabel(option)) },
                    leadingIcon = { if (sortBy == option) IconCheck() },
                    onClick = { showOverflow = false; actions.setSortBy(option) },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sort_ascending)) },
                leadingIcon = { if (sortAscending) IconCheck() },
                onClick = { showOverflow = false; actions.setSortAscending(true) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sort_descending)) },
                leadingIcon = { if (!sortAscending) IconCheck() },
                onClick = { showOverflow = false; actions.setSortAscending(false) },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (viewMode == ViewMode.LIST) R.string.grid_view
                            else R.string.list_view
                        )
                    )
                },
                leadingIcon = {
                    if (viewMode == ViewMode.LIST) IconGrid() else IconList()
                },
                onClick = { showOverflow = false; actions.toggleViewMode() },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (showHidden) R.string.hide_hidden else R.string.show_hidden
                        )
                    )
                },
                leadingIcon = { if (showHidden) IconVisibilityOff() else IconVisible() },
                onClick = { showOverflow = false; actions.toggleHidden() },
            )
            if (!isReadOnly) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.select_all)) },
                    onClick = { showOverflow = false; actions.selectAll() },
                )
            }
            if (canModifyHere) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.new_folder)) },
                    leadingIcon = { IconNewFolder() },
                    onClick = { showOverflow = false; onShowNewFolder() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.new_file)) },
                    leadingIcon = { IconNewFile() },
                    onClick = { showOverflow = false; onShowNewFile() },
                )
            }
        }
    }
}
