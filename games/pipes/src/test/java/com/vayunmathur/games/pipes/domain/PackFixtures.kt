package com.vayunmathur.games.pipes.domain

import com.vayunmathur.games.pipes.data.CellPos
import com.vayunmathur.games.pipes.data.EndpointPair
import com.vayunmathur.games.pipes.data.LevelData
import com.vayunmathur.games.pipes.data.RenderPos
import com.vayunmathur.games.pipes.data.computeAdjacency
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Reads the shipped packs straight off disk. `LevelPack.init` needs a Context to reach the asset
 * manager, which a JVM unit test has no way to provide.
 */
object PackFixtures {

    private val packsDir = File("src/main/assets/packs")

    fun packNames(): List<String> = packsDir.listFiles()!!.map { it.name }.sorted()

    fun levels(packName: String): List<LevelData> {
        val obj = Json.parseToJsonElement(packsDir.resolve(packName).readText()).jsonObject
        return obj["levels"]!!.jsonArray.map { levelFrom(it.jsonObject) }
    }

    fun allLevels(): List<LevelData> = packNames().flatMap { levels(it) }

    private fun levelFrom(json: kotlinx.serialization.json.JsonObject): LevelData {
        val rows = json["rows"]!!.jsonPrimitive.int
        val cols = json["cols"]!!.jsonPrimitive.int
        val grid = json["grid"]?.jsonPrimitive?.content ?: "square"
        val cells = cellsFrom(json, rows, cols)
        val adjacency = adjacencyFrom(json, cells, grid)
        val portals = pairsFrom(json, "portals").toMap()
        val extraEdges = pairsFrom(json, "extraEdges")
        val fullAdjacency = mergedAdjacency(adjacency, cells, portals, extraEdges)
        val renderPositions = renderPositionsFrom(json, cells, grid)
        val endpoints = endpointsFrom(json)
        val bridges = json["bridges"]?.jsonArray?.map { cellFrom(it) }?.toSet() ?: emptySet()
        return LevelData(
            id = json["id"]!!.jsonPrimitive.content,
            rows = rows,
            cols = cols,
            cells = cells,
            adjacency = fullAdjacency,
            renderPositions = renderPositions,
            endpoints = endpoints,
            bridges = bridges,
            optimalMoves = json["optimalMoves"]!!.jsonPrimitive.int,
            portals = portals,
            grid = grid,
        )
    }

    /** Declared cells, or the full rows x cols rectangle when omitted. */
    private fun cellsFrom(
        json: kotlinx.serialization.json.JsonObject,
        rows: Int,
        cols: Int,
    ): Set<CellPos> = json["cells"]?.jsonArray?.map { cellFrom(it) }?.toSet()
        ?: buildSet { for (r in 0 until rows) for (c in 0 until cols) add(CellPos(r, c)) }

    /** Declared adjacency, or the grid-derived adjacency when omitted. */
    private fun adjacencyFrom(
        json: kotlinx.serialization.json.JsonObject,
        cells: Set<CellPos>,
        grid: String,
    ): Map<CellPos, List<CellPos>> = json["adjacency"]?.jsonObject?.map { (key, value) ->
        val parts = key.split(",")
        val cell = CellPos(parts[0].toInt(), parts[1].toInt())
        cell to value.jsonArray.map { cellFrom(it) }
    }?.toMap() ?: if (grid == "hex") {
        com.vayunmathur.games.pipes.data.computeHexAdjacency(cells)
    } else {
        computeAdjacency(cells)
    }

    /** Bidirectional cell pairs declared under [key] ("portals" / "extraEdges"). */
    private fun pairsFrom(
        json: kotlinx.serialization.json.JsonObject,
        key: String,
    ): List<Pair<CellPos, CellPos>> = json[key]?.jsonArray?.flatMap { pair ->
        val ends = pair.jsonArray.map { cellFrom(it) }
        if (ends.size == 2) listOf(ends[0] to ends[1], ends[1] to ends[0]) else emptyList()
    } ?: emptyList()

    /** Base adjacency unioned with portals and extra edges. */
    private fun mergedAdjacency(
        adjacency: Map<CellPos, List<CellPos>>,
        cells: Set<CellPos>,
        portals: Map<CellPos, CellPos>,
        extraEdges: List<Pair<CellPos, CellPos>>,
    ): Map<CellPos, List<CellPos>> {
        if (portals.isEmpty() && extraEdges.isEmpty()) return adjacency
        val merged = adjacency.mapValues { (_, neighbors) -> neighbors.toMutableSet() }.toMutableMap()
        for ((a, b) in portals) merged.getOrPut(a) { mutableSetOf() }.add(b)
        for ((a, b) in extraEdges) merged.getOrPut(a) { mutableSetOf() }.add(b)
        for (cell in cells) merged.putIfAbsent(cell, mutableSetOf())
        return merged.mapValues { (_, neighbors) -> neighbors.toList() }
    }

    /** Declared render positions, or hex-derived ones for hex grids. */
    private fun renderPositionsFrom(
        json: kotlinx.serialization.json.JsonObject,
        cells: Set<CellPos>,
        grid: String,
    ): Map<CellPos, RenderPos>? = json["renderPositions"]?.jsonObject?.map { (key, value) ->
        val parts = key.split(",")
        val cell = CellPos(parts[0].toInt(), parts[1].toInt())
        val posObj = value.jsonObject
        cell to RenderPos(posObj["x"]!!.jsonPrimitive.float, posObj["y"]!!.jsonPrimitive.float)
    }?.toMap() ?: if (grid == "hex") {
        com.vayunmathur.games.pipes.data.hexRenderPositions(cells)
    } else {
        null
    }

    /** Endpoint pairs, colour defaulting to declaration order. */
    private fun endpointsFrom(json: kotlinx.serialization.json.JsonObject): List<EndpointPair> =
        json["endpoints"]!!.jsonArray.mapIndexed { index, element ->
            val ep = element.jsonObject
            EndpointPair(
                ep["color"]?.jsonPrimitive?.intOrNull ?: index,
                ep["cells"]!!.jsonArray.map { cellFrom(it) },
            )
        }

    private fun cellFrom(element: kotlinx.serialization.json.JsonElement): CellPos {
        val pair = element.jsonArray
        return CellPos(pair[0].jsonPrimitive.int, pair[1].jsonPrimitive.int)
    }
}
