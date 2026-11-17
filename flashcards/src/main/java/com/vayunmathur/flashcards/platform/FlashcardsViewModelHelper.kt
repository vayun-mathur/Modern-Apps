package com.vayunmathur.flashcards.platform
import com.vayunmathur.flashcards.ui.TemplateDraft

import com.vayunmathur.flashcards.data.CardTemplate
import com.vayunmathur.flashcards.data.FlashcardsRepository
import com.vayunmathur.flashcards.data.getAllNoteTypesImpl
import com.vayunmathur.flashcards.data.upsertCardTemplatesImpl
import com.vayunmathur.flashcards.data.upsertNoteTypeFieldsImpl
import com.vayunmathur.flashcards.data.upsertNoteTypeImpl
import com.vayunmathur.flashcards.data.NoteType
import com.vayunmathur.flashcards.data.NoteTypeKind

internal object FlashcardsViewModelHelper {
    suspend fun ensureBuiltInNoteTypes(repository: FlashcardsRepository) {
        if (repository.getAllNoteTypesImpl().isNotEmpty()) return
        seedNoteType(
            repository = repository,
            id = FlashcardsViewModel.BASIC_NOTE_TYPE_ID,
            name = "Basic",
            type = NoteTypeKind.STANDARD,
            fields = listOf("Front", "Back"),
            templates = listOf(TemplateDraft("Card 1", "{{Front}}", "{{FrontSide}}\n\n---\n\n{{Back}}")),
        )
        seedNoteType(
            repository = repository,
            id = 2,
            name = "Basic (and reversed card)",
            type = NoteTypeKind.STANDARD,
            fields = listOf("Front", "Back"),
            templates = listOf(
                TemplateDraft("Card 1", "{{Front}}", "{{FrontSide}}\n\n---\n\n{{Back}}"),
                TemplateDraft("Card 2", "{{Back}}", "{{FrontSide}}\n\n---\n\n{{Front}}"),
            ),
        )
        seedNoteType(
            repository = repository,
            id = 3,
            name = "Cloze",
            type = NoteTypeKind.CLOZE,
            fields = listOf("Text", "Back Extra"),
            templates = listOf(
                TemplateDraft("Cloze", "{{cloze:Text}}", "{{cloze:Text}}\n\n---\n\n{{Back Extra}}"),
            ),
        )
    }

    suspend fun seedNoteType(
        repository: FlashcardsRepository,
        id: Long,
        name: String,
        type: Int,
        fields: List<String>,
        templates: List<TemplateDraft>,
    ) {
        repository.upsertNoteTypeImpl(
            NoteType(id = id, name = name, type = type, mod = System.currentTimeMillis() / 1000)
        )
        repository.upsertNoteTypeFieldsImpl(
            fields.mapIndexed { ord, f ->
                com.vayunmathur.flashcards.data.NoteTypeField(noteTypeId = id, ord = ord, name = f)
            }
        )
        repository.upsertCardTemplatesImpl(
            templates.mapIndexed { ord, t ->
                CardTemplate(noteTypeId = id, ord = ord, name = t.name, qfmt = t.qfmt, afmt = t.afmt)
            },
        )
    }
}
