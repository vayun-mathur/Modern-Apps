package com.vayunmathur.flashcards.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.flashcards.R
import com.vayunmathur.flashcards.Route
import com.vayunmathur.flashcards.ui.DeckOption
import com.vayunmathur.flashcards.platform.FlashcardsViewModel
import com.vayunmathur.flashcards.platform.saveNoteImpl
import com.vayunmathur.flashcards.platform.deleteNoteImpl
import com.vayunmathur.flashcards.platform.setNoteSuspendedImpl
import com.vayunmathur.flashcards.platform.MediaStore
import com.vayunmathur.flashcards.platform.noteByIdImpl
import com.vayunmathur.flashcards.ui.NoteEditActions
import com.vayunmathur.flashcards.ui.NoteEditUiState
import com.vayunmathur.flashcards.ui.NoteTypeConfig
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconImage
import com.vayunmathur.library.ui.IconRestore
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.ui.IconVisibilityOff
import com.vayunmathur.library.ui.MarkdownEditor
import com.vayunmathur.library.ui.MarkdownFormatToolbar
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Binds the note with [noteId] (0 for a new note) in [deckId] to the stateless
 * [NoteEditScreen]: resolves the available note types + decks and saves via the ViewModel.
 */
@Composable
fun NoteEditPage(
    backStack: NavBackStack<Route>,
    viewModel: FlashcardsViewModel,
    deckId: Long,
    noteId: Long,
) {
    val dbNote by remember(noteId) { viewModel.noteByIdImpl(noteId) }
        .collectAsStateWithLifecycle(null)
    val noteTypes by viewModel.noteTypes.collectAsStateWithLifecycle()
    val decks by viewModel.decks.collectAsStateWithLifecycle()
    val allCards by viewModel.cards.collectAsStateWithLifecycle()

    val configs = noteTypes.map { NoteTypeConfig(it.noteType.id, it.noteType.name, it.fields.map { f -> f.name }) }
    val isNew = noteId == 0L
    val noteCards = allCards.filter { it.noteId == noteId }
    val suspended = noteCards.isNotEmpty() && noteCards.all { it.isSuspended }

    val context = LocalContext.current
    val media = remember { MediaStore(context) }
    val scope = rememberCoroutineScope()
    var pendingInsert by remember { mutableStateOf<((String) -> Unit)?>(null) }
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        val callback = pendingInsert
        pendingInsert = null
        if (uri != null && callback != null) {
            scope.launch {
                val name = withContext(Dispatchers.IO) { media.import(context, uri) }
                if (name != null) callback(name)
            }
        }
    }

    val actions = remember(backStack, viewModel, deckId, noteId) {
        object : NoteEditActions {
            override fun back() { backStack.pop() }
            override fun save(noteTypeId: Long, deckId: Long, fieldValues: List<String>, tags: String) {
                viewModel.saveNoteImpl(noteId, noteTypeId, deckId, fieldValues, tags)
                backStack.pop()
            }
            override fun deleteNote() {
                dbNote?.let { viewModel.deleteNoteImpl(it) }
                backStack.pop()
            }
            override fun setSuspended(suspended: Boolean) {
                viewModel.setNoteSuspendedImpl(noteId, suspended)
            }
        }
    }

    NoteEditScreen(
        state = NoteEditUiState(
            initialNoteTypeId = dbNote?.noteTypeId ?: configs.firstOrNull()?.id ?: FlashcardsViewModel.BASIC_NOTE_TYPE_ID,
            initialDeckId = dbNote?.deckId ?: deckId,
            initialFieldValues = dbNote?.fieldList ?: emptyList(),
            initialTags = dbNote?.tags.orEmpty(),
            isNew = isNew,
            isSuspended = suspended,
            noteTypes = configs,
            decks = decks.map { DeckOption(it.id, it.name) },
        ),
        actions = actions,
        onPickImage = { insert ->
            pendingInsert = insert
            imagePicker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        },
    )
}


@Composable
internal fun <T> DropdownField(
    label: String,
    selected: String,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.fillMaxWidth().clickable { expanded = true }.padding(vertical = 8.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(selected.ifBlank { "—" }, style = MaterialTheme.typography.bodyLarge)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}
