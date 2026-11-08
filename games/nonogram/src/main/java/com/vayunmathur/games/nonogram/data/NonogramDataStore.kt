package com.vayunmathur.games.nonogram.data

import android.content.Context
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
 * Casual progress is a single level number plus the marks on that one level; there is no per-level
 * record, because advancing clears the marks and there is no way back. Daily progress is kept
 * separately and stamped with the day it belongs to, so yesterday's work can never bleed into today's
 * puzzle — see [dailyDay] and `ensureDailyDay`.
 */
class NonogramDataStore(private val context: Context) {

    val currentLevel: Flow<Int> = context.dataStore.data.map { it[LEVEL_KEY] ?: 1 }

    val filledCells: Flow<Set<Int>> = context.dataStore.data.map { it[FILLED_KEY].toCells() }
    val crossedCells: Flow<Set<Int>> = context.dataStore.data.map { it[CROSSED_KEY].toCells() }
    val revealedBlanks: Flow<Set<Int>> = context.dataStore.data.map { it[REVEALED_KEY].toCells() }
    val hearts: Flow<Int> = context.dataStore.data.map { it[HEARTS_KEY] ?: STARTING_HEARTS }

    val gameMode: Flow<GameMode> = context.dataStore.data.map { prefs ->
        prefs[GAME_MODE_KEY]?.let { runCatching { GameMode.valueOf(it) }.getOrNull() }
            ?: GameMode.CASUAL
    }

    /** Which mark a tap places. Kept so the choice survives leaving and returning to the board. */
    val markMode: Flow<MarkMode> = context.dataStore.data.map { prefs ->
        prefs[MARK_MODE_KEY]?.let { runCatching { MarkMode.valueOf(it) }.getOrNull() }
            ?: MarkMode.FILL
    }

    /** Lifetime count of completed puzzles, which survives [saveLevel] clearing the board. */
    val puzzlesCompleted: Flow<Int> = context.dataStore.data.map { it[COMPLETED_KEY] ?: 0 }

    // ---- Daily ----

    val dailyDay: Flow<Long> = context.dataStore.data.map { it[DAILY_DAY_KEY] ?: NO_DAY }
    val dailyFilledCells: Flow<Set<Int>> =
        context.dataStore.data.map { it[DAILY_FILLED_KEY].toCells() }
    val dailyCrossedCells: Flow<Set<Int>> =
        context.dataStore.data.map { it[DAILY_CROSSED_KEY].toCells() }
    val dailyRevealedBlanks: Flow<Set<Int>> =
        context.dataStore.data.map { it[DAILY_REVEALED_KEY].toCells() }
    val dailyHearts: Flow<Int> =
        context.dataStore.data.map { it[DAILY_HEARTS_KEY] ?: STARTING_HEARTS }

    suspend fun setGameMode(mode: GameMode) {
        context.dataStore.edit { it[GAME_MODE_KEY] = mode.name }
    }

    suspend fun setMarkMode(mode: MarkMode) {
        context.dataStore.edit { it[MARK_MODE_KEY] = mode.name }
    }

    /**
     * Moves to [level] and wipes the board in the same write.
     *
     * One edit rather than four, so a crash between them cannot leave the previous level's marks or
     * spent hearts attached to the new puzzle.
     */
    suspend fun saveLevel(level: Int) {
        context.dataStore.edit { prefs ->
            prefs[LEVEL_KEY] = level
            prefs[FILLED_KEY] = emptySet()
            prefs[CROSSED_KEY] = emptySet()
            prefs[REVEALED_KEY] = emptySet()
            prefs[HEARTS_KEY] = STARTING_HEARTS
        }
    }

    /** Clears the current level's marks and restores its hearts, for restart and for a failed board. */
    suspend fun clearMarks(daily: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[if (daily) DAILY_FILLED_KEY else FILLED_KEY] = emptySet()
            prefs[if (daily) DAILY_CROSSED_KEY else CROSSED_KEY] = emptySet()
            prefs[if (daily) DAILY_REVEALED_KEY else REVEALED_KEY] = emptySet()
            prefs[if (daily) DAILY_HEARTS_KEY else HEARTS_KEY] = STARTING_HEARTS
        }
    }

    /** Marks [index] as part of the picture. */
    suspend fun fillCell(index: Int, daily: Boolean) {
        val filledKey = if (daily) DAILY_FILLED_KEY else FILLED_KEY
        val crossedKey = if (daily) DAILY_CROSSED_KEY else CROSSED_KEY
        context.dataStore.edit { prefs ->
            prefs[filledKey] = prefs[filledKey].toCells().plus(index).toStrings()
            // A confirmed fill makes any note on the same cell meaningless.
            prefs[crossedKey] = prefs[crossedKey].toCells().minus(index).toStrings()
        }
    }

    /** Adds or removes the player's own "looks empty" note on [index]. Never touches hearts. */
    suspend fun setNote(index: Int, crossed: Boolean, daily: Boolean) {
        val crossedKey = if (daily) DAILY_CROSSED_KEY else CROSSED_KEY
        context.dataStore.edit { prefs ->
            val notes = prefs[crossedKey].toCells()
            prefs[crossedKey] = (if (crossed) notes + index else notes - index).toStrings()
        }
    }

    /**
     * Records that [index] turned out to be empty, and spends the heart it cost.
     *
     * The heart is decremented from the stored value *inside* the transaction rather than written as a
     * number worked out beforehand. Two wrong taps in quick succession would otherwise both read the
     * same heart count and write the same result, so only one would be charged - and a later write
     * carrying a stale count could put a spent heart back.
     */
    suspend fun revealBlank(index: Int, daily: Boolean) {
        val revealedKey = if (daily) DAILY_REVEALED_KEY else REVEALED_KEY
        val crossedKey = if (daily) DAILY_CROSSED_KEY else CROSSED_KEY
        val heartsKey = if (daily) DAILY_HEARTS_KEY else HEARTS_KEY
        context.dataStore.edit { prefs ->
            val revealed = prefs[revealedKey].toCells()
            // Already revealed means already paid for; re-tapping must not charge again.
            if (index in revealed) return@edit
            prefs[revealedKey] = (revealed + index).toStrings()
            prefs[crossedKey] = prefs[crossedKey].toCells().minus(index).toStrings()
            prefs[heartsKey] = ((prefs[heartsKey] ?: STARTING_HEARTS) - 1).coerceAtLeast(0)
        }
    }

    /**
     * Banks the win identified by [key] and returns the lifetime completed count.
     *
     * The first call for a key bumps the count; repeats return the stored total unchanged, all in
     * one edit. The ViewModel's in-memory guard covers re-emitted flows within a session, but only
     * these stored keys survive a relaunch: without them, reopening an already-won board (killed
     * app before advancing, completed daily reopened the same day) replays the win and inflates
     * the count that feeds the `puzzles_10` / `puzzles_50` progress achievements. A set, not a
     * single last-key slot, because distinct wins (level 3, then level 4, then level 3 reopened)
     * must each survive.
     */
    suspend fun recordCompletedOnce(key: Long): Int {
        var total = 0
        val marker = key.toString()
        context.dataStore.edit { prefs ->
            val banked = prefs[COMPLETED_WIN_KEYS] ?: emptySet()
            total = prefs[COMPLETED_KEY] ?: 0
            if (marker in banked) return@edit
            total += 1
            prefs[COMPLETED_KEY] = total
            prefs[COMPLETED_WIN_KEYS] = banked + marker
        }
        return total
    }

    /**
     * Rolls the daily board over to [day] if it is not already there.
     *
     * Clearing on rollover is what stops yesterday's marks appearing on today's puzzle. The early
     * return keeps re-entering daily mode from wiping work in progress.
     */
    suspend fun ensureDailyDay(day: Long) {
        context.dataStore.edit { prefs ->
            if (prefs[DAILY_DAY_KEY] == day) return@edit
            prefs[DAILY_DAY_KEY] = day
            prefs[DAILY_FILLED_KEY] = emptySet()
            prefs[DAILY_CROSSED_KEY] = emptySet()
            prefs[DAILY_REVEALED_KEY] = emptySet()
            prefs[DAILY_HEARTS_KEY] = STARTING_HEARTS
        }
    }

    private companion object {
        val LEVEL_KEY = intPreferencesKey("current_level")
        val FILLED_KEY = stringSetPreferencesKey("filled_cells")
        val CROSSED_KEY = stringSetPreferencesKey("crossed_cells")
        val REVEALED_KEY = stringSetPreferencesKey("revealed_blanks")
        val HEARTS_KEY = intPreferencesKey("hearts")
        val GAME_MODE_KEY = stringPreferencesKey("game_mode")
        val MARK_MODE_KEY = stringPreferencesKey("mark_mode")
        val COMPLETED_KEY = intPreferencesKey("puzzles_completed")
        val COMPLETED_WIN_KEYS = stringSetPreferencesKey("puzzles_completed_win_keys")

        val DAILY_DAY_KEY = longPreferencesKey("daily_day")
        val DAILY_FILLED_KEY = stringSetPreferencesKey("daily_filled_cells")
        val DAILY_CROSSED_KEY = stringSetPreferencesKey("daily_crossed_cells")
        val DAILY_REVEALED_KEY = stringSetPreferencesKey("daily_revealed_blanks")
        val DAILY_HEARTS_KEY = intPreferencesKey("daily_hearts")

        /** Sentinel for "no daily played yet"; distinct from epoch day 0, which is a real date. */
        const val NO_DAY = Long.MIN_VALUE
    }
}

/** Cell indices are stored as strings because DataStore has no int-set type. */
private fun Set<String>?.toCells(): Set<Int> =
    this?.mapNotNullTo(mutableSetOf()) { it.toIntOrNull() } ?: emptySet()

private fun Set<Int>.toStrings(): Set<String> = mapTo(mutableSetOf()) { it.toString() }
