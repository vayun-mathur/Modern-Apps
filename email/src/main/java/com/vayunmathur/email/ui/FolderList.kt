package com.vayunmathur.email.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.email.data.EmailFolder
import com.vayunmathur.library.ui.LocalContentColor
import com.vayunmathur.library.ui.NavigationDrawerItem
import com.vayunmathur.library.ui.NavigationDrawerItemDefaults
import com.vayunmathur.library.ui.Text

@Composable
fun FolderList(folders: List<EmailFolder>, selectedFolder: String, onSelect: (String) -> Unit) {
    val folderTree = remember(folders) { buildFolderTree(folders) }
    Column {
        folderTree.forEach { root ->
            RenderFolderTree(root, 0, selectedFolder, onSelect)
        }
    }
}

/** A folder node in the hierarchical folder tree with its child folders. */
data class FolderNode(val folder: EmailFolder, val children: List<FolderNode>)

fun buildFolderTree(folders: List<EmailFolder>): List<FolderNode> {
    val childrenMap = folders.groupBy { it.parentFullName }
    fun buildNode(folder: EmailFolder): FolderNode {
        return FolderNode(folder = folder, children = childrenMap[folder.fullName]?.map { buildNode(it) } ?: emptyList())
    }
    return folders.filter { it.parentFullName == null }.map { buildNode(it) }
}

@Composable
fun RenderFolderTree(node: FolderNode, depth: Int, selectedFolder: String, onSelect: (String) -> Unit) {
    NavigationDrawerItem(
        label = { Text(text = node.folder.name, color = if (node.folder.holdsMessages) LocalContentColor.current else LocalContentColor.current.copy(alpha = 0.5f)) },
        selected = node.folder.fullName == selectedFolder,
        onClick = { if (node.folder.holdsMessages) onSelect(node.folder.fullName) },
        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding).padding(start = (depth * 16).dp)
    )
    node.children.forEach { child -> RenderFolderTree(child, depth + 1, selectedFolder, onSelect) }
}
