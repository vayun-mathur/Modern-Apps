package com.vayunmathur.uniflix.data

import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

/** One installed URL addon: its manifest identity, placement, and last-known health. */
@Entity
data class AddonEntity(
    @PrimaryKey(autoGenerate = true) val dbId: Long = 0L,
    val manifestUrl: String,
    val baseUrl: String,
    val addonId: String,
    val name: String,
    val version: String = "",
    val enabled: Boolean = true,
    /** Lower resolves first; ties broken by install order. */
    val priority: Int = 0,
    /** Per-addon settings JSON (opaque key-values, edited in the addon detail screen). */
    val configJson: String = "{}",
    /** Last fetch/resolve failure, shown as a badge; null when healthy. */
    val lastError: String? = null,
    val updatedAt: Long = 0L,
    /** `http` for URL addons, `js` for installable JavaScript plugins. */
    val kind: String = "http",
    /** Full plugin file source; only set when [kind] is `js`. */
    val jsSource: String = "",
)

/** Contract for addon dao. */
@Dao
interface AddonDao {
    @Query("SELECT * FROM AddonEntity ORDER BY priority ASC, dbId ASC")
    fun observeAllOrdered(): Flow<List<AddonEntity>>

    @Query("SELECT * FROM AddonEntity WHERE enabled = 1 ORDER BY priority ASC, dbId ASC")
    fun observeEnabledOrdered(): Flow<List<AddonEntity>>

    @Query("SELECT * FROM AddonEntity WHERE dbId = :dbId")
    suspend fun getById(dbId: Long): AddonEntity?

    @Query("SELECT * FROM AddonEntity WHERE manifestUrl = :manifestUrl")
    suspend fun getByManifestUrl(manifestUrl: String): AddonEntity?

    @Query("SELECT COUNT(*) FROM AddonEntity")
    suspend fun count(): Int

    @Upsert
    suspend fun upsert(value: AddonEntity): Long

    @Query("DELETE FROM AddonEntity WHERE dbId = :dbId")
    suspend fun deleteById(dbId: Long)

    @Query("UPDATE AddonEntity SET enabled = :enabled WHERE dbId = :dbId")
    suspend fun setEnabled(dbId: Long, enabled: Boolean)

    @Query("UPDATE AddonEntity SET priority = :priority WHERE dbId = :dbId")
    suspend fun setPriority(dbId: Long, priority: Int)

    /** Swaps two addons' priorities atomically so reorder never leaves a duplicate. */
    @Transaction
    suspend fun swapPriorities(first: AddonEntity, second: AddonEntity) {
        setPriority(first.dbId, second.priority)
        setPriority(second.dbId, first.priority)
    }

    @Query("UPDATE AddonEntity SET lastError = :error WHERE dbId = :dbId")
    suspend fun setError(dbId: Long, error: String?)

    @Query("UPDATE AddonEntity SET configJson = :config WHERE dbId = :dbId")
    suspend fun setConfig(dbId: Long, config: String)
}
