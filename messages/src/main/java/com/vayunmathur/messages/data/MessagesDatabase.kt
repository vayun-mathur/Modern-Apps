package com.vayunmathur.messages.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Upsert
    suspend fun upsert(conversation: Conversation)

    @Upsert
    suspend fun upsertAll(conversations: List<Conversation>)

    @Query(
        """
        SELECT c.*, COALESCE(MAX(m.timestamp), 0) AS last_ts
        FROM conversations c
        LEFT JOIN messages m ON m.conversation_id = c.id
        GROUP BY c.id
        ORDER BY last_ts DESC
        """
    )
    fun observeAll(): Flow<List<ConversationWithLastMessage>>

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    fun observe(id: String): Flow<Conversation?>

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    suspend fun get(id: String): Conversation?

    @Query("UPDATE conversations SET unread_count = 0 WHERE id = :id")
    suspend fun markRead(id: String)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM conversations WHERE source = :source")
    suspend fun deleteAllForSource(source: MessageSource)
}

@Dao
interface MessageDao {
    @Upsert
    suspend fun upsert(message: Message)

    @Upsert
    suspend fun upsertAll(messages: List<Message>)

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId ORDER BY timestamp ASC")
    fun observeForConversation(conversationId: String): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun get(id: String): Message?

    @Query("UPDATE messages SET state = :state WHERE id = :id")
    suspend fun updateState(id: String, state: MessageState)

    @Query("UPDATE messages SET reactions_json = :json WHERE id = :id")
    suspend fun updateReactions(id: String, json: String?)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM messages WHERE conversation_id LIKE :prefix")
    suspend fun deleteAllForConvPrefix(prefix: String)
}

/**
 * A [Conversation] plus the timestamp (epoch-ms) of its most recent
 * message, derived at query time from the messages table rather than
 * stored on the conversation row. `last_ts` is 0 when the thread has no
 * messages yet.
 */
data class ConversationWithLastMessage(
    @Embedded val conversation: Conversation,
    @ColumnInfo(name = "last_ts") val lastMessageTimestamp: Long,
)

/**
 * Room can store our enums as their `.name` strings via a single
 * generic-style converter pair. Keeps migrations stable if enum order
 * ever changes (don't store ordinal).
 */
class MessagesConverters {
    @TypeConverter
    fun fromSource(value: MessageSource): String = value.name
    @TypeConverter
    fun toSource(value: String): MessageSource = MessageSource.valueOf(value)

    @TypeConverter
    fun fromDirection(value: MessageDirection): String = value.name
    @TypeConverter
    fun toDirection(value: String): MessageDirection = MessageDirection.valueOf(value)

    @TypeConverter
    fun fromState(value: MessageState): String = value.name
    @TypeConverter
    fun toState(value: String): MessageState = MessageState.valueOf(value)
}

@androidx.room.Database(
    entities = [Conversation::class, Message::class],
    version = 8,
    exportSchema = false,
)
@TypeConverters(MessagesConverters::class)
abstract class MessagesDatabase : androidx.room.RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
}

private val dbLock = Any()
@Volatile private var sharedDb: MessagesDatabase? = null

/**
 * Returns the process-singleton [MessagesDatabase]. We must return the
 * same instance everywhere — Room's Flow change-notifications are
 * per-instance, so writes via one instance never notify Flows observed
 * via another, even when both point at the same SQLite file.
 */
fun buildMessagesDatabase(context: android.content.Context): MessagesDatabase {
    sharedDb?.let { return it }
    return synchronized(dbLock) {
        sharedDb ?: androidx.room.Room.databaseBuilder(
            context.applicationContext,
            MessagesDatabase::class.java,
            "messages.db",
        )
            .fallbackToDestructiveMigration(true)
            .build()
            .also { sharedDb = it }
    }
}
