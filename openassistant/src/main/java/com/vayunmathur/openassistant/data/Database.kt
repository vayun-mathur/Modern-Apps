package com.vayunmathur.openassistant.data
import androidx.room.PrimaryKey
import com.vayunmathur.library.util.DatabaseItem
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.Upsert
import com.vayunmathur.library.util.DefaultConverters
import androidx.room.migration.Migration
import kotlinx.coroutines.flow.Flow

@Entity
data class Conversation(
    val title: String,
    val timestamp: Long = System.currentTimeMillis(),
    @PrimaryKey(autoGenerate = true)
    override val id: Long = 0,
): DatabaseItem

@Entity
data class Message(
    val conversationId: Long,
    val text: String,
    val role: String,
    val imagePaths: List<String> = emptyList(), // Store as paths for better persistence
    val hasAudio: Boolean = false,
    val missingAppPackage: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    @PrimaryKey(autoGenerate = true)
    override val id: Long = 0,
): DatabaseItem

@Entity
data class Memory(
    val content: String,
    @PrimaryKey(autoGenerate = true)
    override val id: Long = 0,
): DatabaseItem

@Dao
interface ConversationDao {
    @Query("SELECT * FROM Conversation ORDER BY timestamp DESC")
    fun getAllFlow(): Flow<List<Conversation>>

    @Query("SELECT * FROM Conversation WHERE id = :id")
    fun getByIdFlow(id: Long): Flow<Conversation?>

    @Query("SELECT * FROM Conversation WHERE id = :id")
    suspend fun getById(id: Long): Conversation?

    @Upsert
    suspend fun upsert(value: Conversation): Long

    @Delete
    suspend fun delete(value: Conversation): Int
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM Message WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun getByConversationFlow(conversationId: Long): Flow<List<Message>>

    @Query("SELECT * FROM Message WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    suspend fun getByConversation(conversationId: Long): List<Message>

    @Query("SELECT * FROM Message WHERE id = :id")
    suspend fun getById(id: Long): Message?

    @Query("DELETE FROM Message WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Upsert
    suspend fun upsert(value: Message): Long

    @Delete
    suspend fun delete(value: Message): Int
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM Memory ORDER BY id ASC")
    fun getAllFlow(): Flow<List<Memory>>

    @Query("SELECT * FROM Memory ORDER BY id ASC")
    suspend fun getAll(): List<Memory>

    @Query("DELETE FROM Memory WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Upsert
    suspend fun upsert(value: Memory): Long

    @Delete
    suspend fun delete(value: Memory): Int
}

@TypeConverters(DefaultConverters::class)
@Database(entities = [Conversation::class, Message::class, Memory::class], version = 3, exportSchema = false)
abstract class AppDatabase: RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun memoryDao(): MemoryDao

    companion object : com.vayunmathur.library.util.DatabaseMigrations {
        override val migrations = listOf(
            Migration(1, 2) { it.execSQL("ALTER TABLE Message ADD COLUMN missingAppPackage TEXT") },
            Migration(2, 3) { it.execSQL("CREATE TABLE IF NOT EXISTS `Memory` (`content` TEXT NOT NULL, `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)") },
        )
    }
}
