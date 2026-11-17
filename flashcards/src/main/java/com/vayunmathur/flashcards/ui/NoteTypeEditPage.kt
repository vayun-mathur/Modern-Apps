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

/** Binds the editor for the note type with [noteTypeId] (0 for a new one). */
@Composable
fun NoteTypeEditPage(backStack: NavBackStack<Route>, viewModel: FlashcardsViewModel, noteTypeId: Long) {
    val noteTypes by viewModel.noteTypes.collectAsStateWithLifecycle()
    val cfg = noteTypes.firstOrNull { it.noteType.id == noteTypeId }
    val isNew = noteTypeId == 0L

    val actions = remember(backStack, viewModel, noteTypeId) {
        object : NoteTypeEditActions {
            override fun back() { backStack.pop() }
            override fun save(name: String, css: String, type: Int, fields: List<String>, templates: List<TemplateDraft>) {
                viewModel.saveNoteTypeImpl(noteTypeId, name, css, type, fields, templates)
                backStack.pop()
            }
            override fun delete() {
                viewModel.deleteNoteTypeImpl(noteTypeId)
                backStack.pop()
            }
        }
    }

    NoteTypeEditScreen(
        state = NoteTypeEditUiState(
            id = noteTypeId,
            name = cfg?.noteType?.name.orEmpty(),
            css = cfg?.noteType?.css.orEmpty(),
            type = cfg?.noteType?.type ?: NoteTypeKind.STANDARD,
            fields = cfg?.fields?.map { it.name } ?: listOf("Front", "Back"),
            templates = cfg?.templates?.map { TemplateDraft(it.name, it.qfmt, it.afmt) }
                ?: listOf(TemplateDraft("Card 1", "{{Front}}", "{{FrontSide}}\n\n---\n\n{{Back}}")),
            isNew = isNew,
        ),
        actions = actions,
    )
}
