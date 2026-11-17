package com.vayunmathur.games.unblockjam.data
import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A cell coordinate on the board. */
data class Coord(
    val x: Int,
    val y: Int
)

/** Board size in cells. */
data class Dimension(
    val width: Int,
    val height: Int
)

/** A sliding block: its top-left position, size, and whether it is fixed. */
data class Block(
    val position: Coord, // top left
    val dimension: Dimension,
    val fixed: Boolean
)

/** A named set of levels with an optional colour scheme. */
data class LevelPack(
    val name: String,
    val levels: List<LevelData>,
    val colorScheme: PackColorScheme? = null
) {
    /** Shipped pack files and the loaded pack registry. */
    companion object {
        private val PACK_FILES = listOf(
            "original_pack.json",
            "walls_1_pack.json",
            "walls_2_pack.json",
            "walls_3_pack.json",
            "size_7_pack.json",
            "size_8_pack.json",
            "size_9_pack.json"
        )

        /** The wall packs share one combined completion achievement. */
        val WALL_PACK_INDICES: List<Int> =
            PACK_FILES.indices.filter { PACK_FILES[it].startsWith("walls_") }

        /** So do the larger-board packs. */
        val SIZE_PACK_INDICES: List<Int> =
            PACK_FILES.indices.filter { PACK_FILES[it].startsWith("size_") }

        var PACKS: List<LevelPack> = listOf()
            private set

        fun init(context: Context) {
            PACKS = PACK_FILES.map { filename ->
                packFromJson(context.assets.open(filename).bufferedReader().readText())
            }
        }
    }
}

/** One puzzle: board size, exit cell, blocks, and solver optimal move count. */
data class LevelData(
    val id: String,
    val dimension: Dimension,
    val exit: Coord,
    val blocks: List<Block>,
    val optimalMoves: Int,
    val lastMovedBlockIndex: Int? = null
)

/** Per-pack board colours as ARGB values. */
data class PackColorScheme(
    val primary: Long,
    val secondary: Long,
    val tertiary: Long,
    val background: Long,
    val surface: Long,
    val primaryContainer: Long,
    val secondaryContainer: Long,
    val onPrimary: Long = 0xFFFFFFFF,
    val onSecondary: Long = 0xFFFFFFFF,
    val onTertiary: Long = 0xFFFFFFFF,
    val onBackground: Long = 0xFFFFFFFF,
    val onSurface: Long = 0xFFFFFFFF,
    val error: Long = 0xFFFF0000
)

internal fun packFromJson(json: String): LevelPack {
    val jsonObject = Json.parseToJsonElement(json).jsonObject
    val colors = jsonObject["colors"]?.jsonObject?.let {
        fun parseColor(key: String, default: Long): Long =
            it[key]?.jsonPrimitive?.content
                ?.removePrefix("0x")?.removePrefix("#")
                ?.toLongOrNull(16) ?: default
        PackColorScheme(
            primary = parseColor("primary", 0xFF3D3021),
            secondary = parseColor("secondary", 0xFF4A3B2A),
            tertiary = parseColor("tertiary", 0xFF8B7E6A),
            background = parseColor("background", 0xFF4A3B2A),
            surface = parseColor("surface", 0xFF3D3021),
            primaryContainer = parseColor("primaryContainer", 0xFFFFA500),
            secondaryContainer = parseColor("secondaryContainer", 0xFF6F5E55),
            onPrimary = parseColor("onPrimary", 0xFFFFFFFF),
            onSecondary = parseColor("onSecondary", 0xFFFFFFFF),
            onTertiary = parseColor("onTertiary", 0xFFFFFFFF),
            onBackground = parseColor("onBackground", 0xFFFFFFFF),
            onSurface = parseColor("onSurface", 0xFFFFFFFF),
            error = parseColor("error", 0xFFFF0000)
        )
    }
    return LevelPack(jsonObject["name"]!!.jsonPrimitive.content, jsonObject["levels"]!!.jsonArray.map {
        fromJson(it.jsonObject)
    }, colors)
}

private fun fromJson(json: JsonObject): LevelData {
    val id = json["id"]!!.jsonPrimitive.content
    val dimension = Dimension(
        json["w"]!!.jsonPrimitive.int,
        json["h"]!!.jsonPrimitive.int
    )
    val exitObj = json["e"]!!.jsonObject
    val exit = Coord(
        exitObj["x"]!!.jsonPrimitive.int,
        dimension.height - exitObj["y"]!!.jsonPrimitive.int - 1
    )
    val blocks = json["b"]!!.jsonArray.map {
        val block = it.jsonObject
        val blockDim = Dimension(
            block["w"]!!.jsonPrimitive.int,
            block["h"]!!.jsonPrimitive.int
        )
        val y = block["y"]?.jsonPrimitive?.int ?: 0
        Block(
            Coord(
                block["x"]?.jsonPrimitive?.int ?: 0,
                dimension.height - y - blockDim.height
            ),
            blockDim,
            block["fixed"]?.jsonPrimitive?.boolean ?: false
        )
    }
    val optimalMoves = json["c"]!!.jsonPrimitive.int
    return LevelData(id, dimension, exit, blocks, optimalMoves)
}
