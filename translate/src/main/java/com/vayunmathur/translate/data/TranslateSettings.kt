package com.vayunmathur.translate.data

import com.vayunmathur.translate.domain.Languages
import com.vayunmathur.translate.domain.parseRecentLanguages
import com.vayunmathur.translate.domain.pushRecentLanguage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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

    suspend fun source(): String =
        context.dataStore.data.map { it[sourceKey] }.first() ?: Languages.AUTO.code

    suspend fun target(): String =
        context.dataStore.data.map { it[targetKey] }.first() ?: DEFAULT_TARGET

    suspend fun setSource(code: String) =
        context.dataStore.edit { it[sourceKey] = code }.let { }

    suspend fun setTarget(code: String) =
        context.dataStore.edit { it[targetKey] = code }.let { }

    suspend fun recentSources(): List<String> =
        context.dataStore.data.map { parseRecentLanguages(it[recentSourceKey]) }.first()

    suspend fun recentTargets(): List<String> =
        context.dataStore.data.map { parseRecentLanguages(it[recentTargetKey]) }.first()

    suspend fun pushRecentSource(code: String) {
        val updated = pushRecentLanguage(recentSources(), code)
        context.dataStore.edit { it[recentSourceKey] = updated.joinToString(",") }
    }

    suspend fun pushRecentTarget(code: String) {
        val updated = pushRecentLanguage(recentTargets(), code)
        context.dataStore.edit { it[recentTargetKey] = updated.joinToString(",") }
    }

    /** Defaults for [TranslateSettings]. */
    companion object {
        private const val DEFAULT_TARGET = "es"
    }
}
