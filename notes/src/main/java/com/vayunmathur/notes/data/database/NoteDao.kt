package com.vayunmathur.notes.data.database

import androidx.room.Dao
import androidx.room.Query
import com.vayunmathur.library.util.TrueDao
import com.vayunmathur.notes.data.Note
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao: TrueDao<Note> {
    @Query("SELECT * FROM Note")
    override fun getAll(): Flow<List<Note>>
}
