package com.vayunmathur.games.pipes.domain

import com.vayunmathur.games.pipes.data.CellPos
import com.vayunmathur.games.pipes.data.LevelData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** A level in progress: the board, plus the undo history whose length is the move count. */
data class SavedProgress(
    val state: BoardState,
    val history: List<BoardState>,
)

/**
 * Compact JSON for [SavedProgress]. A board stores each line as a flat `[r, c, r, c, ...]`
 * array plus the owners of bridge cells; every other owner is implied by the lines.
 */
object ProgressCodec {
    private const val VERSION = 1

    fun encode(progress: SavedProgress): String = buildJsonObject {
        put("v", VERSION)
        put("s", encodeState(progress.state))
        put("h", JsonArray(progress.history.map { encodeState(it) }))
    }.toString()

    /**
     * Decodes [json] for [level], or null when it is unreadable or does not fit the level
     * (a cell off the board, a broken or self-crossing line, an unknown colour, a line
     * touching another colour's dot).
     * A level whose layout changed under a saved game then simply starts fresh.
     */
    fun decode(json: String, level: LevelData): SavedProgress? = runCatching {
        val root = Json.parseToJsonElement(json).jsonObject
        if (root["v"]?.jsonPrimitive?.int != VERSION) return null
        val state = decodeState(root.getValue("s").jsonObject, level) ?: return null
        val history = root.getValue("h").jsonArray.map { decodeState(it.jsonObject, level) ?: return null }
        SavedProgress(state, history)
    }.getOrNull()

    private fun encodeState(state: BoardState): JsonObject = buildJsonObject {
        put("p", buildJsonObject {
            for ((color, path) in state.paths.entries.sortedBy { it.key }) {
                put(color.toString(), buildJsonArray { for (c in path) { add(c.row); add(c.col) } })
            }
        })
        val pathCells = state.paths.values.flatten().groupingBy { it }.eachCount()
        put("o", buildJsonObject {
            for ((cell, owner) in state.cellOwner) {
                if ((pathCells[cell] ?: 0) > 1) put("${cell.row},${cell.col}", owner)
            }
        })
    }

    private fun decodeState(obj: JsonObject, level: LevelData): BoardState? {
        val colors = level.endpoints.map { it.colorIndex }.toSet()
        val paths = obj.getValue("p").jsonObject.mapKeys { it.key.toInt() }.mapValues { (_, v) ->
            v.jsonArray.map { it.jsonPrimitive.int }.chunked(2) { (r, c) -> CellPos(r, c) }
        }
        for ((color, path) in paths) {
            if (color !in colors || path.size < 2 || path.any { it !in level.cells }) return null
            if (path.toSet().size != path.size) return null
            if (path.zipWithNext().any { (a, b) -> b !in level.adjacency[a].orEmpty() }) return null
            val foreignDots = level.endpoints.filter { it.colorIndex != color }.flatMap { it.cells }
            if (path.any { it in foreignDots }) return null
        }
        val bridgeOwners = obj["o"]?.jsonObject.orEmpty().entries.associate { (k, v) ->
            val (r, c) = k.split(",").map { it.toInt() }
            CellPos(r, c) to v.jsonPrimitive.int
        }
        return BoardState(paths, BoardEdits.owners(level, paths, bridgeOwners))
    }
}
