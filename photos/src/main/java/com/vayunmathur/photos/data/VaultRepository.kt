package com.vayunmathur.photos.data

import android.content.Context
import androidx.room3.migration.Migration
import com.vayunmathur.library.room.RoomRepository
import kotlinx.coroutines.flow.Flow

/**
 * Single source of truth for VaultDatabase.
 * Uses a separate instance per password (keyed by password hash), since the vault
 * DB is opened with a different encryption password after biometric unlock.
 * Consumer must call [get] with the vault password.
 */
class VaultRepository private constructor(
    context: Context,
    encryptionPassword: String,
) : RoomRepository<VaultDatabase>(
    context,
    VaultDatabase::class,
    dbName = "vault-db",
    encryptionPassword = encryptionPassword,
    migrations = emptyList<Migration>(),
) {

    private val vaultPhotoDao: VaultPhotoDao get() = db.vaultPhotoDao()

    fun getAllFlow(): Flow<List<VaultPhoto>> = vaultPhotoDao.getAllFlow()
    fun getByIdFlow(id: Long): Flow<VaultPhoto?> = vaultPhotoDao.getByIdFlow(id)
    suspend fun upsert(value: VaultPhoto): Long = vaultPhotoDao.upsert(value)
    suspend fun delete(value: VaultPhoto): Int = vaultPhotoDao.delete(value)

    /** Expose DAO for internal wiring (e.g. SecureFolderViewModel still needs Flow wiring). */
    fun dao(): VaultPhotoDao = vaultPhotoDao

    /** Shared constants and helpers for VaultRepository. */
    companion object {
        // One instance per password; unsynchronized map is guarded by synchronized block.
        private val instances = mutableMapOf<String, VaultRepository>()

        fun get(context: Context, password: String): VaultRepository =
            synchronized(this) {
                instances[password] ?: VaultRepository(context.applicationContext, password)
                    .also { instances[password] = it }
            }

        /** For contexts where password is not yet known — use only to check cached instance. */
        fun getIfExists(password: String): VaultRepository? = synchronized(this) { instances[password] }
    }
}
