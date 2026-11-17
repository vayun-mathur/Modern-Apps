package com.vayunmathur.flashcards.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.flashcards.R
import com.vayunmathur.flashcards.Route
import com.vayunmathur.flashcards.data.Note
import com.vayunmathur.flashcards.ui.DeckOption
import com.vayunmathur.flashcards.platform.FlashcardsViewModel
import com.vayunmathur.flashcards.platform.exportApkgImpl
import com.vayunmathur.flashcards.platform.importApkgImpl
import com.vayunmathur.flashcards.platform.importCsvImpl
import com.vayunmathur.flashcards.platform.deleteNoteImpl
import com.vayunmathur.flashcards.platform.reorderNotesImpl
import com.vayunmathur.flashcards.platform.exportCsvImpl
import com.vayunmathur.flashcards.platform.deleteNotesImpl
import com.vayunmathur.flashcards.ui.NoteListActions
import com.vayunmathur.flashcards.platform.cardsForImpl
import com.vayunmathur.flashcards.platform.notesForImpl
import com.vayunmathur.flashcards.platform.moveNotesImpl
import com.vayunmathur.flashcards.platform.addTagImpl
import com.vayunmathur.flashcards.platform.removeTagImpl
import com.vayunmathur.flashcards.platform.setNotesSuspendedImpl
import com.vayunmathur.flashcards.platform.resetSchedulingForNotesImpl
import com.vayunmathur.flashcards.ui.NoteListUiState
import com.vayunmathur.flashcards.ui.NoteRow
import com.vayunmathur.flashcards.ui.StudyParams
import com.vayunmathur.library.util.NavBackStack

/** Binds the deck with [deckId] to the stateless [NoteListScreen]. */
@Composable
fun NoteListPage(
    backStack: NavBackStack<Route>,
    viewModel: FlashcardsViewModel,
    deckId: Long,
) {
    val context = LocalContext.current
    val shareTitle = androidx.compose.ui.res.stringResource(R.string.share_deck)
    val decks by viewModel.decks.collectAsStateWithLifecycle()
    val notes by remember(deckId) { viewModel.notesForImpl(deckId) }
        .collectAsStateWithLifecycle(emptyList())
    val cards by remember(deckId) { viewModel.cardsForImpl(deckId) }
        .collectAsStateWithLifecycle(emptyList())

    val deckName = decks.firstOrNull { it.id == deckId }?.name ?: ""
    val now = System.currentTimeMillis()
    val cardsByNote = cards.groupBy { it.noteId }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let {
            val name = queryFileName(context, it).orEmpty()
            if (name.endsWith(".apkg", true) || isZip(context, it)) {
                viewModel.importApkgImpl(it)
            } else {
                viewModel.importCsvImpl(deckId, it)
            }
        }
    }

    LaunchedEffect(viewModel, deckId) {
        viewModel.shareRequests.collect { uri ->
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(intent, shareTitle),
            )
        }
    }

    val actions = remember(backStack, viewModel, deckId) {
        object : NoteListActions {
            override fun back() { backStack.pop() }
            override fun openNote(id: Long) {
                val route = Route.NoteEdit(deckId, id)
                // Re-tapping another note replaces the editor instead of stacking
                // NoteEdit on NoteEdit.
                if (backStack.last() is Route.NoteEdit) backStack.setLast(route) else backStack.add(route)
            }
            override fun addNote() {
                val route = Route.NoteEdit(deckId, 0)
                if (backStack.last() is Route.NoteEdit) backStack.setLast(route) else backStack.add(route)
            }
            override fun deleteNote(note: Note) { viewModel.deleteNoteImpl(note) }
            override fun study(tags: Set<String>) {
                backStack.add(Route.Review(deckId, tags = tags.toList()))
            }
            override fun customStudy(params: StudyParams, tags: Set<String>) {
                backStack.add(
                    Route.Review(
                        deckId = deckId,
                        mode = params.mode.ordinal,
                        count = params.count,
                        daysAhead = params.daysAhead,
                        tags = tags.toList(),
                    ),
                )
            }
            override fun reorder(notes: List<Note>) { viewModel.reorderNotesImpl(notes) }
            override fun openStats() { backStack.add(Route.Stats) }
            override fun share() { viewModel.exportApkgImpl(deckId) }
            override fun exportCsv() { viewModel.exportCsvImpl(deckId) }
            override fun deleteNotes(ids: List<Long>) { viewModel.deleteNotesImpl(ids) }
            override fun moveNotes(ids: List<Long>, deckId: Long) { viewModel.moveNotesImpl(ids, deckId) }
            override fun addTag(ids: List<Long>, tag: String) { viewModel.addTagImpl(ids, tag) }
            override fun removeTag(ids: List<Long>, tag: String) { viewModel.removeTagImpl(ids, tag) }
            override fun setSuspended(ids: List<Long>, suspended: Boolean) {
                viewModel.setNotesSuspendedImpl(ids, suspended)
            }
            override fun resetScheduling(ids: List<Long>) { viewModel.resetSchedulingForNotesImpl(ids) }
        }
    }

    val rows = notes.sortedBy { it.position }.map { note ->
        val noteCards = cardsByNote[note.id].orEmpty()
        NoteRow(
            note = note,
            cardCount = noteCards.size,
            suspended = noteCards.isNotEmpty() && noteCards.all { it.isSuspended },
        )
    }
    val allTags = notes
        .flatMap { it.tags.split(" ") }
        .filter { it.isNotBlank() }
        .distinct()
        .sorted()

    NoteListScreen(
        state = NoteListUiState(
            deckName = deckName,
            notes = rows,
            dueCount = cards.count { !it.isSuspended && ((!it.isNew && it.dueDate <= now) || it.isNew) },
            tags = allTags,
            decks = decks.filter { it.id != deckId }.map { DeckOption(it.id, it.name) },
        ),
        actions = actions,
        deckNameSharedKey = "flashcards-deck-name-$deckId",
        onImport = {
            importLauncher.launch(
                arrayOf(
                    "application/octet-stream",
                    "application/zip",
                    "text/csv",
                    "text/comma-separated-values",
                    "text/plain",
                    "*/*",
                ),
            )
        },
    )
}
