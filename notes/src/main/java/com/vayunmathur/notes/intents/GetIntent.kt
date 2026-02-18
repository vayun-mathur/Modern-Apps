package com.vayunmathur.notes.intents

import com.vayunmathur.library.util.AssistantIntent
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.buildDatabase
import com.vayunmathur.notes.data.Note
import com.vayunmathur.notes.data.NoteDatabase
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.serializer

@OptIn(InternalSerializationApi::class)
class GetIntent: AssistantIntent<Unit, List<Note>>(serializer<Unit>(), serializer<List<Note>>()) {

    override suspend fun performCalculation(input: Unit): List<Note> {
        val db = buildDatabase<NoteDatabase>()
        val viewModel = DatabaseViewModel(db, Note::class to db.noteDao())
        return viewModel.getAll()
    }
}