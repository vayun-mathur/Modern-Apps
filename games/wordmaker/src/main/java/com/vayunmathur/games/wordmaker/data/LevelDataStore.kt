package com.vayunmathur.games.wordmaker.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** DataStore persistence for Word Maker progress, settings, competitive score, and daily state. */
class LevelDataStore(context: Context) {

    private val appContext = context.applicationContext
    /** Preference keys and saved-state helpers for [LevelDataStore]. */
    companion object {
        private val LEVEL_KEY = intPreferencesKey("current_level")
        private val FOUND_WORDS_KEY = stringSetPreferencesKey("found_words")
        private val BONUS_WORDS_KEY = stringSetPreferencesKey("bonus_words")
        private val TOTAL_BONUS_WORDS_KEY = intPreferencesKey("total_bonus_words")
        private val TAP_TO_SPELL_KEY = booleanPreferencesKey("tap_to_spell")
        private val WHEEL_SPACING_KEY = stringPreferencesKey("wheel_spacing")
        private val REVEALED_HINTS_KEY = stringSetPreferencesKey("revealed_hints")
        private val GAME_MODE_KEY = stringPreferencesKey("game_mode")
        private val DIFFICULTY_KEY = stringPreferencesKey("competitive_difficulty")
        private val COMPETITIVE_SCORE_KEY = intPreferencesKey("competitive_score")
        private val DAILY_DAY_KEY = longPreferencesKey("daily_day")
        private val DAILY_FOUND_WORDS_KEY = stringSetPreferencesKey("daily_found_words")
        private val DAILY_BONUS_WORDS_KEY = stringSetPreferencesKey("daily_bonus_words")
        private val DAILY_REVEALED_HINTS_KEY = stringSetPreferencesKey("daily_revealed_hints")

        /** Sentinel for "no daily has ever been opened", distinct from epoch day 0 (1970-01-01). */
        const val NO_DAY = Long.MIN_VALUE

        private fun Set<String>?.toCells(): Set<Pair<Int, Int>> = this?.mapNotNull { s ->
            val parts = s.split(",")
            if (parts.size == 2) {
                parts[0].toIntOrNull()?.let { r -> parts[1].toIntOrNull()?.let { c -> r to c } }
            } else null
        }?.toSet() ?: emptySet()
    }

    val currentLevel: Flow<Int> = appContext.dataStore.data.map { it[LEVEL_KEY] ?: 1 }

    val foundWords: Flow<Set<String>> = appContext.dataStore.data.map { it[FOUND_WORDS_KEY] ?: emptySet() }
    val bonusWords: Flow<Set<String>> = appContext.dataStore.data.map { it[BONUS_WORDS_KEY] ?: emptySet() }
    val totalBonusWords: Flow<Int> = appContext.dataStore.data.map { it[TOTAL_BONUS_WORDS_KEY] ?: 0 }

    val tapToSpell: Flow<Boolean> = appContext.dataStore.data.map { it[TAP_TO_SPELL_KEY] ?: false }

    val wheelSpacing: Flow<WheelSpacing> = appContext.dataStore.data.map { prefs ->
        prefs[WHEEL_SPACING_KEY]?.let { runCatching { WheelSpacing.valueOf(it) }.getOrNull() }
            ?: WheelSpacing.DEFAULT
    }

    val gameMode: Flow<GameMode> = appContext.dataStore.data.map { prefs ->
        prefs[GAME_MODE_KEY]?.let { runCatching { GameMode.valueOf(it) }.getOrNull() } ?: GameMode.CASUAL
    }

    val difficulty: Flow<Difficulty> = appContext.dataStore.data.map { prefs ->
        prefs[DIFFICULTY_KEY]?.let { runCatching { Difficulty.valueOf(it) }.getOrNull() } ?: Difficulty.MEDIUM
    }

    val competitiveScore: Flow<Int> = appContext.dataStore.data.map { it[COMPETITIVE_SCORE_KEY] ?: 0 }

    val revealedHints: Flow<Set<Pair<Int, Int>>> =
        appContext.dataStore.data.map { it[REVEALED_HINTS_KEY].toCells() }

    /** Epoch day the saved daily progress belongs to; [NO_DAY] before the first daily is opened. */
    val dailyDay: Flow<Long> = appContext.dataStore.data.map { it[DAILY_DAY_KEY] ?: NO_DAY }
    val dailyFoundWords: Flow<Set<String>> =
        appContext.dataStore.data.map { it[DAILY_FOUND_WORDS_KEY] ?: emptySet() }
    val dailyBonusWords: Flow<Set<String>> =
        appContext.dataStore.data.map { it[DAILY_BONUS_WORDS_KEY] ?: emptySet() }
    val dailyRevealedHints: Flow<Set<Pair<Int, Int>>> =
        appContext.dataStore.data.map { it[DAILY_REVEALED_HINTS_KEY].toCells() }

    /** Rolls the saved daily progress over to [day], discarding the previous day's state. */
    suspend fun ensureDailyDay(day: Long) {
        appContext.dataStore.edit { settings ->
            if (settings[DAILY_DAY_KEY] == day) return@edit
            settings[DAILY_DAY_KEY] = day
            settings[DAILY_FOUND_WORDS_KEY] = emptySet()
            settings[DAILY_BONUS_WORDS_KEY] = emptySet()
            settings[DAILY_REVEALED_HINTS_KEY] = emptySet()
        }
    }

    suspend fun addDailyFoundWord(word: String) {
        appContext.dataStore.edit { settings ->
            settings[DAILY_FOUND_WORDS_KEY] = (settings[DAILY_FOUND_WORDS_KEY] ?: emptySet()) + word
        }
    }

    suspend fun addDailyRevealedHint(row: Int, col: Int) {
        appContext.dataStore.edit { settings ->
            settings[DAILY_REVEALED_HINTS_KEY] =
                (settings[DAILY_REVEALED_HINTS_KEY] ?: emptySet()) + "$row,$col"
        }
    }

    /** Adds a daily bonus word, still folding it into the lifetime total the achievement tracks. */
    suspend fun addDailyBonusWord(word: String): Int {
        var newTotal = 0
        appContext.dataStore.edit { settings ->
            val current = settings[DAILY_BONUS_WORDS_KEY] ?: emptySet()
            newTotal = settings[TOTAL_BONUS_WORDS_KEY] ?: 0
            if (word !in current) {
                settings[DAILY_BONUS_WORDS_KEY] = current + word
                newTotal += 1
                settings[TOTAL_BONUS_WORDS_KEY] = newTotal
            }
        }
        return newTotal
    }

    suspend fun setTapToSpell(enabled: Boolean) {
        appContext.dataStore.edit { it[TAP_TO_SPELL_KEY] = enabled }
    }

    suspend fun setWheelSpacing(spacing: WheelSpacing) {
        appContext.dataStore.edit { it[WHEEL_SPACING_KEY] = spacing.name }
    }

    suspend fun setGameMode(mode: GameMode) {
        appContext.dataStore.edit { it[GAME_MODE_KEY] = mode.name }
    }

    suspend fun setDifficulty(difficulty: Difficulty) {
        appContext.dataStore.edit { it[DIFFICULTY_KEY] = difficulty.name }
    }

    /** Applies [delta] to the persisted competitive score, clamped so it never drops below zero. */
    suspend fun addToCompetitiveScore(delta: Int): Int {
        var newScore = 0
        appContext.dataStore.edit { settings ->
            val current = settings[COMPETITIVE_SCORE_KEY] ?: 0
            newScore = (current + delta).coerceAtLeast(0)
            settings[COMPETITIVE_SCORE_KEY] = newScore
        }
        return newScore
    }

    suspend fun addRevealedHint(row: Int, col: Int) {
        appContext.dataStore.edit { settings ->
            val current = settings[REVEALED_HINTS_KEY] ?: emptySet()
            settings[REVEALED_HINTS_KEY] = current + "$row,$col"
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
            val currentFoundWords = settings[FOUND_WORDS_KEY] ?: emptySet()
            settings[FOUND_WORDS_KEY] = currentFoundWords + word
        }
    }

    suspend fun saveLevel(level: Int) {
        appContext.dataStore.edit { settings ->
            settings[LEVEL_KEY] = level
            settings[FOUND_WORDS_KEY] = emptySet()
            settings[BONUS_WORDS_KEY] = emptySet()
            settings[REVEALED_HINTS_KEY] = emptySet()
        }
    }
}
