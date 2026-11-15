package com.vayunmathur.health.util

import com.vayunmathur.health.data.Ingredient

/**
 * Ingredient lookup for the recipe builder.
 *
 * Both functions used to be HTTP calls to `/api/food/search` and
 * `/api/food/data/:id`. They now read the [FoodDatabase] bundle on the device,
 * so the recipe flow makes no network requests at all — the only time the app
 * touches the network for food is when the user explicitly downloads or
 * refreshes that bundle.
 *
 * The server endpoints are still live for older builds. Nothing here calls
 * them, and there is no network fallback: if the bundle isn't installed these
 * return nothing, and the search UI shows only locally saved ingredients.
 */
object FoodSearchAPI {

    /** Immutable value describing search result; carries id, displayName. */
    data class SearchResult(val id: Long, val displayName: String)

    /** Ranked name matches from the downloaded bundle; empty if it isn't installed. */
    suspend fun searchIngredients(query: String): List<SearchResult> =
        FoodDatabase.search(query)

    /** Full per-100g nutrition for a search hit, or null if the bundle can't supply it. */
    suspend fun getIngredientData(id: Long, displayName: String): Ingredient? {
        val nutritionData = FoodDatabase.nutrition(id) ?: return null

        return Ingredient(
            id = id.toString(),
            originalName = displayName,
            nutritionData = nutritionData
        )
    }
}
