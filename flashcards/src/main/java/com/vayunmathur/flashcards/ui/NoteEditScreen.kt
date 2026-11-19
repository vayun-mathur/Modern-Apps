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
 * The note editor, with no dependency on the ViewModel or the back stack so it can be
 * rendered from a `@Preview`. Draws a note-type dropdown, a deck picker, one
 * [MarkdownEditor] per field of the selected type, and a tags field.
 */
@Composable
fun NoteEditScreen(
    state: NoteEditUiState,
    actions: NoteEditActions,
    onPickImage: (insert: (String) -> Unit) -> Unit = {},
) {
    var typeId by remember { mutableLongStateOf(state.initialNoteTypeId) }
    var deckId by remember { mutableLongStateOf(state.initialDeckId) }
    var tags by remember { mutableStateOf(state.initialTags) }
    var activeField by remember { mutableIntStateOf(-1) }
    // Field values keyed by field name so they survive a note-type switch.
    val values = remember { androidx.compose.runtime.mutableStateMapOf<String, String>() }
    val fieldTfv = remember { mutableStateListOf<TextFieldValue>() }

    fun currentFields(): List<String> = state.noteTypes.firstOrNull { it.id == typeId }?.fieldNames ?: emptyList()

    fun rebuildEditors() {
        val fields = currentFields()
        fieldTfv.clear()
        fields.forEach { name -> fieldTfv.add(TextFieldValue(values[name].orEmpty())) }
    }

    /** Inserts [text] at the active field's cursor (replacing any selection). */
    fun insertAtCursor(text: String) {
        val i = activeField
        if (i !in fieldTfv.indices) return
        val tfv = fieldTfv[i]
        val sel = tfv.selection
        val body = tfv.text
        val newText = body.substring(0, sel.start) + text + body.substring(sel.end)
        fieldTfv[i] = tfv.copy(text = newText, selection = TextRange(sel.start + text.length))
        values[currentFields()[i]] = newText
    }

    /** Wraps the active field's selection in the next `{{cN::…}}` cloze. */
    fun insertCloze() {
        val i = activeField
        if (i !in fieldTfv.indices) return
        val tfv = fieldTfv[i]
        val sel = tfv.selection
        val body = tfv.text
        val next = (Regex("""\{\{c(\d+)::""").findAll(body)
            .mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull() ?: 0) + 1
        val selected = body.substring(sel.start, sel.end)
        val wrapped = "{{c$next::$selected}}"
        val newText = body.substring(0, sel.start) + wrapped + body.substring(sel.end)
        fieldTfv[i] = tfv.copy(text = newText, selection = TextRange(sel.start + wrapped.length))
        values[currentFields()[i]] = newText
    }

    LaunchedEffect(state) {
        typeId = state.initialNoteTypeId
        deckId = state.initialDeckId
        tags = state.initialTags
        values.clear()
        val fields = state.noteTypes.firstOrNull { it.id == typeId }?.fieldNames ?: emptyList()
        fields.forEachIndexed { i, name -> values[name] = state.initialFieldValues.getOrNull(i) ?: "" }
        rebuildEditors()
    }

    AppScaffold(
        title = if (state.isNew) stringResource(R.string.new_card) else stringResource(R.string.edit_card),
        onNavigateBack = { actions.back() },
        actions = {
            if (!state.isNew) {
                IconButton(onClick = { actions.setSuspended(!state.isSuspended) }) {
                    if (state.isSuspended) IconRestore() else IconVisibilityOff()
                }
                IconButton(onClick = { actions.deleteNote() }) { IconDelete() }
            }
            IconButton(onClick = {
                val fields = currentFields()
                actions.save(typeId, deckId, fields.map { values[it].orEmpty() }, tags)
            }) { IconSave() }
        },
        bottomBar = {
            val index = activeField
            if (index in fieldTfv.indices) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                        IconButton(onClick = { onPickImage { name -> insertAtCursor("![]($name)") } }) {
                            IconImage()
                        }
                        TextButton(onClick = { insertCloze() }) {
                            Text(stringResource(R.string.insert_cloze))
                        }
                    }
                    MarkdownFormatToolbar(
                        value = fieldTfv[index],
                        onValueChange = {
                            fieldTfv[index] = it
                            values[currentFields()[index]] = it.text
                        },
                    )
                }
            }
        },
    ) { paddingValues ->
        Column(
            Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            DropdownField(
                label = stringResource(R.string.note_type),
                selected = state.noteTypes.firstOrNull { it.id == typeId }?.name.orEmpty(),
                options = state.noteTypes,
                optionLabel = { it.name },
                onSelect = { config ->
                    typeId = config.id
                    config.fieldNames.forEach { name -> values.getOrPut(name) { "" } }
                    rebuildEditors()
                    activeField = -1
                },
            )
            DropdownField(
                label = stringResource(R.string.deck),
                selected = state.decks.firstOrNull { it.id == deckId }?.name.orEmpty(),
                options = state.decks,
                optionLabel = { it.name },
                onSelect = { deck -> deck.id?.let { deckId = it } },
            )

            currentFields().forEachIndexed { index, name ->
                if (index in fieldTfv.indices) {
                    Text(name, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 12.dp))
                    MarkdownEditor(
                        value = fieldTfv[index],
                        onValueChange = {
                            fieldTfv[index] = it
                            values[name] = it.text
                        },
                        placeholder = name,
                        onFocusChanged = { if (it) activeField = index },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
            }

            OutlinedTextField(
                value = tags,
                onValueChange = { tags = it },
                label = { Text(stringResource(R.string.tags)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 16.dp),
            )
        }
    }
}
