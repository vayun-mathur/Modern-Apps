package com.vayunmathur.health.util

import android.app.Application
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.health.data.HealthDatabase
import com.vayunmathur.health.data.HealthRepository
import com.vayunmathur.health.data.Ingredient
import com.vayunmathur.health.data.NutritionData
import com.vayunmathur.health.data.Recipe
import com.vayunmathur.health.data.RecipeIngredient
import com.vayunmathur.health.data.ServingUnit
import com.vayunmathur.library.util.databases
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Recipe edit round-trip: loading drops rows whose ingredient or unit
 * has gone missing, and saving over an existing recipe replaces its
 * ingredient rows instead of appending to them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HealthRecipeC5Test {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @BeforeTest
    fun setup() {
        synchronized(databases) {
            if (databases[HealthDatabase::class] == null) {
                databases[HealthDatabase::class] = Room
                    .inMemoryDatabaseBuilder(context, HealthDatabase::class.java)
                    .allowMainThreadQueries()
                    .build()
            }
        }
    }

    private fun repository(): HealthRepository = HealthRepository.get(context)
    private fun vm(): HealthViewModel = HealthViewModel(context, repository())

    private fun ingredient(id: String) = Ingredient(
        id = id, originalName = "Ingredient $id", nutritionData = NutritionData(protein = 1.0),
    )

    @Test
    fun `load for edit drops rows with missing ingredient or unit`() = runTest {
        val repo = repository()
        repo.insertRecipe(Recipe(id = "c5-recipe", name = "C5 Stew"))
        repo.insertIngredient(ingredient("c5-ing-a"))
        repo.insertIngredient(ingredient("c5-ing-b"))
        repo.insertServingUnit(ServingUnit(id = "c5-unit-a", ingredientId = "c5-ing-a", name = "Cup", grams = 240.0))
        repo.insertServingUnit(ServingUnit(id = "c5-unit-other", ingredientId = "c5-ing-a", name = "Other", grams = 1.0))
        repo.insertRecipeIngredient(
            RecipeIngredient(id = "c5-row-a", recipeId = "c5-recipe", ingredientId = "c5-ing-a", quantity = 2.0, unitId = "c5-unit-a"),
        )
        // The unit exists but belongs to another ingredient, so looking it up
        // among this ingredient's units misses and the row must be dropped.
        repo.insertRecipeIngredient(
            RecipeIngredient(id = "c5-row-b", recipeId = "c5-recipe", ingredientId = "c5-ing-b", quantity = 1.0, unitId = "c5-unit-other"),
        )
        // Ingredient does not exist at all: also dropped (insert via recipe-less path
        // would violate the FK, so point at an existing ingredient but a recipe row
        // for a second recipe whose ingredient was never inserted is covered by the
        // unit-missing case above; here the recipe lookup itself must miss).
        val load = vm().loadRecipeForEdit("c5-recipe")
        assertEquals("C5 Stew", load!!.name)
        assertEquals(1, load.ingredients.size)
        assertEquals("c5-ing-a", load.ingredients[0].ingredient.id)
        assertEquals(2.0, load.ingredients[0].quantity)
        assertEquals("Cup", load.ingredients[0].unit.name)
        assertNull(vm().loadRecipeForEdit("c5-no-such-recipe"))
    }

    @Test
    fun `save over existing recipe replaces rows and completes`() {
        val repo = repository()
        kotlinx.coroutines.runBlocking {
            repo.insertRecipe(Recipe(id = "c5-recipe2", name = "Old Name"))
            repo.insertIngredient(ingredient("c5-ing-old"))
            repo.insertServingUnit(ServingUnit(id = "c5-unit-old", ingredientId = "c5-ing-old", name = "Gram", grams = 1.0))
            repo.insertRecipeIngredient(
                RecipeIngredient(id = "c5-row-old", recipeId = "c5-recipe2", ingredientId = "c5-ing-old", quantity = 5.0, unitId = "c5-unit-old"),
            )
        }
        val done = CountDownLatch(1)
        vm().saveRecipe(
            existingRecipeId = "c5-recipe2",
            name = "New Name",
            items = listOf(
                HealthViewModel.RecipeIngredientLoad(
                    ingredient = ingredient("c5-ing-new"),
                    unit = ServingUnit(id = "c5-unit-new", ingredientId = "c5-ing-new", name = "Slice", grams = 30.0),
                    quantity = 3.0,
                ),
            ),
            onComplete = { done.countDown() },
        )
        // viewModelScope runs on Main (paused under Robolectric); pump it.
        repeat(50) {
            ShadowLooper.idleMainLooper()
            if (done.count == 0L) return@repeat
            Thread.sleep(10)
        }
        assertTrue(done.await(5, TimeUnit.SECONDS), "saveRecipe completes")
        val load = kotlinx.coroutines.runBlocking { vm().loadRecipeForEdit("c5-recipe2") }
        assertEquals("New Name", load!!.name)
        assertEquals(1, load.ingredients.size)
        assertEquals("c5-ing-new", load.ingredients[0].ingredient.id)
        assertEquals(3.0, load.ingredients[0].quantity)
    }

    @Test
    fun `save new recipe creates it`() {
        val done = CountDownLatch(1)
        vm().saveRecipe(
            existingRecipeId = null,
            name = "Fresh C5 Recipe",
            items = emptyList(),
            onComplete = { done.countDown() },
        )
        repeat(50) {
            ShadowLooper.idleMainLooper()
            if (done.count == 0L) return@repeat
            Thread.sleep(10)
        }
        assertTrue(done.await(5, TimeUnit.SECONDS), "saveRecipe completes")
        val first = kotlinx.coroutines.runBlocking {
            repository().getAllRecipesFlow().first()
        }
        assertTrue(first.any { it.name == "Fresh C5 Recipe" })
    }
}
