package com.vayunmathur.notes.intents

import com.vayunmathur.library.intents.notes.NoteData
import com.vayunmathur.library.util.AssistantIntent
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.buildDatabase
import com.vayunmathur.notes.data.Note
import com.vayunmathur.notes.data.NoteDatabase
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.serializer

@OptIn(InternalSerializationApi::class)
class GetIntent: AssistantIntent<Unit, List<NoteData>>(serializer<Unit>(), serializer<List<NoteData>>()) {

    override suspend fun performCalculation(input: Unit): List<NoteData> {
        val db = buildDatabase<NoteDatabase>()
        val viewModel = DatabaseViewModel(db, Note::class to db.noteDao())
        return viewModel.getAll<Note>().map { NoteData(it.title, it.content) }
    }
}