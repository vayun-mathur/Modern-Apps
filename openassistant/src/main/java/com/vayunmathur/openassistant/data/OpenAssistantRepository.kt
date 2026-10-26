package com.vayunmathur.openassistant.data

import android.content.Context
import com.vayunmathur.library.room.RoomRepository
import kotlinx.coroutines.flow.Flow

/**
 * Single owner of [AppDatabase] for the openassistant module.
 */
class OpenAssistantRepository private constructor(context: Context) :
    RoomRepository<AppDatabase>(context, AppDatabase::class) {

    private val conversationDao get() = db.conversationDao()
    private val messageDao get() = db.messageDao()
    private val memoryDao get() = db.memoryDao()

    // ------------------------------------------------------------------
    // Conversation
    // ------------------------------------------------------------------

    fun conversationsFlow(): Flow<List<Conversation>> = conversationDao.getAllFlow()
    fun conversationByIdFlow(id: Long): Flow<Conversation?> = conversationDao.getByIdFlow(id)
    suspend fun getConversation(id: Long): Conversation? = conversationDao.getById(id)
    suspend fun upsertConversation(value: Conversation): Long = conversationDao.upsert(value)
    suspend fun deleteConversation(value: Conversation): Int = conversationDao.delete(value)

    // ------------------------------------------------------------------
    // Message
    // ------------------------------------------------------------------

    fun messagesForConversationFlow(conversationId: Long): Flow<List<Message>> =
        messageDao.getByConversationFlow(conversationId)
    suspend fun getMessagesForConversation(conversationId: Long): List<Message> =
        messageDao.getByConversation(conversationId)
    suspend fun getMessage(id: Long): Message? = messageDao.getById(id)
    suspend fun deleteMessageById(id: Long) = messageDao.deleteById(id)
    suspend fun upsertMessage(value: Message): Long = messageDao.upsert(value)
    suspend fun deleteMessage(value: Message): Int = messageDao.delete(value)

    // ------------------------------------------------------------------
    // Memory
    // ------------------------------------------------------------------

    fun memoriesFlow(): Flow<List<Memory>> = memoryDao.getAllFlow()
    suspend fun getAllMemories(): List<Memory> = memoryDao.getAll()
    suspend fun deleteMemoryById(id: Long) = memoryDao.deleteById(id)
    suspend fun upsertMemory(value: Memory): Long = memoryDao.upsert(value)
    suspend fun deleteMemory(value: Memory): Int = memoryDao.delete(value)

    /** Backing DAOs for [com.vayunmathur.openassistant.platform.AssistantTools] and similar consumers. */
    internal val conversationDaoRef get(): ConversationDao = conversationDao
    internal val messageDaoRef get(): MessageDao = messageDao
    internal val memoryDaoRef get(): MemoryDao = memoryDao

    companion object {
        @Volatile
        private var instance: OpenAssistantRepository? = null

        fun get(context: Context): OpenAssistantRepository =
            instance ?: synchronized(this) {
                instance ?: OpenAssistantRepository(context).also { instance = it }
            }
    }
}
