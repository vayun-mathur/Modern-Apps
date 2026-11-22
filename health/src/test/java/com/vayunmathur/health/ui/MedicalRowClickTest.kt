package com.vayunmathur.health.ui

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
import com.vayunmathur.testsupport.awaitSemantics
import com.vayunmathur.testsupport.awaitWithMainIdle
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

    /**
     * Expand [section] and tap [row], waiting only on terminal signals.
     *
     * The section header's toggle is a no-op while its entry list is
     * still empty, and the ViewModel's lists are Room flows republished
     * through `stateIn` on the main looper: under CPU contention a plain
     * `waitForIdle()` can return while that IO delivery is still in
     * flight (the RecipeEditorFlowTest genus), so the tap landed on a
     * still-empty section and the row never appeared. Wait first for
     * the row to be in the ViewModel's terminal flow state, then for
     * the row's semantics node to exist (the expand animation runs on
     * the composition scheduler, which [awaitSemantics] pumps), and
     * finally for the navigation itself — the edit route on the back
     * stack is the click's terminal signal.
     */
    private fun expandAndClick(
        section: String,
        row: String,
        loaded: () -> Boolean,
        expected: () -> Route,
        backStack: NavBackStack<Route>,
    ) {
        awaitWithMainIdle("$row loaded in view model") { loaded() }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(section))
        rule.onNodeWithText(section).performClick()
        rule.awaitSemantics("$row row composed") {
            rule.onAllNodesWithText(row).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(row))
        rule.onNodeWithText(row).performClick()
        rule.awaitSemantics("$row opens its edit form") { backStack.last() == expected() }
    }

    @Test
    fun `vaccination row opens its edit form`() = runTest {
        repo().upsertVaccination(VaccinationEntry(id = "row-vac-1", displayName = "RowClick Flu", occurredAt = t))
        val vm = MedicalViewModel(context, repo())
        val backStack = NavBackStack<Route>(arrayOf(Route.Medical))
        page(vm, backStack)
        expandAndClick(
            "Vaccinations", "RowClick Flu",
            loaded = { vm.vaccinations.value.any { it.id == "row-vac-1" } },
            expected = { Route.EditVaccination("row-vac-1") },
            backStack = backStack,
        )
        assertEquals(Route.EditVaccination("row-vac-1"), backStack.last())
    }

    @Test
    fun `allergy row opens its edit form`() = runTest {
        repo().upsertAllergy(AllergyEntry(id = "row-alg-1", displayName = "RowClick Peanut", recordedAt = t))
        val vm = MedicalViewModel(context, repo())
        val backStack = NavBackStack<Route>(arrayOf(Route.Medical))
        page(vm, backStack)
        expandAndClick(
            "Allergies", "RowClick Peanut",
            loaded = { vm.allergies.value.any { it.id == "row-alg-1" } },
            expected = { Route.EditAllergy("row-alg-1") },
            backStack = backStack,
        )
        assertEquals(Route.EditAllergy("row-alg-1"), backStack.last())
    }

    @Test
    fun `condition row opens its edit form`() = runTest {
        repo().upsertCondition(ConditionEntry(id = "row-cond-1", displayName = "RowClick Asthma", onsetAt = t))
        val vm = MedicalViewModel(context, repo())
        val backStack = NavBackStack<Route>(arrayOf(Route.Medical))
        page(vm, backStack)
        expandAndClick(
            "Conditions", "RowClick Asthma",
            loaded = { vm.conditions.value.any { it.id == "row-cond-1" } },
            expected = { Route.EditCondition("row-cond-1") },
            backStack = backStack,
        )
        assertEquals(Route.EditCondition("row-cond-1"), backStack.last())
    }

    @Test
    fun `lab row opens its edit form`() = runTest {
        repo().upsertLabResult(LabResultEntry(id = "row-lab-1", displayName = "RowClick A1c", value = 5.4, unit = "%", takenAt = t))
        val vm = MedicalViewModel(context, repo())
        val backStack = NavBackStack<Route>(arrayOf(Route.Medical))
        page(vm, backStack)
        expandAndClick(
            "Lab results", "RowClick A1c",
            loaded = { vm.labResults.value.any { it.id == "row-lab-1" } },
            expected = { Route.EditLabResult("row-lab-1") },
            backStack = backStack,
        )
        assertEquals(Route.EditLabResult("row-lab-1"), backStack.last())
    }

    @Test
    fun `medication row opens its edit form`() = runTest {
        repo().upsertMedication(MedicationEntry(id = "row-med-1", displayName = "RowClick Med", startedAt = t, status = MedicationStatus.Active))
        val vm = MedicalViewModel(context, repo())
        val backStack = NavBackStack<Route>(arrayOf(Route.Medical))
        page(vm, backStack)
        expandAndClick(
            "Medications", "RowClick Med",
            loaded = { vm.medications.value.any { it.id == "row-med-1" } },
            expected = { Route.EditMedication("row-med-1") },
            backStack = backStack,
        )
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
        // The menu item composing is the terminal signal for the toggle
        // (same idle-vs-animation race as the row expansion above).
        rule.awaitSemantics("fab menu composed") {
            rule.onAllNodesWithText("Add vaccination").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Add vaccination").performClick()
        rule.awaitSemantics("add vaccination route") {
            backStack.last() == Route.EditVaccination()
        }
        assertEquals(Route.EditVaccination(), backStack.last())
    }
}
