package com.vayunmathur.messages.signal.store

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import com.vayunmathur.library.room.buildDatabase

@Entity(tableName = "signal_sessions", primaryKeys = ["address", "deviceId"])
data class SignalSessionEntity(
    val address: String,
    val deviceId: Int,
    val record: ByteArray,
)

@Entity(tableName = "signal_identity_keys")
data class SignalIdentityKeyEntity(
    @PrimaryKey val serviceId: String,
    val identityKey: ByteArray,
    val trustLevel: String,
)

// Pre-key tables are scoped by service ("ACI"/"PNI"): ACI and PNI each have their own
// independent key-id namespace, mirroring signalmeow's per-service scoped stores. This
// replaces the old +1000000 id-offset hack which broke decryption of PNI-addressed
// pre-key messages (their signed-pre-key id=1 collided with the ACI key in one table).
@Entity(tableName = "signal_pre_keys", primaryKeys = ["service", "id"])
data class SignalPreKeyEntity(
    val service: String,
    val id: Int,
    val record: ByteArray,
)

@Entity(tableName = "signal_signed_pre_keys", primaryKeys = ["service", "id"])
data class SignalSignedPreKeyEntity(
    val service: String,
    val id: Int,
    val record: ByteArray,
)

@Entity(tableName = "signal_kyber_pre_keys", primaryKeys = ["service", "id"])
data class SignalKyberPreKeyEntity(
    val service: String,
    val id: Int,
    val lastResort: Boolean,
    val record: ByteArray,
)

@Entity(tableName = "signal_sender_keys", primaryKeys = ["address", "deviceId", "distributionId"])
data class SignalSenderKeyEntity(
    val address: String,
    val deviceId: Int,
    val distributionId: String,
    val record: ByteArray,
)

@Entity(tableName = "signal_sender_key_info")
data class SignalSenderKeyInfoEntity(
    @PrimaryKey val groupId: String,
    val distributionId: String,
    val sharedWith: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "signal_recipients")
data class SignalRecipientEntity(
    @PrimaryKey val aci: String,
    val pni: String? = null,
    val e164: String? = null,
    val contactName: String? = null,
    val contactAvatarHash: String? = null,
    val nickname: String? = null,
    val profileKey: ByteArray? = null,
    val profileName: String? = null,
    val profileAbout: String? = null,
    val profileAboutEmoji: String? = null,
    val profileAvatarPath: String? = null,
    val profileFetchedAt: Long? = null,
    val needsPniSignature: Boolean = false,
    val blocked: Boolean = false,
    val whitelisted: Boolean = false,
    val unregistered: Boolean = false,
)

@Entity(tableName = "signal_groups")
data class SignalGroupEntity(
    @PrimaryKey val groupId: String,
    val masterKey: ByteArray,
    val title: String? = null,
    val avatarUrl: String? = null,
    val revision: Int = 0,
)

@Entity(tableName = "signal_event_buffer")
data class SignalEventBufferEntity(
    @PrimaryKey val ciphertextHash: ByteArray,
    val plaintext: ByteArray?,
    val serverTimestamp: Long,
    val insertTimestamp: Long,
)

@Dao
interface SignalSessionDao {
    @Query("SELECT * FROM signal_sessions WHERE address = :address AND deviceId = :deviceId LIMIT 1")
    suspend fun get(address: String, deviceId: Int): SignalSessionEntity?

    @Query("SELECT deviceId FROM signal_sessions WHERE address = :address")
    suspend fun getSubDeviceIds(address: String): List<Int>

    @Query("SELECT * FROM signal_sessions WHERE address = :address")
    suspend fun getAllForAddress(address: String): List<SignalSessionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SignalSessionEntity)

    @Query("SELECT COUNT(*) > 0 FROM signal_sessions WHERE address = :address AND deviceId = :deviceId")
    suspend fun exists(address: String, deviceId: Int): Boolean

    @Query("DELETE FROM signal_sessions WHERE address = :address AND deviceId = :deviceId")
    suspend fun delete(address: String, deviceId: Int)

    @Query("DELETE FROM signal_sessions WHERE address = :address")
    suspend fun deleteAll(address: String)

    @Query("DELETE FROM signal_sessions")
    suspend fun deleteAllSessions()
}

@Dao
interface SignalIdentityKeyDao {
    @Query("SELECT * FROM signal_identity_keys WHERE serviceId = :serviceId LIMIT 1")
    suspend fun get(serviceId: String): SignalIdentityKeyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SignalIdentityKeyEntity)

    @Query("DELETE FROM signal_identity_keys WHERE serviceId = :serviceId")
    suspend fun delete(serviceId: String)
}

@Dao
interface SignalPreKeyDao {
    @Query("SELECT * FROM signal_pre_keys WHERE service = :service AND id = :id LIMIT 1")
    suspend fun get(service: String, id: Int): SignalPreKeyEntity?

    @Query("SELECT * FROM signal_pre_keys WHERE service = :service")
    suspend fun getAll(service: String): List<SignalPreKeyEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SignalPreKeyEntity)

    @Query("SELECT COUNT(*) > 0 FROM signal_pre_keys WHERE service = :service AND id = :id")
    suspend fun exists(service: String, id: Int): Boolean

    @Query("DELETE FROM signal_pre_keys WHERE service = :service AND id = :id")
    suspend fun delete(service: String, id: Int)

    @Query("DELETE FROM signal_pre_keys WHERE service = :service")
    suspend fun deleteAll(service: String)

    @Query("DELETE FROM signal_pre_keys")
    suspend fun deleteAllServices()

    @Query("SELECT COUNT(*) FROM signal_pre_keys WHERE service = :service")
    suspend fun getCount(service: String): Int

    @Query("SELECT COALESCE(MAX(id), 0) FROM signal_pre_keys WHERE service = :service")
    suspend fun getMaxId(service: String): Int
}

@Dao
interface SignalSignedPreKeyDao {
    @Query("SELECT * FROM signal_signed_pre_keys WHERE service = :service AND id = :id LIMIT 1")
    suspend fun get(service: String, id: Int): SignalSignedPreKeyEntity?

    @Query("SELECT * FROM signal_signed_pre_keys WHERE service = :service")
    suspend fun getAll(service: String): List<SignalSignedPreKeyEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SignalSignedPreKeyEntity)

    @Query("SELECT COUNT(*) > 0 FROM signal_signed_pre_keys WHERE service = :service AND id = :id")
    suspend fun exists(service: String, id: Int): Boolean

    @Query("DELETE FROM signal_signed_pre_keys WHERE service = :service AND id = :id")
    suspend fun delete(service: String, id: Int)

    @Query("DELETE FROM signal_signed_pre_keys WHERE service = :service")
    suspend fun deleteAll(service: String)

    @Query("DELETE FROM signal_signed_pre_keys")
    suspend fun deleteAllServices()
}

@Dao
interface SignalKyberPreKeyDao {
    @Query("SELECT * FROM signal_kyber_pre_keys WHERE service = :service AND id = :id LIMIT 1")
    suspend fun get(service: String, id: Int): SignalKyberPreKeyEntity?

    @Query("SELECT * FROM signal_kyber_pre_keys WHERE service = :service")
    suspend fun getAll(service: String): List<SignalKyberPreKeyEntity>

    @Query("SELECT * FROM signal_kyber_pre_keys WHERE service = :service AND lastResort = 0")
    suspend fun getAllNonLastResort(service: String): List<SignalKyberPreKeyEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SignalKyberPreKeyEntity)

    @Query("SELECT COUNT(*) > 0 FROM signal_kyber_pre_keys WHERE service = :service AND id = :id")
    suspend fun exists(service: String, id: Int): Boolean

    @Query("SELECT lastResort FROM signal_kyber_pre_keys WHERE service = :service AND id = :id")
    suspend fun isLastResort(service: String, id: Int): Boolean?

    @Query("DELETE FROM signal_kyber_pre_keys WHERE service = :service AND id = :id")
    suspend fun delete(service: String, id: Int)

    @Query("DELETE FROM signal_kyber_pre_keys WHERE service = :service AND lastResort = 0")
    suspend fun deleteAllNonLastResort(service: String)

    @Query("DELETE FROM signal_kyber_pre_keys WHERE service = :service")
    suspend fun deleteAll(service: String)

    @Query("DELETE FROM signal_kyber_pre_keys")
    suspend fun deleteAllServices()

    @Query("SELECT COUNT(*) FROM signal_kyber_pre_keys WHERE service = :service")
    suspend fun getCount(service: String): Int

    @Query("SELECT COALESCE(MAX(id), 0) FROM signal_kyber_pre_keys WHERE service = :service")
    suspend fun getMaxId(service: String): Int
}

@Dao
interface SignalSenderKeyDao {
    @Query("SELECT * FROM signal_sender_keys WHERE address = :address AND deviceId = :deviceId AND distributionId = :distributionId LIMIT 1")
    suspend fun get(address: String, deviceId: Int, distributionId: String): SignalSenderKeyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SignalSenderKeyEntity)

    @Query("DELETE FROM signal_sender_keys WHERE address = :address AND deviceId = :deviceId AND distributionId = :distributionId")
    suspend fun delete(address: String, deviceId: Int, distributionId: String)
}

@Dao
interface SignalSenderKeyInfoDao {
    @Query("SELECT * FROM signal_sender_key_info WHERE groupId = :groupId LIMIT 1")
    suspend fun get(groupId: String): SignalSenderKeyInfoEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SignalSenderKeyInfoEntity)

    @Query("DELETE FROM signal_sender_key_info WHERE groupId = :groupId")
    suspend fun delete(groupId: String)
}

@Dao
interface SignalRecipientDao {
    @Query("SELECT * FROM signal_recipients WHERE aci = :aci LIMIT 1")
    suspend fun get(aci: String): SignalRecipientEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SignalRecipientEntity)

    @Query("SELECT * FROM signal_recipients WHERE pni = :pni LIMIT 1")
    suspend fun getByPni(pni: String): SignalRecipientEntity?

    @Query("SELECT * FROM signal_recipients WHERE e164 = :e164 LIMIT 1")
    suspend fun getByE164(e164: String): SignalRecipientEntity?

    @Query("SELECT * FROM signal_recipients WHERE contactName LIKE '%' || :query || '%' OR profileName LIKE '%' || :query || '%' OR e164 LIKE '%' || :query || '%'")
    suspend fun search(query: String): List<SignalRecipientEntity>

    @Query("SELECT * FROM signal_recipients")
    suspend fun getAll(): List<SignalRecipientEntity>

    @Query("SELECT * FROM signal_recipients WHERE (contactName IS NOT NULL AND contactName <> '') OR (profileName IS NOT NULL AND profileName <> '') OR (e164 IS NOT NULL AND e164 <> '')")
    suspend fun getAllContacts(): List<SignalRecipientEntity>

    @Query("SELECT profileKey FROM signal_recipients WHERE aci = :aci LIMIT 1")
    suspend fun getProfileKey(aci: String): ByteArray?

    @Query("DELETE FROM signal_recipients WHERE pni = :pni")
    suspend fun deleteByPni(pni: String)
}

@Dao
interface SignalGroupDao {
    @Query("SELECT * FROM signal_groups WHERE groupId = :groupId LIMIT 1")
    suspend fun get(groupId: String): SignalGroupEntity?

    @Query("SELECT masterKey FROM signal_groups WHERE groupId = :groupId LIMIT 1")
    suspend fun getMasterKey(groupId: String): ByteArray?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SignalGroupEntity)

    @Query("SELECT * FROM signal_groups")
    suspend fun getAll(): List<SignalGroupEntity>
}

@Entity(tableName = "signal_backup_recipients")
data class SignalBackupRecipientEntity(
    @PrimaryKey val id: Long,
    val data: ByteArray,
)

@Entity(tableName = "signal_backup_chats")
data class SignalBackupChatEntity(
    @PrimaryKey val id: Long,
    val recipientId: Long,
    val data: ByteArray,
)

@Entity(
    tableName = "signal_backup_chat_items",
    primaryKeys = ["chatId", "messageId"],
)
data class SignalBackupChatItemEntity(
    val chatId: Long,
    val authorId: Long,
    val messageId: Long,
    val data: ByteArray,
)

@Dao
interface SignalBackupRecipientDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SignalBackupRecipientEntity)

    @Query("SELECT * FROM signal_backup_recipients WHERE id = :id LIMIT 1")
    suspend fun get(id: Long): SignalBackupRecipientEntity?

    @Query("DELETE FROM signal_backup_recipients")
    suspend fun deleteAll()
}

@Dao
interface SignalBackupChatDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SignalBackupChatEntity)

    @Query("SELECT * FROM signal_backup_chats WHERE id = :id LIMIT 1")
    suspend fun get(id: Long): SignalBackupChatEntity?

    @Query("SELECT * FROM signal_backup_chats")
    suspend fun getAll(): List<SignalBackupChatEntity>

    @Query("DELETE FROM signal_backup_chats")
    suspend fun deleteAll()
}

@Dao
interface SignalBackupChatItemDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: SignalBackupChatItemEntity)

    @Query("SELECT * FROM signal_backup_chat_items WHERE chatId = :chatId ORDER BY messageId DESC LIMIT :limit")
    suspend fun getByChatId(chatId: Long, limit: Int = 100): List<SignalBackupChatItemEntity>

    @Query("DELETE FROM signal_backup_chat_items")
    suspend fun deleteAll()
}

@Dao
interface SignalEventBufferDao {
    @Query("SELECT * FROM signal_event_buffer WHERE ciphertextHash = :hash LIMIT 1")
    suspend fun get(hash: ByteArray): SignalEventBufferEntity?

    @Insert
    suspend fun insert(entity: SignalEventBufferEntity)

    @Query("UPDATE signal_event_buffer SET plaintext = NULL WHERE ciphertextHash = :hash")
    suspend fun clearPlaintext(hash: ByteArray)

    @Query("DELETE FROM signal_event_buffer WHERE insertTimestamp < :maxTimestamp AND plaintext IS NULL")
    suspend fun deleteOlderThan(maxTimestamp: Long)
}

@Database(
    entities = [
        SignalSessionEntity::class,
        SignalIdentityKeyEntity::class,
        SignalPreKeyEntity::class,
        SignalSignedPreKeyEntity::class,
        SignalKyberPreKeyEntity::class,
        SignalSenderKeyEntity::class,
        SignalSenderKeyInfoEntity::class,
        SignalRecipientEntity::class,
        SignalGroupEntity::class,
        SignalEventBufferEntity::class,
        SignalBackupRecipientEntity::class,
        SignalBackupChatEntity::class,
        SignalBackupChatItemEntity::class,
    ],
    version = 6,
    exportSchema = false,
)
abstract class SignalDatabase : RoomDatabase() {
    abstract fun sessionDao(): SignalSessionDao
    abstract fun identityKeyDao(): SignalIdentityKeyDao
    abstract fun preKeyDao(): SignalPreKeyDao
    abstract fun signedPreKeyDao(): SignalSignedPreKeyDao
    abstract fun kyberPreKeyDao(): SignalKyberPreKeyDao
    abstract fun senderKeyDao(): SignalSenderKeyDao
    abstract fun senderKeyInfoDao(): SignalSenderKeyInfoDao
    abstract fun recipientDao(): SignalRecipientDao
    abstract fun groupDao(): SignalGroupDao
    abstract fun eventBufferDao(): SignalEventBufferDao
    abstract fun backupRecipientDao(): SignalBackupRecipientDao
    abstract fun backupChatDao(): SignalBackupChatDao
    abstract fun backupChatItemDao(): SignalBackupChatItemDao

    companion object {
        @Volatile
        private var instance: SignalDatabase? = null

        fun getInstance(context: Context): SignalDatabase {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: context.applicationContext.buildDatabase<SignalDatabase>(
                    dbName = "signal_protocol.db",
                )
                    .also { instance = it }
            }
        }
    }
}
