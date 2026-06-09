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
import androidx.room.Room
import androidx.room.RoomDatabase

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
    val trusted: Boolean,
)

@Entity(tableName = "signal_pre_keys")
data class SignalPreKeyEntity(
    @PrimaryKey val id: Int,
    val record: ByteArray,
)

@Entity(tableName = "signal_signed_pre_keys")
data class SignalSignedPreKeyEntity(
    @PrimaryKey val id: Int,
    val record: ByteArray,
)

@Entity(tableName = "signal_kyber_pre_keys")
data class SignalKyberPreKeyEntity(
    @PrimaryKey val id: Int,
    val lastResort: Boolean,
    val record: ByteArray,
)

@Entity(tableName = "signal_sender_keys", primaryKeys = ["address", "distributionId"])
data class SignalSenderKeyEntity(
    val address: String,
    val distributionId: String,
    val record: ByteArray,
)

@Entity(tableName = "signal_recipients")
data class SignalRecipientEntity(
    @PrimaryKey val aci: String,
    val pni: String? = null,
    val e164: String? = null,
    val profileName: String? = null,
    val profileAvatar: String? = null,
    val profileKey: ByteArray? = null,
)

@Entity(tableName = "signal_groups")
data class SignalGroupEntity(
    @PrimaryKey val groupId: String,
    val masterKey: ByteArray,
    val title: String? = null,
    val avatarUrl: String? = null,
    val revision: Int,
)

@Dao
interface SignalSessionDao {
    @Query("SELECT * FROM signal_sessions WHERE address = :address AND deviceId = :deviceId LIMIT 1")
    suspend fun get(address: String, deviceId: Int): SignalSessionEntity?

    @Query("SELECT deviceId FROM signal_sessions WHERE address = :address")
    suspend fun getSubDeviceIds(address: String): List<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SignalSessionEntity)

    @Query("SELECT COUNT(*) > 0 FROM signal_sessions WHERE address = :address AND deviceId = :deviceId")
    suspend fun exists(address: String, deviceId: Int): Boolean

    @Query("DELETE FROM signal_sessions WHERE address = :address AND deviceId = :deviceId")
    suspend fun delete(address: String, deviceId: Int)

    @Query("DELETE FROM signal_sessions WHERE address = :address")
    suspend fun deleteAll(address: String)
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
    @Query("SELECT * FROM signal_pre_keys WHERE id = :id LIMIT 1")
    suspend fun get(id: Int): SignalPreKeyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SignalPreKeyEntity)

    @Query("SELECT COUNT(*) > 0 FROM signal_pre_keys WHERE id = :id")
    suspend fun exists(id: Int): Boolean

    @Query("DELETE FROM signal_pre_keys WHERE id = :id")
    suspend fun delete(id: Int)
}

@Dao
interface SignalSignedPreKeyDao {
    @Query("SELECT * FROM signal_signed_pre_keys WHERE id = :id LIMIT 1")
    suspend fun get(id: Int): SignalSignedPreKeyEntity?

    @Query("SELECT * FROM signal_signed_pre_keys")
    suspend fun getAll(): List<SignalSignedPreKeyEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SignalSignedPreKeyEntity)

    @Query("SELECT COUNT(*) > 0 FROM signal_signed_pre_keys WHERE id = :id")
    suspend fun exists(id: Int): Boolean

    @Query("DELETE FROM signal_signed_pre_keys WHERE id = :id")
    suspend fun delete(id: Int)
}

@Dao
interface SignalKyberPreKeyDao {
    @Query("SELECT * FROM signal_kyber_pre_keys WHERE id = :id LIMIT 1")
    suspend fun get(id: Int): SignalKyberPreKeyEntity?

    @Query("SELECT * FROM signal_kyber_pre_keys")
    suspend fun getAll(): List<SignalKyberPreKeyEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SignalKyberPreKeyEntity)

    @Query("SELECT COUNT(*) > 0 FROM signal_kyber_pre_keys WHERE id = :id")
    suspend fun exists(id: Int): Boolean

    @Query("DELETE FROM signal_kyber_pre_keys WHERE id = :id")
    suspend fun delete(id: Int)

    @Query("DELETE FROM signal_kyber_pre_keys WHERE lastResort = 0")
    suspend fun deleteAllNonLastResort()
}

@Dao
interface SignalSenderKeyDao {
    @Query("SELECT * FROM signal_sender_keys WHERE address = :address AND distributionId = :distributionId LIMIT 1")
    suspend fun get(address: String, distributionId: String): SignalSenderKeyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SignalSenderKeyEntity)

    @Query("DELETE FROM signal_sender_keys WHERE address = :address AND distributionId = :distributionId")
    suspend fun delete(address: String, distributionId: String)
}

@Dao
interface SignalRecipientDao {
    @Query("SELECT * FROM signal_recipients WHERE aci = :aci LIMIT 1")
    suspend fun get(aci: String): SignalRecipientEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SignalRecipientEntity)

    @Query("SELECT * FROM signal_recipients WHERE e164 = :e164 LIMIT 1")
    suspend fun getByE164(e164: String): SignalRecipientEntity?

    @Query("SELECT * FROM signal_recipients WHERE profileName LIKE '%' || :query || '%' OR e164 LIKE '%' || :query || '%'")
    suspend fun search(query: String): List<SignalRecipientEntity>

    @Query("SELECT * FROM signal_recipients")
    suspend fun getAll(): List<SignalRecipientEntity>
}

@Dao
interface SignalGroupDao {
    @Query("SELECT * FROM signal_groups WHERE groupId = :groupId LIMIT 1")
    suspend fun get(groupId: String): SignalGroupEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SignalGroupEntity)

    @Query("SELECT * FROM signal_groups")
    suspend fun getAll(): List<SignalGroupEntity>
}

@Database(
    entities = [
        SignalSessionEntity::class,
        SignalIdentityKeyEntity::class,
        SignalPreKeyEntity::class,
        SignalSignedPreKeyEntity::class,
        SignalKyberPreKeyEntity::class,
        SignalSenderKeyEntity::class,
        SignalRecipientEntity::class,
        SignalGroupEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class SignalDatabase : RoomDatabase() {
    abstract fun sessionDao(): SignalSessionDao
    abstract fun identityKeyDao(): SignalIdentityKeyDao
    abstract fun preKeyDao(): SignalPreKeyDao
    abstract fun signedPreKeyDao(): SignalSignedPreKeyDao
    abstract fun kyberPreKeyDao(): SignalKyberPreKeyDao
    abstract fun senderKeyDao(): SignalSenderKeyDao
    abstract fun recipientDao(): SignalRecipientDao
    abstract fun groupDao(): SignalGroupDao

    companion object {
        @Volatile
        private var instance: SignalDatabase? = null

        fun getInstance(context: Context): SignalDatabase {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    SignalDatabase::class.java,
                    "signal_protocol.db",
                )
                    .fallbackToDestructiveMigration(true)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
