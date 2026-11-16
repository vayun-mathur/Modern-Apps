package com.vayunmathur.openassistant.data
import androidx.room3.ColumnTypeConverters
import androidx.room3.PrimaryKey
import androidx.sqlite.execSQL
import com.vayunmathur.library.util.DatabaseItem
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Delete
import androidx.room3.Entity
import androidx.room3.Query
import androidx.room3.RoomDatabase
import androidx.room3.Upsert
import com.vayunmathur.library.util.DefaultConverters
import androidx.room3.migration.Migration
import kotlinx.coroutines.flow.Flow

/** Immutable value describing conversation. */
@Entity
data class Conversation(
    val title: String,
    val timestamp: Long = System.currentTimeMillis(),
    @PrimaryKey(autoGenerate = true)
    override val id: Long = 0,
): DatabaseItem

/** Immutable value describing message. */
@Entity
data class Message(
    val conversationId: Long,
    val text: String,
    val role: String,
    val imagePaths: List<String> = emptyList(), // Store as paths for better persistence
    val hasAudio: Boolean = false,
    val missingAppPackage: String? = null,
    /** The A2UI block the model attached to this reply, as written (see domain/A2uiBlocks). */
    val a2ui: String? = null,
    /** Final A2UI data-model snapshot for replay (P1); null until an action stores it. */
    val a2uiData: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    @PrimaryKey(autoGenerate = true)
    override val id: Long = 0,
): DatabaseItem

/** Immutable value describing memory. */
@Entity
data class Memory(
    val content: String,
    @PrimaryKey(autoGenerate = true)
    override val id: Long = 0,
): DatabaseItem

/** Contract for conversation dao. */
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

/** Contract for message dao. */
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

/** A WRITE_CONFIRM tool call persisted awaiting host confirmation. */
@Entity(tableName = "PendingToolCall")
data class PendingToolCallEntity(
    @PrimaryKey val id: String,
    val conversationId: Long,
    val toolName: String,
    val argsJson: String,
    val summary: String,
    val createdAt: Long,
    val expiresAt: Long,
    val consumed: Boolean = false,
)

/** PII-redacted audit record for a confirmed tool execution. */
@Entity
data class AuditRecord(
    val toolName: String,
    val argsSummary: String,
    val outcome: String,
    val timestamp: Long = System.currentTimeMillis(),
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
)

/** Contract for pending tool call dao. */
@Dao
interface PendingToolCallDao {
    @Query("SELECT * FROM PendingToolCall WHERE id = :id")
    suspend fun getById(id: String): PendingToolCallEntity?

    @Query("SELECT * FROM PendingToolCall WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    suspend fun getByConversation(conversationId: Long): List<PendingToolCallEntity>

    @Query("DELETE FROM PendingToolCall WHERE id = :id")
    suspend fun deleteById(id: String)

    @Upsert
    suspend fun upsert(value: PendingToolCallEntity): Long
}

/** Contract for audit record dao. */
@Dao
interface AuditRecordDao {
    @Query("SELECT * FROM AuditRecord ORDER BY timestamp DESC")
    fun getAllFlow(): Flow<List<AuditRecord>>

    @Query("SELECT * FROM AuditRecord ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 100): List<AuditRecord>

    @Upsert
    suspend fun upsert(value: AuditRecord): Long
}

/** Contract for memory dao. */
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

/** App Database: a room database implementation for app database. */
@ColumnTypeConverters(DefaultConverters::class)
@Database(
    entities = [Conversation::class, Message::class, Memory::class, PendingToolCallEntity::class, AuditRecord::class],
    version = 5,
    exportSchema = false,
)
abstract class AppDatabase: RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun memoryDao(): MemoryDao
    abstract fun pendingToolCallDao(): PendingToolCallDao
    abstract fun auditRecordDao(): AuditRecordDao

    /** Shared constants and helpers for AppDatabase. */
    companion object : com.vayunmathur.library.util.DatabaseMigrations {
        override val migrations = listOf(
            Migration(1, 2) { it.execSQL("ALTER TABLE Message ADD COLUMN missingAppPackage TEXT") },
            Migration(2, 3) {
                it.execSQL(
                    "CREATE TABLE IF NOT EXISTS `Memory` " +
                        "(`content` TEXT NOT NULL, `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)",
                )
            },
            Migration(3, 4) { it.execSQL("ALTER TABLE Message ADD COLUMN a2ui TEXT") },
            Migration(4, 5) {
                it.execSQL("ALTER TABLE Message ADD COLUMN a2uiData TEXT")
                it.execSQL(
                    "CREATE TABLE IF NOT EXISTS `PendingToolCall` " +
                        "(`id` TEXT NOT NULL, `conversationId` INTEGER NOT NULL, `toolName` TEXT NOT NULL, " +
                        "`argsJson` TEXT NOT NULL, `summary` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "`expiresAt` INTEGER NOT NULL, `consumed` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
                it.execSQL(
                    "CREATE TABLE IF NOT EXISTS `AuditRecord` " +
                        "(`toolName` TEXT NOT NULL, `argsSummary` TEXT NOT NULL, `outcome` TEXT NOT NULL, " +
                        "`timestamp` INTEGER NOT NULL, `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)",
                )
            },
        )
    }
}
