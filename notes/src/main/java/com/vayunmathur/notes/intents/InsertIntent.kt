package com.vayunmathur.notes.intents

import com.vayunmathur.library.intents.notes.NoteData
import com.vayunmathur.library.util.AssistantIntent
import com.vayunmathur.library.room.buildDatabase
import com.vayunmathur.notes.data.DB_NAME
import com.vayunmathur.notes.data.Note
import com.vayunmathur.notes.data.NoteDatabase
import kotlinx.serialization.builtins.serializer

class InsertIntent: AssistantIntent<NoteData, Unit>(NoteData.serializer(), Unit.serializer()) {

    override suspend fun performCalculation(input: NoteData) {
        val db = buildDatabase<NoteDatabase>(dbName = DB_NAME)
        db.noteDao().upsert(Note(title = input.title, content = input.content))
    }
}
