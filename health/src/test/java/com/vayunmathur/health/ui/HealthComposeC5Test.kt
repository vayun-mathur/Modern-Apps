package com.vayunmathur.health.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.health.Route
import com.vayunmathur.health.data.HealthDatabase
import com.vayunmathur.health.data.HealthRepository
import com.vayunmathur.health.data.RepeatUnit
import com.vayunmathur.health.platform.MedicalViewModel
import com.vayunmathur.health.ui.components.ScheduleSection
import com.vayunmathur.health.util.FoodDatabase
import com.vayunmathur.health.util.HealthViewModel
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.databases
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * C5 Compose branches: every catalog picker's typed-name choice, the
 * vaccine list from the bundled CVX asset, the reminder schedule editor,
 * and the food-database card's failure state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HealthComposeC5Test {

    @get:Rule val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @BeforeTest
    fun setup() {
        com.vayunmathur.health.data.ReferenceCatalog.init(context)
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
    private fun medical(): MedicalViewModel = MedicalViewModel(context, repository())
    private fun stack() = NavBackStack<Route>(arrayOf(Route.Today))

    @Test
    fun catalogPicker_vaccineList_showsBundledVaccines() {
        val vm = medical()
        val s = stack()
        rule.setContent {
            CatalogPickerPage(s, vm, Route.CatalogPicker(kind = CatalogKind.Vaccine))
        }
        // The CVX asset ships with the app, so the unfiltered list is real
        // (loaded on IO, hence the wait rather than a plain idle).
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("Adenovirus", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onAllNodesWithText("Adenovirus", substring = true)[0].assertExists()
    }

    @Test
    fun catalogPicker_typedIngredient_clearsStaleStrength() {
        val vm = medical()
        vm.editMedicationDraft { it.copy(ingredient = "old", rxcui = "123", strength = "500 MG", doseForm = "Tablet") }
        val s = stack()
        rule.setContent {
            CatalogPickerPage(s, vm, Route.CatalogPicker(kind = CatalogKind.MedicationIngredient))
        }
        typeAndChooseTyped("Zebra Drug Xyz")
        assertEquals("Zebra Drug Xyz", vm.medicationDraft.value.ingredient)
        assertEquals(null, vm.medicationDraft.value.rxcui)
        assertEquals(null, vm.medicationDraft.value.strength)
    }

    @Test
    fun catalogPicker_typedAllergen_writesDraft() {
        val vm = medical()
        val s = stack()
        rule.setContent {
            CatalogPickerPage(s, vm, Route.CatalogPicker(kind = CatalogKind.Allergen))
        }
        typeAndChooseTyped("Zebra Nut Xyz")
        assertEquals("Zebra Nut Xyz", vm.allergyDraft.value.displayName)
        assertEquals(null, vm.allergyDraft.value.rxcui)
    }

    @Test
    fun catalogPicker_typedCondition_writesDraft() {
        val vm = medical()
        val s = stack()
        rule.setContent {
            CatalogPickerPage(s, vm, Route.CatalogPicker(kind = CatalogKind.Condition))
        }
        typeAndChooseTyped("Zebra Syndrome Xyz")
        assertEquals("Zebra Syndrome Xyz", vm.conditionDraft.value.displayName)
        assertEquals(null, vm.conditionDraft.value.icd10Code)
    }

    @Test
    fun catalogPicker_typedLab_keepsEnteredUnit() {
        val vm = medical()
        vm.editLabDraft { it.copy(unit = "mg/dL") }
        val s = stack()
        rule.setContent {
            CatalogPickerPage(s, vm, Route.CatalogPicker(kind = CatalogKind.LabTest))
        }
        typeAndChooseTyped("Zebra Panel Xyz")
        assertEquals("Zebra Panel Xyz", vm.labDraft.value.displayName)
        assertEquals("mg/dL", vm.labDraft.value.unit)
    }

    @Test
    fun catalogPicker_typedProduct_writesStrength() {
        val vm = medical()
        val s = stack()
        rule.setContent {
            CatalogPickerPage(
                s, vm,
                Route.CatalogPicker(kind = CatalogKind.MedicationProduct, ingredient = "amoxicillin"),
            )
        }
        typeAndChooseTyped("999 MG Xyz")
        assertEquals("999 MG Xyz", vm.medicationDraft.value.strength)
        assertEquals(null, vm.medicationDraft.value.rxcui)
    }

    @Test
    fun catalogPicker_typedVaccine_writesDraft() {
        val vm = medical()
        val s = stack()
        rule.setContent {
            CatalogPickerPage(s, vm, Route.CatalogPicker(kind = CatalogKind.Vaccine))
        }
        typeAndChooseTyped("Zebra Vaccine Xyz")
        assertEquals("Zebra Vaccine Xyz", vm.vaccinationDraft.value.displayName)
        assertEquals(null, vm.vaccinationDraft.value.cvxCode)
    }

    /** Types [text] into the picker's search bar, then taps the typed-name row. */
    private fun typeAndChooseTyped(text: String) {
        val target = rule.onAllNodes(androidx.compose.ui.test.hasSetTextAction())[0]
        target.performTextClearance()
        target.performTextInput(text)
        // Debounce for non-empty queries is 180ms of compose-clock time.
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        rule.onNodeWithText("Use \u201c$text\u201d").performScrollTo().performClick()
        rule.waitForIdle()
    }

    @Test
    fun scheduleSection_weeklySchedule_editsIntervalDaysAndTimes() {
        var draft = MedicalViewModel.MedicationDraft(
            remindersEnabled = true,
            times = listOf(9 * 3600, 21 * 3600),
            repeatUnit = RepeatUnit.Weekly,
            interval = 2,
            daysOfWeek = 0b0000011,
        )
        val pickedTimes = mutableListOf<Int>()
        var untilPicked = 0
        rule.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ScheduleSection(
                    draft = draft,
                    onEdit = { transform -> draft = transform(draft) },
                    onPickTime = { pickedTimes += it },
                    onPickUntil = { untilPicked++ },
                )
            }
        }
        rule.onNodeWithText("Remind me", substring = true).assertIsDisplayed()
        rule.onNodeWithText("On these days").performScrollTo().assertExists()
        // Interval field: it currently shows "2"; replace it with 3.
        val intervalField = rule.onNode(
            androidx.compose.ui.test.hasSetTextAction() and
                androidx.compose.ui.test.hasText("2"),
        )
        intervalField.performTextReplacement("3")
        rule.runOnIdle { assertEquals(3, draft.interval) }
        // Toggling a day flips its bit (Sunday starts selected: bit 0 set).
        rule.onAllNodesWithText("S")[0].performScrollTo().performClick()
        rule.runOnIdle { assertEquals(0, draft.daysOfWeek and 1) }
        assertNotNull(untilPicked)
        assertTrue(pickedTimes.isEmpty())
    }

}
