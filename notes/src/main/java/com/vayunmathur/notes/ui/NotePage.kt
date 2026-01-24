package com.vayunmathur.notes.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconEdit
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.ui.IconVisible
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.pop
import com.vayunmathur.notes.R
import com.vayunmathur.notes.Route
import com.vayunmathur.notes.data.Note

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotePage(backStack: NavBackStack<Route>, viewModel: DatabaseViewModel, noteID: Long) {
    var note by viewModel.getEditable<Note>(noteID) {Note(0, "", "")}
    var isEditing by remember { mutableStateOf(true) }

    Scaffold(topBar = {
        TopAppBar({ }, navigationIcon = {
            IconNavigation(backStack)
        }, actions = {
            IconButton({
                isEditing = !isEditing
            }) {
                if(isEditing) IconVisible() else IconEdit()
            }
            IconButton(onClick = {
                viewModel.delete(note) {
                    backStack.pop()
                }
            }) {
                IconDelete()
            }
        })
    }) { paddingValues ->
        Column(Modifier.padding(paddingValues).padding(horizontal = 16.dp)) {
            BasicTextField(
                note.title,
                { note = note.copy(title = it) },
                Modifier.fillMaxWidth(),
                singleLine = true,
                readOnly = !isEditing,
                textStyle = MaterialTheme.typography.headlineMedium.copy(color = LocalContentColor.current),
                cursorBrush = SolidColor(LocalContentColor.current),
                decorationBox = {innerTextField ->
                    Box(){
                        if(note.title.isEmpty()) Text(text = "Title", style = MaterialTheme.typography.headlineMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant))
                        innerTextField()
                    }
                }
            )
            Spacer(Modifier.height(8.dp))
            BasicTextField(
                note.content,
                { note = note.copy(content = it) },
                Modifier.fillMaxSize(),
                readOnly = !isEditing,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = LocalContentColor.current),
                cursorBrush = SolidColor(LocalContentColor.current),
                decorationBox = { innerTextField ->
                    Box() {
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