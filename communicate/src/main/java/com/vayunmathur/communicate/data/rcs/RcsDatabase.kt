package com.vayunmathur.communicate.data.rcs

import android.content.Context
import androidx.room3.ColumnTypeConverter
import androidx.room3.ColumnTypeConverters
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.vayunmathur.library.room.RoomRepository
import com.vayunmathur.library.util.DatabaseMigrations

/**
 * Room database for RCS-specific data. Mirrors
 * [com.vayunmathur.communicate.data.signal.SignalDatabase] but for the
 * single-registration line: conversations + cached messages with file-transfer
 * metadata. Version 1, no migrations.
 */
@Database(
    entities = [
        RcsConversation::class,
        RcsCachedMessage::class,
        RcsMlsIdentity::class,
        RcsMlsGroup::class,
        RcsOutboxMessage::class,
    ],
    version = 3,
    exportSchema = false,
)
@ColumnTypeConverters(RcsTypeConverters::class)
abstract class RcsDatabase : RoomDatabase() {
    abstract fun conversationDao(): RcsConversationDao
    abstract fun cachedMessageDao(): RcsCachedMessageDao
    abstract fun mlsIdentityDao(): RcsMlsIdentityDao
    abstract fun mlsGroupDao(): RcsMlsGroupDao
    abstract fun outboxDao(): RcsOutboxDao

    companion object : DatabaseMigrations {
        /**
         * v1 → v2: MLS E2EE tables (identity + group state). New tables only,
         * nothing to backfill.
         */
        private val MIGRATION_1_2 = object : androidx.room3.migration.Migration(1, 2) {
            override suspend fun migrate(connection: androidx.sqlite.SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `rcs_mls_identity` (" +
                        "`e164` TEXT NOT NULL, `identity` BLOB NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`e164`))",
                )
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `rcs_mls_group` (" +
                        "`groupIdHex` TEXT NOT NULL, `conversationId` TEXT NOT NULL, " +
                        "`storage` BLOB NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`groupIdHex`))",
                )
            }
        }

        /**
         * v2 → v3: display-receipt correlation (`imdnId` on cached rows),
         * deferred-delivery outbox, identity verification flag, MLS member
         * leaf-index tracking. Additive only; existing rows get defaults.
         */
        private val MIGRATION_2_3 = object : androidx.room3.migration.Migration(2, 3) {
            override suspend fun migrate(connection: androidx.sqlite.SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE `rcs_cached_message` ADD COLUMN `imdnId` TEXT NOT NULL DEFAULT ''",
                )
                connection.execSQL(
                    "ALTER TABLE `rcs_mls_identity` ADD COLUMN `verified` INTEGER NOT NULL DEFAULT 0",
                )
                connection.execSQL(
                    "ALTER TABLE `rcs_mls_group` ADD COLUMN `members` TEXT NOT NULL DEFAULT ''",
                )
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `rcs_outbox` (" +
                        "`messageId` TEXT NOT NULL, `conversationId` TEXT NOT NULL, " +
                        "`kind` TEXT NOT NULL, `body` TEXT NOT NULL, " +
                        "`attachments` TEXT NOT NULL, `attempts` INTEGER NOT NULL, " +
                        "`nextRetryMs` INTEGER NOT NULL, `expiryMs` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`messageId`))",
                )
            }
        }

        override val migrations = listOf(MIGRATION_1_2, MIGRATION_2_3)

        /**
         * Test seam: when set, [getDatabase] returns this instance instead
         * of the SQLCipher-backed singleton (whose native library cannot
         * load on a JVM test host). Production code never sets it.
         */
        @Volatile
        internal var overrideForTest: RcsDatabase? = null

        fun getDatabase(context: Context): RcsDatabase =
            overrideForTest ?: RcsRepository.get(context).database()
    }
}

/**
 * Single owner of [RcsDatabase] (communicate_rcs.db).
 *
 * Mirrors [com.vayunmathur.communicate.data.signal.SignalRepository]: the
 * single `buildDatabase` call site lives here (via [RoomRepository.db]).
 */
class RcsRepository private constructor(context: Context) :
    RoomRepository<RcsDatabase>(context, RcsDatabase::class, "communicate_rcs.db") {

    /** Direct database access; prefer suspend wrappers for new code. */
    fun database(): RcsDatabase = db

    companion object {
        @Volatile
        private var instance: RcsRepository? = null

        fun get(context: Context): RcsRepository =
            instance ?: synchronized(this) {
                instance ?: RcsRepository(context).also { instance = it }
            }
    }
}

class RcsTypeConverters {
    @ColumnTypeConverter
    fun fromStringList(value: List<String>): String = value.joinToString(",")

    @ColumnTypeConverter
    fun toStringList(value: String): List<String> =
        if (value.isEmpty()) emptyList() else value.split(",")
}

// -- Conversation --

@Entity(tableName = "rcs_conversation")
data class RcsConversation(
    /** E.164 for 1:1, conference URI for groups. */
    @PrimaryKey val remoteId: String,
    val isGroup: Boolean = false,
    /** Group subject when known. */
    val name: String? = null,
    /** CSV of participant addresses for groups. */
    val participants: String = "",
    val unreadCount: Int = 0,
    val lastMessageTimestamp: Long = 0L,
)

@Dao
interface RcsConversationDao {
    @Query("SELECT * FROM rcs_conversation WHERE remoteId = :remoteId")
    suspend fun getConversation(remoteId: String): RcsConversation?

    @Query("SELECT * FROM rcs_conversation ORDER BY lastMessageTimestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<RcsConversation>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(conversation: RcsConversation)

    @Query("DELETE FROM rcs_conversation WHERE remoteId = :remoteId")
    suspend fun delete(remoteId: String)

    @Query("DELETE FROM rcs_conversation")
    suspend fun deleteAll()
}

// -- Cached messages --
@Entity(tableName = "rcs_cached_message")
data class RcsCachedMessage(
    @PrimaryKey val messageId: String,
    val conversationId: String,
    val body: String,
    val timestamp: Long,
    val outgoing: Boolean,
    /** Sender address for inbound group messages. */
    val senderId: String = "",
    /** File-transfer metadata (populated by RcsFileTransfer). */
    val ftUrl: String? = null,
    val ftMime: String? = null,
    /** Outgoing delivery status ordinal (MessageStatus ordinal). */
    val status: Int = 0,
    /**
     * Inbound `imdn.Message-ID` for this row (outbound rows carry their own
     * id in [messageId]; inbound rows store the sender's id here so
     * display/read reports can reference it, §4.2).
     */
    val imdnId: String = "",
)

@Dao
interface RcsCachedMessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: RcsCachedMessage)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(messages: List<RcsCachedMessage>)

    @Query("SELECT * FROM rcs_cached_message WHERE conversationId = :id ORDER BY timestamp ASC")
    suspend fun getForConversation(id: String): List<RcsCachedMessage>

    @Query("SELECT * FROM rcs_cached_message WHERE messageId = :messageId LIMIT 1")
    suspend fun get(messageId: String): RcsCachedMessage?

    /** Most-recent message per conversation, newest first (for the thread list). */
    @Query(
        "SELECT * FROM rcs_cached_message WHERE timestamp IN " +
            "(SELECT MAX(timestamp) FROM rcs_cached_message GROUP BY conversationId) " +
            "ORDER BY timestamp DESC",
    )
    suspend fun getLatestPerConversation(): List<RcsCachedMessage>

    /** Advance to Delivered (2) only from a lower state. */
    @Query("UPDATE rcs_cached_message SET status = 2 WHERE messageId = :messageId AND status < 2")
    suspend fun markDelivered(messageId: String)

    /** Advance to Read (3) only from a lower state. */
    @Query("UPDATE rcs_cached_message SET status = 3 WHERE messageId = :messageId AND status < 3")
    suspend fun markReadStatus(messageId: String)

    @Query("DELETE FROM rcs_cached_message WHERE conversationId = :id")
    suspend fun deleteConversation(id: String)

    @Query("DELETE FROM rcs_cached_message")
    suspend fun deleteAll()
}

// -- MLS E2EE (closed-loop, our-app-to-our-app) --

/**
 * Our MLS identity for one local E.164: serialized `IdentityState`
 * (Ed25519 keypair + identity string) from the `communicate_mls` crate.
 */
@Entity(tableName = "rcs_mls_identity")
data class RcsMlsIdentity(
    @PrimaryKey val e164: String,
    val identity: ByteArray,
    val updatedAt: Long = 0L,
    /** User-verified safety number (§5.4). Unverified by default. */
    val verified: Boolean = false,
)

/**
 * One MLS group, keyed by hex-encoded MLS group id bytes. [storage] is the
 * opaque provider snapshot the crate needs on every call; [conversationId]
 * maps back to the RCS thread. [members] tracks E.164 → leaf index
 * (`e164=index` CSV) for member removal (§5.2).
 */
@Entity(tableName = "rcs_mls_group")
data class RcsMlsGroup(
    /** Hex of the MLS group id (BLOB PKs are unreliable in Room). */
    @PrimaryKey val groupIdHex: String,
    val conversationId: String,
    val storage: ByteArray,
    val updatedAt: Long = 0L,
    val members: String = "",
)

@Dao
interface RcsMlsIdentityDao {
    @Query("SELECT * FROM rcs_mls_identity WHERE e164 = :e164 LIMIT 1")
    suspend fun get(e164: String): RcsMlsIdentity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(identity: RcsMlsIdentity)

    @Query("DELETE FROM rcs_mls_identity WHERE e164 = :e164")
    suspend fun delete(e164: String)
}

@Dao
interface RcsMlsGroupDao {
    @Query("SELECT * FROM rcs_mls_group WHERE groupIdHex = :groupIdHex LIMIT 1")
    suspend fun get(groupIdHex: String): RcsMlsGroup?

    @Query("SELECT * FROM rcs_mls_group WHERE conversationId = :conversationId LIMIT 1")
    suspend fun getByConversation(conversationId: String): RcsMlsGroup?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(group: RcsMlsGroup)

    @Query("DELETE FROM rcs_mls_group WHERE groupIdHex = :groupIdHex")
    suspend fun delete(groupIdHex: String)

    @Query("DELETE FROM rcs_mls_group WHERE conversationId = :conversationId")
    suspend fun deleteByConversation(conversationId: String)
}

// -- Deferred-delivery outbox (§4.3) --

/**
 * A message that failed to send and waits for retry: pager text, FT
 * reference, or group fan-out remainder. [kind] is `text` / `file` / `group`;
 * [body] carries the text (or caption); [attachments] the content URIs CSV
 * for files. Retried with backoff until [expiryMs], then marked failed.
 */
@Entity(tableName = "rcs_outbox")
data class RcsOutboxMessage(
    @PrimaryKey val messageId: String,
    val conversationId: String,
    val kind: String,
    val body: String,
    val attachments: String = "",
    val attempts: Int = 0,
    val nextRetryMs: Long,
    val expiryMs: Long,
    val createdAt: Long = 0L,
)

@Dao
interface RcsOutboxDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: RcsOutboxMessage)

    @Query("SELECT * FROM rcs_outbox WHERE nextRetryMs <= :nowMs ORDER BY nextRetryMs ASC LIMIT :limit")
    suspend fun dueForRetry(nowMs: Long, limit: Int = 20): List<RcsOutboxMessage>

    @Query("SELECT * FROM rcs_outbox WHERE messageId = :messageId LIMIT 1")
    suspend fun get(messageId: String): RcsOutboxMessage?

    @Query("DELETE FROM rcs_outbox WHERE messageId = :messageId")
    suspend fun delete(messageId: String)

    @Query("DELETE FROM rcs_outbox WHERE conversationId = :conversationId")
    suspend fun deleteConversation(conversationId: String)

    @Query("DELETE FROM rcs_outbox")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM rcs_outbox")
    suspend fun count(): Int
}
