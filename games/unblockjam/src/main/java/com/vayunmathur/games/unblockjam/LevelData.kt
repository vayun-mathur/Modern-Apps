package com.vayunmathur.games.unblockjam

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
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
    val dimension: Dimension
)

/*
{
    "b": [
      {
        "y": 2,
        "w": 2,
        "h": 1
      },
      {
        "x": 2,
        "y": 2,
        "w": 1,
        "h": 2
      }
    ],
    "e": {
      "x": 4,
      "y": 2
    },
    "w": 4,
    "h": 4,
    "c": "1,2,0;0,2,2;"
  }
 */

data class LevelData(
    val dimension: Dimension,
    val exit: Coord,
    val blocks: List<Block>,
    val optimalMoves: Int
) {
    companion object {

        var LEVELS = listOf<LevelData>()
            private set

        private fun fromJson(json: JsonObject): LevelData {
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
                    blockDim
                )
            }
            val optimalMoves = json["c"]!!.jsonPrimitive.content.split(";").filter { it.isNotEmpty() }.size
            return LevelData(dimension, exit, blocks, optimalMoves)
        }

        fun init(context: Context) {
            val json = context.assets.open("levels.json").bufferedReader().readText()
            LEVELS = Json.parseToJsonElement(json).jsonArray.map {
                fromJson(it.jsonObject)
            }
        }
    }
}
