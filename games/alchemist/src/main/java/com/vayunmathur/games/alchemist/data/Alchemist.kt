package com.vayunmathur.games.alchemist.data
import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object Alchemist {
    lateinit var items: List<AlchemyItem>
        private set
    lateinit var recipes: List<AlchemyRecipe>
        private set

    fun init(context: Context) {
        val jsonItems = Json.decodeFromString<List<JsonItem>>(context.assets.open("items.json").bufferedReader().readText())
        recipes = jsonItems.flatMap { item ->
            item.recipes.map { recipe ->
                AlchemyRecipe(
                    recipe,
                    listOf(item.id)
                )
            }
        }.groupBy { it.inputs }.map { (inputs, outputs) -> AlchemyRecipe(inputs,
            outputs.flatMap { it.outputs }) }
        val nonFinals = recipes.flatMap { it.outputs }.toSet()
        items = jsonItems.map { item -> AlchemyItem(item.id, item.name, item.id !in nonFinals) }
        println(recipes)
        println(items)
    }

    @Serializable
    data class JsonItem(val id: Long, val name: String, val recipes: List<List<Long>>)
}

data class AlchemyItem(val id: Long, val name: String, val final: Boolean)

data class AlchemyRecipe(val inputs: List<Long>, val outputs: List<Long>)
