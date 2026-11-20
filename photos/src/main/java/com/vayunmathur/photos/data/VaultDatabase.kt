package com.vayunmathur.photos.data

import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Delete
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.RoomDatabase
import androidx.room3.Upsert
import com.vayunmathur.library.util.DatabaseItem
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** Immutable value describing vault photo. */
@Serializable
@Entity
data class VaultPhoto(
    @PrimaryKey(autoGenerate = true) override val id: Long = 0,
    val name: String,
    val path: String, // Path in internal storage
    val thumbnailPath: String, // Path to encrypted thumbnail
    val date: Long,
    val width: Int,
    val height: Int,
    val dateModified: Long,
    val videoDuration: Long? = null,
) : DatabaseItem

/** Contract for vault photo dao. */
@Dao
interface VaultPhotoDao {
    @Query("SELECT * FROM VaultPhoto")
    fun getAllFlow(): Flow<List<VaultPhoto>>

    @Query("SELECT * FROM VaultPhoto WHERE id = :id")
    fun getByIdFlow(id: Long): Flow<VaultPhoto?>

    @Upsert
    suspend fun upsert(value: VaultPhoto): Long

    @Delete
    suspend fun delete(value: VaultPhoto): Int
}

/** Vault Database: a room database implementation for vault database. */
@Database(entities = [VaultPhoto::class], version = 1, exportSchema = true)
abstract class VaultDatabase : RoomDatabase() {
    abstract fun vaultPhotoDao(): VaultPhotoDao
}
