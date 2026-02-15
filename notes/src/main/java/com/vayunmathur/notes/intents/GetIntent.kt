package com.vayunmathur.notes.intents

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.vayunmathur.library.util.AssistantIntent
import com.vayunmathur.library.util.buildDatabase
import com.vayunmathur.notes.data.Note
import com.vayunmathur.notes.data.database.NoteDatabase
import kotlinx.coroutines.flow.first
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

@OptIn(InternalSerializationApi::class)
class GetIntent: AssistantIntent<Unit, List<Note>>(serializer<Unit>(), serializer<List<Note>>()) {

    override suspend fun performCalculation(input: Unit): List<Note> {
        val db = buildDatabase<NoteDatabase>()
        println("RUNNING REQUEST")
        return db.noteDao().getAll().first()
    }
}