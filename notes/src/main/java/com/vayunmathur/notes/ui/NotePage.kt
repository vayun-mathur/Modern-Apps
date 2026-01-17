package com.vayunmathur.notes.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavBackStack
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.pop
import com.vayunmathur.notes.Route
import com.vayunmathur.notes.data.Note

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotePage(backStack: NavBackStack<Route>, viewModel: DatabaseViewModel, noteID: Long) {
    var note by viewModel.getEditable<Note>(noteID) {Note(0, "", "")}

    Scaffold(topBar = {
        TopAppBar({ Text("Note") }, navigationIcon = {
            IconNavigation { backStack.pop() }
        })
    }) { paddingValues ->
        Column(Modifier.padding(paddingValues)) {
            TextField(note.title, { note = note.copy(title = it)}, label = { Text("Title") }, singleLine = true)
            TextField(note.content, { note = note.copy(content = it)}, label = { Text("Content") })
        }
    }
}