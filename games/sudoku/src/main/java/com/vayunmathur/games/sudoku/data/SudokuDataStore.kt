package com.vayunmathur.games.sudoku.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/** Remembers the last answer-mode choice from the new-game dialog so it can be pre-filled. */
class SudokuDataStore(private val context: Context) {

    /** Off until the player opts in. */
    val answerMode: Flow<Boolean> = context.dataStore.data.map { it[ANSWER_MODE_KEY] ?: false }

    suspend fun setAnswerMode(enabled: Boolean) {
        context.dataStore.edit { it[ANSWER_MODE_KEY] = enabled }
    }

    /** On until the player opts out: wrong digits draw in red as they play. */
    val highlightMistakes: Flow<Boolean> =
        context.dataStore.data.map { it[HIGHLIGHT_MISTAKES_KEY] ?: true }

    suspend fun setHighlightMistakes(enabled: Boolean) {
        context.dataStore.edit { it[HIGHLIGHT_MISTAKES_KEY] = enabled }
    }

    private companion object {
        val ANSWER_MODE_KEY = booleanPreferencesKey("answer_mode")
        val HIGHLIGHT_MISTAKES_KEY = booleanPreferencesKey("highlight_mistakes")
    }
}
