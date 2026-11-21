package com.vayunmathur.games.sudoku.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private val Context.dataStore by preferencesDataStore(name = "settings")

/** A sudoku game left in progress: board, undo snapshots and clock, saved per variant. */
data class SavedSudokuGame(
    val game: SudokuGameState,
    val history: List<SudokuSnapshot>,
    val elapsedSeconds: Int,
)

/**
 * Compact JSON for [SavedSudokuGame], following the pipes/unblockjam codec approach:
 * flat int arrays for every board list, a version tag, and strict validation on decode.
 * A save that does not fit [GameConfig] (size, difficulty or answer mode changed, a list
 * of the wrong length, a digit out of range) decodes to null and the game starts fresh.
 */
object SudokuProgressCodec {
    private const val VERSION = 1

    fun encode(saved: SavedSudokuGame): String {
        val game = saved.game
        return buildJsonObject {
            put("v", VERSION)
            put("size", game.size.name)
            put("difficulty", game.difficulty.name)
            put("answerMode", game.answerMode)
            put("elapsed", saved.elapsedSeconds)
            put("givens", ints(game.givens))
            put("solution", ints(game.solution))
            put("entries", ints(game.entries))
            put("notes", ints(game.notes))
            put("selected", game.selected)
            put("notesMode", game.notesMode)
            put("moveCount", game.moveCount)
            put("hintsUsed", game.hintsUsed)
            put("usedUndo", game.usedUndo)
            put("lives", game.livesRemaining)
            put("h", JsonArray(saved.history.map { snap ->
                buildJsonObject { put("e", ints(snap.entries)); put("n", ints(snap.notes)) }
            }))
        }.toString()
    }

    fun decode(json: String, config: GameConfig): SavedSudokuGame? = runCatching {
        val root = Json.parseToJsonElement(json).jsonObject
        if (root["v"]?.jsonPrimitive?.int != VERSION) return null
        val size = BoardSize.valueOf(root.getValue("size").jsonPrimitive.content)
        val difficulty = Difficulty.valueOf(root.getValue("difficulty").jsonPrimitive.content)
        if (size != config.size || difficulty != config.difficulty) return null
        val answerMode = root.getValue("answerMode").jsonPrimitive.content.toBooleanStrict()
        if (answerMode != config.answerMode) return null
        val cells = size.cellCount
        val givens = ints(root, "givens", cells) ?: return null
        val solution = ints(root, "solution", cells) ?: return null
        val entries = ints(root, "entries", cells) ?: return null
        val notes = ints(root, "notes", cells) ?: return null
        if (givens.any { it !in 0..size.side } || solution.any { it !in 1..size.side }) return null
        if (entries.any { it !in 0..size.side }) return null
        val maxMask = (1 shl size.side) - 1
        if (notes.any { it !in 0..maxMask }) return null
        val selected = root.getValue("selected").jsonPrimitive.int
        if (selected !in -1 until cells) return null
        val history = root.getValue("h").jsonArray.map { element ->
            val obj = element.jsonObject
            val e = obj.getValue("e").jsonArray.map { it.jsonPrimitive.int }
            val n = obj.getValue("n").jsonArray.map { it.jsonPrimitive.int }
            if (e.size != cells || n.size != cells) return null
            if (e.any { it !in 0..size.side } || n.any { it !in 0..maxMask }) return null
            SudokuSnapshot(e, n)
        }
        val elapsed = root.getValue("elapsed").jsonPrimitive.int
        if (elapsed < 0) return null
        val game = SudokuGameState(
            size = size,
            difficulty = difficulty,
            givens = givens,
            solution = solution,
            entries = entries,
            notes = notes,
            selected = selected,
            notesMode = root.getValue("notesMode").jsonPrimitive.content.toBooleanStrict(),
            moveCount = root.getValue("moveCount").jsonPrimitive.int,
            hintsUsed = root.getValue("hintsUsed").jsonPrimitive.int,
            isWon = false,
            usedUndo = root.getValue("usedUndo").jsonPrimitive.content.toBooleanStrict(),
            answerMode = answerMode,
            livesRemaining = root.getValue("lives").jsonPrimitive.int,
        )
        if (game.isWon || game.isGameOver || game.livesRemaining !in 0..ANSWER_MODE_LIVES) return null
        SavedSudokuGame(game, history, elapsed)
    }.getOrNull()

    private fun ints(values: List<Int>): JsonArray = buildJsonArray { for (v in values) add(v) }

    private fun ints(root: kotlinx.serialization.json.JsonObject, key: String, size: Int): List<Int>? {
        val list = root.getValue(key).jsonArray.map { it.jsonPrimitive.int }
        return list.takeIf { it.size == size }
    }
}

/** Remembers preferences and the in-progress game (one per [GameConfig.variant]). */
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

    /** The saved game for [config]'s variant, or null if none fits [config]. */
    suspend fun loadInProgress(config: GameConfig): SavedSudokuGame? {
        val raw = context.dataStore.data.first()[inProgressKey(config.variant)] ?: return null
        return SudokuProgressCodec.decode(raw, config)
    }

    suspend fun saveInProgress(saved: SavedSudokuGame) {
        context.dataStore.edit {
            it[inProgressKey(saved.game.variant)] = SudokuProgressCodec.encode(saved)
        }
    }

    suspend fun clearInProgress(variant: String) {
        context.dataStore.edit { it.remove(inProgressKey(variant)) }
    }

    private companion object {
        val ANSWER_MODE_KEY = booleanPreferencesKey("answer_mode")
        val HIGHLIGHT_MISTAKES_KEY = booleanPreferencesKey("highlight_mistakes")
        fun inProgressKey(variant: String) = stringPreferencesKey("in_progress_$variant")
    }
}
