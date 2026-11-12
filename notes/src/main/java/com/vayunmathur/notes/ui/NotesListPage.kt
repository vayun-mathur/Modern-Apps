package com.vayunmathur.notes.ui

import androidx.compose.ui.res.stringResource
import com.vayunmathur.notes.R
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.IconDragHandle
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.vayunmathur.library.ui.BackupButtons
import com.vayunmathur.library.room.SqlCipherDbCodec
import com.vayunmathur.library.ui.CommonSearchBar
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.notes.Route
import com.vayunmathur.notes.data.Note
import com.vayunmathur.notes.data.noteDbConfigs
import com.vayunmathur.notes.platform.NotesListActions
import com.vayunmathur.notes.platform.NotesListUiState
import com.vayunmathur.notes.platform.NotesViewModel
import com.vayunmathur.library.ui.ReorderableItem
import com.vayunmathur.library.ui.rememberReorderableLazyListState
import com.vayunmathur.library.ui.reorderDragHandle

/** Binds [NotesViewModel] and the nav back stack to the stateless [NotesListScreen]. */
@Composable
fun NotesListPage(backStack: NavBackStack<Route>, viewModel: NotesViewModel) {
    val context = LocalContext.current
    val notes by viewModel.notes.collectAsStateWithLifecycle()

    val actions = remember(backStack, viewModel) {
        object : NotesListActions {
            override fun openNote(id: Long) { backStack.add(Route.Note(id)) }
            override fun createNote() { backStack.add(Route.Note(0)) }
            override fun delete(note: Note) { viewModel.delete(note) }
            override fun upsertAll(notes: List<Note>) { viewModel.upsertAll(notes) }
            override fun renameTag(from: String, to: String) { viewModel.renameTag(from, to) }
            override fun deleteTag(tag: String) { viewModel.deleteTag(tag) }
        }
    }

    NotesListScreen(
        state = NotesListUiState(notes = notes, showAddButton = backStack.last() !is Route.Note),
        actions = actions,
        // Passed in rather than built inside the screen: the backup buttons need the
        // database passphrase, which only exists on a real device.
        backupButtons = {
            BackupButtons(
                dbConfigs = remember { noteDbConfigs(context) },
                dbCodec = SqlCipherDbCodec,
                extraFiles = remember { com.vayunmathur.notes.platform.NoteImageStore.backupFiles(context) },
                extraFilesMapping = remember {
                    val dir = com.vayunmathur.notes.platform.NoteImageStore.backupFiles(context).single()
                    mapOf(dir.name to dir)
                }
            )
        },
    )
}
