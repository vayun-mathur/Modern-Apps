package com.vayunmathur.openassistant.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.vayunmathur.openassistant.data.Conversation
import com.vayunmathur.openassistant.data.Message

@TypeConverters(Converters::class)
@Database(entities = [Message::class, Conversation::class], version = 5)
abstract class MessageDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun conversationDao(): ConversationDao
}
