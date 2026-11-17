package com.vayunmathur.notes.intents

import com.vayunmathur.library.intents.notes.NoteData
import com.vayunmathur.library.util.AssistantIntent
import com.vayunmathur.notes.data.NotesRepository
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/** Assistant intent returning all notes as shared note DTOs. */
class GetIntent: AssistantIntent<Unit, List<NoteData>>(Unit.serializer(), ListSerializer(NoteData.serializer())) {

    override suspend fun performCalculation(input: Unit): List<NoteData> {
        val repo = NotesRepository.get(this)
        return repo.getAll().map { NoteData(it.title, it.content) }
    }
}
