package com.vayunmathur.health.ui

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.health.Route
import com.vayunmathur.health.data.AllergyEntry
import com.vayunmathur.health.data.ConditionEntry
import com.vayunmathur.health.data.HealthDatabase
import com.vayunmathur.health.data.HealthRepository
import com.vayunmathur.health.data.LabResultEntry
import com.vayunmathur.health.data.MedicationEntry
import com.vayunmathur.health.data.MedicationStatus
import com.vayunmathur.health.data.VaccinationEntry
import com.vayunmathur.health.platform.MedicalViewModel
import com.vayunmathur.library.util.LocalNavResultRegistry
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavResultRegistry
import com.vayunmathur.library.util.databases
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MedicalRowClickTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val t: Instant = Instant.parse("2024-05-04T09:30:00Z")

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

    private fun page(vm: MedicalViewModel, backStack: NavBackStack<Route>) {
        rule.setContent {
            CompositionLocalProvider(LocalNavResultRegistry provides NavResultRegistry()) {
                MedicalPage(backStack = backStack, viewModel = vm)
            }
        }
    }

    private fun expandAndClick(section: String, row: String) {
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(section))
        rule.onNodeWithText(section).performClick()
        rule.waitForIdle()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(row))
        rule.onNodeWithText(row).performClick()
        rule.waitForIdle()
    }

    @Test
    fun `vaccination row opens its edit form`() = runTest {
        repo().upsertVaccination(VaccinationEntry(id = "row-vac-1", displayName = "RowClick Flu", occurredAt = t))
        val vm = MedicalViewModel(context, repo())
        val backStack = NavBackStack<Route>(arrayOf(Route.Medical))
        page(vm, backStack)
        expandAndClick("Vaccinations", "RowClick Flu")
        assertEquals(Route.EditVaccination("row-vac-1"), backStack.last())
    }

    @Test
    fun `allergy row opens its edit form`() = runTest {
        repo().upsertAllergy(AllergyEntry(id = "row-alg-1", displayName = "RowClick Peanut", recordedAt = t))
        val vm = MedicalViewModel(context, repo())
        val backStack = NavBackStack<Route>(arrayOf(Route.Medical))
        page(vm, backStack)
        expandAndClick("Allergies", "RowClick Peanut")
        assertEquals(Route.EditAllergy("row-alg-1"), backStack.last())
    }

    @Test
    fun `condition row opens its edit form`() = runTest {
        repo().upsertCondition(ConditionEntry(id = "row-cond-1", displayName = "RowClick Asthma", onsetAt = t))
        val vm = MedicalViewModel(context, repo())
        val backStack = NavBackStack<Route>(arrayOf(Route.Medical))
        page(vm, backStack)
        expandAndClick("Conditions", "RowClick Asthma")
        assertEquals(Route.EditCondition("row-cond-1"), backStack.last())
    }

    @Test
    fun `lab row opens its edit form`() = runTest {
        repo().upsertLabResult(LabResultEntry(id = "row-lab-1", displayName = "RowClick A1c", value = 5.4, unit = "%", takenAt = t))
        val vm = MedicalViewModel(context, repo())
        val backStack = NavBackStack<Route>(arrayOf(Route.Medical))
        page(vm, backStack)
        expandAndClick("Lab results", "RowClick A1c")
        assertEquals(Route.EditLabResult("row-lab-1"), backStack.last())
    }

    @Test
    fun `medication row opens its edit form`() = runTest {
        repo().upsertMedication(MedicationEntry(id = "row-med-1", displayName = "RowClick Med", startedAt = t, status = MedicationStatus.Active))
        val vm = MedicalViewModel(context, repo())
        val backStack = NavBackStack<Route>(arrayOf(Route.Medical))
        page(vm, backStack)
        expandAndClick("Medications", "RowClick Med")
        assertEquals(Route.EditMedication("row-med-1"), backStack.last())
    }

    @Test
    fun `fab menu items open add forms`() {
        val vm = MedicalViewModel(context, repo())
        val backStack = NavBackStack<Route>(arrayOf(Route.Medical))
        page(vm, backStack)
        rule.waitForIdle()
        val clickables = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        assertTrue(clickables.isNotEmpty())
        // The FAB toggle is the last clickable in the tree (bottom-end menu button).
        rule.onAllNodes(hasClickAction())[clickables.size - 1].performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Add vaccination").performClick()
        assertEquals(Route.EditVaccination(), backStack.last())
    }
}
