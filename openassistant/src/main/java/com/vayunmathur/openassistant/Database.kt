package com.vayunmathur.openassistant

import androidx.room.PrimaryKey
import com.vayunmathur.library.util.DatabaseItem
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.vayunmathur.library.util.DefaultConverters
import com.vayunmathur.library.util.TrueDao

@Entity
data class Conversation(
    val title: String,
    val timestamp: Long = System.currentTimeMillis(),
    @PrimaryKey(autoGenerate = true)
    override val id: Long = 0,
): DatabaseItem

/**
 * Data class representing a persistent Message within a conversation.
 */
@Entity
data class Message(
    val conversationId: Long,
    val text: String,
    val role: String,
    val imagePaths: List<String> = emptyList(), // Store as paths for better persistence
    val hasAudio: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    @PrimaryKey(autoGenerate = true)
    override val id: Long = 0,
): DatabaseItem


@Dao
interface ConversationDao: TrueDao<Conversation>

@Dao
interface MessageDao: TrueDao<Message>

@TypeConverters(DefaultConverters::class)
@Database(entities = [Conversation::class, Message::class], version = 1)
abstract class AppDatabase: RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
}