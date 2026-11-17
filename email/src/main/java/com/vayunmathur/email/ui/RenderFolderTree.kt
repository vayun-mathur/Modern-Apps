package com.vayunmathur.email.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.LocalContentColor
import com.vayunmathur.library.ui.NavigationDrawerItem
import com.vayunmathur.library.ui.NavigationDrawerItemDefaults
import com.vayunmathur.library.ui.Text

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
