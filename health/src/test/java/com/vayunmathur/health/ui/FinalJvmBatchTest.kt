package com.vayunmathur.health.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.health.Route
import com.vayunmathur.health.data.HealthDatabase
import com.vayunmathur.health.data.HealthRepository
import com.vayunmathur.health.data.Ingredient
import com.vayunmathur.health.data.MedicationEntry
import com.vayunmathur.health.data.MedicationStatus
import com.vayunmathur.health.data.NutritionData
import com.vayunmathur.health.data.Recipe
import com.vayunmathur.health.data.RecipeIngredient
import com.vayunmathur.health.data.RecordType
import com.vayunmathur.health.data.ServingUnit
import com.vayunmathur.health.platform.MedicalViewModel
import com.vayunmathur.health.util.FoodDatabase
import com.vayunmathur.health.util.HealthAPI
import com.vayunmathur.health.util.HealthViewModel
import com.vayunmathur.library.util.LocalNavResultRegistry
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavResultRegistry
import com.vayunmathur.library.util.databases
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FinalJvmBatchTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val at: Instant = Instant.parse("2024-06-09T08:00:00Z")

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
        runBlocking { HealthRepository.get(context).getLastRecord(RecordType.Steps) }
    }

    private fun repo(): HealthRepository = HealthRepository.get(context)
    private fun vm(): HealthViewModel = HealthViewModel(context, repo())
    private fun medicalVm(): MedicalViewModel = MedicalViewModel(context, repo())

    @Test
    fun `bar chart tap selects a bar`() {
        rule.setContent {
            Column {
                GenericBarChart(
                    data = listOf("Mon" to 5000.0, "Tue" to 8000.0, "Wed" to 3000.0, "Thu" to 9000.0),
                    totalBarCount = 7,
                    goalValue = 10000.0,
                    barColor = Color.Blue,
                    goalColor = Color.Red,
                )
            }
        }
        rule.onNodeWithText("Tue").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Mon").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("12.0k", substring = true).assertExists()
    }

    @Test
    fun `swipe deleting a medication removes it`() = runBlocking<Unit> {
        repo().upsertMedication(MedicationEntry(id = "final-med-1", displayName = "FinalSwipe Med", startedAt = at, status = MedicationStatus.Active))
        val backStack = NavBackStack<Route>(arrayOf(Route.Medical))
        rule.setContent {
            CompositionLocalProvider(LocalNavResultRegistry provides NavResultRegistry()) {
                MedicalPage(backStack = backStack, viewModel = medicalVm())
            }
        }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Medications"))
        rule.onNodeWithText("Medications").performClick()
        rule.waitForIdle()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("FinalSwipe Med"))
        rule.onNodeWithText("FinalSwipe Med", substring = true).performTouchInput { swipeLeft() }
        rule.waitForIdle()
        val deletes = rule.onAllNodesWithText("Delete", substring = false).fetchSemanticsNodes()
        rule.onAllNodesWithText("Delete", substring = false)[deletes.size - 1].performClick()
        rule.waitForIdle()
        repeat(100) {
            if (repo().getMedication("final-med-1") == null) return@repeat
            Thread.sleep(10)
            rule.waitForIdle()
        }
        assertNull(repo().getMedication("final-med-1"))
    }

    @Test
    fun `medication status change to completed reveals ended field`() {
        val viewModel = medicalVm()
        viewModel.startMedicationDraft()
        viewModel.editMedicationDraft { it.copy(ingredient = "Status Med") }
        val backStack = NavBackStack<Route>(arrayOf(Route.Medical, Route.EditMedication()))
        rule.setContent {
            CompositionLocalProvider(LocalNavResultRegistry provides NavResultRegistry()) {
                AddMedicationPage(backStack, viewModel)
            }
        }
        rule.waitForIdle()
        // Status select row: open it and pick the completed label.
        viewModel.editMedicationDraft { it.copy(status = MedicationStatus.Completed) }
        rule.waitForIdle()
        assertEquals(MedicationStatus.Completed, viewModel.medicationDraft.value.status)
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Ended"))
        rule.onNodeWithText("Ended", substring = true).assertExists()
    }

}
