package com.vayunmathur.health.ui

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.health.Route
import com.vayunmathur.health.data.HealthDatabase
import com.vayunmathur.health.data.HealthRepository
import com.vayunmathur.health.data.Ingredient
import com.vayunmathur.health.data.NutritionData
import com.vayunmathur.health.data.ServingUnit
import com.vayunmathur.health.platform.MedicalViewModel
import com.vayunmathur.health.util.FoodDatabase
import com.vayunmathur.health.util.HealthAPI
import com.vayunmathur.health.util.HealthViewModel
import com.vayunmathur.library.util.LocalNavResultRegistry
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavResultRegistry
import com.vayunmathur.library.util.databases
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClinicalFormsTest {

    @get:Rule
    val rule = createComposeRule()

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
        HealthAPI.initLocal(context, HealthRepository.get(context))
        FoodDatabase.init(context)
        runBlocking { HealthRepository.get(context).getLastRecord(com.vayunmathur.health.data.RecordType.Steps) }
    }

    private fun medicalVm(): MedicalViewModel = MedicalViewModel(context, HealthRepository.get(context))
    private fun vm(): HealthViewModel = HealthViewModel(context, HealthRepository.get(context))

    private fun withRegistry(content: @androidx.compose.runtime.Composable () -> Unit) {
        rule.setContent {
            CompositionLocalProvider(LocalNavResultRegistry provides NavResultRegistry()) { content() }
        }
    }

    @Test
    fun `allergy form reaction and note edit the draft`() {
        val viewModel = medicalVm()
        viewModel.startAllergyDraft()
        viewModel.editAllergyDraft { it.copy(displayName = "Clinical Peanut") }
        val backStack = NavBackStack<Route>(arrayOf(Route.Medical, Route.EditAllergy()))
        withRegistry { AddAllergyPage(backStack, viewModel) }
        rule.onNodeWithText("Reaction").performTextInput("hives")
        assertEquals("hives", viewModel.allergyDraft.value.reaction)
        rule.onNodeWithText("Note").performTextInput("since childhood")
        assertEquals("since childhood", viewModel.allergyDraft.value.note)
    }

    @Test
    fun `condition form note edits the draft`() {
        val viewModel = medicalVm()
        viewModel.startConditionDraft()
        viewModel.editConditionDraft { it.copy(displayName = "Clinical Asthma") }
        val backStack = NavBackStack<Route>(arrayOf(Route.Medical, Route.EditCondition()))
        withRegistry { AddConditionPage(backStack, viewModel) }
        rule.onNodeWithText("Note").performTextInput("seasonal")
        assertEquals("seasonal", viewModel.conditionDraft.value.note)
    }

    @Test
    fun `lab form value unit and save persists`() = runBlocking<Unit> {
        val viewModel = medicalVm()
        viewModel.startLabDraft()
        viewModel.editLabDraft { it.copy(displayName = "Clinical A1c") }
        val backStack = NavBackStack<Route>(arrayOf(Route.Medical, Route.EditLabResult()))
        withRegistry { AddLabResultPage(backStack, viewModel) }
        rule.onNode(hasText("Result") and hasSetTextAction()).performTextInput("5.9")
        assertEquals("5.9", viewModel.labDraft.value.value)
        rule.onNodeWithText("Save").performClick()
        var saved = false
        repeat(100) {
            if (HealthRepository.get(context).getLabResultsFlow().first().any { it.displayName == "Clinical A1c" && it.value == 5.9 }) { saved = true; return@repeat }
            Thread.sleep(10)
            rule.waitForIdle()
        }
        assertTrue(saved)
    }

    @Test
    fun `ingredient quantity dialog confirms quantity and unit`() = runBlocking<Unit> {
        val repo = HealthRepository.get(context)
        val ingredient = Ingredient(id = "qty-flow-1", originalName = "Quantity Rice", nutritionData = NutritionData(calories = 130.0))
        repo.insertIngredient(ingredient)
        val unit = ServingUnit(id = "qty-flow-unit", ingredientId = ingredient.id, name = "Cup", grams = 100.0)
        repo.insertServingUnit(unit)
        var confirmed: Pair<Double, ServingUnit>? = null
        rule.setContent {
            IngredientQuantityDialog(vm(), ingredient, 1.0, unit, onDismiss = {}, onConfirm = { q, u -> confirmed = q to u })
        }
        rule.waitForIdle()
        rule.onNodeWithText("Confirm", substring = true).performClick()
        assertNotNull(confirmed)
        assertEquals(unit.id, confirmed!!.second.id)
    }
}
