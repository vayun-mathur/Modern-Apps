package com.vayunmathur.notes.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.RoomDatabase
import com.vayunmathur.library.util.TrueDao
import com.vayunmathur.notes.data.Note

@Dao
interface NoteDao: TrueDao<Note>

@Database(entities = [Note::class], version = 2)
abstract class NoteDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
}
