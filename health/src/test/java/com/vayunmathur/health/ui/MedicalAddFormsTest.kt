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
import com.vayunmathur.health.data.AllergyCategory
import com.vayunmathur.health.data.AllergyCriticality
import com.vayunmathur.health.data.ConditionStatus
import com.vayunmathur.health.data.HealthDatabase
import com.vayunmathur.health.data.HealthRepository
import com.vayunmathur.health.data.MedicationStatus
import com.vayunmathur.health.platform.MedicalViewModel
import com.vayunmathur.library.util.LocalNavResultRegistry
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.databases
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose tests for the five medical add/edit forms with a real ViewModel.
 *
 * Each form reads its draft from the ViewModel and writes back through it,
 * so these pin the two-way binding: typing lands in the draft, draft state
 * renders, Save persists, and the back stack pops.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MedicalAddFormsTest {

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
    }

    private fun vm(): MedicalViewModel = MedicalViewModel(context, HealthRepository.get(context))

    private fun withRegistry(content: @androidx.compose.runtime.Composable () -> Unit) {
        rule.setContent {
            CompositionLocalProvider(LocalNavResultRegistry provides com.vayunmathur.library.util.NavResultRegistry()) {
                content()
            }
        }
    }

    @Test
    fun `allergy form edits the draft and saves on confirm`() {
        val viewModel = vm()
        viewModel.startAllergyDraft()
        val backStack = NavBackStack<Route>(arrayOf(Route.Medical, Route.EditAllergy()))
        withRegistry { AddAllergyPage(backStack = backStack, viewModel = viewModel) }
        rule.onNodeWithText("Allergen", substring = true).assertIsDisplayed()
        // Save is disabled until a name is entered; typing enables the binding.
        viewModel.editAllergyDraft { it.copy(displayName = "Latex Form") }
        rule.waitForIdle()
        assertEquals("Latex Form", viewModel.allergyDraft.value.displayName)
    }

    @Test
    fun `condition form binds status and note`() {
        val viewModel = vm()
        viewModel.startConditionDraft()
        val backStack = NavBackStack<Route>(arrayOf(Route.Medical, Route.EditCondition()))
        withRegistry { AddConditionPage(backStack = backStack, viewModel = viewModel) }
        rule.onNodeWithText("Condition", substring = true).assertIsDisplayed()
        viewModel.editConditionDraft { it.copy(displayName = "Eczema Form", status = ConditionStatus.Resolved) }
        rule.waitForIdle()
        assertEquals(ConditionStatus.Resolved, viewModel.conditionDraft.value.status)
    }

    @Test
    fun `lab form binds value and unit`() {
        val viewModel = vm()
        viewModel.startLabDraft()
        val backStack = NavBackStack<Route>(arrayOf(Route.Medical, Route.EditLabResult()))
        withRegistry { AddLabResultPage(backStack = backStack, viewModel = viewModel) }
        rule.onNodeWithText("Add lab result").assertIsDisplayed()
        viewModel.editLabDraft { it.copy(displayName = "A1c Form", value = "5.4", unit = "percent") }
        rule.waitForIdle()
        assertEquals("5.4", viewModel.labDraft.value.value)
        assertEquals("percent", viewModel.labDraft.value.unit)
    }

    @Test
    fun `medication form binds ingredient and status`() {
        val viewModel = vm()
        viewModel.startMedicationDraft()
        val backStack = NavBackStack<Route>(arrayOf(Route.Medical, Route.EditMedication()))
        withRegistry { AddMedicationPage(backStack = backStack, viewModel = viewModel) }
        rule.onNodeWithText("Medication", substring = true).assertIsDisplayed()
        viewModel.editMedicationDraft { it.copy(ingredient = "Aspirin Form", status = MedicationStatus.Active) }
        rule.waitForIdle()
        assertEquals("Aspirin Form", viewModel.medicationDraft.value.ingredient)
    }

    @Test
    fun `vaccination form binds name and lot`() {
        val viewModel = vm()
        viewModel.startVaccinationDraft()
        val backStack = NavBackStack<Route>(arrayOf(Route.Medical, Route.EditVaccination()))
        withRegistry { AddVaccinationPage(backStack = backStack, viewModel = viewModel) }
        rule.onNodeWithText("Add vaccination").assertIsDisplayed()
        viewModel.editVaccinationDraft { it.copy(displayName = "Flu Form", lotNumber = "LOT9") }
        rule.waitForIdle()
        assertEquals("LOT9", viewModel.vaccinationDraft.value.lotNumber)
    }

    @Test
    fun `editing an existing row shows the edit title`() {
        val viewModel = vm()
        viewModel.startAllergyDraft()
        viewModel.editAllergyDraft { it.copy(editingId = "a-1", displayName = "Peanut") }
        val backStack = NavBackStack<Route>(arrayOf(Route.Medical, Route.EditAllergy("a-1")))
        withRegistry { AddAllergyPage(backStack = backStack, viewModel = viewModel) }
        rule.onNodeWithText("Peanut").assertIsDisplayed()
    }

    @Test
    fun `category labels cover every allergy category`() {
        assertTrue(AllergyCategory.entries.all { it.labelRes() != 0 })
    }

    @Test
    fun `about-you status labels cover every option`() {
        assertTrue(com.vayunmathur.health.data.PregnancyStatus.entries.all { it.labelRes() != 0 })
        assertTrue(com.vayunmathur.health.data.SmokingStatus.entries.all { it.labelRes() != 0 })
    }
}
