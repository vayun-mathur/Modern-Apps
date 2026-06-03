package com.vayunmathur.messages.whatsapp

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

/**
 * Room database for WhatsApp-specific data.
 * Stores device info, session keys, and message metadata.
 */
@Database(
    entities = [WhatsAppDevice::class, WhatsAppSession::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(WhatsAppTypeConverters::class)
abstract class WhatsAppDatabase : RoomDatabase() {
    abstract fun deviceDao(): WhatsAppDeviceDao
    abstract fun sessionDao(): WhatsAppSessionDao

    companion object {
        @Volatile
        private var INSTANCE: WhatsAppDatabase? = null

        fun getDatabase(context: Context): WhatsAppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    WhatsAppDatabase::class.java,
                    "whatsapp_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}

class WhatsAppTypeConverters {
    @TypeConverter
    fun fromStringList(value: List<String>): String {
        return value.joinToString(",")
    }

    @TypeConverter
    fun toStringList(value: String): List<String> {
        return if (value.isEmpty()) emptyList() else value.split(",")
    }
}
