package com.vayunmathur.games.pipes.data

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Immutable value describing cell pos; carries row, col. */
data class CellPos(val row: Int, val col: Int)

/** Immutable value describing render pos; carries x, y. */
data class RenderPos(val x: Float, val y: Float)

/** Immutable value describing endpoint pair; carries colorIndex, cells. */
data class EndpointPair(val colorIndex: Int, val cells: List<CellPos>)

/** Immutable value describing level data. */
data class LevelData(
    val id: String,
    val rows: Int,
    val cols: Int,
    val cells: Set<CellPos>,
    val adjacency: Map<CellPos, List<CellPos>>,
    val renderPositions: Map<CellPos, RenderPos>?,
    val endpoints: List<EndpointPair>,
    val bridges: Set<CellPos>,
    val optimalMoves: Int,
    val portals: Map<CellPos, CellPos> = emptyMap(),
    val grid: String = "square"
)

/** Immutable value describing level pack. */
data class LevelPack(
    val name: String,
    val shape: String,
    val levels: List<LevelData>
) {
    /** Shared constants and helpers for LevelPack. */
    companion object {
        private val PACK_FILES = listOf(
            "packs/5x5.json",
            "packs/6x6.json",
            "packs/7x7.json",
            "packs/8x8.json",
            "packs/9x9.json",
            "packs/10x10.json",
            "packs/11x11.json",
            "packs/12x12.json",
            "packs/13x13.json",
            "packs/14x14.json",
            "packs/tower.json",
            "packs/hourglass.json",
            "packs/blob.json",
            "packs/inkblot.json",
            "packs/walls.json",
            "packs/blockers.json",
            "packs/bridges.json",
            "packs/hexes.json",
            "packs/portals.json",
            "packs/triplets.json",
            "packs/wrap.json"
        )

        var PACKS: List<LevelPack> = listOf()
            private set

        fun init(context: Context) {
            PACKS = PACK_FILES.map { filename ->
                packFromJson(context.assets.open(filename).bufferedReader().readText())
            }
        }
    }
}

private fun rectangularCells(rows: Int, cols: Int): Set<CellPos> {
    return buildSet {
        for (r in 0 until rows) for (c in 0 until cols) add(CellPos(r, c))
    }
}

internal fun computeAdjacency(cells: Set<CellPos>): Map<CellPos, List<CellPos>> {
    val dirs = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)
    return cells.associateWith { cell ->
        dirs.mapNotNull { (dr, dc) ->
            val neighbor = CellPos(cell.row + dr, cell.col + dc)
            if (neighbor in cells) neighbor else null
        }
    }
}

/**
 * Six-neighbour adjacency for odd-r offset hex grids (same [CellPos] coords as squares).
 * Even rows use W/E + NW/NE/SW/SE shifted left, odd rows shifted right. Filters to [cells].
 */
internal fun computeHexAdjacency(cells: Set<CellPos>): Map<CellPos, List<CellPos>> {
    return cells.associateWith { cell ->
        val even = cell.row % 2 == 0
        val dirs = if (even) {
            listOf(-1 to -1, -1 to 0, 0 to -1, 0 to 1, 1 to -1, 1 to 0)
        } else {
            listOf(-1 to 0, -1 to 1, 0 to -1, 0 to 1, 1 to 0, 1 to 1)
        }
        dirs.mapNotNull { (dr, dc) ->
            val neighbor = CellPos(cell.row + dr, cell.col + dc)
            if (neighbor in cells) neighbor else null
        }
    }
}

/**
 * Staggered pixel centers for odd-r hex grids: x = col + 0.5 * (row parity), y = row * 0.866.
 * Consumed via [LevelData.renderPositions]; keeps logic in [CellPos] while drawing honeycomb.
 */
internal fun hexRenderPositions(cells: Set<CellPos>): Map<CellPos, RenderPos> {
    return cells.associateWith { cell ->
        val x = cell.col + 0.5f * (cell.row and 1)
        val y = cell.row * 0.8660254f
        RenderPos(x, y)
    }
}

/** True when a level needs the custom-topology path (solver exemption, hex art, portal rings). */
fun LevelData.isCustomTopology(): Boolean {
    if (bridges.isNotEmpty() || portals.isNotEmpty()) return true
    if (renderPositions != null) return true
    if (endpoints.any { it.cells.size != 2 }) return true
    val square = computeAdjacency(cells)
    if (adjacency.keys != square.keys) return true
    for ((cell, neighbors) in adjacency) {
        if (neighbors.toSet() != (square[cell]?.toSet() ?: emptySet<CellPos>())) return true
    }
    return false
}

private fun packFromJson(json: String): LevelPack {
    val obj = Json.parseToJsonElement(json).jsonObject
    val name = obj["name"]!!.jsonPrimitive.content
    val shape = obj["shape"]!!.jsonPrimitive.content
    val levels = obj["levels"]!!.jsonArray.map { levelFromJson(it.jsonObject) }
    return LevelPack(name, shape, levels)
}

private fun levelFromJson(json: kotlinx.serialization.json.JsonObject): LevelData {
    val id = json["id"]!!.jsonPrimitive.content
    val rows = json["rows"]!!.jsonPrimitive.int
    val cols = json["cols"]!!.jsonPrimitive.int
    // "grid": "hex" derives odd-r six-neighbour adjacency + staggered centers, so hex packs only
    // declare cells + endpoints. Anything else is square with an optional explicit adjacency.
    val grid = json["grid"]?.jsonPrimitive?.content ?: "square"
    val cells = cellsFromJson(json, rows, cols)
    val adjacency = adjacencyFromJson(json, cells, grid)
    // Portals as paired cells: "portals": [[[r,c],[r,c]], ...]. Each pair is bidirectional.
    // Transit is a normal adjacency step: the links are unioned into the adjacency below, and
    // isValidStep accepts them like any other edge.
    val portals = pairsFromJson(json, "portals").toMap()
    // Extra long-range edges (wrap/torus): "extraEdges": [[[r,c],[r,c]], ...], bidirectional.
    val extraEdges = pairsFromJson(json, "extraEdges")
    val fullAdjacency = mergedAdjacency(adjacency, cells, portals, extraEdges)
    val renderPositions = renderPositionsFromJson(json, cells, grid)
    val endpoints = endpointsFromJson(json)
    val bridges = json["bridges"]?.jsonArray?.map {
        val arr = it.jsonArray
        CellPos(arr[0].jsonPrimitive.int, arr[1].jsonPrimitive.int)
    }?.toSet() ?: emptySet()
    val optimalMoves = json["optimalMoves"]!!.jsonPrimitive.int
    return LevelData(
        id, rows, cols, cells, fullAdjacency, renderPositions, endpoints, bridges, optimalMoves, portals, grid,
    )
}

/** Declared cells, or the full rows x cols rectangle when omitted. */
private fun cellsFromJson(
    json: kotlinx.serialization.json.JsonObject,
    rows: Int,
    cols: Int,
): Set<CellPos> = json["cells"]?.jsonArray?.map {
    val arr = it.jsonArray
    CellPos(arr[0].jsonPrimitive.int, arr[1].jsonPrimitive.int)
}?.toSet() ?: rectangularCells(rows, cols)

/** Declared adjacency, or the grid-derived adjacency when omitted. */
private fun adjacencyFromJson(
    json: kotlinx.serialization.json.JsonObject,
    cells: Set<CellPos>,
    grid: String,
): Map<CellPos, List<CellPos>> = json["adjacency"]?.jsonObject?.map { (key, value) ->
    val parts = key.split(",")
    val cell = CellPos(parts[0].toInt(), parts[1].toInt())
    val neighbors = value.jsonArray.map {
        val arr = it.jsonArray
        CellPos(arr[0].jsonPrimitive.int, arr[1].jsonPrimitive.int)
    }
    cell to neighbors
}?.toMap() ?: if (grid == "hex") computeHexAdjacency(cells) else computeAdjacency(cells)

/** Bidirectional cell pairs declared under [key] ("portals" / "extraEdges"). */
private fun pairsFromJson(
    json: kotlinx.serialization.json.JsonObject,
    key: String,
): List<Pair<CellPos, CellPos>> = json[key]?.jsonArray?.flatMap { pair ->
    val ends = pair.jsonArray.map { c ->
        val arr = c.jsonArray
        CellPos(arr[0].jsonPrimitive.int, arr[1].jsonPrimitive.int)
    }
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
    for ((a, b) in portals) {
        merged.getOrPut(a) { mutableSetOf() }.add(b)
    }
    for ((a, b) in extraEdges) {
        merged.getOrPut(a) { mutableSetOf() }.add(b)
    }
    for (cell in cells) merged.putIfAbsent(cell, mutableSetOf())
    return merged.mapValues { (_, neighbors) -> neighbors.toList() }
}

/** Declared render positions, or hex-derived ones for hex grids. */
private fun renderPositionsFromJson(
    json: kotlinx.serialization.json.JsonObject,
    cells: Set<CellPos>,
    grid: String,
): Map<CellPos, RenderPos>? = json["renderPositions"]?.jsonObject?.map { (key, value) ->
    val parts = key.split(",")
    val cell = CellPos(parts[0].toInt(), parts[1].toInt())
    val posObj = value.jsonObject
    val pos = RenderPos(posObj["x"]!!.jsonPrimitive.float, posObj["y"]!!.jsonPrimitive.float)
    cell to pos
}?.toMap() ?: if (grid == "hex") hexRenderPositions(cells) else null

/** Endpoint pairs, colour defaulting to declaration order. */
private fun endpointsFromJson(json: kotlinx.serialization.json.JsonObject): List<EndpointPair> =
    json["endpoints"]!!.jsonArray.mapIndexed { index, endpoint ->
        val epObj = endpoint.jsonObject
        val colorIndex = epObj["color"]?.jsonPrimitive?.intOrNull ?: index
        val epCells = epObj["cells"]!!.jsonArray.map { c ->
            val arr = c.jsonArray
            CellPos(arr[0].jsonPrimitive.int, arr[1].jsonPrimitive.int)
        }
        EndpointPair(colorIndex, epCells)
    }
