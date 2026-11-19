package com.vayunmathur.files.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import com.vayunmathur.files.R
import com.vayunmathur.files.platform.FileBrowserItem
import com.vayunmathur.files.platform.FilesActions
import com.vayunmathur.files.platform.FilesUiState
import com.vayunmathur.files.ui.dialogs.PropertiesDialog
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconMenu
import com.vayunmathur.library.ui.SnackbarHost
import com.vayunmathur.library.ui.SnackbarHostState
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TextField
import com.vayunmathur.library.ui.VerticalDivider
import com.vayunmathur.library.ui.isExpandedWidth
import com.vayunmathur.library.ui.R as UiR

/**
 * The directory browser, with no dependency on the ViewModel so it can be rendered from a
 * `@Preview` — see `src/screenshotTest`, which is where the store listing images come from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DirectoryScreen(
    state: FilesUiState,
    actions: FilesActions,
    /** Owned by the binder, which is where the ViewModel's messages arrive. */
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    /** Opens the system folder picker for "unzip here"; needs an Activity. */
    onPickUnzipDestination: () -> Unit = {},
    /** Opens the navigation drawer. */
    onOpenDrawer: () -> Unit = {},
) {
    val isReadOnly = state.zipPath != null
    val isCategory = state.categoryTitle != null
    // A category view lists files gathered from across storage, so structural edits
    // (new/paste/move/rename/archive) that target "here" don't apply.
    val canModifyHere = !isReadOnly && !isCategory

    var itemBeingRenamed by remember { mutableStateOf<FileBrowserItem?>(null) }
    var itemForProperties by remember { mutableStateOf<FileBrowserItem?>(null) }
    var showArchiveDialog by remember { mutableStateOf(false) }
    var archiveName by remember { mutableStateOf("archive.zip") }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var showNewFileDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.selectedPaths) {
        if (state.selectedPaths.isEmpty()) itemBeingRenamed = null
    }

    if (showArchiveDialog) {
        AlertDialog(
            onDismissRequest = { showArchiveDialog = false },
            title = { Text(stringResource(R.string.archive_selection)) },
            text = {
                TextField(
                    value = archiveName,
                    onValueChange = { archiveName = it },
                    label = { Text(stringResource(R.string.zip_file_name_label)) },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        actions.archive(archiveName)
                        showArchiveDialog = false
                    }) { Text(stringResource(R.string.archive)) }
            },
            dismissButton = {
                TextButton(onClick = { showArchiveDialog = false }) {
                    Text(stringResource(UiR.string.cancel))
                }
            })
    }

    itemBeingRenamed?.let { renaming ->
        var newName by remember(renaming.key) { mutableStateOf(renaming.name) }
        AlertDialog(
            onDismissRequest = { itemBeingRenamed = null },
            title = { Text(stringResource(UiR.string.rename)) },
            text = {
                TextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text(stringResource(R.string.new_name_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        actions.rename(renaming, newName)
                        itemBeingRenamed = null
                    })
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    actions.rename(renaming, newName)
                    itemBeingRenamed = null
                }) { Text(stringResource(UiR.string.rename)) }
            },
            dismissButton = {
                TextButton(onClick = { itemBeingRenamed = null }) {
                    Text(stringResource(UiR.string.cancel))
                }
            })
    }

    if (showNewFolderDialog) {
        NameDialog(
            title = stringResource(R.string.new_folder),
            label = stringResource(R.string.folder_name_label),
            initial = "",
            confirmLabel = stringResource(UiR.string.create),
            onConfirm = { actions.createFolder(it); showNewFolderDialog = false },
            onDismiss = { showNewFolderDialog = false },
        )
    }
    if (showNewFileDialog) {
        NameDialog(
            title = stringResource(R.string.new_file),
            label = stringResource(R.string.file_name_label),
            initial = "untitled.txt",
            confirmLabel = stringResource(UiR.string.create),
            onConfirm = { actions.createFile(it); showNewFileDialog = false },
            onDismiss = { showNewFileDialog = false },
        )
    }
    itemForProperties?.let { item ->
        PropertiesDialog(item = item, onDismiss = { itemForProperties = null })
    }

    val focusManager = LocalFocusManager.current

    // Only the parts of Back that are not navigation. Folder, archive and category depth are back
    // stack entries now, so everything else has to fall through to the navigation host - swallowing
    // it here would also swallow the predictive-back gesture and its preview.
    BackHandler(state.isSearchActive || state.selectedPaths.isNotEmpty()) {
        itemBeingRenamed = null
        if (state.isSearchActive) actions.setSearchActive(false) else actions.handleBack()
    }

    AppScaffold(
        modifier = Modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null
            ) {
                focusManager.clearFocus()
                itemBeingRenamed = null
                actions.clearSelection()
            },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        navigationIcon = {
                if (!state.isSearchActive && state.selectedPaths.isEmpty()) {
                    IconButton(onClick = onOpenDrawer) { IconMenu() }
                }
            },
        title = {
            DirectoryTitle(
                state = state,
                actions = actions,
                isCategory = isCategory,
                isReadOnly = isReadOnly,
            )
        },
        actions = {
            DirectoryTopActions(
                state = state,
                actions = actions,
                canModifyHere = canModifyHere,
                isReadOnly = isReadOnly,
                onRename = { itemBeingRenamed = it },
                onShowProperties = { itemForProperties = it },
                onArchive = {
                    archiveName =
                        if (state.selectedPaths.size == 1) "${state.selectedPaths.first().name}.zip"
                        else "archive.zip"
                    showArchiveDialog = true
                },
                onPickUnzipDestination = onPickUnzipDestination,
                onShowNewFolder = { showNewFolderDialog = true },
                onShowNewFile = { showNewFileDialog = true },
            )
        },
    ) { padding ->
        // Expanded windows (desktop, large tablets) list the parent folder beside the current
        // one. This stays inside the same destination on purpose: files deliberately has no
        // list/detail metadata, so one back-stack entry per folder level is what keeps Back
        // walking up exactly one folder with a predictive-back preview of where it lands. A
        // second destination would split that walk. Categories and zip contents have no parent
        // folder, the root has nothing above it, and compact widths keep the push behavior -
        // all of those stay single-pane exactly as before.
        val atRoot = state.currentDirectory.absolutePath == state.rootDirectory.absolutePath
        if (isExpandedWidth() && !isCategory && !isReadOnly && !atRoot) {
            Row(Modifier.fillMaxSize().padding(padding)) {
                DirectoryParentPane(
                    state = state,
                    actions = actions,
                    modifier = Modifier.weight(0.35f).fillMaxHeight(),
                )
                VerticalDivider(modifier = Modifier.fillMaxHeight())
                DirectoryListing(
                    state = state,
                    actions = actions,
                    canModifyHere = canModifyHere,
                    isReadOnly = isReadOnly,
                    modifier = Modifier.weight(0.65f).fillMaxHeight(),
                    onClearRenameTarget = { itemBeingRenamed = null },
                )
            }
        } else {
            DirectoryListing(
                state = state,
                actions = actions,
                canModifyHere = canModifyHere,
                isReadOnly = isReadOnly,
                modifier = Modifier.fillMaxSize().padding(padding),
                onClearRenameTarget = { itemBeingRenamed = null },
            )
        }
    }
}
