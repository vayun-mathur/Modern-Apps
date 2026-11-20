package com.vayunmathur.files.ui

import android.content.ClipDescription
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.files.R
import com.vayunmathur.files.platform.FilesActions
import com.vayunmathur.files.platform.FilesUiState
import com.vayunmathur.library.ui.IconChevronRight
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextField
import java.io.File

/** Title slot for [DirectoryScreen]: search field, category title, or breadcrumb trail. */
@Composable
internal fun DirectoryTitle(
    state: FilesUiState,
    actions: FilesActions,
    isCategory: Boolean,
    isReadOnly: Boolean,
) {
    DirectoryTitle(
        rootDirectory = state.rootDirectory,
        rootDisplayName = state.rootDisplayName,
        currentDirectory = state.currentDirectory,
        zipPath = state.zipPath,
        zipInternalPath = state.zipInternalPath,
        isSearchActive = state.isSearchActive,
        searchQuery = state.searchQuery,
        categoryTitle = state.categoryTitle,
        actions = actions,
        isCategory = isCategory,
        isReadOnly = isReadOnly,
    )
}

@Composable
internal fun DirectoryTitle(
    rootDirectory: File,
    rootDisplayName: String,
    currentDirectory: File,
    zipPath: File?,
    zipInternalPath: String,
    isSearchActive: Boolean,
    searchQuery: String,
    categoryTitle: String?,
    actions: FilesActions,
    isCategory: Boolean,
    isReadOnly: Boolean,
) {
    val root = rootDirectory
    val breadcrumbs = remember(
        currentDirectory, zipPath, zipInternalPath, rootDisplayName
    ) {
        val crumbs = mutableListOf<Crumb>()
        val zp = zipPath
        if (zp == null) {
            fileAncestors(currentDirectory, root).forEach { f ->
                crumbs.add(Crumb(displayName = if (f.absolutePath == root.absolutePath) rootDisplayName else f.name, realFile = f, zipInternalPath = null))
            }
        } else {
            val parent = zp.parentFile ?: root
            fileAncestors(parent, root).forEach { f ->
                crumbs.add(Crumb(displayName = if (f.absolutePath == root.absolutePath) rootDisplayName else f.name, realFile = f, zipInternalPath = null))
            }
            crumbs.add(Crumb(displayName = zp.name, realFile = null, zipInternalPath = ""))
            var accum = ""
            for (seg in zipInternalPath.split("/").filter { it.isNotEmpty() }) {
                accum = if (accum.isEmpty()) seg else "$accum/$seg"
                crumbs.add(Crumb(displayName = seg, realFile = null, zipInternalPath = accum))
            }
        }
        crumbs
    }

    if (isSearchActive) {
        val searchFocus = remember { FocusRequester() }
        TextField(
            value = searchQuery,
            onValueChange = { actions.setSearchQuery(it) },
            placeholder = { Text(stringResource(R.string.search_files)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(searchFocus),
        )
        LaunchedEffect(Unit) { searchFocus.requestFocus() }
    } else if (isCategory) {
        Text(categoryTitle.orEmpty(), style = MaterialTheme.typography.titleLarge)
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.horizontalScroll(rememberScrollState())
        ) {
            breadcrumbs.forEachIndexed { index, crumb ->
                var isBreadcrumbDraggingOver by remember(crumb) {
                    mutableStateOf(false)
                }

                val canDrop = !isReadOnly && crumb.realFile != null && crumb.zipInternalPath == null

                Box(
                    modifier = Modifier
                        .background(
                            if (isBreadcrumbDraggingOver) MaterialTheme.colorScheme.primaryContainer.copy(
                                alpha = 0.5f
                            )
                            else Color.Transparent, shape = MaterialTheme.shapes.small
                        )
                        .then(
                            if (canDrop) {
                                Modifier.dragAndDropTarget(
                                    shouldStartDragAndDrop = { event ->
                                        event.mimeTypes().contains(
                                            ClipDescription.MIMETYPE_TEXT_PLAIN
                                        )
                                    },
                                    target = remember(crumb.realFile.absolutePath) {
                                        dropTarget(
                                            onDragStateChange = { isBreadcrumbDraggingOver = it },
                                            onDrop = { sources -> actions.moveToBreadcrumb(sources, crumb.realFile) }
                                        )
                                    })
                            } else Modifier
                        )
                        .clickable {
                            val rf = crumb.realFile
                            if (rf != null) {
                                if (zipPath != null) actions.navigateToZipParentRealFolder(rf)
                                else actions.navigateTo(rf)
                            } else {
                                actions.navigateToZipInternalPath(crumb.zipInternalPath ?: "")
                            }
                        }
                        .padding(4.dp)) {
                    Text(
                        text = crumb.displayName, style = MaterialTheme.typography.titleLarge
                    )
                }
                if (index < breadcrumbs.size - 1) {
                    IconChevronRight(tint = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}
