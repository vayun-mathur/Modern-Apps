package com.vayunmathur.flashcards.util

import android.app.Application
import com.vayunmathur.flashcards.data.Card
import com.vayunmathur.flashcards.data.CardState
import com.vayunmathur.flashcards.data.CardTemplate
import com.vayunmathur.flashcards.data.FIELD_SEPARATOR
import com.vayunmathur.flashcards.data.Note
import com.vayunmathur.flashcards.data.NoteType
import com.vayunmathur.flashcards.data.NoteTypeKind
import com.vayunmathur.flashcards.data.deleteCardsByNoteImpl
import com.vayunmathur.flashcards.data.deleteCardsByNotesImpl
import com.vayunmathur.flashcards.data.deleteFieldsByNoteTypeImpl
import com.vayunmathur.flashcards.data.deleteNoteImpl
import com.vayunmathur.flashcards.data.deleteNoteTypeImpl
import com.vayunmathur.flashcards.data.deleteNotesByIdsImpl
import com.vayunmathur.flashcards.data.deleteReviewLogsByCardsImpl
import com.vayunmathur.flashcards.data.deleteTemplatesByNoteTypeImpl
import com.vayunmathur.flashcards.data.getCardsByDeckImpl
import com.vayunmathur.flashcards.data.getCardsByNoteImpl
import com.vayunmathur.flashcards.data.getCardsByNotesImpl
import com.vayunmathur.flashcards.data.getFieldsForNoteTypeImpl
import com.vayunmathur.flashcards.data.getNoteImpl
import com.vayunmathur.flashcards.data.getNoteTypeImpl
import com.vayunmathur.flashcards.data.getNotesByDeckImpl
import com.vayunmathur.flashcards.data.getNotesByIdsImpl
import com.vayunmathur.flashcards.data.getNotesByNoteTypeImpl
import com.vayunmathur.flashcards.data.getReviewLogsByCardImpl
import com.vayunmathur.flashcards.data.getTemplatesForNoteTypeImpl
import com.vayunmathur.flashcards.data.insertReviewLogImpl
import com.vayunmathur.flashcards.data.moveCardsByNotesImpl
import com.vayunmathur.flashcards.data.regenerateCardsImpl
import com.vayunmathur.flashcards.data.setCardsSuspendedByNotesImpl
import com.vayunmathur.flashcards.data.setCardsSuspendedImpl
import com.vayunmathur.flashcards.data.upsertCardTemplatesImpl
import com.vayunmathur.flashcards.data.upsertCardsImpl
import com.vayunmathur.flashcards.data.upsertNoteImpl
import com.vayunmathur.flashcards.data.upsertNoteTypeFieldsImpl
import com.vayunmathur.flashcards.data.upsertNoteTypeImpl
import com.vayunmathur.flashcards.data.upsertNotesImpl
import com.vayunmathur.flashcards.R
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.util.AppMessages

// ------------------------------------------------------------------
// Note writes + bulk operations + note-type CRUD + scheduling reset —
// moved from FlashcardsViewModel.kt (TooManyFunctions/LargeClass split);
// behavior identical.
// ------------------------------------------------------------------

internal fun FlashcardsViewModel.saveNoteImpl(
    noteId: Long,
    noteTypeId: Long,
    deckId: Long,
    fieldValues: List<String>,
    tags: String,
) = launchIoImpl {
    val cfg = noteTypes.value.firstOrNull { it.noteType.id == noteTypeId } ?: return@launchIoImpl
    val flds = fieldValues.joinToString(FIELD_SEPARATOR)
    val sortField = fieldValues.firstOrNull().orEmpty()
    val existing = if (noteId != 0L) repository.getNoteImpl(noteId) else null
    val position = existing?.position
        ?: ((repository.getNotesByDeckImpl(deckId).maxOfOrNull { it.position } ?: 0.0) + 1.0)
    val note = Note(
        id = noteId,
        noteTypeId = noteTypeId,
        deckId = deckId,
        guid = existing?.guid ?: FlashcardsViewModel.randomGuid(),
        flds = flds,
        sortField = sortField,
        tags = tags.trim(),
        mod = FlashcardsViewModel.nowSeconds(),
        position = position,
    )
    val savedId = repository.upsertNoteImpl(note)
    val finalNote = if (noteId == 0L) note.copy(id = savedId) else note
    repository.regenerateCardsImpl(finalNote, cfg.noteType, cfg.templates, cfg.fields)
    // Keep generated cards in the note's deck (handles a note being moved decks).
    val misplaced = repository.getCardsByNoteImpl(finalNote.id).filter { it.deckId != finalNote.deckId }
    if (misplaced.isNotEmpty()) {
        repository.upsertCardsImpl(misplaced.map { it.copy(deckId = finalNote.deckId) })
    }
}

internal fun FlashcardsViewModel.deleteNoteImpl(note: Note) = launchIoImpl {
    val cards = repository.getCardsByNoteImpl(note.id)
    val logs = cards.flatMap { repository.getReviewLogsByCardImpl(it.id) }
    repository.deleteCardsByNoteImpl(note.id)
    if (cards.isNotEmpty()) repository.deleteReviewLogsByCardsImpl(cards.map { it.id })
    repository.deleteNoteImpl(note)
    AppMessages.show(
        getApplication<Application>().getString(R.string.deleted),
        actionLabel = getApplication<Application>().getString(UiR.string.undo),
        duration = AppMessages.Duration.Long,
    ) {
        launchIoImpl {
            repository.upsertNoteImpl(note)
            if (cards.isNotEmpty()) repository.upsertCardsImpl(cards)
            logs.forEach { repository.insertReviewLogImpl(it) }
        }
    }
}

/** Suspends or unsuspends every card of [noteId], and tracks the leech tag. */
internal fun FlashcardsViewModel.setNoteSuspendedImpl(noteId: Long, suspended: Boolean) = launchIoImpl {
    repository.setCardsSuspendedByNotesImpl(listOf(noteId), if (suspended) 1 else 0)
}

/** Suspends the card currently shown in the review session and advances. */
internal fun FlashcardsViewModel.suspendCurrentCardImpl() = launchIoImpl {
    val s = session ?: return@launchIoImpl
    val card = s.removeCurrent() ?: return@launchIoImpl
    reviewLogUndoStack.addLast(null)
    repository.setCardsSuspendedImpl(listOf(card.id), 1)
    publishReviewImpl()
}

internal fun FlashcardsViewModel.reorderNotesImpl(notes: List<Note>) =
    launchIoImpl { repository.upsertNotesImpl(notes) }

/** Deletes multiple notes (and their cards/logs) with a single undo action. */
internal fun FlashcardsViewModel.deleteNotesImpl(noteIds: List<Long>) = launchIoImpl {
    if (noteIds.isEmpty()) return@launchIoImpl
    val notes = repository.getNotesByIdsImpl(noteIds)
    val cards = repository.getCardsByNotesImpl(noteIds)
    val logs = cards.flatMap { repository.getReviewLogsByCardImpl(it.id) }
    repository.deleteCardsByNotesImpl(noteIds)
    if (cards.isNotEmpty()) repository.deleteReviewLogsByCardsImpl(cards.map { it.id })
    repository.deleteNotesByIdsImpl(noteIds)
    AppMessages.show(
        getApplication<Application>().getString(R.string.deleted),
        actionLabel = getApplication<Application>().getString(UiR.string.undo),
        duration = AppMessages.Duration.Long,
    ) {
        launchIoImpl {
            repository.upsertNotesImpl(notes)
            if (cards.isNotEmpty()) repository.upsertCardsImpl(cards)
            logs.forEach { repository.insertReviewLogImpl(it) }
        }
    }
}

/** Moves notes (and their cards) to another deck. */
internal fun FlashcardsViewModel.moveNotesImpl(noteIds: List<Long>, deckId: Long) = launchIoImpl {
    if (noteIds.isEmpty()) return@launchIoImpl
    val notes = repository.getNotesByIdsImpl(noteIds)
    var position = repository.getNotesByDeckImpl(deckId).maxOfOrNull { it.position } ?: 0.0
    val moved = notes.map { note ->
        position += 1.0
        note.copy(deckId = deckId, position = position)
    }
    repository.upsertNotesImpl(moved)
    repository.moveCardsByNotesImpl(noteIds, deckId)
}

internal fun FlashcardsViewModel.addTagImpl(noteIds: List<Long>, tag: String) = launchIoImpl {
    val clean = tag.trim()
    if (clean.isEmpty() || noteIds.isEmpty()) return@launchIoImpl
    val updated = repository.getNotesByIdsImpl(noteIds).map { note ->
        val tags = note.tags.split(" ").filter { it.isNotBlank() }.toMutableSet()
        tags.add(clean)
        note.copy(tags = tags.joinToString(" "))
    }
    repository.upsertNotesImpl(updated)
}

internal fun FlashcardsViewModel.removeTagImpl(noteIds: List<Long>, tag: String) = launchIoImpl {
    val clean = tag.trim()
    if (clean.isEmpty() || noteIds.isEmpty()) return@launchIoImpl
    val updated = repository.getNotesByIdsImpl(noteIds).map { note ->
        val tags = note.tags.split(" ").filter { it.isNotBlank() && it != clean }
        note.copy(tags = tags.joinToString(" "))
    }
    repository.upsertNotesImpl(updated)
}

internal fun FlashcardsViewModel.setNotesSuspendedImpl(noteIds: List<Long>, suspended: Boolean) =
    launchIoImpl {
        if (noteIds.isEmpty()) return@launchIoImpl
        repository.setCardsSuspendedByNotesImpl(noteIds, if (suspended) 1 else 0)
    }

/** Resets scheduling for every card of the given notes back to new. */
internal fun FlashcardsViewModel.resetSchedulingForNotesImpl(noteIds: List<Long>) = launchIoImpl {
    if (noteIds.isEmpty()) return@launchIoImpl
    val cards = repository.getCardsByNotesImpl(noteIds)
    resetCardsImpl(cards)
}

/** Resets scheduling for every card in a deck back to new. */
internal fun FlashcardsViewModel.resetSchedulingForDeckImpl(deckId: Long) = launchIoImpl {
    resetCardsImpl(repository.getCardsByDeckImpl(deckId))
}

internal suspend fun FlashcardsViewModel.resetCardsImpl(cards: List<Card>) {
    if (cards.isEmpty()) return
    repository.upsertCardsImpl(
        cards.map {
            it.copy(
                state = CardState.NEW,
                stability = 0.0,
                difficulty = 0.0,
                reps = 0,
                lapses = 0,
                dueDate = 0,
                lastReview = 0,
            )
        },
    )
    repository.deleteReviewLogsByCardsImpl(cards.map { it.id })
}

internal fun FlashcardsViewModel.saveNoteTypeImpl(
    id: Long,
    name: String,
    css: String,
    type: Int,
    fieldNames: List<String>,
    templates: List<TemplateDraft>,
) = launchIoImpl {
    val savedId = repository.upsertNoteTypeImpl(
        NoteType(id = id, name = name, type = type, css = css, mod = FlashcardsViewModel.nowSeconds())
    )
    val ntId = if (id == 0L) savedId else id
    repository.deleteFieldsByNoteTypeImpl(ntId)
    repository.upsertNoteTypeFieldsImpl(
        fieldNames.mapIndexed { ord, fieldName ->
            com.vayunmathur.flashcards.data.NoteTypeField(
                noteTypeId = ntId,
                ord = ord,
                name = fieldName,
            )
        },
    )

    repository.deleteTemplatesByNoteTypeImpl(ntId)
    val effective = if (type == NoteTypeKind.CLOZE) templates.take(1) else templates
    repository.upsertCardTemplatesImpl(
        effective.mapIndexed { ord, t ->
            CardTemplate(noteTypeId = ntId, ord = ord, name = t.name, qfmt = t.qfmt, afmt = t.afmt)
        },
    )

    // Regenerate cards for every note of this type against the new templates.
    val nt = repository.getNoteTypeImpl(ntId) ?: return@launchIoImpl
    val newFields = repository.getFieldsForNoteTypeImpl(ntId)
    val newTemplates = repository.getTemplatesForNoteTypeImpl(ntId)
    repository.getNotesByNoteTypeImpl(ntId).forEach { note ->
        repository.regenerateCardsImpl(note, nt, newTemplates, newFields)
    }
}

internal fun FlashcardsViewModel.deleteNoteTypeImpl(id: Long) = launchIoImpl {
    if (id in FlashcardsViewModel.BUILT_IN_NOTE_TYPE_IDS) return@launchIoImpl
    val notes = repository.getNotesByNoteTypeImpl(id)
    notes.forEach { repository.deleteCardsByNoteImpl(it.id) }
    notes.forEach { repository.deleteNoteImpl(it) }
    repository.deleteFieldsByNoteTypeImpl(id)
    repository.deleteTemplatesByNoteTypeImpl(id)
    repository.getNoteTypeImpl(id)?.let { repository.deleteNoteTypeImpl(it) }
}
