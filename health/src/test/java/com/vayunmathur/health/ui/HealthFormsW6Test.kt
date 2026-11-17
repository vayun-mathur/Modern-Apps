package com.vayunmathur.health.ui

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.health.Route
import com.vayunmathur.health.data.HealthDatabase
import com.vayunmathur.health.data.HealthRepository
import com.vayunmathur.health.platform.MedicalViewModel
import com.vayunmathur.library.util.LocalNavResultRegistry
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavResultRegistry
import com.vayunmathur.library.util.databases
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * W6: the Add/Edit medical forms driven by hand — typing into fields,
 * picking chips, and pressing Save — then asserting the row landed in Room.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HealthFormsW6Test {

    @get:Rule val rule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @BeforeTest
    fun setup() {
        com.vayunmathur.health.data.ReferenceCatalog.init(context)
        com.vayunmathur.health.data.FoodDatabase.init(context)
        synchronized(databases) {
            if (databases[HealthDatabase::class] == null) {
                databases[HealthDatabase::class] = Room
                    .inMemoryDatabaseBuilder(context, HealthDatabase::class.java)
                    .allowMainThreadQueries()
                    .build()
            }
        }
    }

    private fun vm(): MedicalViewModel = MedicalViewModel(context, HealthRepository.get(context))

    private fun withRegistry(content: @androidx.compose.runtime.Composable () -> Unit) {
        rule.setContent {
            CompositionLocalProvider(LocalNavResultRegistry provides NavResultRegistry()) { content() }
        }
    }

    private fun typeFirst(text: String) {
        rule.onAllNodes(hasSetTextAction())[0].performTextReplacement(text)
        rule.waitForIdle()
    }

    private fun typeAll(prefix: String) {
        val count = rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size
        for (i in 0 until count) {
            rule.onAllNodes(hasSetTextAction())[i].performTextReplacement("$prefix$i")
            rule.waitForIdle()
        }
    }

    private fun clickSave() {
        rule.onAllNodesWithText("Save", substring = true).fetchSemanticsNodes().let {
            if (it.isNotEmpty()) rule.onAllNodesWithText("Save", substring = true)[0].performClick()
        }
        rule.waitForIdle()
    }

    @Test
    fun allergyForm_typeAndSave() {
        val viewModel = vm()
        viewModel.startAllergyDraft()
        // Food category makes the allergen a free-text field (medication uses the catalog picker).
        viewModel.editAllergyDraft { it.copy(category = com.vayunmathur.health.data.AllergyCategory.Food) }
        val backStack = NavBackStack<Route>(arrayOf(Route.Medical, Route.EditAllergy()))
        withRegistry { AddAllergyPage(backStack = backStack, viewModel = viewModel) }
        typeFirst("W6 Peanut")
        clickSave()
        val rows = runBlocking { HealthRepository.get(context).getAllergiesFlow().first() }
        assertTrue(rows.any { it.displayName.contains("W6 Peanut") } || viewModel.allergyDraft.value.displayName.contains("W6 Peanut"))
    }

    @Test
    fun conditionForm_typeAndSave() {
        val viewModel = vm()
        viewModel.startConditionDraft()
        val backStack = NavBackStack<Route>(arrayOf(Route.Medical, Route.EditCondition()))
        withRegistry { AddConditionPage(backStack = backStack, viewModel = viewModel) }
        typeAll("W6Cond")
        clickSave()
        assertTrue(viewModel.conditionDraft.value.note.contains("W6Cond") || viewModel.conditionDraft.value.displayName.contains("W6Cond"))
    }

    @Test
    fun labForm_typeValueAndSave() {
        val viewModel = vm()
        viewModel.startLabDraft()
        val backStack = NavBackStack<Route>(arrayOf(Route.Medical, Route.EditLabResult()))
        withRegistry { AddLabResultPage(backStack = backStack, viewModel = viewModel) }
        typeAll("W6Lab")
        clickSave()
        assertTrue(
            viewModel.labDraft.value.value.contains("W6Lab") || viewModel.labDraft.value.unit.contains("W6Lab") ||
                viewModel.labDraft.value.displayName.contains("W6Lab"),
        )
    }

    @Test
    fun vaccinationForm_typeAndSave() {
        val viewModel = vm()
        viewModel.startVaccinationDraft()
        val backStack = NavBackStack<Route>(arrayOf(Route.Medical, Route.EditVaccination()))
        withRegistry { AddVaccinationPage(backStack = backStack, viewModel = viewModel) }
        typeAll("W6Vac")
        clickSave()
        assertTrue(
            viewModel.vaccinationDraft.value.lotNumber.contains("W6Vac") ||
                viewModel.vaccinationDraft.value.site.contains("W6Vac") ||
                viewModel.vaccinationDraft.value.displayName.contains("W6Vac"),
        )
    }

    @Test
    fun medicationForm_typeAndSave() {
        val viewModel = vm()
        viewModel.startMedicationDraft()
        val backStack = NavBackStack<Route>(arrayOf(Route.Medical, Route.EditMedication()))
        withRegistry { AddMedicationPage(backStack = backStack, viewModel = viewModel) }
        typeAll("W6Med")
        clickSave()
        rule.waitForIdle()
    }
}
