package com.vayunmathur.communicate.data.whatsapp

import android.content.Context
import androidx.room3.ColumnTypeConverter
import androidx.room3.ColumnTypeConverters
import androidx.room3.Database
import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.vayunmathur.library.util.DatabaseMigrations

/**
 * Room database for WhatsApp-specific data.
 * Stores device info, session keys, conversations, media requests, and avatar cache.
 * Aligned with Go wadb.Database which has: Conversation, Message, PollOption,
 * MediaRequest, HSNotif, AvatarCache queries.
 *
 * v7 (Rust migration): E2E tables now hold Rust's own versioned blobs (RECORD_VERSION=1)
 * rather than Java SignalRecord. Old Java blobs are incompatible, so migration clears E2E tables.
 *
 * communicate v2: adds message delivery status (sent/delivered/read ticks) and group metadata
 * (isGroup/name/participants) on conversations.
 */
@Database(
    entities = [
        WhatsAppDevice::class,
        WhatsAppSession::class,
        WhatsAppConversation::class,
        WhatsAppMediaRequest::class,
        WhatsAppAvatarCache::class,
        WhatsAppPollOption::class,
        WhatsAppPollSecret::class,
        // Rust-backed E2E protocol stores (was libsignal-backed)
        WhatsAppE2ESession::class,
        WhatsAppE2EIdentity::class,
        WhatsAppE2EPreKey::class,
        WhatsAppE2ESignedPreKey::class,
        WhatsAppE2ESenderKey::class,
        // communicate additions: message/reaction cache for the merged inbox.
        WhatsAppCachedMessage::class,
        WhatsAppCachedReaction::class,
        // communicate v3: device-contact sync (address book → LID/phone mapping).
        WhatsAppContact::class,
        // communicate v4: call log.
        WhatsAppCallLog::class,
    ],
    version = 4,
    exportSchema = true
)
@ColumnTypeConverters(WhatsAppTypeConverters::class)
abstract class WhatsAppDatabase : RoomDatabase() {
    abstract fun deviceDao(): WhatsAppDeviceDao
    abstract fun sessionDao(): WhatsAppSessionDao
    abstract fun conversationDao(): WhatsAppConversationDao
    abstract fun mediaRequestDao(): WhatsAppMediaRequestDao
    abstract fun avatarCacheDao(): WhatsAppAvatarCacheDao
    abstract fun pollOptionDao(): WhatsAppPollOptionDao
    abstract fun pollSecretDao(): WhatsAppPollSecretDao
    abstract fun e2eSessionDao(): WhatsAppE2ESessionDao
    abstract fun e2eIdentityDao(): WhatsAppE2EIdentityDao
    abstract fun e2ePreKeyDao(): WhatsAppE2EPreKeyDao
    abstract fun e2eSignedPreKeyDao(): WhatsAppE2ESignedPreKeyDao
    abstract fun e2eSenderKeyDao(): WhatsAppE2ESenderKeyDao
    abstract fun cachedMessageDao(): WhatsAppCachedMessageDao
    abstract fun cachedReactionDao(): WhatsAppCachedReactionDao
    abstract fun contactDao(): WhatsAppContactDao
    abstract fun callLogDao(): WhatsAppCallLogDao

    /** Schema migrations and the singleton accessor for [WhatsAppDatabase]. */
    companion object : DatabaseMigrations {
        /**
         * v1 → v2: message delivery status (ticks) + group metadata on conversations. All new
         * columns are additive with defaults, so existing rows stay valid.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE whatsapp_cached_message ADD COLUMN status INTEGER NOT NULL DEFAULT 0",
                )
                connection.execSQL(
                    "ALTER TABLE whatsapp_history_sync_conversation ADD COLUMN isGroup INTEGER NOT NULL DEFAULT 0",
                )
                connection.execSQL(
                    "ALTER TABLE whatsapp_history_sync_conversation ADD COLUMN name TEXT",
                )
                connection.execSQL(
                    "ALTER TABLE whatsapp_history_sync_conversation ADD COLUMN participants TEXT NOT NULL DEFAULT ''",
                )
            }
        }

        /** v2 → v3: device-contact sync table (address book → WhatsApp LID/phone mapping). */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS whatsapp_contact (" +
                        "phoneE164 TEXT NOT NULL PRIMARY KEY, " +
                        "lid TEXT NOT NULL DEFAULT '', " +
                        "displayName TEXT NOT NULL DEFAULT '', " +
                        "onWhatsApp INTEGER NOT NULL DEFAULT 0, " +
                        "updatedAt INTEGER NOT NULL DEFAULT 0)",
                )
            }
        }

        /** v3 → v4: call-log table for the WhatsApp calling stack. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS whatsapp_call_log (" +
                        "callId TEXT NOT NULL PRIMARY KEY, " +
                        "peerJid TEXT NOT NULL DEFAULT '', " +
                        "peerName TEXT NOT NULL DEFAULT '', " +
                        "outgoing INTEGER NOT NULL DEFAULT 0, " +
                        "video INTEGER NOT NULL DEFAULT 0, " +
                        "startTime INTEGER NOT NULL DEFAULT 0, " +
                        "durationSeconds INTEGER NOT NULL DEFAULT 0, " +
                        "outcome TEXT NOT NULL DEFAULT '')",
                )
            }
        }

        override val migrations: List<Migration> = listOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)

        /**
         * Test seam: when set, [getDatabase] returns this instance instead
         * of the SQLCipher-backed singleton (whose native library cannot
         * load on a JVM test host). Production code never sets it.
         */
        @Volatile
        internal var overrideForTest: WhatsAppDatabase? = null

        fun getDatabase(context: Context): WhatsAppDatabase =
            overrideForTest ?: WhatsAppRepository.get(context).database()
    }
}

/** Room column converters for WhatsApp entities; string lists are stored as CSV. */
class WhatsAppTypeConverters {
    @ColumnTypeConverter
    fun fromStringList(value: List<String>): String {
        return value.joinToString(",")
    }

    @ColumnTypeConverter
    fun toStringList(value: String): List<String> {
        return if (value.isEmpty()) emptyList() else value.split(",")
    }
}

/**
 * History sync conversation, matching Go wadb.Conversation.
 */
@Entity(tableName = "whatsapp_history_sync_conversation")
data class WhatsAppConversation(
    @PrimaryKey
    val chatJid: String,
    val userLoginId: String = "",
    val lastMessageTimestamp: Long = 0L,
    val archived: Boolean = false,
    val pinned: Boolean = false,
    val muteEndTime: Long = 0L,
    val endOfHistoryTransferType: Int = 0,
    val ephemeralExpiration: Long = 0L,
    val ephemeralSettingTimestamp: Long = 0L,
    val markedAsUnread: Boolean = false,
    val unreadCount: Int = 0,
    /** communicate v2: true for group chats (@g.us). */
    val isGroup: Boolean = false,
    /** communicate v2: group subject / display name when known. */
    val name: String? = null,
    /** communicate v2: CSV of participant JIDs for groups (empty for 1:1). */
    val participants: String = "",
)

/** Queries for WhatsApp conversations. */
@Dao
interface WhatsAppConversationDao {
    @Query("SELECT * FROM whatsapp_history_sync_conversation WHERE chatJid = :chatJid")
    suspend fun getConversation(chatJid: String): WhatsAppConversation?

    @Query("SELECT * FROM whatsapp_history_sync_conversation ORDER BY lastMessageTimestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<WhatsAppConversation>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(conversation: WhatsAppConversation)

    @Query("DELETE FROM whatsapp_history_sync_conversation WHERE chatJid = :chatJid")
    suspend fun delete(chatJid: String)

    @Query("DELETE FROM whatsapp_history_sync_conversation")
    suspend fun deleteAll()

    @Query("UPDATE whatsapp_history_sync_conversation SET muteEndTime = :muteEndTime WHERE chatJid = :chatJid")
    suspend fun updateMuteEndTime(chatJid: String, muteEndTime: Long)

    @Query("UPDATE whatsapp_history_sync_conversation SET pinned = :pinned WHERE chatJid = :chatJid")
    suspend fun updatePinned(chatJid: String, pinned: Boolean)

    @Query("UPDATE whatsapp_history_sync_conversation SET archived = :archived WHERE chatJid = :chatJid")
    suspend fun updateArchived(chatJid: String, archived: Boolean)

    @Query("UPDATE whatsapp_history_sync_conversation SET markedAsUnread = :unread WHERE chatJid = :chatJid")
    suspend fun updateMarkedAsUnread(chatJid: String, unread: Boolean)
}

/**
 * Media backfill request, matching Go wadb.MediaRequest.
 */
@Entity(tableName = "whatsapp_media_backfill_request")
data class WhatsAppMediaRequest(
    @PrimaryKey
    val messageId: String,
    val userLoginId: String = "",
    val portalId: String = "",
    val portalReceiver: String = "",
    val mediaKey: ByteArray = ByteArray(0),
    val status: Int = 0, // 0=not_requested, 1=requested, 2=failed, 3=skipped
    val error: String = "",
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as WhatsAppMediaRequest
        return messageId == other.messageId
    }

    override fun hashCode(): Int = messageId.hashCode()
}

/** Queries for pending WhatsApp media backfill requests. */
@Dao
interface WhatsAppMediaRequestDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(request: WhatsAppMediaRequest)

    @Query("DELETE FROM whatsapp_media_backfill_request WHERE messageId = :messageId")
    suspend fun delete(messageId: String)

    @Query("SELECT * FROM whatsapp_media_backfill_request WHERE status = 0")
    suspend fun getUnrequested(): List<WhatsAppMediaRequest>
}

/**
 * Avatar cache entry, matching Go wadb.AvatarCacheEntry.
 */
@Entity(tableName = "whatsapp_avatar_cache", primaryKeys = ["entityJid", "avatarId"])
data class WhatsAppAvatarCache(
    val entityJid: String,
    val avatarId: String,
    val directPath: String = "",
    val expiry: Long = 0L,
    val gone: Boolean = false,
)

/** Queries for the WhatsApp avatar cache. */
@Dao
interface WhatsAppAvatarCacheDao {
    @Query("SELECT * FROM whatsapp_avatar_cache WHERE entityJid = :entityJid AND avatarId = :avatarId")
    suspend fun get(entityJid: String, avatarId: String): WhatsAppAvatarCache?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: WhatsAppAvatarCache)
}

/**
 * Poll option hash mapping, matching Go wadb.PollOption.
 * Maps SHA256 option hashes to option string IDs for poll vote resolution.
 */
@Entity(tableName = "whatsapp_poll_option", primaryKeys = ["msgId", "optionHash"])
data class WhatsAppPollOption(
    val msgId: String,
    val optionHash: String,
    val optionName: String,
)

/** Queries for WhatsApp poll options. */
@Dao
interface WhatsAppPollOptionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(option: WhatsAppPollOption)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(options: List<WhatsAppPollOption>)

    @Query("SELECT * FROM whatsapp_poll_option WHERE msgId = :msgId")
    suspend fun getByMessageId(msgId: String): List<WhatsAppPollOption>

    @Query("SELECT * FROM whatsapp_poll_option WHERE msgId = :msgId AND optionHash = :hash")
    suspend fun getByHash(msgId: String, hash: String): WhatsAppPollOption?

    @Query("DELETE FROM whatsapp_poll_option WHERE msgId = :msgId")
    suspend fun deleteByMessageId(msgId: String)
}

/**
 * Per-poll shared secret (MessageContextInfo.messageSecret, hex-encoded), keyed by the poll
 * creation message id. Needed to encrypt our votes and decrypt incoming votes; persisted so
 * voting works after a restart, not just within the session.
 */
@Entity(tableName = "whatsapp_poll_secret")
data class WhatsAppPollSecret(
    @PrimaryKey val msgId: String,
    val secret: String,
)

/** Queries for WhatsApp poll vote secrets. */
@Dao
interface WhatsAppPollSecretDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(secret: WhatsAppPollSecret)

    @Query("SELECT secret FROM whatsapp_poll_secret WHERE msgId = :msgId")
    suspend fun get(msgId: String): String?
}

// -- libsignal-backed E2E protocol stores (whatsmeow signal store equivalents) --

/** Signal session record keyed by (signalAddress, deviceId). */
@Entity(tableName = "whatsapp_e2e_sessions", primaryKeys = ["address", "deviceId"])
data class WhatsAppE2ESession(
    val address: String,
    val deviceId: Int,
    val record: ByteArray,
)

/** Remote identity key keyed by signal address (user[:device] string). */
@Entity(tableName = "whatsapp_e2e_identities")
data class WhatsAppE2EIdentity(
    @PrimaryKey val address: String,
    val identityKey: ByteArray,
)

/** One-time pre key record keyed by id. */
@Entity(tableName = "whatsapp_e2e_pre_keys")
data class WhatsAppE2EPreKey(
    @PrimaryKey val id: Int,
    val record: ByteArray,
    val uploaded: Boolean = false,
)

/** Signed pre key record keyed by id. */
@Entity(tableName = "whatsapp_e2e_signed_pre_keys")
data class WhatsAppE2ESignedPreKey(
    @PrimaryKey val id: Int,
    val record: ByteArray,
)

/** Sender (group) key record keyed by (address, deviceId, distributionId). */
@Entity(tableName = "whatsapp_e2e_sender_keys", primaryKeys = ["address", "deviceId", "distributionId"])
data class WhatsAppE2ESenderKey(
    val address: String,
    val deviceId: Int,
    val distributionId: String,
    val record: ByteArray,
)

/** Queries for WhatsApp E2E (Signal protocol) session records. */
@Dao
interface WhatsAppE2ESessionDao {
    @Query("SELECT * FROM whatsapp_e2e_sessions WHERE address = :address AND deviceId = :deviceId LIMIT 1")
    suspend fun get(address: String, deviceId: Int): WhatsAppE2ESession?

    @Query("SELECT deviceId FROM whatsapp_e2e_sessions WHERE address = :address")
    suspend fun getSubDeviceIds(address: String): List<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: WhatsAppE2ESession)

    @Query("SELECT COUNT(*) > 0 FROM whatsapp_e2e_sessions WHERE address = :address AND deviceId = :deviceId")
    suspend fun exists(address: String, deviceId: Int): Boolean

    @Query("DELETE FROM whatsapp_e2e_sessions WHERE address = :address AND deviceId = :deviceId")
    suspend fun delete(address: String, deviceId: Int)

    @Query("DELETE FROM whatsapp_e2e_sessions WHERE address = :address")
    suspend fun deleteAll(address: String)
}

/** Queries for WhatsApp peer identity keys. */
@Dao
interface WhatsAppE2EIdentityDao {
    @Query("SELECT * FROM whatsapp_e2e_identities WHERE address = :address LIMIT 1")
    suspend fun get(address: String): WhatsAppE2EIdentity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: WhatsAppE2EIdentity)

    @Query("DELETE FROM whatsapp_e2e_identities WHERE address = :address")
    suspend fun delete(address: String)
}

/** Queries for our WhatsApp one-time EC pre-keys. */
@Dao
interface WhatsAppE2EPreKeyDao {
    @Query("SELECT * FROM whatsapp_e2e_pre_keys WHERE id = :id LIMIT 1")
    suspend fun get(id: Int): WhatsAppE2EPreKey?

    @Query("SELECT * FROM whatsapp_e2e_pre_keys")
    suspend fun getAll(): List<WhatsAppE2EPreKey>

    @Query("SELECT * FROM whatsapp_e2e_pre_keys WHERE uploaded = 0")
    suspend fun getUnuploaded(): List<WhatsAppE2EPreKey>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: WhatsAppE2EPreKey)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<WhatsAppE2EPreKey>)

    @Query("UPDATE whatsapp_e2e_pre_keys SET uploaded = 1 WHERE id <= :maxId")
    suspend fun markUploadedUpTo(maxId: Int)

    @Query("SELECT COUNT(*) > 0 FROM whatsapp_e2e_pre_keys WHERE id = :id")
    suspend fun exists(id: Int): Boolean

    @Query("DELETE FROM whatsapp_e2e_pre_keys WHERE id = :id")
    suspend fun delete(id: Int)

    @Query("SELECT COUNT(*) FROM whatsapp_e2e_pre_keys")
    suspend fun getCount(): Int

    @Query("SELECT COALESCE(MAX(id), 0) FROM whatsapp_e2e_pre_keys")
    suspend fun getMaxId(): Int
}

/** Queries for our WhatsApp signed EC pre-keys. */
@Dao
interface WhatsAppE2ESignedPreKeyDao {
    @Query("SELECT * FROM whatsapp_e2e_signed_pre_keys WHERE id = :id LIMIT 1")
    suspend fun get(id: Int): WhatsAppE2ESignedPreKey?

    @Query("SELECT * FROM whatsapp_e2e_signed_pre_keys")
    suspend fun getAll(): List<WhatsAppE2ESignedPreKey>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: WhatsAppE2ESignedPreKey)

    @Query("SELECT COUNT(*) > 0 FROM whatsapp_e2e_signed_pre_keys WHERE id = :id")
    suspend fun exists(id: Int): Boolean

    @Query("DELETE FROM whatsapp_e2e_signed_pre_keys WHERE id = :id")
    suspend fun delete(id: Int)
}

/** Queries for WhatsApp group sender-key records. */
@Dao
interface WhatsAppE2ESenderKeyDao {
    @Query("SELECT * FROM whatsapp_e2e_sender_keys WHERE address = :address AND " +
        "deviceId = :deviceId AND distributionId = :distributionId LIMIT 1")
    suspend fun get(address: String, deviceId: Int, distributionId: String): WhatsAppE2ESenderKey?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: WhatsAppE2ESenderKey)

    @Query("DELETE FROM whatsapp_e2e_sender_keys WHERE address = :address " +
        "AND deviceId = :deviceId AND distributionId = :distributionId")
    suspend fun delete(address: String, deviceId: Int, distributionId: String)
}

// -- communicate message/thread cache (merged inbox + restart persistence) --

/**
 * Cached WhatsApp message for the merged inbox. `serviceData` carries the rich-feature JSON
 * (reactions/poll/edited/revoked/quoted/group) that [WhatsAppServiceData] serializes.
 */
@Entity(tableName = "whatsapp_cached_message")
data class WhatsAppCachedMessage(
    @PrimaryKey val messageId: String,
    val conversationJid: String,
    val body: String,
    val timestamp: Long,
    val outgoing: Boolean,
    val senderJid: String = "",
    val senderName: String = "",
    val isEdited: Boolean = false,
    val isRevoked: Boolean = false,
    val quotedMessageId: String? = null,
    val mediaUrl: String? = null,
    val mediaMime: String? = null,
    val mediaName: String? = null,
    val serviceData: String? = null,
    /**
     * communicate v2: outgoing delivery status, encoded as the [com.vayunmathur.communicate.data.MessageStatus]
     * ordinal (0=None, 1=Sent, 2=Delivered, 3=Read, 4=Failed). Inbound messages stay 0.
     */
    val status: Int = 0,
)

/** Queries for cached WhatsApp messages. */
@Dao
interface WhatsAppCachedMessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: WhatsAppCachedMessage)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(messages: List<WhatsAppCachedMessage>)

    @Query("SELECT * FROM whatsapp_cached_message WHERE conversationJid = :jid ORDER BY timestamp ASC")
    suspend fun getForConversation(jid: String): List<WhatsAppCachedMessage>

    @Query("SELECT * FROM whatsapp_cached_message WHERE messageId = :messageId LIMIT 1")
    suspend fun get(messageId: String): WhatsAppCachedMessage?

    /** Most-recent message per conversation, newest first (for the thread list). */
    @Query(
        "SELECT * FROM whatsapp_cached_message WHERE timestamp IN " +
            "(SELECT MAX(timestamp) FROM whatsapp_cached_message GROUP BY conversationJid) " +
            "ORDER BY timestamp DESC",
    )
    suspend fun getLatestPerConversation(): List<WhatsAppCachedMessage>

    @Query("UPDATE whatsapp_cached_message SET body = :body, isEdited = 1 WHERE messageId = :messageId")
    suspend fun markEdited(messageId: String, body: String)

    @Query("UPDATE whatsapp_cached_message SET isRevoked = 1, body = '' WHERE messageId = :messageId")
    suspend fun markRevoked(messageId: String)

    @Query("UPDATE whatsapp_cached_message SET serviceData = :serviceData WHERE messageId = :messageId")
    suspend fun updateServiceData(messageId: String, serviceData: String?)

    /** Advance to Delivered (2) only from a lower state (won't downgrade a Read message). */
    @Query("UPDATE whatsapp_cached_message SET status = 2 WHERE messageId = :messageId AND status < 2")
    suspend fun markDelivered(messageId: String)

    /** Advance to Read (3) only from a lower state. */
    @Query("UPDATE whatsapp_cached_message SET status = 3 WHERE messageId = :messageId AND status < 3")
    suspend fun markReadStatus(messageId: String)

    @Query("DELETE FROM whatsapp_cached_message WHERE conversationJid = :jid")
    suspend fun deleteConversation(jid: String)

    @Query("DELETE FROM whatsapp_cached_message")
    suspend fun deleteAll()
}

/** A single reaction on a cached message (one row per reactor). */
@Entity(tableName = "whatsapp_cached_reaction", primaryKeys = ["messageId", "senderJid"])
data class WhatsAppCachedReaction(
    val messageId: String,
    val emoji: String,
    val senderJid: String,
    val timestamp: Long,
)

/** Queries for cached WhatsApp message reactions. */
@Dao
interface WhatsAppCachedReactionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(reaction: WhatsAppCachedReaction)

    @Query("SELECT * FROM whatsapp_cached_reaction WHERE messageId = :messageId")
    suspend fun getForMessage(messageId: String): List<WhatsAppCachedReaction>

    @Query("DELETE FROM whatsapp_cached_reaction WHERE messageId = :messageId AND senderJid = :senderJid")
    suspend fun remove(messageId: String, senderJid: String)

    @Query("DELETE FROM whatsapp_cached_reaction WHERE messageId = :messageId")
    suspend fun clearForMessage(messageId: String)
}

// -- communicate device-contact sync (address book → WhatsApp LID/phone mapping, Phase C 2f) --

/**
 * A device address-book contact synced to WhatsApp. Keyed by the E.164 phone number; [lid] and
 * [onWhatsApp] are filled in from `xwa2_primary_contacts_full_sync` / `xwa2_contact_discovery`
 * results, [displayName] from the device contact name.
 */
@Entity(tableName = "whatsapp_contact")
data class WhatsAppContact(
    @PrimaryKey val phoneE164: String,
    val lid: String = "",
    val displayName: String = "",
    val onWhatsApp: Boolean = false,
    val updatedAt: Long = 0L,
)

/** Queries for WhatsApp contacts. */
@Dao
interface WhatsAppContactDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(contact: WhatsAppContact)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(contacts: List<WhatsAppContact>)

    @Query("SELECT * FROM whatsapp_contact WHERE phoneE164 = :phoneE164 LIMIT 1")
    suspend fun get(phoneE164: String): WhatsAppContact?

    @Query("SELECT * FROM whatsapp_contact WHERE lid = :lid LIMIT 1")
    suspend fun getByLid(lid: String): WhatsAppContact?

    @Query("SELECT * FROM whatsapp_contact WHERE onWhatsApp = 1 ORDER BY displayName COLLATE NOCASE ASC")
    suspend fun getOnWhatsApp(): List<WhatsAppContact>

    @Query("SELECT * FROM whatsapp_contact")
    suspend fun getAll(): List<WhatsAppContact>

    @Query("DELETE FROM whatsapp_contact")
    suspend fun deleteAll()
}

// -- communicate call log (Phase D 3e) --

/** A completed/attempted WhatsApp call, keyed by call id. */
@Entity(tableName = "whatsapp_call_log")
data class WhatsAppCallLog(
    @PrimaryKey val callId: String,
    val peerJid: String = "",
    val peerName: String = "",
    val outgoing: Boolean = false,
    val video: Boolean = false,
    val startTime: Long = 0L,
    val durationSeconds: Long = 0L,
    /** "answered" | "missed" | "rejected" | "failed" | reason string. */
    val outcome: String = "",
)

/** Queries for the WhatsApp call log. */
@Dao
interface WhatsAppCallLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: WhatsAppCallLog)

    @Query("SELECT * FROM whatsapp_call_log ORDER BY startTime DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 200): List<WhatsAppCallLog>

    @Query("SELECT * FROM whatsapp_call_log WHERE callId = :callId LIMIT 1")
    suspend fun get(callId: String): WhatsAppCallLog?

    @Query("DELETE FROM whatsapp_call_log")
    suspend fun deleteAll()
}
