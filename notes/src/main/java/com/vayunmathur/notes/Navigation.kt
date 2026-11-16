package com.vayunmathur.notes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.util.FullscreenPage
import com.vayunmathur.library.util.ListDetailPage
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.MorphPage
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.notes.platform.NotesViewModel
import com.vayunmathur.notes.ui.ExternalNoteScreen
import com.vayunmathur.notes.ui.InkEditorPage
import com.vayunmathur.notes.ui.NotePage
import com.vayunmathur.notes.ui.NotesListPage

@Composable
fun Navigation(notesViewModel: NotesViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.NotesList)
    val externalOpens by notesViewModel.externalOpens.collectAsStateWithLifecycle()
    LaunchedEffect(externalOpens) {
        externalOpens.firstOrNull()?.let { uri ->
            notesViewModel.consumeExternal(uri)
            backStack.add(Route.ExternalNote(uri))
        }
    }
    MainNavigation(backStack) {
        entry<Route.NotesList>(metadata = ListPage()) {
            NotesListPage(backStack, notesViewModel)
        }
        entry<Route.Note>(metadata = ListDetailPage() + MorphPage()) {
            NotePage(backStack, notesViewModel, it.id)
        }
        entry<Route.ExternalNote>(metadata = ListDetailPage()) {
            ExternalNoteScreen(backStack, notesViewModel, it.uri)
        }
        entry<Route.InkEditor>(metadata = FullscreenPage()) {
            InkEditorPage(backStack, notesViewModel, it.noteId, it.blockId)
        }
    }
}
