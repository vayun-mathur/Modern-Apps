package com.vayunmathur.games.sudoku.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/**
 * The answer-mode switch on the home screen.
 *
 * Persisted on its own rather than folded into the stats record because it is a preference, not a
 * result: stats are keyed per variant and rewritten on every game, while this is one global default
 * read when a puzzle is dealt. The game itself carries a snapshot — see
 * [SudokuGameState.answerMode] — so toggling mid-game cannot rewrite the rules under the player.
 */
class SudokuDataStore(private val context: Context) {

    /** Whether new puzzles reject wrong digits and cost lives. Off until the player opts in. */
    val answerMode: Flow<Boolean> = context.dataStore.data.map { it[ANSWER_MODE_KEY] ?: false }

    suspend fun setAnswerMode(enabled: Boolean) {
        context.dataStore.edit { it[ANSWER_MODE_KEY] = enabled }
    }

    private companion object {
        val ANSWER_MODE_KEY = booleanPreferencesKey("answer_mode")
    }
}
