package com.vayunmathur.openassistant.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.vayunmathur.openassistant.data.dao.ConversationDao
import com.vayunmathur.openassistant.data.dao.MessageDao

@TypeConverters(Converters::class)
@Database(entities = [Message::class, Conversation::class], version = 5)
abstract class MessageDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun conversationDao(): ConversationDao
}
