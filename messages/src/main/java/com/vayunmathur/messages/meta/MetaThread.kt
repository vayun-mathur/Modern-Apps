package com.vayunmathur.messages.meta

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Thread/conversation metadata for Meta platforms.
 */
@Entity(tableName = "meta_threads")
data class MetaThread(
    @PrimaryKey
    val threadId: String, // int64 as string
    val platform: String, // "messenger" or "instagram"
    val name: String?,
    val isGroup: Boolean,
    val lastActivity: Long,
    val unreadCount: Int = 0,
)

/**
 * Sync state for incremental updates.
 */
@Entity(tableName = "meta_sync_state")
data class MetaSyncState(
    @PrimaryKey
    val platform: String,
    val lastSyncToken: String?,
    val lastSyncTime: Long,
)

@Dao
interface MetaThreadDao {
    @Query("SELECT * FROM meta_threads WHERE threadId = :threadId")
    suspend fun getThread(threadId: String): MetaThread?

    @Query("SELECT * FROM meta_threads WHERE platform = :platform ORDER BY lastActivity DESC")
    suspend fun getThreadsForPlatform(platform: String): List<MetaThread>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertThread(thread: MetaThread)

    @Query("UPDATE meta_threads SET unreadCount = :count WHERE threadId = :threadId")
    suspend fun updateUnreadCount(threadId: String, count: Int)

    @Query("DELETE FROM meta_threads WHERE threadId = :threadId")
    suspend fun deleteThread(threadId: String)
}

@Dao
interface MetaSyncStateDao {
    @Query("SELECT * FROM meta_sync_state WHERE platform = :platform")
    suspend fun getSyncState(platform: String): MetaSyncState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSyncState(state: MetaSyncState)
}
