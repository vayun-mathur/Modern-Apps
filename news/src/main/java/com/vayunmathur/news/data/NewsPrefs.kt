package com.vayunmathur.news.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.vayunmathur.news.domain.FeedSort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.newsDataStore by preferencesDataStore(name = "news")

/**
 * The app's only preferences owner. Feed sort and reader settings.
 * A corrupt DataStore file reads as the documented defaults (Top
 * stories, 17sp sans) instead of crashing — see the catch in [safeData].
 *
 * There is no per-source enable/disable any more: every source is
 * always in the corpus, and categories + search are the filters. The
 * legacy `disabled_sources` key (and the stale `reader_web_view` value)
 * is never read; [clearLegacySourcePrefs] removes it when present so
 * stale values cannot linger or crash anything.
 */
class NewsPrefs(context: Context) {
    private val appContext = context.applicationContext

    private val safeData: Flow<androidx.datastore.preferences.core.Preferences> =
        appContext.newsDataStore.data.catch { e ->
            if (e is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw e
        }

    /** Persisted feed order; absent or unrecognised reads as Top stories. */
    val feedSort: Flow<FeedSort> = safeData.map { prefs ->
        FeedSort.entries.firstOrNull { it.name == prefs[FEED_SORT] } ?: FeedSort.TOP
    }
    val readerTextSp: Flow<Int> = safeData.map { it[TEXT_SP] ?: DEFAULT_TEXT_SP }
    val readerSerif: Flow<Boolean> = safeData.map { it[SERIF] ?: false }

    suspend fun setFeedSort(sort: FeedSort) {
        appContext.newsDataStore.edit { it[FEED_SORT] = sort.name }
    }

    /** Drops the retired per-source toggle state, if an old install left any. */
    suspend fun clearLegacySourcePrefs() {
        appContext.newsDataStore.edit { prefs ->
            if (prefs.contains(LEGACY_DISABLED_SOURCES)) prefs.remove(LEGACY_DISABLED_SOURCES)
        }
    }

    suspend fun setReaderTextSp(sp: Int) {
        appContext.newsDataStore.edit { it[TEXT_SP] = sp.coerceIn(MIN_TEXT_SP, MAX_TEXT_SP) }
    }

    suspend fun setReaderSerif(serif: Boolean) {
        appContext.newsDataStore.edit { it[SERIF] = serif }
    }

    companion object {
        const val DEFAULT_TEXT_SP = 17
        const val MIN_TEXT_SP = 13
        const val MAX_TEXT_SP = 24
        private val LEGACY_DISABLED_SOURCES = stringSetPreferencesKey("disabled_sources")
        private val FEED_SORT = stringPreferencesKey("feed_sort")
        private val TEXT_SP = intPreferencesKey("reader_text_sp")
        private val SERIF = booleanPreferencesKey("reader_serif")
    }
}
