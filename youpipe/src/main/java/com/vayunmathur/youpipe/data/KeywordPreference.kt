package com.vayunmathur.youpipe.data

import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * A per-keyword recommendation preference keyed by the lowercased token
 * ([keyword]). When [muted], candidates whose title tokens include this keyword
 * are hard-filtered from the feed.
 */
@Entity
data class KeywordPreference(
    @PrimaryKey val keyword: String,
    val muted: Boolean = true,
)

/** Contract for keyword preference dao. */
@Dao
interface KeywordPreferenceDao {
    @Query("SELECT * FROM KeywordPreference")
    suspend fun getAll(): List<KeywordPreference>

    @Query("SELECT * FROM KeywordPreference")
    fun getAllFlow(): Flow<List<KeywordPreference>>

    @Upsert
    suspend fun upsert(value: KeywordPreference)

    @Query("DELETE FROM KeywordPreference WHERE keyword = :keyword")
    suspend fun delete(keyword: String)

    @Query("DELETE FROM KeywordPreference")
    suspend fun clearAll()
}
