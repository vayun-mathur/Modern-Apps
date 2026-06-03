package com.vayunmathur.messages.meta

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

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
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MetaDatabase::class.java,
                    "meta_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
