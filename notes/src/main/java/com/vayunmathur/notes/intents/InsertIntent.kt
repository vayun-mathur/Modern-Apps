package com.vayunmathur.notes.intents

import com.vayunmathur.library.intents.notes.NoteData
import com.vayunmathur.library.util.AssistantIntent
import com.vayunmathur.library.util.buildDatabase
import com.vayunmathur.notes.data.Note
import com.vayunmathur.notes.data.NoteDatabase
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.serializer

@OptIn(InternalSerializationApi::class)
class InsertIntent: AssistantIntent<NoteData, Unit>(serializer<NoteData>(), serializer<Unit>()) {

    override suspend fun performCalculation(input: NoteData) {
        val db = buildDatabase<NoteDatabase>()
        db.noteDao().upsert(Note(title = input.title, content = input.content))
    }
}
