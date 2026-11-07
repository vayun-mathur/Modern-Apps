package com.vayunmathur.health.platform

import android.app.Application
import android.os.Looper
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.health.data.AllergyEntry
import com.vayunmathur.health.data.ConditionEntry
import com.vayunmathur.health.data.DoseEvent
import com.vayunmathur.health.data.HealthDatabase
import com.vayunmathur.health.data.HealthRepository
import com.vayunmathur.health.data.Ingredient
import com.vayunmathur.health.data.LabResultEntry
import com.vayunmathur.health.data.MedicalKind
import com.vayunmathur.health.data.NutritionData
import com.vayunmathur.health.data.Recipe
import com.vayunmathur.health.data.RecipeIngredient
import com.vayunmathur.health.data.ServingUnit
import com.vayunmathur.health.util.HealthViewModel
import com.vayunmathur.library.util.databases
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SavePaths2Test {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val at: Instant = Instant.parse("2024-06-08T08:00:00Z")

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

    private fun repo(): HealthRepository = HealthRepository.get(context)
    private fun medical(): MedicalViewModel = MedicalViewModel(context, repo())
    private fun vm(): HealthViewModel = HealthViewModel(context, repo())

    private suspend fun settle(cond: suspend () -> Boolean) {
        repeat(200) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (cond()) return
            Thread.sleep(5)
            kotlinx.coroutines.delay(5)
        }
    }

    @Test
    fun `editing a foreign allergy detaches it`() = runTest {
        val repo = repo()
        repo.upsertAllergy(AllergyEntry(id = "alg-foreign-2", displayName = "Foreign Dust", recordedAt = at, fhirResourceId = "fhir-alg-2", dataSourceId = "provider-x"))
        val vm = medical()
        vm.startAllergyDraft("alg-foreign-2")
        settle { vm.allergyDraft.value.editingId == "alg-foreign-2" }
        vm.editAllergyDraft { it.copy(displayName = "Foreign Dust Edited", reaction = "sneeze") }
        vm.saveAllergyDraft()
        settle { repo.getAllergy("alg-foreign-2")?.displayName == "Foreign Dust Edited" }
        val saved = repo.getAllergy("alg-foreign-2")
        assertNull(saved?.fhirResourceId)
        assertNull(saved?.dataSourceId)
        assertTrue(repo.isTombstoned(MedicalKind.Allergy, "provider-x", "fhir-alg-2"))
    }

    @Test
    fun `editing a foreign condition detaches it`() = runTest {
        val repo = repo()
        repo.upsertCondition(ConditionEntry(id = "cond-foreign-2", displayName = "Foreign Cold", onsetAt = at, fhirResourceId = "fhir-cond-2", dataSourceId = "provider-x"))
        val vm = medical()
        vm.startConditionDraft("cond-foreign-2")
        settle { vm.conditionDraft.value.editingId == "cond-foreign-2" }
        vm.editConditionDraft { it.copy(displayName = "Foreign Cold Edited") }
        vm.saveConditionDraft()
        settle { repo.getCondition("cond-foreign-2")?.displayName == "Foreign Cold Edited" }
        assertNull(repo.getCondition("cond-foreign-2")?.fhirResourceId)
        assertTrue(repo.isTombstoned(MedicalKind.Condition, "provider-x", "fhir-cond-2"))
    }

    @Test
    fun `editing a foreign lab result detaches and parses ranges`() = runTest {
        val repo = repo()
        repo.upsertLabResult(LabResultEntry(id = "lab-foreign-2", displayName = "Foreign Glucose", value = 90.0, takenAt = at, fhirResourceId = "fhir-lab-2", dataSourceId = "provider-x"))
        val vm = medical()
        vm.startLabDraft("lab-foreign-2")
        settle { vm.labDraft.value.editingId == "lab-foreign-2" }
        vm.editLabDraft { it.copy(value = "95,5", referenceLow = "70", referenceHigh = "99", unit = "mg/dL") }
        vm.saveLabDraft()
        settle { repo.getLabResult("lab-foreign-2")?.value == 95.5 }
        val saved = repo.getLabResult("lab-foreign-2")
        assertEquals(70.0, saved?.referenceLow)
        assertEquals(99.0, saved?.referenceHigh)
        assertNull(saved?.fhirResourceId)
    }

    @Test
    fun `delete dose event removes it and forgets the row`() = runTest {
        val repo = repo()
        val event = DoseEvent(id = "dose-del-2", medicationId = "med-dose-2", takenAt = at, fhirResourceId = "fhir-dose-2", dataSourceId = "provider-x")
        repo.upsertDoseEvent(event)
        val vm = medical()
        vm.deleteDoseEvent(event)
        settle { repo.isTombstoned(MedicalKind.Dose, "provider-x", "fhir-dose-2") }
        assertNull(repo.getDoseEvent("dose-del-2"))
        assertTrue(repo.isTombstoned(MedicalKind.Dose, "provider-x", "fhir-dose-2"))
    }

    @Test
    fun `load recipe skips rows with missing ingredient or unit`() = runTest {
        val repo = repo()
        repo.insertRecipe(Recipe(id = "load-skip-recipe", name = "Skip Stew"))
        repo.insertIngredient(Ingredient(id = "load-skip-ing", originalName = "Skip Potato", nutritionData = NutritionData(calories = 80.0)))
        repo.insertServingUnit(ServingUnit(id = "load-skip-unit", ingredientId = "load-skip-ing", name = "Piece", grams = 150.0))
        repo.insertRecipeIngredient(RecipeIngredient(id = "load-ri-ok", recipeId = "load-skip-recipe", ingredientId = "load-skip-ing", quantity = 2.0, unitId = "load-skip-unit"))
        // A unit that exists but belongs to another ingredient: the loader
        // only accepts units from the ingredient's own unit list.
        repo.insertIngredient(Ingredient(id = "load-other-ing", originalName = "Other Herb", nutritionData = NutritionData(calories = 5.0)))
        repo.insertServingUnit(ServingUnit(id = "load-other-unit", ingredientId = "load-other-ing", name = "Leaf", grams = 1.0))
        repo.insertRecipeIngredient(RecipeIngredient(id = "load-ri-nounit", recipeId = "load-skip-recipe", ingredientId = "load-skip-ing", quantity = 1.0, unitId = "load-other-unit"))
        val loaded = vm().loadRecipeForEdit("load-skip-recipe")
        assertEquals("Skip Stew", loaded?.name)
        assertEquals(1, loaded?.ingredients?.size)
        assertEquals(2.0, loaded?.ingredients?.first()?.quantity)
    }

    @Test
    fun `save recipe as new creates recipe with ingredient rows`() = runTest {
        val repo = repo()
        val vm = vm()
        val ingredient = Ingredient(id = "save-new-ing", originalName = "New Lentils", nutritionData = NutritionData(calories = 110.0))
        val unit = ServingUnit(id = "save-new-unit", ingredientId = ingredient.id, name = "Cup", grams = 200.0)
        var completed = false
        vm.saveRecipe(existingRecipeId = null, name = "New Lentil Soup", items = listOf(HealthViewModel.RecipeIngredientLoad(ingredient, unit, 1.5))) { completed = true }
        settle { completed }
        assertTrue(completed)
        val created = repo.getAllRecipesFlow().first().first { it.name == "New Lentil Soup" }
        assertEquals(1, repo.getIngredientsForRecipe(created.id).size)
        assertEquals(1.5, repo.getIngredientsForRecipe(created.id).first().quantity)
    }
}
