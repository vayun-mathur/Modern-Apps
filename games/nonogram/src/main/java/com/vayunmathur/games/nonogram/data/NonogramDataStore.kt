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

    /**
     * The whole board for one mode, read from a single preferences snapshot.
     *
     * One flow rather than combining the per-set flows above: each of those collects the store
     * separately, so a combine of them can briefly pair the fills of one write with the hearts of the
     * previous one.
     */
    fun board(daily: Boolean): Flow<StoredBoard> = context.dataStore.data.map { prefs ->
        StoredBoard(
            filled = prefs[if (daily) DAILY_FILLED_KEY else FILLED_KEY].toCells(),
            crossed = prefs[if (daily) DAILY_CROSSED_KEY else CROSSED_KEY].toCells(),
            revealed = prefs[if (daily) DAILY_REVEALED_KEY else REVEALED_KEY].toCells(),
            hearts = prefs[if (daily) DAILY_HEARTS_KEY else HEARTS_KEY] ?: STARTING_HEARTS,
        )
    }

    /**
     * Writes the board the ViewModel holds in memory, in one edit.
     *
     * The ViewModel is the source of truth while a puzzle is open — it applies each move to its own
     * state first and persists afterwards — so this stores a whole snapshot rather than replaying a
     * delta against whatever is on disk. The write is dropped if the store has since moved on to a
     * different casual [level] or daily [day]: a snapshot that was queued behind "Next level" or a
     * daily rollover must not paint the previous puzzle's marks onto the new one.
     */
    suspend fun saveBoard(board: StoredBoard, daily: Boolean, level: Int, day: Long) {
        context.dataStore.edit { prefs ->
            if (daily && prefs[DAILY_DAY_KEY] != day) return@edit
            if (!daily && (prefs[LEVEL_KEY] ?: 1) != level) return@edit
            prefs[if (daily) DAILY_FILLED_KEY else FILLED_KEY] = board.filled.toStrings()
            prefs[if (daily) DAILY_CROSSED_KEY else CROSSED_KEY] = board.crossed.toStrings()
            prefs[if (daily) DAILY_REVEALED_KEY else REVEALED_KEY] = board.revealed.toStrings()
            prefs[if (daily) DAILY_HEARTS_KEY else HEARTS_KEY] = board.hearts
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

/** One mode's stored marks and hearts; see [NonogramDataStore.board]. */
data class StoredBoard(
    val filled: Set<Int> = emptySet(),
    val crossed: Set<Int> = emptySet(),
    val revealed: Set<Int> = emptySet(),
    val hearts: Int = STARTING_HEARTS,
)

/** Cell indices are stored as strings because DataStore has no int-set type. */
private fun Set<String>?.toCells(): Set<Int> =
    this?.mapNotNullTo(mutableSetOf()) { it.toIntOrNull() } ?: emptySet()

private fun Set<Int>.toStrings(): Set<String> = mapTo(mutableSetOf()) { it.toString() }
