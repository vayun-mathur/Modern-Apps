package com.vayunmathur.messages.whatsapp

import android.content.Context
import androidx.room.Database
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.vayunmathur.library.room.buildDatabase

/**
 * Room database for WhatsApp-specific data.
 * Stores device info, session keys, conversations, media requests, and avatar cache.
 * Aligned with Go wadb.Database which has: Conversation, Message, PollOption,
 * MediaRequest, HSNotif, AvatarCache queries.
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
        // libsignal-backed E2E protocol stores
        WhatsAppE2ESession::class,
        WhatsAppE2EIdentity::class,
        WhatsAppE2EPreKey::class,
        WhatsAppE2ESignedPreKey::class,
        WhatsAppE2ESenderKey::class,
    ],
    version = 6,
    exportSchema = false
)
@TypeConverters(WhatsAppTypeConverters::class)
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

    companion object {
        @Volatile
        private var INSTANCE: WhatsAppDatabase? = null

        fun getDatabase(context: Context): WhatsAppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = context.applicationContext.buildDatabase<WhatsAppDatabase>(
                    dbName = "whatsapp_database"
                )
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
)

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

@Dao
interface WhatsAppE2EIdentityDao {
    @Query("SELECT * FROM whatsapp_e2e_identities WHERE address = :address LIMIT 1")
    suspend fun get(address: String): WhatsAppE2EIdentity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: WhatsAppE2EIdentity)

    @Query("DELETE FROM whatsapp_e2e_identities WHERE address = :address")
    suspend fun delete(address: String)
}

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

@Dao
interface WhatsAppE2ESenderKeyDao {
    @Query("SELECT * FROM whatsapp_e2e_sender_keys WHERE address = :address AND deviceId = :deviceId AND distributionId = :distributionId LIMIT 1")
    suspend fun get(address: String, deviceId: Int, distributionId: String): WhatsAppE2ESenderKey?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: WhatsAppE2ESenderKey)

    @Query("DELETE FROM whatsapp_e2e_sender_keys WHERE address = :address AND deviceId = :deviceId AND distributionId = :distributionId")
    suspend fun delete(address: String, deviceId: Int, distributionId: String)
}
