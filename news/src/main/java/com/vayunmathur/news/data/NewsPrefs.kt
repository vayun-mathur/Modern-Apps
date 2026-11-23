package com.vayunmathur.news.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.newsDataStore by preferencesDataStore(name = "news")

/**
 * The app's only preferences owner. Enabled sources and reader settings.
 * A corrupt DataStore file reads as the documented defaults (all sources
 * enabled, 17sp sans) instead of crashing — see the catch in [safeData].
 */
class NewsPrefs(context: Context) {
    private val appContext = context.applicationContext

    private val safeData: Flow<androidx.datastore.preferences.core.Preferences> =
        appContext.newsDataStore.data.catch { e ->
            if (e is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw e
        }

    /** Null means "never set": every source is enabled by default. */
    val disabledSourceIds: Flow<Set<String>> = safeData.map { it[DISABLED_SOURCES] ?: emptySet() }
    val readerTextSp: Flow<Int> = safeData.map { it[TEXT_SP] ?: DEFAULT_TEXT_SP }
    val readerSerif: Flow<Boolean> = safeData.map { it[SERIF] ?: false }
    val readerWebView: Flow<Boolean> = safeData.map { it[WEB_VIEW] ?: false }

    suspend fun setSourceEnabled(id: String, enabled: Boolean) {
        appContext.newsDataStore.edit { prefs ->
            val current = prefs[DISABLED_SOURCES] ?: emptySet()
            prefs[DISABLED_SOURCES] = if (enabled) current - id else current + id
        }
    }

    suspend fun setReaderTextSp(sp: Int) {
        appContext.newsDataStore.edit { it[TEXT_SP] = sp.coerceIn(MIN_TEXT_SP, MAX_TEXT_SP) }
    }

    suspend fun setReaderSerif(serif: Boolean) {
        appContext.newsDataStore.edit { it[SERIF] = serif }
    }

    suspend fun setReaderWebView(webView: Boolean) {
        appContext.newsDataStore.edit { it[WEB_VIEW] = webView }
    }

    companion object {
        const val DEFAULT_TEXT_SP = 17
        const val MIN_TEXT_SP = 13
        const val MAX_TEXT_SP = 24
        private val DISABLED_SOURCES = stringSetPreferencesKey("disabled_sources")
        private val TEXT_SP = intPreferencesKey("reader_text_sp")
        private val SERIF = booleanPreferencesKey("reader_serif")
        private val WEB_VIEW = booleanPreferencesKey("reader_web_view")
    }
}
