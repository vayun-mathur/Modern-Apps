package com.vayunmathur.messages.meta

import android.content.Context
import androidx.room.Database
import androidx.room.RoomDatabase
import com.vayunmathur.library.room.buildDatabase

/**
 * Room database for Meta-specific data (Messenger/Instagram).
 * Stores sync state, thread metadata, and other platform-specific data.
 */
@Database(
    entities = [MetaThread::class, MetaSyncState::class],
    version = 1,
    exportSchema = false
)
abstract class MetaDatabase : RoomDatabase() {
    abstract fun threadDao(): MetaThreadDao
    abstract fun syncStateDao(): MetaSyncStateDao

    companion object {
        @Volatile
        private var INSTANCE: MetaDatabase? = null

        fun getDatabase(context: Context): MetaDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = context.applicationContext.buildDatabase<MetaDatabase>(
                    dbName = "meta_database"
                )
                INSTANCE = instance
                instance
            }
        }
    }
}
