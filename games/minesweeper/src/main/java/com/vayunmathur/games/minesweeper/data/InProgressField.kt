package com.vayunmathur.games.minesweeper.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** A field left in progress: board, clock and tap mode, saved per [GameConfig.variant]. */
data class SavedMinesweeperGame(
    val game: MinesweeperGameState,
    val elapsedSeconds: Int,
    val tapMode: TapMode,
    val placedAnyFlag: Boolean,
)

/**
 * Compact JSON for [SavedMinesweeperGame], following the pipes/unblockjam codec approach:
 * per-cell lists as 0/1 strings, a version tag, and strict validation on decode. A save
 * that does not fit [config] (dimensions, mine count, lengths, mine total) is dropped.
 */
object MinesweeperProgressCodec {
    private const val VERSION = 1

    fun encode(saved: SavedMinesweeperGame): String {
        val game = saved.game
        return buildJsonObject {
            put("v", VERSION)
            put("cols", game.cols)
            put("rows", game.rows)
            put("mineCount", game.mineCount)
            put("started", game.started)
            put("explodedAt", game.explodedAt)
            put("elapsed", saved.elapsedSeconds)
            put("tapMode", saved.tapMode.name)
            put("placedFlag", saved.placedAnyFlag)
            put("mines", bits(game.mines))
            put("revealed", bits(game.revealed))
            put("flagged", bits(game.flagged))
        }.toString()
    }

    fun decode(json: String, config: GameConfig): SavedMinesweeperGame? = runCatching {
        val root = Json.parseToJsonElement(json).jsonObject
        if (root["v"]?.jsonPrimitive?.int != VERSION) return null
        val cols = root.getValue("cols").jsonPrimitive.int
        val rows = root.getValue("rows").jsonPrimitive.int
        if (cols != config.size.cols || rows != config.size.rows) return null
        val mineCount = root.getValue("mineCount").jsonPrimitive.int
        if (mineCount != config.difficulty.mineCount(config.size)) return null
        val cells = cols * rows
        val mines = bits(root.getValue("mines").jsonPrimitive.content, cells) ?: return null
        val revealed = bits(root.getValue("revealed").jsonPrimitive.content, cells) ?: return null
        val flagged = bits(root.getValue("flagged").jsonPrimitive.content, cells) ?: return null
        val started = root.getValue("started").jsonPrimitive.content.toBooleanStrict()
        if (started && mines.count { it } != mineCount) return null
        if (!started && (mines.any { it } || revealed.any { it } || flagged.any { it })) return null
        if (revealed.indices.any { revealed[it] && flagged[it] }) return null
        val elapsed = root.getValue("elapsed").jsonPrimitive.int
        if (elapsed < 0) return null
        val game = MinesweeperGameState(
            cols = cols,
            rows = rows,
            mineCount = mineCount,
            mines = mines,
            neighbourCounts = neighbourCounts(mines, cols, rows),
            revealed = revealed,
            flagged = flagged,
            outcome = GameOutcome.PLAYING,
            explodedAt = root.getValue("explodedAt").jsonPrimitive.int,
            started = started,
        )
        if (game.explodedAt != -1 || game.isOver) return null
        SavedMinesweeperGame(
            game = game,
            elapsedSeconds = elapsed,
            tapMode = TapMode.valueOf(root.getValue("tapMode").jsonPrimitive.content),
            placedAnyFlag = root.getValue("placedFlag").jsonPrimitive.content.toBooleanStrict(),
        )
    }.getOrNull()

    private fun bits(values: List<Boolean>): String =
        values.joinToString("") { if (it) "1" else "0" }

    private fun bits(raw: String, cells: Int): List<Boolean>? {
        if (raw.length != cells || raw.any { it != '0' && it != '1' }) return null
        return raw.map { it == '1' }
    }

    private fun neighbourCounts(mines: List<Boolean>, cols: Int, rows: Int): List<Int> =
        List(cols * rows) { index ->
            // Mirrors FieldGenerator: a mine cell's own count is 0, never read.
            if (mines[index]) 0 else neighbourIndices(cols, rows, index).count { mines[it] }
        }
}

/**
 * Unfinished fields, one encoded string per variant, so process death never loses a
 * game in progress. Own prefs file, following unblockjam's InProgressBoards.
 */
class InProgressField(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(config: GameConfig): SavedMinesweeperGame? =
        prefs.getString(config.variant, null)
            ?.let { MinesweeperProgressCodec.decode(it, config) }

    fun save(config: GameConfig, saved: SavedMinesweeperGame) {
        prefs.edit { putString(config.variant, MinesweeperProgressCodec.encode(saved)) }
    }

    fun clear(variant: String) {
        prefs.edit { remove(variant) }
    }

    private companion object {
        const val PREFS_NAME = "minesweeper_in_progress"
    }
}
