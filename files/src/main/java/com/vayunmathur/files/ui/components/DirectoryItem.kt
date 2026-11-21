package com.vayunmathur.files.ui.components

import android.content.ClipData
import android.content.ClipDescription
import android.text.format.Formatter
import android.view.View
import android.webkit.MimeTypeMap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.vayunmathur.files.platform.FileBrowserItem
import com.vayunmathur.files.ui.FileLeading
import com.vayunmathur.files.ui.dropTarget
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DirectoryItem(
    file: FileBrowserItem,
    isSelected: Boolean,
    isReadOnly: Boolean,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
    onMove: (List<File>) -> Unit,
    onStartDrag: () -> List<File>,
    /** Parent-path subtitle for recursive-search hits; null in normal browsing. */
    subtitle: String? = null,
) {
    var isDraggingOver by remember { mutableStateOf(false) }
    val currentOnMove by rememberUpdatedState(onMove)
    val currentOnStartDrag by rememberUpdatedState(onStartDrag)

    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isDraggingOver) MaterialTheme.colorScheme.primaryContainer
                else if (isSelected) MaterialTheme.colorScheme.surfaceVariant
                else Color.Transparent
            )
            .dragAndDropSource { _ ->
                val paths = currentOnStartDrag()
                if (paths.isEmpty()) return@dragAndDropSource null

                val uris = try {
                    paths.map { path ->
                        FileProvider.getUriForFile(
                            context, "${context.packageName}.fileprovider", path
                        )
                    }
                } catch (_: Exception) {
                    return@dragAndDropSource null
                }
                val mimeTypes = paths.map { path ->
                    val extension = path.name.substringAfterLast(
                        '.', ""
                    )
                    if (extension == "md") "text/markdown"
                    else MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                        extension
                    ) ?: "*/*"
                }.toMutableList().apply {
                    add(
                        ClipDescription.MIMETYPE_TEXT_PLAIN
                    )
                }.distinct().toTypedArray()

                val clipData = ClipData(
                    paths.first().name, mimeTypes, ClipData.Item(
                        paths.first().absolutePath, null, null, uris.first()
                    )
                )
                for (i in 1 until uris.size) {
                    clipData.addItem(
                        ClipData.Item(
                            paths[i].absolutePath, null, null, uris[i]
                        )
                    )
                }

                DragAndDropTransferData(
                    clipData = clipData,
                    flags = View.DRAG_FLAG_GLOBAL or View.DRAG_FLAG_GLOBAL_URI_READ
                )
            }
            .then(
                if (file.isDirectory && !isReadOnly && file.realFile != null) {
                    Modifier.dragAndDropTarget(shouldStartDragAndDrop = { event ->
                        event.mimeTypes().contains(
                            ClipDescription.MIMETYPE_TEXT_PLAIN
                        )
                    }, target = remember(file.key) {
                        dropTarget(
                            onDragStateChange = { isDraggingOver = it },
                            onDrop = { currentOnMove(it) }
                        )
                    })
                } else Modifier
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        ListItem(
            content = { Text(file.name.ifEmpty { "/" }) },
            leadingContent = { FileLeading(file, isSelected, 40.dp) },
            supportingContent = {
                if (subtitle != null) {
                    Text(subtitle)
                } else if (!file.isDirectory) {
                    file.size?.let { size -> Text(Formatter.formatShortFileSize(context, size)) }
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }
}
