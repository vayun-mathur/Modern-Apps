package com.vayunmathur.notes.intents

import com.vayunmathur.library.intents.notes.NoteData
import com.vayunmathur.library.util.AssistantIntent
import com.vayunmathur.library.room.buildDatabase
import com.vayunmathur.notes.data.DB_NAME
import com.vayunmathur.notes.data.NoteDatabase
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

class GetIntent: AssistantIntent<Unit, List<NoteData>>(Unit.serializer(), ListSerializer(NoteData.serializer())) {

    override suspend fun performCalculation(input: Unit): List<NoteData> {
        val db = buildDatabase<NoteDatabase>(dbName = DB_NAME)
        return db.noteDao().getAll().map { NoteData(it.title, it.content) }
    }
}
