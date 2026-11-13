package com.vayunmathur.games.arrows.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/**
 * Everything the game remembers.
 *
 * Casual progress is a single level number plus which arrows have flown and how many hearts are left;
 * advancing clears both, so there is no level list and no way back. Daily progress is kept separately
 * and stamped with the day it belongs to, so yesterday's board can never bleed into today's.
 */
class ArrowsDataStore(private val context: Context) {

    val currentLevel: Flow<Int> = context.dataStore.data.map { it[LEVEL_KEY] ?: 1 }

    val removedArrows: Flow<Set<Int>> = context.dataStore.data.map { it[REMOVED_KEY].toIds() }

    val hearts: Flow<Int> = context.dataStore.data.map { it[HEARTS_KEY] ?: STARTING_HEARTS }

    val gameMode: Flow<GameMode> = context.dataStore.data.map { prefs ->
        prefs[GAME_MODE_KEY]?.let { runCatching { GameMode.valueOf(it) }.getOrNull() }
            ?: GameMode.CASUAL
    }

    /** Whether tapping a blocked arrow should preview why it cannot leave. */
    val showRoutes: Flow<Boolean> = context.dataStore.data.map { it[SHOW_ROUTES_KEY] ?: true }

    /** Whether the one-time explanation of redirectors has been seen (dismissed or a board cleared). */
    val redirectorHintSeen: Flow<Boolean> =
        context.dataStore.data.map { it[REDIRECTOR_HINT_SEEN_KEY] ?: false }

    /** Whether the one-time explanation of why a blocked tap costs a heart has been shown. */
    val heartHintSeen: Flow<Boolean> = context.dataStore.data.map { it[HEART_HINT_SEEN_KEY] ?: false }

    /** Lifetime count of cleared boards, which survives [saveLevel] resetting the current one. */
    val boardsCleared: Flow<Int> = context.dataStore.data.map { it[CLEARED_KEY] ?: 0 }

    // ---- Daily ----

    val dailyDay: Flow<Long> = context.dataStore.data.map { it[DAILY_DAY_KEY] ?: NO_DAY }
    val dailyRemoved: Flow<Set<Int>> = context.dataStore.data.map { it[DAILY_REMOVED_KEY].toIds() }
    val dailyHearts: Flow<Int> =
        context.dataStore.data.map { it[DAILY_HEARTS_KEY] ?: STARTING_HEARTS }

    suspend fun setGameMode(mode: GameMode) {
        context.dataStore.edit { it[GAME_MODE_KEY] = mode.name }
    }

    suspend fun setShowRoutes(enabled: Boolean) {
        context.dataStore.edit { it[SHOW_ROUTES_KEY] = enabled }
    }

    suspend fun markRedirectorHintSeen() {
        context.dataStore.edit { it[REDIRECTOR_HINT_SEEN_KEY] = true }
    }

    suspend fun markHeartHintSeen() {
        context.dataStore.edit { it[HEART_HINT_SEEN_KEY] = true }
    }

    /**
     * Moves to [level] and resets the board in the same write.
     *
     * One edit rather than three, so a crash between them cannot leave the previous level's cleared
     * arrows or spent hearts attached to the new board.
     */
    suspend fun saveLevel(level: Int) {
        context.dataStore.edit { prefs ->
            prefs[LEVEL_KEY] = level
            prefs[REMOVED_KEY] = emptySet()
            prefs[HEARTS_KEY] = STARTING_HEARTS
        }
    }

    /** Puts every arrow back and restores the hearts, for a restart or a failed level. */
    suspend fun resetBoard(daily: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[if (daily) DAILY_REMOVED_KEY else REMOVED_KEY] = emptySet()
            prefs[if (daily) DAILY_HEARTS_KEY else HEARTS_KEY] = STARTING_HEARTS
        }
    }

    /** Records the outcome of a tap: which arrows have gone, and what the hearts stand at. */
    suspend fun saveProgress(removed: Set<Int>, hearts: Int, daily: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[if (daily) DAILY_REMOVED_KEY else REMOVED_KEY] =
                removed.mapTo(mutableSetOf()) { it.toString() }
            prefs[if (daily) DAILY_HEARTS_KEY else HEARTS_KEY] = hearts
        }
    }

    /**
     * Counts one cleared board, unless [key] was already banked.
     *
     * The check and the bump share one edit, so a won board replaying through a fresh ViewModel
     * after process death — or two collectors firing at once — cannot count it twice. Returns the
     * new total, or null if [key] was already counted.
     */
    suspend fun recordClearedOnce(key: Long): Int? {
        var total: Int? = null
        context.dataStore.edit { prefs ->
            if (prefs[BANKED_KEY] == key) return@edit
            val next = (prefs[CLEARED_KEY] ?: 0) + 1
            prefs[BANKED_KEY] = key
            prefs[CLEARED_KEY] = next
            total = next
        }
        return total
    }

    /**
     * Rolls the daily board over to [day] if it is not already there.
     *
     * Resetting on rollover is what stops yesterday's cleared arrows appearing on today's board. The
     * early return keeps re-entering daily mode from wiping work in progress.
     */
    suspend fun ensureDailyDay(day: Long) {
        context.dataStore.edit { prefs ->
            if (prefs[DAILY_DAY_KEY] == day) return@edit
            prefs[DAILY_DAY_KEY] = day
            prefs[DAILY_REMOVED_KEY] = emptySet()
            prefs[DAILY_HEARTS_KEY] = STARTING_HEARTS
        }
    }

    private companion object {
        val LEVEL_KEY = intPreferencesKey("current_level")
        /** The clear key already banked; guards the lifetime counter across process death. */
        val BANKED_KEY = longPreferencesKey("last_banked_clear")
        val REMOVED_KEY = stringSetPreferencesKey("removed_arrows")
        val HEARTS_KEY = intPreferencesKey("hearts")
        val GAME_MODE_KEY = stringPreferencesKey("game_mode")
        val SHOW_ROUTES_KEY = booleanPreferencesKey("show_routes")
        val CLEARED_KEY = intPreferencesKey("boards_cleared")
        val REDIRECTOR_HINT_SEEN_KEY = booleanPreferencesKey("redirector_hint_seen")
        val HEART_HINT_SEEN_KEY = booleanPreferencesKey("heart_hint_seen")

        val DAILY_DAY_KEY = longPreferencesKey("daily_day")
        val DAILY_REMOVED_KEY = stringSetPreferencesKey("daily_removed_arrows")
        val DAILY_HEARTS_KEY = intPreferencesKey("daily_hearts")

        /** Sentinel for "no daily played yet"; distinct from epoch day 0, which is a real date. */
        const val NO_DAY = Long.MIN_VALUE
    }
}

/** Arrow ids are stored as strings because DataStore has no int-set type. */
private fun Set<String>?.toIds(): Set<Int> =
    this?.mapNotNullTo(mutableSetOf()) { it.toIntOrNull() } ?: emptySet()
