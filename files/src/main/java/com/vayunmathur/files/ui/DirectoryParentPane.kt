package com.vayunmathur.files.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.files.R
import com.vayunmathur.files.platform.FileBrowserItem
import com.vayunmathur.files.platform.FilesActions
import com.vayunmathur.files.platform.FilesUiState
import com.vayunmathur.files.platform.SortBy
import com.vayunmathur.files.platform.ViewMode
import com.vayunmathur.files.ui.components.DirectoryItem
import com.vayunmathur.files.ui.components.GridItem
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import java.io.File

/**
 * The parent folder listed beside the current one on expanded windows.
 *
 * Rendered *inside* the current directory destination rather than as a second nav destination,
 * because files deliberately carries no list/detail metadata: one back-stack entry per folder
 * level is what makes Back walk up exactly one folder with a predictive-back preview of where it
 * lands. A second destination would split that walk and break the gesture.
 *
 * Reads [File]s directly instead of going through the shared view model, which holds exactly one
 * location - the current one, owned by the main pane. Navigation-only: tapping a folder moves
 * there (popping back to it when it is already on the stack, pushing otherwise, exactly like the
 * breadcrumb), tapping a file opens it. Selection stays on the current pane so the two never
 * fight over the selection set - a tap here clears it first - and folders stay drop targets so
 * drag-and-drop works across panes.
 *
 * The caller sizes the pane with [modifier]; this fills whatever it is given.
 */
@Composable
fun DirectoryParentPane(
    state: FilesUiState,
    actions: FilesActions,
    modifier: Modifier = Modifier,
) {
    DirectoryParentPane(
        currentDirectory = state.currentDirectory,
        rootDirectory = state.rootDirectory,
        rootDisplayName = state.rootDisplayName,
        showHidden = state.showHidden,
        sortBy = state.sortBy,
        sortAscending = state.sortAscending,
        viewMode = state.viewMode,
        selectedPaths = state.selectedPaths,
        actions = actions,
        modifier = modifier,
    )
}

@Composable
fun DirectoryParentPane(
    currentDirectory: File,
    rootDirectory: File,
    rootDisplayName: String,
    showHidden: Boolean,
    sortBy: SortBy,
    sortAscending: Boolean,
    viewMode: ViewMode,
    selectedPaths: Set<FileBrowserItem>,
    actions: FilesActions,
    modifier: Modifier = Modifier,
) {
    val current = currentDirectory
    val root = rootDirectory
    val parent = remember(current) { current.parentFile }
    if (parent == null || current.absolutePath == root.absolutePath) {
        Box(modifier.fillMaxSize()) {}
        return
    }

    val siblings = remember(
        parent.absolutePath, showHidden, sortBy, sortAscending
    ) {
        val all = parent.listFiles()?.toList().orEmpty()
        val visible = if (showHidden) all else all.filterNot { it.name.startsWith(".") }
        val (dirs, files) = visible.map { it.toSiblingItem() }.partition { it.isDirectory }
        sortSiblings(dirs, sortBy, sortAscending) +
            sortSiblings(files, sortBy, sortAscending)
    }

    val onSiblingClick: (FileBrowserItem) -> Unit = { child ->
        if (selectedPaths.isNotEmpty()) actions.clearSelection()
        val target = child.realFile
        if (child.isDirectory) {
            if (target != null) actions.navigateTo(target)
        } else if (child.name.endsWith(".zip", ignoreCase = true) && target != null) {
            actions.openZipFile(child)
        } else if (child.name.endsWith(".apk", ignoreCase = true) && target != null) {
            actions.installApk(child)
        } else {
            actions.openFile(child)
        }
    }

    val header = if (parent.absolutePath == root.absolutePath) rootDisplayName else parent.name
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .clickable { actions.navigateTo(parent) }
        ) {
            SectionHeader(header)
        }
        if (siblings.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.no_files),
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        } else if (viewMode == ViewMode.GRID) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 104.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                gridItems(siblings, key = { it.key }) { child ->
                    GridItem(
                        file = child,
                        isSelected = child.realFile?.absolutePath == current.absolutePath,
                        onClick = { onSiblingClick(child) },
                        onLongClick = {},
                    )
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(siblings, key = { it.key }) { child ->
                    DirectoryItem(
                        file = child,
                        isSelected = child.realFile?.absolutePath == current.absolutePath,
                        isReadOnly = false,
                        onLongClick = {},
                        onClick = { onSiblingClick(child) },
                        onMove = { sources ->
                            val target = child.realFile
                            if (child.isDirectory && target != null) {
                                actions.moveInto(sources, target)
                            }
                        },
                        onStartDrag = { listOfNotNull(child.realFile) },
                    )
                    HorizontalDivider(
                        thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
            }
        }
    }
}

private fun File.toSiblingItem() = FileBrowserItem(
    name = name,
    isDirectory = isDirectory,
    size = if (isFile) length() else null,
    realFile = this,
    zipInnerPath = null,
    key = absolutePath,
    lastModified = lastModified(),
)

private fun sortSiblings(
    items: List<FileBrowserItem>,
    sortBy: SortBy,
    ascending: Boolean,
): List<FileBrowserItem> {
    val comparator: Comparator<FileBrowserItem> = when (sortBy) {
        SortBy.NAME -> compareBy { it.name.lowercase() }
        SortBy.DATE -> compareBy { it.lastModified }
        SortBy.SIZE -> compareBy { it.size ?: -1L }
        SortBy.TYPE -> compareBy<FileBrowserItem> {
            it.name.substringAfterLast('.', "").lowercase()
        }.thenBy { it.name.lowercase() }
    }
    val sorted = items.sortedWith(comparator)
    return if (ascending) sorted else sorted.reversed()
}
