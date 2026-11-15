@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.vayunmathur.health.data

import kotlin.uuid.Uuid
import androidx.room3.Embedded
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

/** Immutable value describing ingredient. */
@Entity
data class Ingredient(
    @PrimaryKey val id: String, // Unique identifier, possibly from API
    val originalName: String,
    val customName: String? = null,
    val isRecipe: Boolean = false,
    // Nutrition data is stored *per 100g* of the ingredient.
    @Embedded(prefix = "nutrition_") val nutritionData: NutritionData
) {
    val displayName: String
        get() = customName ?: originalName
}

/** Immutable value describing recipe. */
@Entity
data class Recipe(
    @PrimaryKey val id: String = Uuid.random().toString(),
    val name: String
)

@Entity(
    foreignKeys = [
        ForeignKey(
            entity = Ingredient::class,
            parentColumns = ["id"],
            childColumns = ["ingredientId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Recipe::class,
            parentColumns = ["id"],
            childColumns = ["recipeId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("ingredientId"),
        Index("recipeId")
    ]
)
/** Immutable value describing serving unit. */
data class ServingUnit(
    @PrimaryKey val id: String = Uuid.random().toString(),
    val ingredientId: String? = null,
    val recipeId: String? = null,
    val name: String, // e.g., "Cup", "Slice", "Serving", "Gram"
    val grams: Double // Equivalent amount in grams
)

@Entity(
    foreignKeys = [
        ForeignKey(
            entity = Recipe::class,
            parentColumns = ["id"],
            childColumns = ["recipeId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Ingredient::class,
            parentColumns = ["id"],
            childColumns = ["ingredientId"],
            // Restrict deletion of an ingredient if a recipe uses it.
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = ServingUnit::class,
            parentColumns = ["id"],
            childColumns = ["unitId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index("recipeId"),
        Index("ingredientId"),
        Index("unitId")
    ]
)
/** Immutable value describing recipe ingredient. */
data class RecipeIngredient(
    @PrimaryKey val id: String = Uuid.random().toString(),
    val recipeId: String,
    val ingredientId: String,
    val quantity: Double,
    val unitId: String
)
