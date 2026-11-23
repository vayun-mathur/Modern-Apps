package com.vayunmathur.files.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.files.R
import com.vayunmathur.files.platform.FileBrowserItem
import com.vayunmathur.files.platform.FilesActions
import com.vayunmathur.files.platform.FilesSearchHit
import com.vayunmathur.files.platform.FilesUiState
import com.vayunmathur.files.platform.ViewMode
import com.vayunmathur.files.platform.searchMatches
import com.vayunmathur.files.ui.components.DirectoryItem
import com.vayunmathur.files.ui.components.GridItem
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import java.io.File

/**
 * The listing below the top bar: the paste bar plus the current folder's contents.
 *
 * Split out of [DirectoryScreen] so the expanded-width layout can place it beside the parent
 * pane; the single-pane path renders it full-bleed exactly as before.
 */
@Composable
internal fun DirectoryListing(
    state: FilesUiState,
    actions: FilesActions,
    canModifyHere: Boolean,
    isReadOnly: Boolean,
    modifier: Modifier = Modifier,
    /** Clears the rename dialog target; owned by [DirectoryScreen]. */
    onClearRenameTarget: () -> Unit = {},
) {
    DirectoryListing(
        directories = state.directories,
        files = state.files,
        searchQuery = state.searchQuery,
        searchResults = state.searchResults,
        isSearchLoading = state.isSearchLoading,
        clipboardCount = state.clipboardCount,
        selectedPaths = state.selectedPaths,
        isSearchActive = state.isSearchActive,
        viewMode = state.viewMode,
        actions = actions,
        canModifyHere = canModifyHere,
        isReadOnly = isReadOnly,
        modifier = modifier,
        onClearRenameTarget = onClearRenameTarget,
    )
}

@Composable
internal fun DirectoryListing(
    directories: List<FileBrowserItem>,
    files: List<FileBrowserItem>,
    searchQuery: String,
    clipboardCount: Int,
    selectedPaths: Set<FileBrowserItem>,
    isSearchActive: Boolean,
    viewMode: ViewMode,
    actions: FilesActions,
    canModifyHere: Boolean,
    isReadOnly: Boolean,
    modifier: Modifier = Modifier,
    /** Clears the rename dialog target; owned by [DirectoryScreen]. */
    onClearRenameTarget: () -> Unit = {},
    /**
     * Recursive-search hits for a non-blank query, flattened with parent
     * subtitles. Null keeps the current-folder fast path/filter (blank query,
     * previews, zip and category scopes).
     */
    searchResults: List<FilesSearchHit>? = null,
    /** True while the recursive walk for [searchQuery] is still running. */
    isSearchLoading: Boolean = false,
) {
    val query = searchQuery.trim()
    val useRecursive = query.isNotEmpty() && searchResults != null
    val subtitles = if (useRecursive) {
        searchResults.associate { it.item.key to it.parentSubtitle }
    } else {
        emptyMap()
    }
    val allItems = if (useRecursive) {
        searchResults.map { it.item }
    } else {
        (directories + files).filter { searchMatches(it.name, query) }
    }

    Column(modifier) {
            // Paste bar: shown when the clipboard has files and we can write here.
            if (clipboardCount > 0 && canModifyHere && selectedPaths.isEmpty() && !isSearchActive) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            pluralStringResource(R.plurals.ready_to_paste, clipboardCount, clipboardCount),
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { actions.pasteHere() }) { Text(stringResource(R.string.paste)) }
                        IconButton(onClick = { actions.clearClipboard() }) { IconClose() }
                    }
                }
            }

            val onItemClick: (FileBrowserItem) -> Unit = { child ->
                if (selectedPaths.isNotEmpty()) {
                    actions.toggleSelection(child)
                } else if (child.isDirectory) {
                    if (child.realFile != null) actions.navigateTo(child.realFile)
                    else actions.navigateIntoZipDir(child.name)
                } else if (child.name.endsWith(".zip", ignoreCase = true) && child.realFile != null) {
                    actions.openZipFile(child)
                } else if (child.name.endsWith(".apk", ignoreCase = true) && child.realFile != null) {
                    actions.installApk(child)
                } else {
                    actions.openFile(child)
                }
            }
            val onItemLongClick: (FileBrowserItem) -> Unit = { child ->
                if (!isReadOnly) {
                    onClearRenameTarget()
                    actions.toggleSelection(child)
                }
            }

            if (useRecursive && isSearchLoading && allItems.isEmpty()) {
                Text(
                    stringResource(R.string.searching_files),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            } else if (allItems.isEmpty()) {
                EmptyState(
                    title = if (query.isNotEmpty()) {
                        stringResource(R.string.no_search_results, query)
                    } else {
                        stringResource(R.string.no_files)
                    },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            } else if (viewMode == ViewMode.GRID) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 104.dp),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    gridItems(allItems, key = { it.key }) { child ->
                        GridItem(
                            file = child,
                            isSelected = selectedPaths.any { it.key == child.key },
                            onClick = { onItemClick(child) },
                            onLongClick = { onItemLongClick(child) },
                            subtitle = subtitles[child.key],
                        )
                    }
                }
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    items(allItems, key = { it.key }) { child ->
                        val isSelected = selectedPaths.any { it.key == child.key }
                        DirectoryItem(
                            file = child,
                            isSelected = isSelected,
                            isReadOnly = isReadOnly,
                            subtitle = subtitles[child.key],
                            onLongClick = { onItemLongClick(child) },
                            onClick = { onItemClick(child) },
                            onMove = { sources: List<File> ->
                                if (!isReadOnly && child.isDirectory && child.realFile != null) {
                                    actions.moveInto(sources, child.realFile)
                                }
                            },
                            onStartDrag = {
                                if (isReadOnly) emptyList()
                                else if (selectedPaths.any { it.key == child.key }) selectedPaths.mapNotNull { it.realFile }.toList()
                                else listOfNotNull(child.realFile)
                            })
                        HorizontalDivider(
                            thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant
                        )
                    }
                }
            }
    }
}
