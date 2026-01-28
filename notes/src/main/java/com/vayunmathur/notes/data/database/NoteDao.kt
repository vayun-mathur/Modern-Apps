package com.vayunmathur.notes.data.database

import androidx.room.Dao
import androidx.room.Query
import com.vayunmathur.library.util.TrueDao
import com.vayunmathur.notes.data.Note
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao: TrueDao<Note> {
    @Query("SELECT * FROM Note ORDER BY position ASC")
    override fun getAll(): Flow<List<Note>>
    @Query("DELETE FROM Note")
    override suspend fun deleteAll()
}
