package com.vayunmathur.games.wordmaker.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class LevelDataStore(context: Context) {

    private val appContext = context.applicationContext
    private val LEVEL_KEY = intPreferencesKey("current_level")
    private val FOUND_WORDS_KEY = stringSetPreferencesKey("found_words")
    private val BONUS_WORDS_KEY = stringSetPreferencesKey("bonus_words")

    val currentLevel: Flow<Int> = appContext.dataStore.data
        .map { preferences ->
            204 //preferences[LEVEL_KEY] ?: 1
        }

    val foundWords: Flow<Set<String>> = appContext.dataStore.data.map { it[FOUND_WORDS_KEY] ?: emptySet() }
    val bonusWords: Flow<Set<String>> = appContext.dataStore.data.map { it[BONUS_WORDS_KEY] ?: emptySet() }

    suspend fun addBonusWord(word: String) {
        appContext.dataStore.edit { settings ->
            val currentBonusWords = settings[BONUS_WORDS_KEY] ?: emptySet()
            settings[BONUS_WORDS_KEY] = currentBonusWords + word
        }
    }

    suspend fun addFoundWord(word: String) {
        appContext.dataStore.edit { settings ->
            val currentFoundWords = settings[FOUND_WORDS_KEY] ?: emptySet()
            settings[FOUND_WORDS_KEY] = currentFoundWords + word
        }
    }

    suspend fun saveLevel(level: Int) {
        appContext.dataStore.edit { settings ->
            settings[LEVEL_KEY] = level
            settings[FOUND_WORDS_KEY] = emptySet()
            settings[BONUS_WORDS_KEY] = emptySet()
        }
    }
}
