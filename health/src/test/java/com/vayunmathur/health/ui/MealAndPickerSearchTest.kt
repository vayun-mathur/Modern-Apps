package com.vayunmathur.health.ui

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.health.Route
import com.vayunmathur.health.data.HealthDatabase
import com.vayunmathur.health.data.HealthRepository
import com.vayunmathur.health.data.Recipe
import com.vayunmathur.health.data.RecordType
import com.vayunmathur.health.platform.MedicalViewModel
import com.vayunmathur.health.data.FoodDatabase
import com.vayunmathur.health.data.HealthAPI
import com.vayunmathur.health.platform.HealthViewModel
import com.vayunmathur.library.util.LocalNavResultRegistry
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavResultRegistry
import com.vayunmathur.library.util.databases
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MealAndPickerSearchTest {

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
        com.vayunmathur.health.data.ReferenceCatalog.init(context)
        runBlocking { HealthRepository.get(context).getLastRecord(RecordType.Steps) }
    }

    private fun vm(): HealthViewModel = HealthViewModel(context, HealthRepository.get(context))
    private fun medicalVm(): MedicalViewModel = MedicalViewModel(context, HealthRepository.get(context))

    @Test
    fun `log meal dialog selects a recipe and saves a nutrition row`() = runBlocking<Unit> {
        val repo = HealthRepository.get(context)
        repo.insertRecipe(Recipe(id = "mealflow-recipe", name = "MealFlow Stew"))
        var dismissed = false
        rule.setContent { LogMealDialog(vm(), Instant.parse("2024-03-01T12:00:00Z")) { dismissed = true } }
        rule.onNodeWithText("Select Recipe", substring = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("MealFlow Stew").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Save").performClick()
        rule.waitForIdle()
        assertTrue(dismissed)
        // Wait on the terminal Room emission while pumping the main
        // looper: logMeal runs on viewModelScope (Main), so its insert
        // segment only executes when the paused Robolectric looper is
        // idled — the previous Thread.sleep poll never pumped it and
        // burned its 1s wall-clock budget in a batched (forkEvery = 20)
        // JVM before the IO hop landed. Collecting on IO + pumped idle
        // is the NonogramViewModelTest blocking pattern.
        val loggedJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
            .async {
                repo.getRecordsFlow(RecordType.Nutrition)
                    .first { rows -> rows.any { it.metadata == "MealFlow Stew" } }
                true
            }
        runCatching {
            com.vayunmathur.testsupport.awaitWithMainIdle("meal logged to Room") { loggedJob.isCompleted }
        }
        assertTrue(loggedJob.isCompleted && loggedJob.await(), "meal was logged to Room")
    }

    @Test
    fun `catalog vaccine search filters the bundled list`() {
        val viewModel = medicalVm()
        viewModel.startVaccinationDraft()
        val backStack = NavBackStack<Route>(arrayOf(Route.Medical, Route.CatalogPicker(CatalogKind.Vaccine)))
        rule.setContent {
            CompositionLocalProvider(LocalNavResultRegistry provides NavResultRegistry()) {
                CatalogPickerPage(backStack, viewModel, Route.CatalogPicker(CatalogKind.Vaccine))
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Search vaccines", substring = true).performTextInput("adenovirus")
        rule.waitForIdle()
        rule.onNodeWithText("Adenovirus types 4 and 7", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Adenovirus types 4 and 7", substring = true).performClick()
        assertEquals("Adenovirus types 4 and 7", viewModel.vaccinationDraft.value.displayName)
        assertEquals(Route.Medical, backStack.last())
    }

    @Test
    fun `catalog medication search with absent catalog shows empty state`() {
        val viewModel = medicalVm()
        viewModel.startMedicationDraft()
        val backStack = NavBackStack<Route>(arrayOf(Route.Medical, Route.CatalogPicker(CatalogKind.MedicationIngredient)))
        rule.setContent {
            CompositionLocalProvider(LocalNavResultRegistry provides NavResultRegistry()) {
                CatalogPickerPage(backStack, viewModel, Route.CatalogPicker(CatalogKind.MedicationIngredient))
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Search medications", substring = true).performTextInput("amox")
        rule.waitForIdle()
        // Catalog DB asset is absent in tests: the picker stays on its
        // empty/absent state instead of crashing, and no draft is written.
        assertEquals("", viewModel.medicationDraft.value.ingredient)
    }
}
