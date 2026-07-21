package com.vayunmathur.notes.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.ui.OdfMarkdownEditor
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.notes.R
import com.vayunmathur.notes.Route
import com.vayunmathur.notes.util.NotesViewModel

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
    val context = LocalContext.current

    var loaded by remember(uri) { mutableStateOf(false) }
    var title by remember(uri) { mutableStateOf("") }
    var content by remember(uri) { mutableStateOf("") }

    LaunchedEffect(uri) {
        val result = notesViewModel.readExternal(uri)
        if (result == null) {
            Toast.makeText(context, context.getString(R.string.external_read_failed), Toast.LENGTH_SHORT).show()
            backStack.pop()
            return@LaunchedEffect
        }
        title = result.title
        content = result.content
        loaded = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title.ifBlank { stringResource(R.string.title) }) },
                navigationIcon = { IconNavigation { backStack.pop() } },
                actions = {
                    IconButton(onClick = {
                        notesViewModel.saveExternal(uri, content) { ok ->
                            val msg = if (ok) R.string.external_saved else R.string.external_save_failed
                            Toast.makeText(context, context.getString(msg), Toast.LENGTH_SHORT).show()
                        }
                    }) { IconSave() }
                    IconButton(onClick = {
                        notesViewModel.addExternalToApp(title, content) { id ->
                            backStack.pop()
                            backStack.add(Route.Note(id))
                        }
                    }) { IconAdd() }
                },
            )
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
