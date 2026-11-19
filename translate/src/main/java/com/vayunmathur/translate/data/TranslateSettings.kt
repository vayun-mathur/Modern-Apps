package com.vayunmathur.translate.data

import com.vayunmathur.translate.domain.Languages
import com.vayunmathur.translate.domain.parseRecentLanguages
import com.vayunmathur.translate.domain.pushRecentLanguage

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "translate_settings")

/**
 * Persists the last-used source/target languages so they survive app restarts,
 * plus a short most-recently-used list per direction for the language picker.
 * Recents are comma-joined strings rather than string sets because sets
 * don't keep insertion order.
 */
class TranslateSettings(private val context: Context) {

    private val sourceKey = stringPreferencesKey("source_lang")
    private val targetKey = stringPreferencesKey("target_lang")
    private val recentSourceKey = stringPreferencesKey("recent_source_langs")
    private val recentTargetKey = stringPreferencesKey("recent_target_langs")

    // A corrupt preferences file makes DataStore's data flow throw
    // (CorruptionException/IOException) on every read, which would crash
    // the ViewModel init read and then crash again on every relaunch.
    // Reading as empty preferences falls back to the defaults instead.
    private fun prefs(): Flow<Preferences> =
        context.dataStore.data.catch { emit(emptyPreferences()) }

    suspend fun source(): String =
        prefs().map { it[sourceKey] }.first() ?: Languages.AUTO.code

    suspend fun target(): String =
        prefs().map { it[targetKey] }.first() ?: DEFAULT_TARGET

    // Writes are best-effort for the same reason reads are: a corrupt
    // file must not turn a language pick into a crash. The in-memory
    // state has already changed; only persistence is lost.
    suspend fun setSource(code: String) {
        runCatching { context.dataStore.edit { it[sourceKey] = code } }
    }

    suspend fun setTarget(code: String) {
        runCatching { context.dataStore.edit { it[targetKey] = code } }
    }

    suspend fun recentSources(): List<String> =
        prefs().map { parseRecentLanguages(it[recentSourceKey]) }.first()

    suspend fun recentTargets(): List<String> =
        prefs().map { parseRecentLanguages(it[recentTargetKey]) }.first()

    suspend fun pushRecentSource(code: String) {
        val updated = pushRecentLanguage(recentSources(), code)
        runCatching { context.dataStore.edit { it[recentSourceKey] = updated.joinToString(",") } }
    }

    suspend fun pushRecentTarget(code: String) {
        val updated = pushRecentLanguage(recentTargets(), code)
        runCatching { context.dataStore.edit { it[recentTargetKey] = updated.joinToString(",") } }
    }

    /** Defaults for [TranslateSettings]. */
    companion object {
        private const val DEFAULT_TARGET = "es"
    }
}
