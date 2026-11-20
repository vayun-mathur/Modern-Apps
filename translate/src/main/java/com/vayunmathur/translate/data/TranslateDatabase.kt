package com.vayunmathur.translate.data

import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Delete
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.RoomDatabase
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

const val TRANSLATE_DB_NAME = "translate-db"

/**
 * One saved translation: the source text, its translation, and the language pair.
 *
 * A row is created the first time a translation completes and refreshed (same id,
 * bumped [createdAt]) when the same input + pair translates again, so re-translating
 * moves the entry to the top instead of duplicating it. [isFavorite] pins the entry:
 * favorites survive [TranslateRepository.pruneHistory] and sort first in history.
 */
@Entity
data class TranslationRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceText: String,
    val translatedText: String,
    val sourceLang: String,
    val targetLang: String,
    val isFavorite: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

/** Room DAO for saved translation history and favourites. */
@Dao
interface TranslationDao {
    @Query("SELECT * FROM TranslationRecord ORDER BY isFavorite DESC, createdAt DESC")
    fun getHistoryFlow(): Flow<List<TranslationRecord>>

    @Query("SELECT * FROM TranslationRecord WHERE isFavorite = 1 ORDER BY createdAt DESC")
    fun getFavoritesFlow(): Flow<List<TranslationRecord>>

    /** The existing row for this exact input + pair, if any (for dedupe on re-translate). */
    @Query(
        "SELECT * FROM TranslationRecord WHERE sourceText = :source AND sourceLang = :src " +
            "AND targetLang = :tgt LIMIT 1",
    )
    suspend fun findDuplicate(source: String, src: String, tgt: String): TranslationRecord?

    @Upsert
    suspend fun upsert(value: TranslationRecord): Long

    @Query("UPDATE TranslationRecord SET isFavorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean)

    @Delete
    suspend fun delete(value: TranslationRecord): Int

    @Query("DELETE FROM TranslationRecord WHERE isFavorite = 0")
    suspend fun clearHistory()

    /** Non-favorites past [keepNewest], oldest first — the prune candidates. */
    @Query(
        "SELECT id FROM TranslationRecord WHERE isFavorite = 0 " +
            "ORDER BY createdAt DESC LIMIT -1 OFFSET :keepNewest",
    )
    suspend fun overflowIds(keepNewest: Int): List<Long>

    @Query("DELETE FROM TranslationRecord WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)
}

/** Room database holding saved translations. */
@Database(entities = [TranslationRecord::class], version = 1, exportSchema = true)
abstract class TranslateDatabase : RoomDatabase() {
    abstract fun translationDao(): TranslationDao
}
