package com.vayunmathur.flashcards.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.flashcards.R
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.flashcards.Route
import com.vayunmathur.flashcards.data.NoteTypeKind
import com.vayunmathur.flashcards.platform.FlashcardsViewModel
import com.vayunmathur.flashcards.platform.deleteNoteTypeImpl
import com.vayunmathur.flashcards.platform.saveNoteTypeImpl
import com.vayunmathur.flashcards.ui.NoteTypeEditActions
import com.vayunmathur.flashcards.ui.NoteTypeEditUiState
import com.vayunmathur.flashcards.ui.NoteTypeListActions
import com.vayunmathur.flashcards.ui.NoteTypeListUiState
import com.vayunmathur.flashcards.ui.NoteTypeSummary
import com.vayunmathur.flashcards.ui.TemplateDraft
import com.vayunmathur.flashcards.domain.TemplateEngine
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.ConfirmDialog
import com.vayunmathur.library.ui.FilterChip
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack

@Composable
fun NoteTypeEditScreen(state: NoteTypeEditUiState, actions: NoteTypeEditActions) {
    var name by remember(state) { mutableStateOf(state.name) }
    var css by remember(state) { mutableStateOf(state.css) }
    var type by remember(state) { mutableIntStateOf(state.type) }
    val fields = remember(state) { mutableStateListOf(*state.fields.toTypedArray()) }
    val templates = remember(state) { mutableStateListOf(*state.templates.toTypedArray()) }
    val builtIn = state.id in FlashcardsViewModel.BUILT_IN_NOTE_TYPE_IDS

    AppScaffold(
        title = if (state.isNew) stringResource(R.string.new_note_type) else stringResource(R.string.edit_note_type),
        onNavigateBack = { actions.back() },
        actions = {
            IconButton(onClick = {
                actions.save(name.ifBlank { "Note type" }, css, type, fields.toList(), templates.toList())
            }) { com.vayunmathur.library.ui.IconSave() }
        },
        scrollBehavior = appBarScrollBehavior(),
    ) { paddingValues ->
        Column(
            Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(UiR.string.name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = type == NoteTypeKind.STANDARD,
                    onClick = { type = NoteTypeKind.STANDARD },
                    label = { Text(stringResource(R.string.note_type_standard)) },
                    enabled = !builtIn,
                )
                FilterChip(
                    selected = type == NoteTypeKind.CLOZE,
                    onClick = {
                        type = NoteTypeKind.CLOZE
                        while (templates.size > 1) templates.removeAt(templates.lastIndex)
                    },
                    label = { Text(stringResource(R.string.note_type_cloze)) },
                    enabled = !builtIn,
                )
            }

            // Fields
            Text(
                stringResource(R.string.fields),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
            fields.forEachIndexed { index, fieldName ->
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = fieldName,
                        onValueChange = { fields[index] = it },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { if (index > 0) fields.add(index - 1, fields.removeAt(index)) }) {
                        com.vayunmathur.library.ui.IconKeyboardArrowUp()
                    }
                    IconButton(onClick = { if (fields.size > 1) fields.removeAt(index) }) { IconDelete() }
                }
            }
            OutlinedButton(onClick = { fields.add("Field ${fields.size + 1}") }, modifier = Modifier.padding(top = 4.dp)) {
                IconAdd(); Text(stringResource(R.string.add_field))
            }

            // Templates
            Text(
                stringResource(R.string.templates),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
            templates.forEachIndexed { index, draft ->
                TemplateEditor(
                    draft = draft,
                    canDelete = templates.size > 1 && type != NoteTypeKind.CLOZE,
                    onChange = { templates[index] = it },
                    onDelete = { templates.removeAt(index) },
                    fields = fields.toList(),
                    isCloze = type == NoteTypeKind.CLOZE,
                )
            }
            if (type != NoteTypeKind.CLOZE) {
                OutlinedButton(
                    onClick = { templates.add(TemplateDraft("Card ${templates.size + 1}", "{{Front}}", "{{Back}}")) },
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    IconAdd(); Text(stringResource(R.string.add_template))
                }
            }

            if (!state.isNew && !builtIn) {
                OutlinedButton(onClick = { actions.delete() }, modifier = Modifier.padding(top = 24.dp)) {
                    IconDelete(); Text(stringResource(UiR.string.delete))
                }
            }
        }
    }
}

@Composable
private fun TemplateEditor(
    draft: TemplateDraft,
    canDelete: Boolean,
    onChange: (TemplateDraft) -> Unit,
    onDelete: () -> Unit,
    fields: List<String>,
    isCloze: Boolean,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { onChange(draft.copy(name = it)) },
                    label = { Text(stringResource(R.string.template_name)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                if (canDelete) {
                    IconButton(onClick = onDelete) { IconDelete() }
                }
            }
            OutlinedTextField(
                value = draft.qfmt,
                onValueChange = { onChange(draft.copy(qfmt = it)) },
                label = { Text(stringResource(R.string.template_front)) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            OutlinedTextField(
                value = draft.afmt,
                onValueChange = { onChange(draft.copy(afmt = it)) },
                label = { Text(stringResource(R.string.template_back)) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            NoteTypeEditorSection(draft, fields, isCloze)
        }
    }
}
