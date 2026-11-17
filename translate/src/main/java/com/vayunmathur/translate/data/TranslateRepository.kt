package com.vayunmathur.translate.data

import android.content.Context
import androidx.room3.withWriteTransaction
import com.vayunmathur.library.room.RoomRepository
import kotlinx.coroutines.flow.Flow

/** Owns saved translations. Favorites never count toward the history cap. */
class TranslateRepository private constructor(context: Context) :
    RoomRepository<TranslateDatabase>(context, TranslateDatabase::class, TRANSLATE_DB_NAME) {

    private val dao get() = db.translationDao()

    val history: Flow<List<TranslationRecord>> get() = dao.getHistoryFlow()
    val favorites: Flow<List<TranslationRecord>> get() = dao.getFavoritesFlow()

    /** Refresh an existing input/language pair without losing its favorite flag. */
    suspend fun record(source: String, translated: String, src: String, tgt: String) {
        if (source.isBlank() || translated.isBlank()) return
        db.withWriteTransaction {
            val existing = dao.findDuplicate(source, src, tgt)
            dao.upsert(
                existing?.copy(
                    translatedText = translated,
                    createdAt = System.currentTimeMillis(),
                ) ?: TranslationRecord(
                    sourceText = source,
                    translatedText = translated,
                    sourceLang = src,
                    targetLang = tgt,
                ),
            )
            pruneHistory()
        }
    }

    suspend fun setFavorite(id: Long, favorite: Boolean) {
        db.withWriteTransaction {
            dao.setFavorite(id, favorite)
            pruneHistory()
        }
    }

    suspend fun delete(record: TranslationRecord): Int = dao.delete(record)

    /** Removes every non-favorite row. Favorites are kept. */
    suspend fun clearHistory() = dao.clearHistory()

    private suspend fun pruneHistory() {
        val overflow = dao.overflowIds(MAX_HISTORY)
        if (overflow.isNotEmpty()) dao.deleteByIds(overflow)
    }

    /** Singleton access and the history cap for [TranslateRepository]. */
    companion object {
        const val MAX_HISTORY = 200

        @Volatile private var instance: TranslateRepository? = null

        fun get(context: Context): TranslateRepository =
            instance ?: synchronized(this) {
                instance ?: TranslateRepository(context).also { instance = it }
            }
    }
}
