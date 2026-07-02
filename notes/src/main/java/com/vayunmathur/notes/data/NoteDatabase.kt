package com.vayunmathur.notes.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import com.vayunmathur.library.util.DatabaseHelper
import com.vayunmathur.library.util.DatabaseMigrations
import androidx.room.migration.Migration
import kotlinx.coroutines.flow.Flow

const val DB_NAME = "notes-db"

/** Backup config shared by [AppBackupAgent] and the in-app backup buttons. */
fun noteDbConfigs(context: Context): List<Pair<String, String>> =
    listOf(DB_NAME to DatabaseHelper(context).getPassphrase())

@Dao
interface NoteDao {
    @Query("SELECT * FROM Note")
    fun getAllFlow(): Flow<List<Note>>

    @Query("SELECT * FROM Note")
    suspend fun getAll(): List<Note>

    @Query("SELECT * FROM Note WHERE id = :id")
    fun getByIdFlow(id: Long): Flow<Note?>

    @Upsert
    suspend fun upsert(value: Note): Long

    @Delete
    suspend fun delete(value: Note): Int

    @Upsert
    suspend fun upsertAll(t: List<Note>)
}

@Database(entities = [Note::class], version = 2, exportSchema = false)
abstract class NoteDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao

    companion object : DatabaseMigrations {
        // v2 adds the nullable `blocks` column (JSON list of NoteBlock). Existing
        // rows keep blocks = NULL and are read as a single text block.
        override val migrations = listOf(
            Migration(1, 2) {
                it.execSQL("ALTER TABLE Note ADD COLUMN blocks TEXT")
            },
        )
    }
}
