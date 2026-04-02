package com.vayunmathur.notes.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconEdit
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.IconShare
import com.vayunmathur.library.ui.IconVisible
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.notes.parseMarkdown
import com.vayunmathur.notes.Route
import com.vayunmathur.notes.data.Note
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotePage(backStack: NavBackStack<Route>, viewModel: DatabaseViewModel, noteID: Long) {
    var note by viewModel.getEditable<Note>(noteID) {Note(0, "", "")}
    var isEditing by remember { mutableStateOf(true) }

    val context = LocalContext.current

    Scaffold(topBar = {
        TopAppBar({ }, navigationIcon = {
            IconNavigation(backStack)
        }, actions = {
            IconButton({
                isEditing = !isEditing
            }) {
                if(isEditing) IconVisible() else IconEdit()
            }
            IconButton({
                val fileUri = getTmpFileUri(context, note.title, note.content)

                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/markdown"
                    putExtra(Intent.EXTRA_STREAM, fileUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "Share Note"))
            }) {
                IconShare()
            }
            IconButton(onClick = {
                viewModel.delete(note)
                backStack.pop()
            }) {
                IconDelete()
            }
        })
    }) { paddingValues ->
        LazyColumn(contentPadding = paddingValues + PaddingValues(horizontal = 16.dp) + PaddingValues(bottom = 16.dp)) {
            item {
                BasicTextField(
                    note.title,
                    { note = note.copy(title = it) },
                    Modifier.fillMaxWidth(),
                    singleLine = true,
                    readOnly = !isEditing,
                    textStyle = MaterialTheme.typography.headlineMedium.copy(color = LocalContentColor.current),
                    cursorBrush = SolidColor(LocalContentColor.current),
                    decorationBox = { innerTextField ->
                        Box {
                            if (note.title.isEmpty()) Text(
                                text = "Title",
                                style = MaterialTheme.typography.headlineMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                            )
                            innerTextField()
                        }
                    }
                )
            }
            item {
                Spacer(Modifier.height(8.dp))
            }
            item {
                var value by remember(note.id) {
                    mutableStateOf(
                        TextFieldValue(
                            parseMarkdown(note.content)
                        )
                    )
                }
                val noMarkers by remember {
                    derivedStateOf {
                        TextFieldValue(
                            parseMarkdown(
                                note.content,
                                false
                            )
                        )
                    }
                }
                BasicTextField(
                    if (isEditing) value else noMarkers,
                    {
                        note = note.copy(content = it.text)
                        value = it.copy(annotatedString = parseMarkdown(it.text))
                    },
                    Modifier.fillMaxSize(),
                    readOnly = !isEditing,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = LocalContentColor.current),
                    cursorBrush = SolidColor(LocalContentColor.current),
                    decorationBox = { innerTextField ->
                        Box {
                            if (note.content.isEmpty()) Text(
                                text = "Content",
                                style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                            )
                            innerTextField()
                        }
                    }
                )
            }
        }
    }
}

fun getTmpFileUri(context: Context, fileName: String, content: String): Uri {
    val cachePath = File(context.cacheDir, "shared_notes")
    cachePath.mkdirs() // Create folder if it doesn't exist

    val file = File(cachePath, "$fileName.md")
    file.writeText(content) // Write your DB string to the file

    return FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )
}