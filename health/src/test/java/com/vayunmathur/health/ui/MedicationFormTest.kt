package com.vayunmathur.health.ui

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.health.Route
import com.vayunmathur.health.data.HealthDatabase
import com.vayunmathur.health.data.HealthRepository
import com.vayunmathur.health.data.MedicationStatus
import com.vayunmathur.health.platform.MedicalViewModel
import com.vayunmathur.library.util.LocalNavResultRegistry
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavResultRegistry
import com.vayunmathur.library.util.databases
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MedicationFormTest {

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
        runBlocking { HealthRepository.get(context).getLastRecord(com.vayunmathur.health.data.RecordType.Steps) }
    }

    private fun medicalVm(): MedicalViewModel = MedicalViewModel(context, HealthRepository.get(context))

    @Test
    fun `medication form dosage note and status edit the draft`() {
        val viewModel = medicalVm()
        viewModel.startMedicationDraft()
        viewModel.editMedicationDraft { it.copy(ingredient = "FormMed UI") }
        val backStack = NavBackStack<Route>(arrayOf(Route.Medical, Route.EditMedication()))
        rule.setContent {
            CompositionLocalProvider(LocalNavResultRegistry provides NavResultRegistry()) {
                AddMedicationPage(backStack, viewModel)
            }
        }
        rule.onNodeWithText("Dosage", substring = true).performTextInput("1 tablet daily")
        assertEquals("1 tablet daily", viewModel.medicationDraft.value.dosage)
        rule.onNodeWithText("Note", substring = true).performTextInput("with food")
        assertEquals("with food", viewModel.medicationDraft.value.note)
        // Reminders section is present on the same form.
        rule.onNodeWithText("Remind me", substring = true).assertExists()
        assertTrue(MedicationStatus.entries.all { it.selectorLabelRes() != 0 })
    }

    @Test
    fun `medication save through the form persists entry`() = runBlocking<Unit> {
        val viewModel = medicalVm()
        viewModel.startMedicationDraft()
        viewModel.editMedicationDraft { it.copy(ingredient = "FormSaved Med", dosage = "2x") }
        val backStack = NavBackStack<Route>(arrayOf(Route.Medical, Route.EditMedication()))
        rule.setContent {
            CompositionLocalProvider(LocalNavResultRegistry provides NavResultRegistry()) {
                AddMedicationPage(backStack, viewModel)
            }
        }
        rule.onNodeWithText("Save").performClick()
        assertEquals(Route.Medical, backStack.last())
        var saved = false
        repeat(100) {
            if (HealthRepository.get(context).getMedicationsFlow().first().any { it.displayName == "FormSaved Med" }) { saved = true; return@repeat }
            Thread.sleep(10)
            rule.waitForIdle()
        }
        assertTrue(saved)
    }
}
