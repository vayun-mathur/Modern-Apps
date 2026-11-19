package com.vayunmathur.notes.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.ui.OdfMarkdownEditor
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.notes.R
import com.vayunmathur.notes.Route
import com.vayunmathur.notes.platform.NotesViewModel

/**
 * Standalone markdown editor for a file opened from outside the app (VIEW/EDIT/SEND).
 *
 * The file is NOT added to the app database on open. It behaves like a normal
 * markdown file editor: edits are held in memory and written back to the original
 * file only when the user taps Save. An explicit "Add to app" action imports the
 * current content as a real note and opens it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExternalNoteScreen(
    backStack: NavBackStack<Route>,
    notesViewModel: NotesViewModel,
    uri: String,
) {
    val readFailedMsg = stringResource(R.string.external_read_failed)
    val savedMsg = stringResource(R.string.external_saved)
    val saveFailedMsg = stringResource(R.string.external_save_failed)

    var loaded by remember(uri) { mutableStateOf(false) }
    var title by remember(uri) { mutableStateOf("") }
    var content by remember(uri) { mutableStateOf("") }

    LaunchedEffect(uri) {
        val result = notesViewModel.readExternal(uri)
        if (result == null) {
            AppMessages.show(readFailedMsg)
            backStack.pop()
            return@LaunchedEffect
        }
        title = result.title
        content = result.content
        loaded = true
    }

    AppScaffold(
        title = title.ifBlank { stringResource(R.string.title) },
        onNavigateBack = { backStack.pop() },
        actions = {
            IconButton(onClick = {
                notesViewModel.saveExternal(uri, content) { ok ->
                    AppMessages.show(if (ok) savedMsg else saveFailedMsg)
                }
            }) { IconSave() }
            IconButton(onClick = {
                notesViewModel.addExternalToApp(title, content) { id ->
                    backStack.pop()
                    backStack.add(Route.Note(id))
                }
            }) { IconAdd() }
        },
    ) { paddingValues ->
        if (loaded) {
            OdfMarkdownEditor(
                initialMarkdown = content,
                onMarkdownChanged = { content = it },
                modifier = Modifier.fillMaxSize().padding(paddingValues),
            )
        } else {
            Box(Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }
}
