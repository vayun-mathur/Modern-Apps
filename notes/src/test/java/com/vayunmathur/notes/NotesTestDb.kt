package com.vayunmathur.notes

import android.content.Context
import androidx.room3.Room
import com.vayunmathur.library.util.databases
import com.vayunmathur.notes.data.NoteDatabase
import com.vayunmathur.notes.data.NotesRepository

/** Binds [NotesRepository]'s singleton to an in-memory DB (avoids SQLCipher on JVM). */
fun seedNotesDb(context: Context): NotesRepository {
    synchronized(databases) {
        if (databases[NoteDatabase::class] !is NoteDatabase) {
            databases[NoteDatabase::class] = Room.inMemoryDatabaseBuilder(
                context.applicationContext, NoteDatabase::class.java,
            ).allowMainThreadQueries().build()
        }
    }
    return NotesRepository.get(context.applicationContext)
}

fun notesDb(context: Context): NoteDatabase {
    seedNotesDb(context)
    return synchronized(databases) { databases[NoteDatabase::class] as NoteDatabase }
}
