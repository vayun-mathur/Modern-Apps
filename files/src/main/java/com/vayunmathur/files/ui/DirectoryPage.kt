package com.vayunmathur.files.ui

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.files.R
import com.vayunmathur.files.data.saf.StorageUris
import com.vayunmathur.files.platform.FileBrowserItem
import com.vayunmathur.files.platform.FilesActions
import com.vayunmathur.files.platform.FilesViewModel
import com.vayunmathur.library.ui.SnackbarHostState

/**
 * Binds [FilesViewModel] to the stateless [DirectoryScreen].
 *
 * [isCurrent] is false while this entry is animating away under a newer one. Every folder on the
 * back stack is a separate entry sharing one view model, so without it the outgoing screen would
 * repaint with the contents of the folder being opened just as it slides out of view.
 *
 * No flow is collected at this root. Each section below collects only the flows it draws
 * (with [collectAsStateWithLifecycle]), so a change in one flow recomposes only its section.
 * A flow feeding several sections is collected once at their lowest common consumer:
 * `selectedPaths`/`isSearchActive` in [DirectoryScreenCore] (Back, nav icon and the
 * sections all need them), and the sort/view/search/hidden flows in the sections that
 * share them. `clipboardIsCut` has no UI consumer, so it is not collected at all.
 * Every collected value passes through [frozen], preserving the [isCurrent] freeze that
 * the old single `lastShown` snapshot provided.
 */
@Composable
fun DirectoryPage(
    viewModel: FilesViewModel,
    actions: FilesActions,
    isCurrent: Boolean,
    onOpenDrawer: () -> Unit = {},
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(snackbarHostState) {
        viewModel.snackbarMessages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.intents.collect { intent ->
            try {
                context.startActivity(intent)
            } catch (_: Exception) {
                viewModel.showMessage(resources.getString(R.string.no_app_found_to_open_file))
            }
        }
    }

    val installPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            viewModel.onInstallPermissionResult()
        }
    LaunchedEffect(Unit) {
        viewModel.installPermissionRequests.collect {
            installPermissionLauncher.launch(unknownAppSourcesSettings(context))
        }
    }

    // The "unzip here" folder picker needs an Activity, so it is launched from here rather
    // than from the screen. The zip and the fallback folder are read from the view model
    // when the pick returns, so neither flow needs collecting at this root.
    val treeLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            val zipToUnzip = viewModel.selectedPaths.value.singleOrNull()?.takeIf {
                !it.isDirectory && it.realFile != null && it.name.endsWith(".zip", ignoreCase = true)
            }
            if (uri != null && zipToUnzip != null) {
                // Resolve the picked tree against its own volume: splitting the
                // URI on ':' and re-rooting at the primary volume extracts an
                // SD-card pick into the same-named internal folder instead.
                val path = StorageUris.fileForTreeUri(context, uri)
                    ?: viewModel.currentDirectory.value
                viewModel.unzip(zipToUnzip, path)
            }
        }

    // Lowest common consumer for selectedPaths/isSearchActive: DirectoryScreenCore.
    val selectedPaths by viewModel.selectedPaths.collectAsStateWithLifecycle()
    val isSearchActive by viewModel.isSearchActive.collectAsStateWithLifecycle()
    DirectoryScreenCore(
        selectedPaths = frozen(isCurrent, selectedPaths),
        isSearchActive = frozen(isCurrent, isSearchActive),
        actions = actions,
        snackbarHostState = snackbarHostState,
        onOpenDrawer = onOpenDrawer,
        title = { searchFocus -> DirectoryTitleLive(viewModel, actions, isCurrent, searchFocus) },
        topActions = { onRename, onShowProperties, onArchive, onShowNewFolder, onShowNewFile ->
            DirectoryTopActionsLive(
                viewModel = viewModel,
                actions = actions,
                isCurrent = isCurrent,
                onRename = onRename,
                onShowProperties = onShowProperties,
                onArchive = onArchive,
                onPickUnzipDestination = { treeLauncher.launch(null) },
                onShowNewFolder = onShowNewFolder,
                onShowNewFile = onShowNewFile,
            )
        },
        body = { padding, onClearRenameTarget ->
            DirectoryBodyLive(viewModel, actions, isCurrent, padding, onClearRenameTarget)
        },
    )
}

/**
 * The last value seen while [isCurrent], or [live] itself while current. Keeps a
 * non-current back-stack entry frozen on the folder it showed, per collected flow.
 */
@Composable
private fun <T> frozen(isCurrent: Boolean, live: T): T {
    var last by remember { mutableStateOf(live) }
    SideEffect { if (isCurrent) last = live }
    return if (isCurrent) live else last
}

@Composable
private fun DirectoryTitleLive(
    viewModel: FilesViewModel,
    actions: FilesActions,
    isCurrent: Boolean,
    searchFocusRequester: androidx.compose.ui.focus.FocusRequester,
) {
    val currentDirectory by viewModel.currentDirectory.collectAsStateWithLifecycle()
    val zipPath by viewModel.zipPath.collectAsStateWithLifecycle()
    val zipInternalPath by viewModel.zipInternalPath.collectAsStateWithLifecycle()
    val isSearchActive by viewModel.isSearchActive.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val categoryTitle by viewModel.categoryTitle.collectAsStateWithLifecycle()
    val shownZipPath = frozen(isCurrent, zipPath)
    val shownCategoryTitle = frozen(isCurrent, categoryTitle)
    DirectoryTitle(
        rootDirectory = viewModel.rootDirectory,
        rootDisplayName = Build.MODEL,
        currentDirectory = frozen(isCurrent, currentDirectory),
        zipPath = shownZipPath,
        zipInternalPath = frozen(isCurrent, zipInternalPath),
        isSearchActive = frozen(isCurrent, isSearchActive),
        searchQuery = frozen(isCurrent, searchQuery),
        categoryTitle = shownCategoryTitle,
        actions = actions,
        isCategory = shownCategoryTitle != null,
        isReadOnly = shownZipPath != null,
        searchFocusRequester = searchFocusRequester,
    )
}

@Composable
private fun DirectoryTopActionsLive(
    viewModel: FilesViewModel,
    actions: FilesActions,
    isCurrent: Boolean,
    onRename: (FileBrowserItem) -> Unit,
    onShowProperties: (FileBrowserItem) -> Unit,
    onArchive: () -> Unit,
    onPickUnzipDestination: () -> Unit,
    onShowNewFolder: () -> Unit,
    onShowNewFile: () -> Unit,
) {
    val selectedPaths by viewModel.selectedPaths.collectAsStateWithLifecycle()
    val isSearchActive by viewModel.isSearchActive.collectAsStateWithLifecycle()
    val incomingUris by viewModel.incomingUris.collectAsStateWithLifecycle()
    val sortBy by viewModel.sortBy.collectAsStateWithLifecycle()
    val sortAscending by viewModel.sortAscending.collectAsStateWithLifecycle()
    val viewMode by viewModel.viewMode.collectAsStateWithLifecycle()
    val showHidden by viewModel.showHidden.collectAsStateWithLifecycle()
    val zipPath by viewModel.zipPath.collectAsStateWithLifecycle()
    val categoryTitle by viewModel.categoryTitle.collectAsStateWithLifecycle()
    val isReadOnly = frozen(isCurrent, zipPath) != null
    val isCategory = frozen(isCurrent, categoryTitle) != null
    DirectoryTopActions(
        selectedPaths = frozen(isCurrent, selectedPaths),
        isSearchActive = frozen(isCurrent, isSearchActive),
        hasIncomingUris = frozen(isCurrent, incomingUris) != null,
        sortBy = frozen(isCurrent, sortBy),
        sortAscending = frozen(isCurrent, sortAscending),
        viewMode = frozen(isCurrent, viewMode),
        showHidden = frozen(isCurrent, showHidden),
        actions = actions,
        canModifyHere = !isReadOnly && !isCategory,
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
private fun DirectoryBodyLive(
    viewModel: FilesViewModel,
    actions: FilesActions,
    isCurrent: Boolean,
    padding: PaddingValues,
    onClearRenameTarget: () -> Unit,
) {
    val currentDirectory by viewModel.currentDirectory.collectAsStateWithLifecycle()
    val zipPath by viewModel.zipPath.collectAsStateWithLifecycle()
    val categoryTitle by viewModel.categoryTitle.collectAsStateWithLifecycle()
    val isReadOnly = frozen(isCurrent, zipPath) != null
    val isCategory = frozen(isCurrent, categoryTitle) != null
    val canModifyHere = !isReadOnly && !isCategory
    DirectoryBody(
        currentDirectory = frozen(isCurrent, currentDirectory),
        rootDirectory = viewModel.rootDirectory,
        isCategory = isCategory,
        isReadOnly = isReadOnly,
        padding = padding,
        parentPane = { modifier -> DirectoryParentPaneLive(viewModel, actions, isCurrent, modifier) },
        listing = { modifier ->
            DirectoryListingLive(
                viewModel = viewModel,
                actions = actions,
                isCurrent = isCurrent,
                canModifyHere = canModifyHere,
                isReadOnly = isReadOnly,
                modifier = modifier,
                onClearRenameTarget = onClearRenameTarget,
            )
        },
    )
}

@Composable
private fun DirectoryParentPaneLive(
    viewModel: FilesViewModel,
    actions: FilesActions,
    isCurrent: Boolean,
    modifier: Modifier,
) {
    val currentDirectory by viewModel.currentDirectory.collectAsStateWithLifecycle()
    val showHidden by viewModel.showHidden.collectAsStateWithLifecycle()
    val sortBy by viewModel.sortBy.collectAsStateWithLifecycle()
    val sortAscending by viewModel.sortAscending.collectAsStateWithLifecycle()
    val viewMode by viewModel.viewMode.collectAsStateWithLifecycle()
    val selectedPaths by viewModel.selectedPaths.collectAsStateWithLifecycle()
    DirectoryParentPane(
        currentDirectory = frozen(isCurrent, currentDirectory),
        rootDirectory = viewModel.rootDirectory,
        rootDisplayName = Build.MODEL,
        showHidden = frozen(isCurrent, showHidden),
        sortBy = frozen(isCurrent, sortBy),
        sortAscending = frozen(isCurrent, sortAscending),
        viewMode = frozen(isCurrent, viewMode),
        selectedPaths = frozen(isCurrent, selectedPaths),
        actions = actions,
        modifier = modifier,
    )
}

@Composable
private fun DirectoryListingLive(
    viewModel: FilesViewModel,
    actions: FilesActions,
    isCurrent: Boolean,
    canModifyHere: Boolean,
    isReadOnly: Boolean,
    modifier: Modifier,
    onClearRenameTarget: () -> Unit,
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val categoryTitle by viewModel.categoryTitle.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    val isSearchLoading by viewModel.isSearchLoading.collectAsStateWithLifecycle()
    val clipboard by viewModel.clipboard.collectAsStateWithLifecycle()
    val selectedPaths by viewModel.selectedPaths.collectAsStateWithLifecycle()
    val isSearchActive by viewModel.isSearchActive.collectAsStateWithLifecycle()
    val viewMode by viewModel.viewMode.collectAsStateWithLifecycle()
    val (directories, files) = frozen(isCurrent, entries)
    // Zip and category scopes keep the current-listing filter: no recursive walk.
    val recursiveResults = if (isReadOnly || frozen(isCurrent, categoryTitle) != null) {
        null
    } else {
        frozen(isCurrent, searchResults)
    }
    DirectoryListing(
        directories = directories,
        files = files,
        searchQuery = frozen(isCurrent, searchQuery),
        searchResults = recursiveResults,
        isSearchLoading = frozen(isCurrent, isSearchLoading),
        clipboardCount = frozen(isCurrent, clipboard).size,
        selectedPaths = frozen(isCurrent, selectedPaths),
        isSearchActive = frozen(isCurrent, isSearchActive),
        viewMode = frozen(isCurrent, viewMode),
        actions = actions,
        canModifyHere = canModifyHere,
        isReadOnly = isReadOnly,
        modifier = modifier,
        onClearRenameTarget = onClearRenameTarget,
    )
}
