package com.vayunmathur.games.unblockjam

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class Coord(
    val x: Int,
    val y: Int
)

data class Dimension(
    val width: Int,
    val height: Int
)

data class Block(
    val position: Coord, // top left
    val dimension: Dimension,
    val fixed: Boolean
)

data class LevelPack(
    val name: String,
    val levels: List<LevelData>
) {
    companion object {
        private val PACK_FILES = listOf(
            "original_pack.json"
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

data class LevelData(
    val id: String,
    val dimension: Dimension,
    val exit: Coord,
    val blocks: List<Block>,
    val optimalMoves: Int,
    val lastMovedBlockIndex: Int? = null
)

private fun packFromJson(json: String): LevelPack {
    val jsonObject = Json.parseToJsonElement(json).jsonObject
    return LevelPack(jsonObject["name"]!!.jsonPrimitive.content, jsonObject["levels"]!!.jsonArray.map {
        fromJson(it.jsonObject)
    })
}

private fun fromJson(json: JsonObject): LevelData {
    val id = json["id"]!!.jsonPrimitive.content
    val dimension = Dimension(
        json["w"]!!.jsonPrimitive.int,
        json["h"]!!.jsonPrimitive.int
    )
    val exit = Coord(
        json["e"]!!.jsonObject["x"]!!.jsonPrimitive.int,
        dimension.height - (json["e"]!!.jsonObject["y"]!!.jsonPrimitive.int) - 1
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