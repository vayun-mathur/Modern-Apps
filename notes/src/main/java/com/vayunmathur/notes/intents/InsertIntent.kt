package com.vayunmathur.notes.intents

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.vayunmathur.library.intents.notes.InsertNoteData
import com.vayunmathur.library.util.AssistantIntent
import com.vayunmathur.library.util.buildDatabase
import com.vayunmathur.notes.data.Note
import com.vayunmathur.notes.data.database.NoteDatabase
import kotlinx.coroutines.flow.first
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

@OptIn(InternalSerializationApi::class)
class InsertIntent: AssistantIntent<InsertNoteData, Unit>(serializer<InsertNoteData>(), serializer<Unit>()) {

    override suspend fun performCalculation(input: InsertNoteData) {
        val db = buildDatabase<NoteDatabase>()
        db.noteDao().upsert(Note(title = input.title, content = input.content))
    }
}