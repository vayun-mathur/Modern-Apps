package com.vayunmathur.files.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.vayunmathur.files.platform.FilesActions
import com.vayunmathur.files.platform.FilesUiState
import com.vayunmathur.library.ui.VerticalDivider
import com.vayunmathur.library.ui.isExpandedWidth

/**
 * Directory listing pane(s): the expanded-width parent pane beside the
 * listing, or the listing alone. Extracted from [DirectoryScreen] to keep
 * that file under the length limit. Behavior identical — only moved.
 */
@Composable
fun DirectoryBody(
    state: FilesUiState,
    actions: FilesActions,
    canModifyHere: Boolean,
    isReadOnly: Boolean,
    isCategory: Boolean,
    padding: PaddingValues,
    onClearRenameTarget: () -> Unit,
) {
    // Expanded windows (desktop, large tablets) list the parent folder beside the current
    // one. This stays inside the same destination on purpose: files deliberately has no
    // list/detail metadata, so one back-stack entry per folder level is what keeps Back
    // walking up exactly one folder with a predictive-back preview of where it lands. A
    // second destination would split that walk. Categories and zip contents have no parent
    // folder, the root has nothing above it, and compact widths keep the push behavior -
    // all of those stay single-pane exactly as before.
    DirectoryBody(
        currentDirectory = state.currentDirectory,
        rootDirectory = state.rootDirectory,
        isCategory = isCategory,
        isReadOnly = isReadOnly,
        padding = padding,
        parentPane = { modifier ->
            DirectoryParentPane(state = state, actions = actions, modifier = modifier)
        },
        listing = { modifier ->
            DirectoryListing(
                state = state,
                actions = actions,
                canModifyHere = canModifyHere,
                isReadOnly = isReadOnly,
                modifier = modifier,
                onClearRenameTarget = onClearRenameTarget,
            )
        },
    )
}

@Composable
fun DirectoryBody(
    currentDirectory: java.io.File,
    rootDirectory: java.io.File,
    isCategory: Boolean,
    isReadOnly: Boolean,
    padding: PaddingValues,
    parentPane: @Composable (Modifier) -> Unit,
    listing: @Composable (Modifier) -> Unit,
) {
    val atRoot = currentDirectory.absolutePath == rootDirectory.absolutePath
    if (isExpandedWidth() && !isCategory && !isReadOnly && !atRoot) {
        Row(Modifier.fillMaxSize().padding(padding)) {
            parentPane(Modifier.weight(0.35f).fillMaxHeight())
            VerticalDivider(modifier = Modifier.fillMaxHeight())
            listing(Modifier.weight(0.65f).fillMaxHeight())
        }
    } else {
        listing(Modifier.fillMaxSize().padding(padding))
    }
}
