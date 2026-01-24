package com.vayunmathur.notes.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavBackStack
import com.vayunmathur.library.ui.ListPage
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.notes.Route
import com.vayunmathur.notes.data.Note

@Composable
fun NotesListPage(backStack: NavBackStack<Route>, viewModel: DatabaseViewModel) {
    ListPage<Note, Route, Route.Note>(backStack, viewModel, "Notes", {
        Text(it.title)
    }, {
        Text(it.content.substringBefore('\n').take(40))
    }, { Route.Note(it) }, { Route.Note(0) }, isReorderable = true)
}