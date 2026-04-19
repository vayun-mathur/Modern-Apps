package com.vayunmathur.games.wordmaker.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class LevelDataStore(context: Context) {

    private val appContext = context.applicationContext

    companion object {
        private val LANGUAGE_KEY = stringPreferencesKey("language")
        private val FOUND_WORDS_KEY = stringSetPreferencesKey("found_words")
        private val BONUS_WORDS_KEY = stringSetPreferencesKey("bonus_words")
        private val TOTAL_BONUS_WORDS_KEY = intPreferencesKey("total_bonus_words")
        // Legacy key from before multi-language support
        private val LEGACY_LEVEL_KEY = intPreferencesKey("current_level")

        private fun levelKey(languageId: String) = intPreferencesKey("level_$languageId")
    }

    val currentLanguage: Flow<String> = appContext.dataStore.data
        .map { it[LANGUAGE_KEY] ?: "en" }

    @OptIn(ExperimentalCoroutinesApi::class)
    val currentLevel: Flow<Int> = currentLanguage.flatMapLatest { lang ->
        appContext.dataStore.data.map { prefs ->
            prefs[levelKey(lang)]
                ?: if (lang == "en") prefs[LEGACY_LEVEL_KEY] ?: 1 else 1
        }
    }

    val foundWords: Flow<Set<String>> = appContext.dataStore.data
        .map { it[FOUND_WORDS_KEY] ?: emptySet() }

    val bonusWords: Flow<Set<String>> = appContext.dataStore.data
        .map { it[BONUS_WORDS_KEY] ?: emptySet() }

    val totalBonusWords: Flow<Int> = appContext.dataStore.data
        .map { it[TOTAL_BONUS_WORDS_KEY] ?: 0 }

    suspend fun saveLanguage(languageId: String) {
        appContext.dataStore.edit { settings ->
            settings[LANGUAGE_KEY] = languageId
            settings[FOUND_WORDS_KEY] = emptySet()
            settings[BONUS_WORDS_KEY] = emptySet()
        }
    }

    suspend fun saveLevel(level: Int) {
        val lang = currentLanguage.first()
        appContext.dataStore.edit { settings ->
            settings[levelKey(lang)] = level
            settings[FOUND_WORDS_KEY] = emptySet()
            settings[BONUS_WORDS_KEY] = emptySet()
        }
    }

    suspend fun addBonusWord(word: String): Int {
        var newTotal = 0
        appContext.dataStore.edit { settings ->
            val currentBonusWords = settings[BONUS_WORDS_KEY] ?: emptySet()
            if (word !in currentBonusWords) {
                settings[BONUS_WORDS_KEY] = currentBonusWords + word
                val currentTotal = settings[TOTAL_BONUS_WORDS_KEY] ?: 0
                newTotal = currentTotal + 1
                settings[TOTAL_BONUS_WORDS_KEY] = newTotal
            } else {
                newTotal = settings[TOTAL_BONUS_WORDS_KEY] ?: 0
            }
        }
        return newTotal
    }

    suspend fun addFoundWord(word: String) {
        appContext.dataStore.edit { settings ->
            settings[FOUND_WORDS_KEY] = (settings[FOUND_WORDS_KEY] ?: emptySet()) + word
        }
    }
}
