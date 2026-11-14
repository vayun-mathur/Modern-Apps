package com.vayunmathur.communicate.data.signal

import android.content.Context
import androidx.room3.ColumnInfo
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
import com.vayunmathur.library.util.DatabaseMigrations

/**
 * Room database for Signal-specific data.
 * Mirrors [com.vayunmathur.communicate.data.whatsapp.WhatsAppDatabase] but for Signal.
 *
 * Entities/DAOs mirror WhatsApp's structure: conversations, cached messages/reactions,
 * contacts, E2E session/identity/prekey/sender-key stores, and call log.
 */
@Database(
    entities = [
        SignalDevice::class,
        SignalSession::class,
        SignalConversation::class,
        SignalCachedMessage::class,
        SignalCachedReaction::class,
        SignalContact::class,
        SignalE2ESession::class,
        SignalE2EIdentity::class,
        SignalE2EPreKey::class,
        SignalE2ESignedPreKey::class,
        SignalE2EKyberPreKey::class,
        SignalE2EKyberUsedBaseKey::class,
        SignalE2ESenderKey::class,
        SignalSenderCertificate::class,
        SignalProfileKey::class,
        SignalCallLog::class,
    ],
    version = 5,
    exportSchema = false
)
@ColumnTypeConverters(SignalTypeConverters::class)
abstract class SignalDatabase : RoomDatabase() {
    abstract fun deviceDao(): SignalDeviceDao
    abstract fun sessionDao(): SignalSessionDao
    abstract fun conversationDao(): SignalConversationDao
    abstract fun cachedMessageDao(): SignalCachedMessageDao
    abstract fun cachedReactionDao(): SignalCachedReactionDao
    abstract fun contactDao(): SignalContactDao
    abstract fun e2eSessionDao(): SignalE2ESessionDao
    abstract fun e2eIdentityDao(): SignalE2EIdentityDao
    abstract fun e2ePreKeyDao(): SignalE2EPreKeyDao
    abstract fun e2eSignedPreKeyDao(): SignalE2ESignedPreKeyDao
    abstract fun e2eKyberPreKeyDao(): SignalE2EKyberPreKeyDao
    abstract fun e2eKyberUsedBaseKeyDao(): SignalE2EKyberUsedBaseKeyDao
    abstract fun e2eSenderKeyDao(): SignalE2ESenderKeyDao
    abstract fun senderCertificateDao(): SignalSenderCertificateDao
    abstract fun profileKeyDao(): SignalProfileKeyDao
    abstract fun callLogDao(): SignalCallLogDao

    /** Schema migrations and the singleton accessor for [SignalDatabase]. */
    companion object : DatabaseMigrations {
        /**
         * v1 → v2: Kyber pre-key storage. The protocol store needs somewhere to keep our own Kyber
         * pre-keys, plus the (kyberPreKeyId, signedPreKeyId, baseKey) tuples that make last-resort
         * keys single-use — reusing one is what `ReusedBaseKeyException` guards against.
         */
        private val MIGRATION_1_2 = object : androidx.room3.migration.Migration(1, 2) {
            override suspend fun migrate(connection: SQLiteConnection) {
                // Must match Room's generated createAllTables statements byte for byte, including the
                // absence of column defaults — TableInfo validation compares them and throws on a
                // mismatch. Both tables are new, so there is nothing to backfill.
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `signal_e2e_kyber_pre_keys` (" +
                        "`id` INTEGER NOT NULL, `record` BLOB NOT NULL, " +
                        "`lastResort` INTEGER NOT NULL, `uploaded` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))",
                )
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `signal_e2e_kyber_used_base_keys` (" +
                        "`kyberPreKeyId` INTEGER NOT NULL, `signedPreKeyId` INTEGER NOT NULL, " +
                        "`baseKeyB64` TEXT NOT NULL, " +
                        "PRIMARY KEY(`kyberPreKeyId`, `signedPreKeyId`, `baseKeyB64`))",
                )
            }
        }

        /**
         * v2 → v3: sealed sender. The delivery certificate is a rotating credential, and recipient
         * profile keys are secret material used to derive unidentified-access keys — both belong in the
         * encrypted database rather than the plaintext preferences blob.
         */
        private val MIGRATION_2_3 = object : androidx.room3.migration.Migration(2, 3) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `signal_sender_certificate` (" +
                        "`id` INTEGER NOT NULL, `record` BLOB NOT NULL, " +
                        "`expiration` INTEGER NOT NULL, `includesE164` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))",
                )
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `signal_profile_keys` (" +
                        "`address` TEXT NOT NULL, `profileKey` BLOB NOT NULL, " +
                        "PRIMARY KEY(`address`))",
                )
            }
        }

        /**
         * v3 → v4: store the group master key in its own column.
         *
         * Conversation ids used to be the hex of the master key's first 8 bytes, which both leaked secret
         * material and was not the identifier the protocol uses. They are now the derived 32-byte
         * `GroupIdentifier`. Existing group rows cannot be converted — the full master key was never
         * stored, so the new id is not recoverable from the old one — so they are dropped and will be
         * recreated from inbound traffic.
         */
        private val MIGRATION_3_4 = object : androidx.room3.migration.Migration(3, 4) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE signal_conversation ADD COLUMN groupMasterKey BLOB")
                connection.execSQL("ALTER TABLE signal_conversation ADD COLUMN" +
                    "groupRevision INTEGER NOT NULL DEFAULT 0")
                connection.execSQL("DELETE FROM signal_cached_message WHERE conversationId LIKE 'group:%'")
                connection.execSQL("DELETE FROM signal_conversation WHERE chatId LIKE 'group:%'")
            }
        }

        /** v4 → v5: keep the PNI from contact discovery, which is how a new contact is addressed. */
        private val MIGRATION_4_5 = object : androidx.room3.migration.Migration(4, 5) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE signal_contact ADD COLUMN pni TEXT NOT NULL DEFAULT ''")
            }
        }

        override val migrations = listOf<androidx.room3.migration.Migration>(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5)

        fun getDatabase(context: Context): SignalDatabase =
            SignalRepository.get(context).database()
    }
}

/** Room column converters for Signal entities; string lists are stored as CSV. */
class SignalTypeConverters {
    @ColumnTypeConverter
    fun fromStringList(value: List<String>): String = value.joinToString(",")

    @ColumnTypeConverter
    fun toStringList(value: String): List<String> =
        if (value.isEmpty()) emptyList() else value.split(",")
}

// -- Device / session (transport) --

/** A linked Signal device: its device id, phone number, platform and last-seen time. */
@Entity(tableName = "signal_devices")
data class SignalDevice(
    @PrimaryKey val deviceId: String,
    val phoneNumber: String,
    val pushName: String,
    val platform: String,
    val lastSeen: Long,
    val jid: String = "",
    val timezone: String = "",
    val loggedInAt: Long = 0L,
)

/** A stored Signal protocol session record for one peer address (legacy transport table). */
@Entity(tableName = "signal_sessions")
data class SignalSession(
    @PrimaryKey val jid: String,
    val sessionData: ByteArray,
    val timestamp: Long,
    val isPreKey: Boolean = false,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SignalSession
        return jid == other.jid && sessionData.contentEquals(other.sessionData) && timestamp == other.timestamp
    }
    override fun hashCode(): Int {
        var result = jid.hashCode()
        result = 31 * result + sessionData.contentHashCode()
        result = 31 * result + timestamp.hashCode()
        return result
    }
}

/** Queries for linked Signal devices. */
@Dao
interface SignalDeviceDao {
    @Query("SELECT * FROM signal_devices WHERE deviceId = :deviceId")
    suspend fun getDevice(deviceId: String): SignalDevice?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDevice(device: SignalDevice)

    @Query("DELETE FROM signal_devices WHERE deviceId = :deviceId")
    suspend fun deleteDevice(deviceId: String)
}

/** Queries for stored Signal protocol sessions. */
@Dao
interface SignalSessionDao {
    @Query("SELECT * FROM signal_sessions WHERE jid = :jid")
    suspend fun getSession(jid: String): SignalSession?

    @Query("SELECT EXISTS(SELECT 1 FROM signal_sessions WHERE jid = :jid)")
    suspend fun containsSession(jid: String): Boolean

    @Query("SELECT * FROM signal_sessions")
    suspend fun getAllSessions(): List<SignalSession>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: SignalSession)

    @Query("DELETE FROM signal_sessions WHERE jid = :jid")
    suspend fun deleteSession(jid: String)

    @Query("DELETE FROM signal_sessions")
    suspend fun clearAllSessions()
}

// -- Conversation --

/** A Signal conversation row: a 1:1 chat keyed by address or a group keyed by its derived group id. */
@Entity(tableName = "signal_conversation")
data class SignalConversation(
    @PrimaryKey val chatId: String,
    val isGroup: Boolean = false,
    val name: String? = null,
    /** CSV of participant identifiers (ACIs/phone numbers) for groups. */
    val participants: String = "",
    val unreadCount: Int = 0,
    val lastMessageTimestamp: Long = 0L,
    val archived: Boolean = false,
    val pinned: Boolean = false,
    /**
     * The group's 32-byte master key. Secret material: it encrypts the group's attributes and
     * membership, so it is stored here rather than being folded into [chatId], which is the derived
     * public identifier.
     */
    val groupMasterKey: ByteArray? = null,
    // Declared here so the ALTER TABLE default in MIGRATION_3_4 matches what Room expects; adding a
    // NOT NULL column requires a default, and TableInfo validation compares them.
    @ColumnInfo(defaultValue = "0") val groupRevision: Int = 0,
)

/** Queries for Signal conversations. */
@Dao
interface SignalConversationDao {
    @Query("SELECT * FROM signal_conversation WHERE chatId = :chatId")
    suspend fun getConversation(chatId: String): SignalConversation?

    @Query("SELECT * FROM signal_conversation ORDER BY lastMessageTimestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<SignalConversation>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(conversation: SignalConversation)

    @Query("DELETE FROM signal_conversation WHERE chatId = :chatId")
    suspend fun delete(chatId: String)

    @Query("DELETE FROM signal_conversation")
    suspend fun deleteAll()

    @Query("UPDATE signal_conversation SET pinned = :pinned WHERE chatId = :chatId")
    suspend fun updatePinned(chatId: String, pinned: Boolean)

    @Query("UPDATE signal_conversation SET archived = :archived WHERE chatId = :chatId")
    suspend fun updateArchived(chatId: String, archived: Boolean)
}

// -- Cached messages --

/** A cached Signal message row, including edit/revoke state, media pointer and delivery status. */
@Entity(tableName = "signal_cached_message")
data class SignalCachedMessage(
    @PrimaryKey val messageId: String,
    val conversationId: String,
    val body: String,
    val timestamp: Long,
    val outgoing: Boolean,
    val senderId: String = "",
    val isEdited: Boolean = false,
    val isRevoked: Boolean = false,
    val mediaUrl: String? = null,
    val mediaMime: String? = null,
    val serviceData: String? = null,
    /** Outgoing delivery status ordinal (MessageStatus ordinal). */
    val status: Int = 0,
)

/** Queries for cached Signal messages. */
@Dao
interface SignalCachedMessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: SignalCachedMessage)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(messages: List<SignalCachedMessage>)

    @Query("SELECT * FROM signal_cached_message WHERE conversationId = :id ORDER BY timestamp ASC")
    suspend fun getForConversation(id: String): List<SignalCachedMessage>

    @Query("SELECT * FROM signal_cached_message WHERE messageId = :messageId LIMIT 1")
    suspend fun get(messageId: String): SignalCachedMessage?

    /**
     * Find an outgoing message by its sent timestamp, which is how receipts identify it. Not scoped to a
     * conversation on purpose: a receipt has to resolve regardless of which id the message was filed
     * under, and the timestamp is the message's protocol-level identity.
     */
    @Query("SELECT * FROM signal_cached_message WHERE timestamp = :timestamp AND outgoing = 1 LIMIT 1")
    suspend fun getOutgoingByTimestamp(timestamp: Long): SignalCachedMessage?

    /** As [getOutgoingByTimestamp], but either direction — for read syncs from our own other devices. */
    @Query("SELECT * FROM signal_cached_message WHERE timestamp = :timestamp LIMIT 1")
    suspend fun getByTimestamp(timestamp: Long): SignalCachedMessage?

    /** Most-recent message per conversation, newest first (for the thread list). */
    @Query(
        "SELECT * FROM signal_cached_message WHERE timestamp IN " +
            "(SELECT MAX(timestamp) FROM signal_cached_message GROUP BY conversationId) " +
            "ORDER BY timestamp DESC",
    )
    suspend fun getLatestPerConversation(): List<SignalCachedMessage>

    @Query("UPDATE signal_cached_message SET body = :body, isEdited = 1 WHERE messageId = :messageId")
    suspend fun markEdited(messageId: String, body: String)

    @Query("UPDATE signal_cached_message SET isRevoked = 1, body = '' WHERE messageId = :messageId")
    suspend fun markRevoked(messageId: String)

    @Query("UPDATE signal_cached_message SET serviceData = :serviceData WHERE messageId = :messageId")
    suspend fun updateServiceData(messageId: String, serviceData: String?)

    /** Advance to Delivered (2) only from a lower state. */
    @Query("UPDATE signal_cached_message SET status = 2 WHERE messageId = :messageId AND status < 2")
    suspend fun markDelivered(messageId: String)

    /** Advance to Read (3) only from a lower state. */
    @Query("UPDATE signal_cached_message SET status = 3 WHERE messageId = :messageId AND status < 3")
    suspend fun markReadStatus(messageId: String)

    @Query("DELETE FROM signal_cached_message WHERE conversationId = :id")
    suspend fun deleteConversation(id: String)

    @Query("DELETE FROM signal_cached_message")
    suspend fun deleteAll()
}

// -- Reactions --

/** One emoji reaction on a cached Signal message, keyed by message and sender. */
@Entity(tableName = "signal_cached_reaction", primaryKeys = ["messageId", "senderId"])
data class SignalCachedReaction(
    val messageId: String,
    val emoji: String,
    val senderId: String,
    val timestamp: Long,
)

/** Queries for cached Signal message reactions. */
@Dao
interface SignalCachedReactionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(reaction: SignalCachedReaction)

    @Query("SELECT * FROM signal_cached_reaction WHERE messageId = :messageId")
    suspend fun getForMessage(messageId: String): List<SignalCachedReaction>

    @Query("DELETE FROM signal_cached_reaction WHERE messageId = :messageId AND senderId = :senderId")
    suspend fun remove(messageId: String, senderId: String)

    @Query("DELETE FROM signal_cached_reaction WHERE messageId = :messageId")
    suspend fun clearForMessage(messageId: String)
}

// -- Contacts --

/** A Signal contact discovered via CDSi, keyed by ACI with its phone number and PNI. */
@Entity(tableName = "signal_contact")
data class SignalContact(
    @PrimaryKey val aci: String,
    val phoneE164: String = "",
    val displayName: String = "",
    val onSignal: Boolean = false,
    val updatedAt: Long = 0L,
    /**
     * The phone-number identity. Contact discovery returns this, not the ACI — an ACI only comes back
     * when we already hold the contact's profile key — so this is what addresses a contact we have never
     * exchanged messages with.
     */
    @ColumnInfo(defaultValue = "''") val pni: String = "",
)

/** Queries for Signal contacts. */
@Dao
interface SignalContactDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(contact: SignalContact)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(contacts: List<SignalContact>)

    @Query("SELECT * FROM signal_contact WHERE aci = :aci LIMIT 1")
    suspend fun get(aci: String): SignalContact?

    @Query("SELECT * FROM signal_contact WHERE phoneE164 = :phone LIMIT 1")
    suspend fun getByPhone(phone: String): SignalContact?

    @Query("SELECT * FROM signal_contact WHERE pni = :pni AND pni != '' LIMIT 1")
    suspend fun getByPni(pni: String): SignalContact?

    @Query("DELETE FROM signal_contact WHERE phoneE164 = :phone")
    suspend fun deleteByPhone(phone: String)

    @Query("SELECT * FROM signal_contact WHERE onSignal = 1 ORDER BY displayName COLLATE NOCASE ASC")
    suspend fun getOnSignal(): List<SignalContact>

    @Query("SELECT * FROM signal_contact")
    suspend fun getAll(): List<SignalContact>

    @Query("DELETE FROM signal_contact")
    suspend fun deleteAll()
}

// -- E2E stores --

/** A Signal protocol (double-ratchet) session record for one peer device. */
@Entity(tableName = "signal_e2e_sessions", primaryKeys = ["address", "deviceId"])
data class SignalE2ESession(
    val address: String,
    val deviceId: Int,
    val record: ByteArray,
)

/** A peer identity key for one Signal address, used for trust-on-first-use checks. */
@Entity(tableName = "signal_e2e_identities")
data class SignalE2EIdentity(
    @PrimaryKey val address: String,
    val identityKey: ByteArray,
)

/** One of our one-time EC pre-keys, with whether it has been uploaded to the service. */
@Entity(tableName = "signal_e2e_pre_keys")
data class SignalE2EPreKey(
    @PrimaryKey val id: Int,
    val record: ByteArray,
    val uploaded: Boolean = false,
)

/** One of our signed EC pre-keys (the serialized protocol record). */
@Entity(tableName = "signal_e2e_signed_pre_keys")
data class SignalE2ESignedPreKey(
    @PrimaryKey val id: Int,
    val record: ByteArray,
)

/** One of our Kyber (post-quantum) pre-keys; the last-resort key is the reusable fallback. */
@Entity(tableName = "signal_e2e_kyber_pre_keys")
data class SignalE2EKyberPreKey(
    @PrimaryKey val id: Int,
    val record: ByteArray,
    val lastResort: Boolean = false,
    val uploaded: Boolean = false,
)

/**
 * Records that a last-resort Kyber pre-key has already been used with a given signed pre-key and
 * sender base key. Re-seeing a tuple means a replayed pre-key message.
 */
@Entity(
    tableName = "signal_e2e_kyber_used_base_keys",
    primaryKeys = ["kyberPreKeyId", "signedPreKeyId", "baseKeyB64"],
)
data class SignalE2EKyberUsedBaseKey(
    val kyberPreKeyId: Int,
    val signedPreKeyId: Int,
    val baseKeyB64: String,
)

/** A group sender-key record for one (address, device, distribution) triple. */
@Entity(tableName = "signal_e2e_sender_keys", primaryKeys = ["address", "deviceId", "distributionId"])
data class SignalE2ESenderKey(
    val address: String,
    val deviceId: Int,
    val distributionId: String,
    val record: ByteArray,
)

/** Queries for Signal protocol session records. */
@Dao
interface SignalE2ESessionDao {
    @Query("SELECT * FROM signal_e2e_sessions WHERE address = :address AND deviceId = :deviceId LIMIT 1")
    suspend fun get(address: String, deviceId: Int): SignalE2ESession?

    @Query("SELECT deviceId FROM signal_e2e_sessions WHERE address = :address")
    suspend fun getSubDeviceIds(address: String): List<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SignalE2ESession)

    @Query("SELECT COUNT(*) > 0 FROM signal_e2e_sessions WHERE address = :address AND deviceId = :deviceId")
    suspend fun exists(address: String, deviceId: Int): Boolean

    @Query("DELETE FROM signal_e2e_sessions WHERE address = :address AND deviceId = :deviceId")
    suspend fun delete(address: String, deviceId: Int)

    @Query("DELETE FROM signal_e2e_sessions WHERE address = :address")
    suspend fun deleteAll(address: String)
}

/** Queries for peer identity keys. */
@Dao
interface SignalE2EIdentityDao {
    @Query("SELECT * FROM signal_e2e_identities WHERE address = :address LIMIT 1")
    suspend fun get(address: String): SignalE2EIdentity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SignalE2EIdentity)

    @Query("DELETE FROM signal_e2e_identities WHERE address = :address")
    suspend fun delete(address: String)
}

/** Queries for our one-time EC pre-keys. */
@Dao
interface SignalE2EPreKeyDao {
    @Query("SELECT * FROM signal_e2e_pre_keys WHERE id = :id LIMIT 1")
    suspend fun get(id: Int): SignalE2EPreKey?

    @Query("SELECT * FROM signal_e2e_pre_keys")
    suspend fun getAll(): List<SignalE2EPreKey>

    @Query("SELECT * FROM signal_e2e_pre_keys WHERE uploaded = 0")
    suspend fun getUnuploaded(): List<SignalE2EPreKey>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SignalE2EPreKey)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<SignalE2EPreKey>)

    @Query("UPDATE signal_e2e_pre_keys SET uploaded = 1 WHERE id <= :maxId")
    suspend fun markUploadedUpTo(maxId: Int)

    @Query("SELECT COUNT(*) > 0 FROM signal_e2e_pre_keys WHERE id = :id")
    suspend fun exists(id: Int): Boolean

    @Query("DELETE FROM signal_e2e_pre_keys WHERE id = :id")
    suspend fun delete(id: Int)

    @Query("SELECT COUNT(*) FROM signal_e2e_pre_keys")
    suspend fun getCount(): Int

    @Query("SELECT COALESCE(MAX(id), 0) FROM signal_e2e_pre_keys")
    suspend fun getMaxId(): Int
}

/** Queries for our signed EC pre-keys. */
@Dao
interface SignalE2ESignedPreKeyDao {
    @Query("SELECT * FROM signal_e2e_signed_pre_keys WHERE id = :id LIMIT 1")
    suspend fun get(id: Int): SignalE2ESignedPreKey?

    @Query("SELECT * FROM signal_e2e_signed_pre_keys")
    suspend fun getAll(): List<SignalE2ESignedPreKey>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SignalE2ESignedPreKey)

    @Query("SELECT COUNT(*) > 0 FROM signal_e2e_signed_pre_keys WHERE id = :id")
    suspend fun exists(id: Int): Boolean

    @Query("DELETE FROM signal_e2e_signed_pre_keys WHERE id = :id")
    suspend fun delete(id: Int)
}

/** Queries for our Kyber pre-keys. */
@Dao
interface SignalE2EKyberPreKeyDao {
    @Query("SELECT * FROM signal_e2e_kyber_pre_keys WHERE id = :id LIMIT 1")
    suspend fun get(id: Int): SignalE2EKyberPreKey?

    @Query("SELECT * FROM signal_e2e_kyber_pre_keys")
    suspend fun getAll(): List<SignalE2EKyberPreKey>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SignalE2EKyberPreKey)

    @Query("SELECT COUNT(*) > 0 FROM signal_e2e_kyber_pre_keys WHERE id = :id")
    suspend fun exists(id: Int): Boolean

    @Query("DELETE FROM signal_e2e_kyber_pre_keys WHERE id = :id")
    suspend fun delete(id: Int)
}

/** Queries for used last-resort Kyber base keys (replay detection). */
@Dao
interface SignalE2EKyberUsedBaseKeyDao {
    @Query(
        "SELECT COUNT(*) > 0 FROM signal_e2e_kyber_used_base_keys " +
            "WHERE kyberPreKeyId = :kyberPreKeyId AND signedPreKeyId = :signedPreKeyId AND baseKeyB64 = :baseKeyB64",
    )
    suspend fun exists(kyberPreKeyId: Int, signedPreKeyId: Int, baseKeyB64: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SignalE2EKyberUsedBaseKey)

    @Query("DELETE FROM signal_e2e_kyber_used_base_keys WHERE kyberPreKeyId = :kyberPreKeyId")
    suspend fun deleteForKyberPreKey(kyberPreKeyId: Int)
}

/** Queries for group sender-key records. */
@Dao
interface SignalE2ESenderKeyDao {
    @Query("SELECT * FROM signal_e2e_sender_keys WHERE address = :address AND " +
        "deviceId = :deviceId AND distributionId = :distributionId LIMIT 1")
    suspend fun get(address: String, deviceId: Int, distributionId: String): SignalE2ESenderKey?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SignalE2ESenderKey)

    @Query("DELETE FROM signal_e2e_sender_keys WHERE address = :address " +
        "AND deviceId = :deviceId AND distributionId = :distributionId")
    suspend fun delete(address: String, deviceId: Int, distributionId: String)
}

// -- Sealed sender --

/**
 * The delivery certificate, a single rotating row. [expiration] is the certificate's own expiry, used
 * to refresh it before it lapses.
 */
@Entity(tableName = "signal_sender_certificate")
data class SignalSenderCertificate(
    @PrimaryKey val id: Int = 0,
    val record: ByteArray,
    val expiration: Long,
    val includesE164: Boolean,
)

/**
 * A recipient's profile key, from which their unidentified-access key is derived. Secret material —
 * this table lives in the encrypted database.
 */
@Entity(tableName = "signal_profile_keys")
data class SignalProfileKey(
    @PrimaryKey val address: String,
    val profileKey: ByteArray,
)

/** Queries for the sealed-sender delivery certificate row. */
@Dao
interface SignalSenderCertificateDao {
    @Query("SELECT * FROM signal_sender_certificate WHERE id = 0 LIMIT 1")
    suspend fun get(): SignalSenderCertificate?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SignalSenderCertificate)

    @Query("DELETE FROM signal_sender_certificate")
    suspend fun clear()
}

/** Queries for recipient profile keys. */
@Dao
interface SignalProfileKeyDao {
    @Query("SELECT * FROM signal_profile_keys WHERE address = :address LIMIT 1")
    suspend fun get(address: String): SignalProfileKey?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SignalProfileKey)
}

// -- Call log --

/** One Signal voice/video call in the call log, with peer, direction and outcome. */
@Entity(tableName = "signal_call_log")
data class SignalCallLog(
    @PrimaryKey val callId: String,
    val peerId: String = "",
    val peerName: String = "",
    val outgoing: Boolean = false,
    val video: Boolean = false,
    val startTime: Long = 0L,
    val durationSeconds: Long = 0L,
    val outcome: String = "",
)

/** Queries for the Signal call log. */
@Dao
interface SignalCallLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: SignalCallLog)

    @Query("SELECT * FROM signal_call_log ORDER BY startTime DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 200): List<SignalCallLog>

    @Query("SELECT * FROM signal_call_log WHERE callId = :callId LIMIT 1")
    suspend fun get(callId: String): SignalCallLog?

    @Query("DELETE FROM signal_call_log")
    suspend fun deleteAll()
}
